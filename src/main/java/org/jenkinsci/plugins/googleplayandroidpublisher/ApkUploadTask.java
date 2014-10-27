package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.FileContent;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.Apk;
import com.google.api.services.androidpublisher.model.ApkListing;
import com.google.api.services.androidpublisher.model.ExpansionFile;
import com.google.api.services.androidpublisher.model.ExpansionFilesUploadResponse;
import com.google.api.services.androidpublisher.model.Track;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.FilePath;
import hudson.model.BuildListener;
import net.dongliu.apk.parser.bean.ApkMeta;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static hudson.Util.join;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ApkPublisher.ExpansionFileSet;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ApkPublisher.PERCENTAGE_FORMATTER;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ApkPublisher.RecentChanges;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ApkPublisher.TYPE_MAIN;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ApkPublisher.TYPE_PATCH;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.ALPHA;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.BETA;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.PRODUCTION;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.ROLLOUT;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getApkMetadata;

class ApkUploadTask extends AbstractPublisherTask<Boolean> {

    private final String applicationId;
    private final List<FilePath> apkFiles;
    private final Map<Integer, ExpansionFileSet> expansionFiles;
    private final boolean usePreviousExpansionFilesIfMissing;
    private final ReleaseTrack track;
    private final double rolloutFraction;
    private final RecentChanges[] recentChangeList;
    private final List<Integer> existingVersionCodes;
    private int latestMainExpansionFileVersionCode;
    private int latestPatchExpansionFileVersionCode;

    ApkUploadTask(BuildListener listener, GoogleRobotCredentials credentials, String applicationId,
            List<FilePath> apkFiles, Map<Integer, ExpansionFileSet> expansionFiles,
            boolean usePreviousExpansionFilesIfMissing, ReleaseTrack track, double rolloutPercentage,
            ApkPublisher.RecentChanges[] recentChangeList) {
        super(listener, credentials);
        this.applicationId = applicationId;
        this.apkFiles = apkFiles;
        this.expansionFiles = expansionFiles;
        this.usePreviousExpansionFilesIfMissing = usePreviousExpansionFilesIfMissing;
        this.track = track;
        this.rolloutFraction = rolloutPercentage / 100d;
        this.recentChangeList = recentChangeList;
        this.existingVersionCodes = new ArrayList<Integer>();
    }

    protected Boolean execute() throws IOException, InterruptedException, UploadException {
        // Open an edit via the Google Play API, thereby ensuring that our credentials etc. are working
        createEdit(applicationId);

        // Get the list of existing APKs and their info
        final List<Apk> existingApks = editService.apks().list(applicationId, editId).execute().getApks();
        for (Apk apk : existingApks) {
            existingVersionCodes.add(apk.getVersionCode());
        }

        // Upload each of the APKs
        logger.println(String.format("Uploading APK(s) with application ID: %s", applicationId));
        final SortedSet<Integer> uploadedVersionCodes = new TreeSet<Integer>();
        for (FilePath apkFile : apkFiles) {
            final ApkMeta metadata = getApkMetadata(new File(apkFile.getRemote()));
            final String apkSha1Hash = getSha1Hash(apkFile.getRemote());

            // Log some useful information about the file that will be uploaded
            logger.println(String.format("      APK file: %s", apkFile.getName()));
            logger.println(String.format("    SHA-1 hash: %s", apkSha1Hash));
            logger.println(String.format("   versionCode: %d", metadata.getVersionCode()));
            logger.println(String.format(" minSdkVersion: %s", metadata.getMinSdkVersion()));
            logger.println();

            // Check whether this APK already exists on the server (i.e. uploading it would fail)
            for (Apk apk : existingApks) {
                if (apk.getBinary().getSha1().toLowerCase(Locale.ENGLISH).equals(apkSha1Hash)) {
                    logger.println("This APK already exists on the server; it cannot be uploaded again");
                    return false;
                }
            }

            // If not, we can upload the file
            FileContent apk =
                    new FileContent("application/vnd.android.package-archive", new File(apkFile.getRemote()));
            Apk uploadedApk = editService.apks().upload(applicationId, editId, apk).execute();
            uploadedVersionCodes.add(uploadedApk.getVersionCode());
        }

        // Upload the expansion files, or associate the previous ones, if configured
        if (!expansionFiles.isEmpty() || usePreviousExpansionFilesIfMissing) {
            for (int versionCode : uploadedVersionCodes) {
                ExpansionFileSet fileSet = expansionFiles.get(versionCode);
                FilePath mainFile = fileSet == null ? null : fileSet.getMainFile();
                FilePath patchFile = fileSet == null ? null : fileSet.getPatchFile();

                logger.println(String.format("Handling expansion files for versionCode %d", versionCode));
                applyExpansionFile(versionCode, TYPE_MAIN, mainFile, usePreviousExpansionFilesIfMissing);
                applyExpansionFile(versionCode, TYPE_PATCH, patchFile, usePreviousExpansionFilesIfMissing);
                logger.println();
            }
        }

        // Prepare to assign the APK(s) to the desired track
        final Track trackToAssign = new Track();
        trackToAssign.setTrack(track.getApiValue());
        trackToAssign.setVersionCodes(new ArrayList<Integer>(uploadedVersionCodes));
        if (track == PRODUCTION) {
            // Remove older APKs from the beta track
            unassignOlderApks(BETA, uploadedVersionCodes.first());

            // If there's an existing rollout, we need to clear it out so a new production/rollout APK can be added
            final Track rolloutTrack = fetchTrack(ROLLOUT);
            if (rolloutTrack != null) {
                logger.println(String.format("Removing existing staged rollout APK(s): %s",
                        join(rolloutTrack.getVersionCodes(), ", ")));
                rolloutTrack.setVersionCodes(null);
                editService.tracks().update(applicationId, editId, rolloutTrack.getTrack(), rolloutTrack).execute();
            }

            // Check whether we want a new staged rollout
            if (rolloutFraction < 1) {
                // Override the track name
                trackToAssign.setTrack(ROLLOUT.getApiValue());
                trackToAssign.setUserFraction(rolloutFraction);

                // Check whether we also need to override the desired rollout percentage
                Double currentFraction = rolloutTrack == null ? rolloutFraction : rolloutTrack.getUserFraction();
                if (currentFraction != null && currentFraction > rolloutFraction) {
                    logger.println(String.format("Staged rollout percentage will remain at %s%% rather than the " +
                                    "configured %s%% because there were APK(s) already in a staged rollout, and " +
                                    "Google Play makes it impossible to reduce the rollout percentage in this case",
                            PERCENTAGE_FORMATTER.format(currentFraction * 100),
                            PERCENTAGE_FORMATTER.format(rolloutFraction * 100)));
                    trackToAssign.setUserFraction(currentFraction);
                }
            }
        } else if (rolloutFraction < 1) {
            logger.println("Ignoring staged rollout percentage as it only applies to production releases");
        }

        // Remove older APKs from the alpha track
        unassignOlderApks(ALPHA, uploadedVersionCodes.first());

        // Assign the new APK(s) to the desired track
        if (trackToAssign.getTrack().equals(ROLLOUT.getApiValue())) {
            logger.println(
                    String.format("Assigning uploaded APK(s) to be rolled out to %s%% of production users...",
                            PERCENTAGE_FORMATTER.format(trackToAssign.getUserFraction() * 100)));
        } else {
            logger.println(String.format("Assigning uploaded APK(s) to %s release track...", track));
        }
        Track updatedTrack = editService.tracks()
                .update(applicationId, editId, trackToAssign.getTrack(), trackToAssign)
                .execute();
        logger.println(String.format("The %s release track will now contain the APK(s): %s", track,
                join(updatedTrack.getVersionCodes(), ", ")));

        // Apply recent changes text to the APK(s), if provided
        if (recentChangeList != null) {
            for (Integer versionCode : uploadedVersionCodes) {
                AndroidPublisher.Edits.Apklistings listings = editService.apklistings();
                for (RecentChanges changes : recentChangeList) {
                    ApkListing listing =
                            new ApkListing().setLanguage(changes.language).setRecentChanges(changes.text);
                    listings.update(applicationId, editId, versionCode, changes.language, listing).execute();
                }
            }
        }

        // Commit all the changes
        editService.commit(applicationId, editId).execute();
        logger.println("Changes were successfully applied to Google Play");

        return true;
    }

    /** Applies an expansion file to an APK, whether from a given file, or by using previously-uploaded file.  */
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
            final int latestVersionCodeWithExpansion = type.equals(TYPE_MAIN) ?
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
        latestMainExpansionFileVersionCode = fetchLatestExpansionFileVersionCode(TYPE_MAIN);
        latestPatchExpansionFileVersionCode = fetchLatestExpansionFileVersionCode(TYPE_PATCH);
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

    /** @return The SHA-1 hash of the given file, as a lower-case hex string. */
    private static String getSha1Hash(String path) throws IOException {
        return DigestUtils.shaHex(new FileInputStream(path)).toLowerCase(Locale.ENGLISH);
    }

    /** @return The desired track fetched from the API, or {@code null} if the track has no APKs assigned. */
    private Track fetchTrack(ReleaseTrack track) throws IOException {
        final List<Track> existingTracks = editService.tracks().list(applicationId, editId).execute().getTracks();
        for (Track t : existingTracks) {
            if (t.getTrack().equals(track.getApiValue())) {
                return t;
            }
        }
        return null;
    }

    /**
     * Removes old version codes from the given track on the server, if it exists.
     *
     * @param track The track whose assigned versions should be changed.
     * @param maxVersionCode The maximum allowed version code; all lower than this will be removed from the track.
     */
    private void unassignOlderApks(ReleaseTrack track, int maxVersionCode) throws IOException {
        final Track trackToAssign = fetchTrack(track);
        if (trackToAssign == null || trackToAssign.getVersionCodes() == null) {
            return;
        }

        List<Integer> versionCodes = new ArrayList<Integer>(trackToAssign.getVersionCodes());
        for (Iterator<Integer> it = versionCodes.iterator(); it.hasNext(); ) {
            if (it.next() < maxVersionCode) {
                it.remove();
            }
        }
        trackToAssign.setVersionCodes(versionCodes);
        editService.tracks().update(applicationId, editId, trackToAssign.getTrack(), trackToAssign).execute();
    }

}
