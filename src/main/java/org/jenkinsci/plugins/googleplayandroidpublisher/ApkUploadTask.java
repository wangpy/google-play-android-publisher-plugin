package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.FileContent;
import com.google.api.services.androidpublisher.model.Apk;
import com.google.api.services.androidpublisher.model.Bundle;
import com.google.api.services.androidpublisher.model.ExpansionFile;
import com.google.api.services.androidpublisher.model.ExpansionFilesUploadResponse;
import com.google.api.services.androidpublisher.model.LocalizedText;
import com.google.api.services.androidpublisher.model.TrackRelease;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.FilePath;
import hudson.model.TaskListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ApkPublisher.ExpansionFileSet;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ApkPublisher.RecentChanges;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.DEOBFUSCATION_FILE_TYPE_PROGUARD;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.OBB_FILE_TYPE_MAIN;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.OBB_FILE_TYPE_PATCH;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getAppFileMetadata;

class ApkUploadTask extends TrackPublisherTask<Boolean> {

    private final FilePath workspace;
    private final List<FilePath> appFilesToUpload;
    private final Map<FilePath, FilePath> appFilesToMappingFiles;
    private final Map<Long, ExpansionFileSet> expansionFiles;
    private final boolean usePreviousExpansionFilesIfMissing;
    private final RecentChanges[] recentChangeList;
    private final List<Integer> existingVersionCodes;
    private int latestMainExpansionFileVersionCode;
    private int latestPatchExpansionFileVersionCode;

    // TODO: Could be renamed
    ApkUploadTask(TaskListener listener, GoogleRobotCredentials credentials, String applicationId,
                  FilePath workspace, List<FilePath> appFilesToUpload, Map<FilePath, FilePath> appFilesToMappingFiles,
                  Map<Long, ExpansionFileSet> expansionFiles, boolean usePreviousExpansionFilesIfMissing,
                  ReleaseTrack track, double rolloutPercentage, ApkPublisher.RecentChanges[] recentChangeList) {
        super(listener, credentials, applicationId, track, rolloutPercentage);
        this.workspace = workspace;
        this.appFilesToUpload = appFilesToUpload;
        this.appFilesToMappingFiles = appFilesToMappingFiles;
        this.expansionFiles = expansionFiles;
        this.usePreviousExpansionFilesIfMissing = usePreviousExpansionFilesIfMissing;
        this.recentChangeList = recentChangeList;
        this.existingVersionCodes = new ArrayList<Integer>();
    }

    protected Boolean execute() throws IOException, InterruptedException {
        // Open an edit via the Google Play API, thereby ensuring that our credentials etc. are working
        logger.println(String.format("Authenticating to Google Play API...%n" +
                        "- Credential:     %s%n" +
                        "- Application ID: %s%n", getCredentialName(), applicationId));
        createEdit(applicationId);

        // Get the list of existing app files and their info
        // TODO: This if/else can probably be nicer
        List<String> existingAppFileHashes = new ArrayList<>();
        if (appFilesToUpload.get(0).getName().endsWith(".aab")) {
            List<Bundle> existingBundles = editService.bundles().list(applicationId, editId).execute().getBundles();
            if (existingBundles != null) {
                for (Bundle bundle : existingBundles) {
                    existingVersionCodes.add(bundle.getVersionCode());
                    existingAppFileHashes.add(bundle.getSha1());
                }
            }
        } else {
            List<Apk> existingApks = editService.apks().list(applicationId, editId).execute().getApks();
            if (existingApks != null) {
                for (Apk apk : existingApks) {
                    existingVersionCodes.add(apk.getVersionCode());
                    existingAppFileHashes.add(apk.getBinary().getSha1());
                }
            }
        }

        // Upload each of the files
        logger.println(String.format("Uploading %d file(s) with application ID: %s%n", appFilesToUpload.size(), applicationId));
        final ArrayList<Integer> uploadedVersionCodes = new ArrayList<>();
        for (FilePath appFile : appFilesToUpload) {
            final AppFileMetadata metadata = getAppFileMetadata(appFile);
            final String appFileSha1Hash = getSha1Hash(appFile.getRemote());
            final boolean isAppBundle = appFile.getName().endsWith(".aab");
            final String fileType = isAppBundle ? "AAB" : "APK";

            // Log some useful information about the file that will be uploaded
            logger.println(String.format("      %s file: %s", fileType, getRelativeFileName(appFile)));
            logger.println(String.format("    SHA-1 hash: %s", appFileSha1Hash));
            logger.println(String.format("   versionCode: %d", metadata.getVersionCode()));
            logger.println(String.format(" minSdkVersion: %s", metadata.getMinSdkVersion()));

            // Check whether this file already exists on the server (i.e. uploading it would fail)
            for (String hash : existingAppFileHashes) {
                if (hash.toLowerCase(Locale.ROOT).equals(appFileSha1Hash)) {
                    logger.println();
                    logger.println("This file already exists in the Google Play account; it cannot be uploaded again");
                    return false;
                }
            }

            // If not, we can upload the file
            FileContent fileToUpload = new FileContent("application/octet-stream", new File(appFile.getRemote()));
            // TODO: This if/else can probably be nicer
            final int uploadedVersionCode;
            if (appFile.getName().endsWith(".aab")) {
                Bundle uploadedBundle = editService.bundles().upload(applicationId, editId, fileToUpload).execute();
                uploadedVersionCode = uploadedBundle.getVersionCode();
                uploadedVersionCodes.add(uploadedVersionCode);
            } else {
                Apk uploadedApk = editService.apks().upload(applicationId, editId, fileToUpload).execute();
                uploadedVersionCode = uploadedApk.getVersionCode();
                uploadedVersionCodes.add(uploadedVersionCode);
            }

            // Upload the ProGuard mapping file for this file, if there is one
            final FilePath mappingFile = appFilesToMappingFiles.get(appFile);
            if (mappingFile != null) {
                final String relativeFileName = getRelativeFileName(mappingFile);

                // Google Play API doesn't accept empty mapping files
                logger.println(String.format(" Mapping file size: %s", mappingFile.length()));
                if (mappingFile.length() == 0) {
                    logger.println(String.format(" Ignoring empty ProGuard mapping file: %s", relativeFileName));
                } else {
                    logger.println(String.format(" Uploading associated ProGuard mapping file: %s", relativeFileName));
                    FileContent mapping =
                            new FileContent("application/octet-stream", new File(mappingFile.getRemote()));
                    editService.deobfuscationfiles().upload(applicationId, editId, uploadedVersionCode,
                            DEOBFUSCATION_FILE_TYPE_PROGUARD, mapping).execute();
                }
            }
            logger.println("");
        }

        // Upload the expansion files, or associate the previous ones, if configured
        if (!expansionFiles.isEmpty() || usePreviousExpansionFilesIfMissing) {
            for (int versionCode : uploadedVersionCodes) {
                ExpansionFileSet fileSet = expansionFiles.get((long) versionCode);
                FilePath mainFile = fileSet == null ? null : fileSet.getMainFile();
                FilePath patchFile = fileSet == null ? null : fileSet.getPatchFile();

                logger.println(String.format("Handling expansion files for versionCode %d", versionCode));
                applyExpansionFile(versionCode, OBB_FILE_TYPE_MAIN, mainFile, usePreviousExpansionFilesIfMissing);
                applyExpansionFile(versionCode, OBB_FILE_TYPE_PATCH, patchFile, usePreviousExpansionFilesIfMissing);
                logger.println();
            }
        }

        // Assign all uploaded app files to the configured track
        List<LocalizedText> releaseNotes = Util.transformReleaseNotes(recentChangeList);
        TrackRelease release = Util.buildRelease(uploadedVersionCodes, rolloutFraction, releaseNotes);
        assignAppFilesToTrack(track, rolloutFraction, release);

        // Commit all the changes
        try {
            logger.println("Applying changes to Google Play...");
            editService.commit(applicationId, editId).execute();
        } catch (SocketTimeoutException e) {
            // The API is quite prone to timing out for no apparent reason,
            // despite having successfully committed the changes on the backend.
            // So here we check whether the files uploaded were actually committed
            logger.println(String.format("- An error occurred while applying changes: %s", e));
            logger.println("- Checking whether the changes have been applied anyway...\n");
            if (!wereAppFilesUploaded(uploadedVersionCodes)) {
                logger.println("The files that were uploaded were not found on Google Play");
                logger.println("- No changes have been applied to the Google Play account");
                return false;
            }
        }

        // If committing didn't throw an exception, everything worked fine
        logger.println("Changes were successfully applied to Google Play");
        return true;
    }

    /** Applies an expansion file to an APK, whether from a given file, or by using previously-uploaded file. */
    private void applyExpansionFile(int versionCode, String type, FilePath filePath, boolean usePreviousIfMissing)
            throws IOException {
        // If there was a file provided, simply upload it
        if (filePath != null) {
            logger.println(String.format("- Uploading new %s expansion file: %s", type, filePath.getName()));
            uploadExpansionFile(versionCode, type, filePath);
            return;
        }

        // Otherwise, check whether we should reuse an existing expansion file
        if (usePreviousIfMissing) {
            // Ensure we know what the latest expansion files versions are
            fetchLatestExpansionFileVersionCodes();

            // If there is no previous APK with this type of expansion file, there's nothing we can do
            final int latestVersionCodeWithExpansion = type.equals(OBB_FILE_TYPE_MAIN) ?
                    latestMainExpansionFileVersionCode : latestPatchExpansionFileVersionCode;
            if (latestVersionCodeWithExpansion == -1) {
                logger.println(String.format("- No %1$s expansion file to apply, and no existing APK with a %1$s " +
                        "expansion file was found", type));
                return;
            }

            // Otherwise, associate the latest expansion file of this type with the new APK
            logger.println(String.format("- Applying %s expansion file from previous APK: %d", type,
                    latestVersionCodeWithExpansion));
            ExpansionFile fileRef = new ExpansionFile().setReferencesVersion(latestVersionCodeWithExpansion);
            editService.expansionfiles().update(applicationId, editId, versionCode, type, fileRef).execute();
            return;
        }

        // If we don't want to reuse an existing file, then there's nothing to do
        logger.println(String.format("- No %s expansion file to apply", type));
    }

    /** Determines whether there are already-existing APKs for this app which have expansion files associated. */
    private void fetchLatestExpansionFileVersionCodes() throws IOException {
        // Don't do this again if we've already attempted to find the expansion files
        if (latestMainExpansionFileVersionCode != 0 && latestPatchExpansionFileVersionCode != 0) {
            return;
        }

        // Sort the existing APKs so that the newest come first
        Collections.sort(existingVersionCodes);
        Collections.reverse(existingVersionCodes);

        // Find the latest APK with a main expansion file, and the latest with a patch expansion file
        latestMainExpansionFileVersionCode = fetchLatestExpansionFileVersionCode(OBB_FILE_TYPE_MAIN);
        latestPatchExpansionFileVersionCode = fetchLatestExpansionFileVersionCode(OBB_FILE_TYPE_PATCH);
    }

    /** @return The version code of the newest APK which has an expansion file of this type, else {@code -1}. */
    private int fetchLatestExpansionFileVersionCode(String type) throws IOException {
        // Find the latest APK with a patch expansion file
        for (int versionCode : existingVersionCodes) {
            ExpansionFile file = getExpansionFile(versionCode, type);
            if (file == null) {
                continue;
            }
            if (file.getFileSize() != null && file.getFileSize() > 0) {
                return versionCode;
            }
            if (file.getReferencesVersion() != null && file.getReferencesVersion() > 0) {
                return file.getReferencesVersion();
            }
        }

        // There's no existing expansion file of this type
        return -1;
    }

    /** @return The expansion file API info for the given criteria, or {@code null} if no such file exists. */
    private ExpansionFile getExpansionFile(int versionCode, String type) throws IOException {
        try {
            return editService.expansionfiles().get(applicationId, editId, versionCode, type).execute();
        } catch (GoogleJsonResponseException e) {
            // A 404 response from the API means that there is no such expansion file/reference
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Uploads the given file as an certain type expansion file, associating it with a given APK.
     *
     * @return The expansion file API response.
     */
    private ExpansionFilesUploadResponse uploadExpansionFile(int versionCode, String type, FilePath filePath)
            throws IOException {
        FileContent file = new FileContent("application/octet-stream", new File(filePath.getRemote()));
        return editService.expansionfiles().upload(applicationId, editId, versionCode, type, file).execute();
    }

    /**
     * Starts a new API session and determines whether a list of version codes were successfully uploaded.
     *
     * @param uploadedVersionCodes The list to be checked for existence.
     * @return {@code true} if the version codes in the list were found to now exist on Google Play.
     */
    private boolean wereAppFilesUploaded(Collection<Integer> uploadedVersionCodes) throws IOException {
        // Last edit is finished; create a new one to get the current state
        createEdit(applicationId);

        // Get the current list of version codes
        List<Integer> currentVersionCodes = new ArrayList<>();
        // TODO: Also check for AABs
        List<Apk> currentApks = editService.apks().list(applicationId, editId).execute().getApks();
        if (currentApks == null) currentApks = Collections.emptyList();
        for (Apk apk : currentApks) {
            currentVersionCodes.add(apk.getVersionCode());
        }

        // The upload succeeded if the current list of version codes intersects with the list we tried to upload
        return uploadedVersionCodes.removeAll(currentVersionCodes);
    }

    /** @return The path to the given file, relative to the build workspace. */
    private String getRelativeFileName(FilePath file) {
        final String ws = workspace.getRemote();
        String path = file.getRemote();
        if (path.startsWith(ws) && path.length() > ws.length()) {
            path = path.substring(ws.length());
        }
        if (path.charAt(0) == File.separatorChar && path.length() > 1) {
            path = path.substring(1);
        }
        return path;
    }

    /** @return The SHA-1 hash of the given file, as a lower-case hex string. */
    private static String getSha1Hash(String path) throws IOException {
        try (FileInputStream fis = new FileInputStream(path)) {
            return DigestUtils.sha1Hex(fis).toLowerCase(Locale.ROOT);
        }
    }

}
