package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.common.annotations.VisibleForTesting;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Publisher;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import net.dongliu.apk.parser.exception.ParserException;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.AppFileFormat;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.UploadFile;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.zip.ZipException;

import static hudson.Util.fixEmptyAndTrim;
import static hudson.Util.join;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.OBB_FILE_REGEX;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.OBB_FILE_TYPE_MAIN;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.PERCENTAGE_FORMATTER;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.fromConfigValue;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.REGEX_LANGUAGE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.REGEX_VARIABLE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.SUPPORTED_LANGUAGES;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getPublisherErrorMessage;

/** Uploads Android application files to the Google Play Developer Console. */
public class ApkPublisher extends GooglePlayPublisher {

    @VisibleForTesting String filesPattern;
    private String deobfuscationFilesPattern;
    private String expansionFilesPattern;
    private boolean usePreviousExpansionFilesIfMissing;
    @VisibleForTesting String trackName;
    private Double rolloutPercent;
    private RecentChanges[] recentChangeList;

    @Deprecated private transient String apkFilesPattern;
    @Deprecated private transient String rolloutPercentage;

    @DataBoundConstructor
    public ApkPublisher() {
        // No parameters here are mandatory, though the credentials in the parent class are
    }

    @SuppressWarnings("unused")
    protected Object readResolve() {
        // Migrate from old `apkFilesPattern` to `filesPattern`
        if (apkFilesPattern != null) {
            setFilesPattern(apkFilesPattern);
        }

        // Migrate from `rolloutPercentage` string to numeric `rolloutPercent`
        if (rolloutPercentage != null) {
            setRolloutPercentage(rolloutPercentage);
        }

        return this;
    }

    // Required for Pipeline builds still using `apkFilesPattern`
    @Deprecated
    @DataBoundSetter
    public void setApkFilesPattern(String value) {
        setFilesPattern(value);
    }

    // Required for the Snippet Generator, since the field has a @DataBoundSetter
    @Deprecated
    public String getApkFilesPattern() {
        return getFilesPattern();
    }

    @DataBoundSetter
    public void setFilesPattern(@Nonnull String pattern) {
        this.filesPattern = DescriptorImpl.defaultFilesPattern.equals(pattern) ? null : pattern;
    }

    @Nonnull
    public String getFilesPattern() {
        return fixEmptyAndTrim(filesPattern) == null ? DescriptorImpl.defaultFilesPattern : filesPattern;
    }

    // Required for Pipeline builds still using `rolloutPercentage`
    @Deprecated
    @DataBoundSetter
    public void setRolloutPercentage(String percentage) {
        String input = percentage.replace("%", "").trim();
        double value;
        try {
            value = Double.parseDouble(input);
        } catch (NumberFormatException ignore) {
            value = DescriptorImpl.defaultRolloutPercent;
        }
        setRolloutPercent(value);
    }

    // Required for the Snippet Generator, since the field has a @DataBoundSetter
    @Nonnull
    @Deprecated
    public String getRolloutPercentage() {
        double value = rolloutPercent == null ? DescriptorImpl.defaultRolloutPercent : rolloutPercent;
        return String.valueOf(value);
    }

    @DataBoundSetter
    public void setRolloutPercent(Double percent) {
        this.rolloutPercent = (percent == null || percent.intValue() == DescriptorImpl.defaultRolloutPercent) ? null : percent;
    }

    @Nonnull
    @SuppressFBWarnings("BX_UNBOXING_IMMEDIATELY_REBOXED")
    public Double getRolloutPercent() {
        return rolloutPercent == null ? DescriptorImpl.defaultRolloutPercent : rolloutPercent;
    }

    @DataBoundSetter
    public void setTrackName(@Nonnull String trackName) {
        this.trackName = DescriptorImpl.defaultTrackName.equalsIgnoreCase(trackName) ? null : trackName;
    }

    @Nonnull
    public String getTrackName() {
        return fixEmptyAndTrim(trackName) == null ? DescriptorImpl.defaultTrackName : trackName;
    }

    @DataBoundSetter
    public void setDeobfuscationFilesPattern(String deobfuscationFilesPattern) {
        this.deobfuscationFilesPattern = deobfuscationFilesPattern;
    }

    public String getDeobfuscationFilesPattern() {
        return fixEmptyAndTrim(deobfuscationFilesPattern);
    }

    @DataBoundSetter
    public void setExpansionFilesPattern(String expansionFilesPattern) {
        this.expansionFilesPattern = expansionFilesPattern;
    }

    public String getExpansionFilesPattern() {
        return fixEmptyAndTrim(expansionFilesPattern);
    }

    @DataBoundSetter
    public void setUsePreviousExpansionFilesIfMissing(Boolean value) {
        if (value == null) {
            this.usePreviousExpansionFilesIfMissing = true;
        } else {
            this.usePreviousExpansionFilesIfMissing = value;
        }
    }

    public boolean getUsePreviousExpansionFilesIfMissing() {
        return usePreviousExpansionFilesIfMissing;
    }

    @DataBoundSetter
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public void setRecentChangeList(RecentChanges[] recentChangeList) {
        this.recentChangeList = recentChangeList;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public RecentChanges[] getRecentChangeList() {
        return recentChangeList;
    }

    private String getExpandedFilesPattern() throws IOException, InterruptedException {
        return expand(getFilesPattern());
    }

    private String getCanonicalTrackName() throws IOException, InterruptedException {
        String name = expand(getTrackName());
        if (name == null) {
            return null;
        }
        return name.toLowerCase(Locale.ENGLISH);
    }

    private String getExpandedDeobfuscationFilesPattern() throws IOException, InterruptedException {
        return expand(getDeobfuscationFilesPattern());
    }

    private String getExpandedExpansionFilesPattern() throws IOException, InterruptedException {
        return expand(getExpansionFilesPattern());
    }

    private RecentChanges[] getExpandedRecentChangesList() throws IOException, InterruptedException {
        if (recentChangeList == null) {
            return null;
        }
        RecentChanges[] expanded = new RecentChanges[recentChangeList.length];
        for (int i = 0; i < recentChangeList.length; i++) {
            RecentChanges r = recentChangeList[i];
            expanded[i] = new RecentChanges(expand(r.language), expand(r.text));
        }
        return expanded;
    }

    private boolean isConfigValid(PrintStream logger) throws IOException, InterruptedException {
        final List<String> errors = new ArrayList<>();

        // Check whether a file pattern was provided
        if (getExpandedFilesPattern() == null) {
            errors.add("Relative path, or pattern to locate AAB or APK file(s) was not specified");
        }

        // Track name is also required
        final String trackName = getCanonicalTrackName();
        final ReleaseTrack track = fromConfigValue(trackName);
        if (trackName == null) {
            errors.add("Release track was not specified");
        } else if (track == null) {
            errors.add(String.format("'%s' is not a valid release track", trackName));
        } else {
            // Check for valid rollout percentage
            double pct = getRolloutPercent();
            if (Double.compare(pct, 0) < 0 || Double.compare(pct, 100) > 0) {
                errors.add(String.format("%s%% is not a valid rollout percentage", PERCENTAGE_FORMATTER.format(pct)));
            }
        }

        // Print accumulated errors
        if (!errors.isEmpty()) {
            logger.println("Cannot upload to Google Play:");
            for (String error : errors) {
                logger.print("- ");
                logger.println(error);
            }
        }

        return errors.isEmpty();
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher,
                        @Nonnull TaskListener listener) throws InterruptedException, IOException {
        super.perform(run, workspace, launcher, listener);

        // Calling publishApk logs the reason when a failure occurs, so in that case we just need to throw here
        if (!publishApk(run, workspace, listener)) {
            throw new AbortException("Upload to Google Play failed");
        }
    }

    private boolean publishApk(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull TaskListener listener)
            throws InterruptedException, IOException {
        final PrintStream logger = listener.getLogger();

        // Check whether we should execute at all
        final Result buildResult = run.getResult();
        if (buildResult != null && buildResult.isWorseThan(Result.UNSTABLE)) {
            logger.println("Skipping upload to Google Play due to build result");
            return true;
        }

        // Check that the job has been configured correctly
        if (!isConfigValid(logger)) {
            return false;
        }

        // Find the filename(s) which match the pattern after variable expansion
        final String filesPattern = getExpandedFilesPattern();
        List<String> relativePaths = workspace.act(new FindFilesTask(filesPattern));
        if (relativePaths.isEmpty()) {
            logger.println(String.format("No AAB or APK files matching the pattern '%s' could be found", filesPattern));
            return false;
        }

        // Get the full remote path in the workspace for each filename
        final List<UploadFile> validFiles = new ArrayList<>();
        for (String path : relativePaths) {
            FilePath file = workspace.child(path);
            try {
                // Attempt to parse the file as an Android app
                UploadFile uploadFile = new UploadFile(file);
                validFiles.add(uploadFile);
            } catch (ZipException e) {
                // If the file is empty or not a zip file, we don't need to dump the whole stacktrace
                logger.println(String.format("File does not appear to be a valid AAB or APK: %s", file.getRemote()));
                return false;
            } catch (ParserException e) {
                // Show a bit more information for APK parse exceptions
                logger.println(String.format("File does not appear to be a valid APK: %s%n- %s",
                        file.getRemote(), e.getMessage()));
                return false;
            } catch (IOException e) {
                // Otherwise, it's something more esoteric, so rethrow, dumping the stacktrace to the log
                logger.println(String.format("File does not appear to be a valid AAB or APK: %s", file.getRemote()));
                throw e;
            }
        }

        // If there are multiple matches, ensure that all have the same application ID
        final Set<String> applicationIds = validFiles.stream()
                .map(UploadFile::getApplicationId).collect(Collectors.toSet());
        if (applicationIds.size() != 1) {
            logger.println(String.format("Multiple files matched the pattern '%s', " +
                            "but they have inconsistent application IDs:", filesPattern));
            for (String id : applicationIds) {
                logger.print("- ");
                logger.println(id);
            }
            return false;
        }

        // If both APKs and bundles were found, then prefer the bundles.
        // e.g. a release job may build a bundle for upload, but also build a fat APK for archiving,
        // or testing, etc. (though really the user should configure `apkFilesPattern` more sensibly)
        boolean hasMultipleFileTypes = validFiles.stream()
                .map(UploadFile::getFileFormat)
                .collect(Collectors.toSet())
                .size() > 1;
        if (hasMultipleFileTypes) {
            logger.println("Both AAB and APK files were found; only the AAB files will be uploaded");
            List<UploadFile> nonBundleFiles = validFiles.stream()
                    .filter(f -> f.getFileFormat() != AppFileFormat.BUNDLE)
                    .collect(Collectors.toList());
            validFiles.removeAll(nonBundleFiles);
        }

        // Find the obfuscation mapping filename(s) which match the pattern after variable expansion
        final String mappingFilesPattern = getExpandedDeobfuscationFilesPattern();
        if (getExpandedDeobfuscationFilesPattern() != null) {
            List<String> relativeMappingPaths = workspace.act(new FindFilesTask(mappingFilesPattern));
            if (relativeMappingPaths.isEmpty()) {
                logger.println(String.format("No obfuscation mapping files matching the pattern '%s' could be found; " +
                        "no files will be uploaded", filesPattern));
                return false;
            }

            // Create a mapping of app files to their obfuscation mapping file
            if (relativeMappingPaths.size() == 1) {
                // If there is only one mapping file, associate it with each of the app files
                FilePath mappingFile = workspace.child(relativeMappingPaths.get(0));
                for (UploadFile appFile : validFiles) {
                    appFile.setMappingFile(mappingFile);
                }
            } else if (relativeMappingPaths.size() == validFiles.size()) {
                // If there are multiple mapping files, this usually means that there is one per dimension;
                // the folder structure will typically look like this for the app files and their mapping files:
                //
                // - build/outputs/apk/dimension_one/release/app-release.apk
                // - build/outputs/apk/dimension_two/release/app-release.apk
                // - build/outputs/mapping/dimension_one/release/mapping.txt
                // - build/outputs/mapping/dimension_two/release/mapping.txt
                //
                // i.e. an app file and its mapping file don't share the same path prefix, but as the directories are named
                // by dimension, we assume that the order of the output of both FindFileTasks here will be the same
                //
                // We use this assumption here to associate the individual mapping files with the discovered app files
                for (int i = 0, n = validFiles.size(); i < n; i++) {
                    FilePath mappingFile = workspace.child(relativeMappingPaths.get(i));
                    validFiles.get(i).setMappingFile(mappingFile);
                }
            } else {
                // If, for some reason, the number of app files don't match, we won't deal with this situation
                logger.println(String.format("There are %d AAB/APKs to be uploaded, but only %d obfuscation mapping " +
                        "files were found matching the pattern '%s':",
                        validFiles.size(), relativeMappingPaths.size(), mappingFilesPattern));
                for (String path : relativePaths) {
                    logger.println(String.format("- %s", path));
                }
                for (String path : relativeMappingPaths) {
                    logger.println(String.format("- %s", path));
                }
                return false;
            }
        }

        final String applicationId = applicationIds.iterator().next();

        // Find the expansion filename(s) which match the pattern after variable expansion
        final Map<Long, ExpansionFileSet> expansionFiles = new TreeMap<>();
        final String expansionPattern = getExpandedExpansionFilesPattern();
        if (expansionPattern != null) {
            List<String> expansionPaths = workspace.act(new FindFilesTask(expansionPattern));

            // Check that the expansion files found apply to the app files to be uploaded
            for (String path : expansionPaths) {
                FilePath file = workspace.child(path);

                // Check that the filename is in the right format
                Matcher matcher = OBB_FILE_REGEX.matcher(file.getName());
                if (!matcher.matches()) {
                    logger.println(String.format("Expansion file '%s' doesn't match the required naming scheme", path));
                    return false;
                }

                // We can only associate expansion files with the application ID we're going to upload
                final String appId = matcher.group(3);
                if (!applicationId.equals(appId)) {
                    logger.println(String.format("Expansion filename '%s' doesn't match the application ID to be "
                            + "uploaded: %s", path, applicationId));
                    return false;
                }

                // We can only associate expansion files with version codes we're going to upload
                final long versionCode = Long.parseLong(matcher.group(2));
                final Set<Long> versionCodes = validFiles.stream()
                        .map(UploadFile::getVersionCode).collect(Collectors.toSet());
                if (!versionCodes.contains(versionCode)) {
                    logger.println(String.format("Expansion filename '%s' doesn't match the versionCode of any of "
                            + "APK(s) to be uploaded: %s", path, join(versionCodes, ", ")));
                    return false;
                }

                // File looks good, so add it to the fileset for this version code
                final String type = matcher.group(1).toLowerCase(Locale.ENGLISH);
                ExpansionFileSet fileSet = expansionFiles.get(versionCode);
                if (fileSet == null) {
                    fileSet = new ExpansionFileSet();
                    expansionFiles.put(versionCode, fileSet);
                }
                if (type.equals(OBB_FILE_TYPE_MAIN)) {
                    fileSet.setMainFile(file);
                } else {
                    fileSet.setPatchFile(file);
                }
            }

            // If there are patch files, make sure that each has a main file, or "use previous if missing" is enabled
            for (ExpansionFileSet fileSet : expansionFiles.values()) {
                if (!usePreviousExpansionFilesIfMissing && fileSet.getPatchFile() != null
                        && fileSet.getMainFile() == null) {
                    logger.println(String.format("Patch expansion file '%s' was provided, but no main expansion file " +
                            "was provided, and the option to reuse a pre-existing expansion file was " +
                            "disabled.%nGoogle Play requires that each APK with a patch file also has a main " +
                            "file.", fileSet.getPatchFile().getName()));
                    return false;
                }
            }
        }

        // Upload the file(s) from the workspace
        try {
            GoogleRobotCredentials credentials = getCredentialsHandler().getServiceAccountCredentials(run.getParent());
            return workspace.act(new ApkUploadTask(listener, credentials, applicationId, workspace, validFiles,
                    expansionFiles, usePreviousExpansionFilesIfMissing, fromConfigValue(getCanonicalTrackName()),
                    getRolloutPercent(), getExpandedRecentChangesList()));
        } catch (UploadException e) {
            logger.println(String.format("Upload failed: %s", getPublisherErrorMessage(e)));
            logger.println("No changes have been applied to the Google Play account");
        }
        return false;
    }

    static final class ExpansionFileSet implements Serializable {

        private static final long serialVersionUID = 1;

        FilePath mainFile;
        FilePath patchFile;

        public FilePath getMainFile() {
            return mainFile;
        }

        public void setMainFile(FilePath mainFile) {
            this.mainFile = mainFile;
        }

        public FilePath getPatchFile() {
            return patchFile;
        }

        public void setPatchFile(FilePath patchFile) {
            this.patchFile = patchFile;
        }

    }

    public static final class RecentChanges extends AbstractDescribableImpl<RecentChanges> implements Serializable {

        private static final long serialVersionUID = 1;

        @Exported
        public final String language;

        @Exported
        public final String text;

        @DataBoundConstructor
        public RecentChanges(String language, String text) {
            this.language = language;
            this.text = text;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<RecentChanges> {

            @Override
            public String getDisplayName() {
                return "Recent changes";
            }

            public ComboBoxModel doFillLanguageItems() {
                return new ComboBoxModel(SUPPORTED_LANGUAGES);
            }

            public FormValidation doCheckLanguage(@QueryParameter String value) {
                value = fixEmptyAndTrim(value);
                if (value != null && !value.matches(REGEX_LANGUAGE) && !value.matches(REGEX_VARIABLE)) {
                    return FormValidation.warning("Should be a language code like 'be' or 'en-GB'");
                }
                return FormValidation.ok();
            }

            public FormValidation doCheckText(@QueryParameter String value) {
                value = fixEmptyAndTrim(value);
                if (value != null && value.length() > 500) {
                    return FormValidation.error("Recent changes text must be 500 characters or fewer");
                }
                return FormValidation.ok();
            }

        }

    }

    @Symbol("androidApkUpload")
    @Extension
    public static final class DescriptorImpl extends GooglePlayBuildStepDescriptor<Publisher> {
        public static final String defaultFilesPattern = "**/build/outputs/**/*.aab, **/build/outputs/**/*.apk";
        public static final String defaultTrackName = ReleaseTrack.PRODUCTION.getApiValue();
        public static final int defaultRolloutPercent = 100;

        public String getDisplayName() {
            return "Upload Android AAB/APKs to Google Play";
        }

    }

}
