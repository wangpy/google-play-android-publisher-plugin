package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.common.annotations.VisibleForTesting;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import net.dongliu.apk.parser.exception.ParserException;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.UploadFile;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static hudson.Util.fixEmptyAndTrim;
import static hudson.Util.tryParseNumber;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.PERCENTAGE_FORMATTER;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.fromConfigValue;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getPublisherErrorMessage;

public class ReleaseTrackAssignmentBuilder extends GooglePlayBuilder {

    @VisibleForTesting Boolean fromVersionCode;
    @VisibleForTesting String applicationId;
    @VisibleForTesting String versionCodes;
    private String filesPattern;
    @VisibleForTesting String trackName;
    @VisibleForTesting Double rolloutPercent;

    @Deprecated private transient String apkFilesPattern;
    @Deprecated private transient String rolloutPercentage;

    @DataBoundConstructor
    public ReleaseTrackAssignmentBuilder() {
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

    @DataBoundSetter
    public void setFromVersionCode(Boolean fromVersionCode) {
        this.fromVersionCode = fromVersionCode;
    }

    public Boolean getFromVersionCode() {
        return fromVersionCode;
    }

    public boolean isFromVersionCode() {
        return fromVersionCode == null || fromVersionCode;
    }

    @DataBoundSetter
    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public String getApplicationId() {
        return applicationId;
    }

    @DataBoundSetter
    public void setVersionCodes(String versionCodes) {
        this.versionCodes = versionCodes;
    }

    public String getVersionCodes() {
        return versionCodes;
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

    private String getExpandedApplicationId() throws IOException, InterruptedException {
        return expand(getApplicationId());
    }

    private String getExpandedVersionCodes() throws IOException, InterruptedException {
        return expand(getVersionCodes());
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

    private boolean isConfigValid(PrintStream logger) throws IOException, InterruptedException {
        final List<String> errors = new ArrayList<>();

        // Check whether the relevant values were provided, based on the method chosen
        if (isFromVersionCode()) {
            if (getExpandedApplicationId() == null) {
                errors.add("No application ID was specified");
            }
            if (getExpandedVersionCodes() == null) {
                errors.add("No version codes were specified");
            }
        } else if (getExpandedFilesPattern() == null) {
            errors.add("Path or pattern to AAB/APK file(s) was not specified");
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
            logger.println("Cannot make changes to Google Play:");
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

        // Calling assignAppFiles logs the reason when a failure occurs, so in that case we just need to throw here
        if (!assignAppFiles(run, workspace, listener)) {
            throw new AbortException("Assignment failed");
        }
    }

    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private boolean assignAppFiles(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull TaskListener listener)
            throws IOException, InterruptedException {
        final PrintStream logger = listener.getLogger();

        // Check that the job has been configured correctly
        if (!isConfigValid(logger)) {
            return false;
        }

        // Figure out the list of version codes to assign
        String applicationId;
        Collection<Long> versionCodeList = new TreeSet<>();
        if (isFromVersionCode()) {
            applicationId = getExpandedApplicationId();
            String codes = getExpandedVersionCodes();
            for (String s : codes.split("[,\\s]+")) {
                long versionCode = tryParseNumber(s.trim(), -1).longValue();
                if (versionCode != -1) {
                    versionCodeList.add(versionCode);
                }
            }
        } else {
            AppInfo info = getApplicationInfoForAppFiles(workspace, logger, getExpandedFilesPattern());
            if (info == null) {
                return false;
            }
            applicationId = info.applicationId;
            versionCodeList.addAll(info.versionCodes);
        }

        // Assign the APKs to the desired track
        try {
            GoogleRobotCredentials credentials = getCredentialsHandler().getServiceAccountCredentials(run.getParent());
            return workspace.act(new TrackAssignmentTask(listener, credentials, applicationId, versionCodeList,
                            fromConfigValue(getCanonicalTrackName()), getRolloutPercent()));
        } catch (UploadException e) {
            logger.println(String.format("Assignment failed: %s", getPublisherErrorMessage(e)));
            logger.println("No changes have been applied to the Google Play account");
        }
        return false;
    }

    private AppInfo getApplicationInfoForAppFiles(FilePath workspace, PrintStream logger, String appFilesPattern)
            throws IOException, InterruptedException {
        // Find the filename(s) which match the pattern after variable expansion
        List<String> relativePaths = workspace.act(new FindFilesTask(appFilesPattern));
        if (relativePaths.isEmpty()) {
            logger.println(String.format("No AAB/APK files matching the pattern '%s' could be found", appFilesPattern));
            return null;
        }

        // Read the metadata from each file found
        final List<UploadFile> appFilesToMove = new ArrayList<>();
        for (String path : relativePaths) {
            FilePath file = workspace.child(path);
            try {
                // Attempt to parse the file as an Android app
                UploadFile appFile = new UploadFile(file);
                appFilesToMove.add(appFile);
                logger.println(String.format("Found %s file with version code %d: %s",
                        appFile.getFileFormat(), appFile.getVersionCode(), path));
            } catch (ParserException | IOException e) {
                throw new IOException(String.format("File does not appear to be valid: %s", file.getRemote()), e);
            }
        }

        // If there are multiple matches, ensure that all have the same application ID
        final Set<String> applicationIds = appFilesToMove.stream()
                .map(UploadFile::getApplicationId).collect(Collectors.toSet());
        if (applicationIds.size() != 1) {
            logger.println(String.format("Multiple files matched the pattern '%s', " +
                    "but they have inconsistent application IDs:", appFilesPattern));
            for (String id : applicationIds) {
                logger.print("- ");
                logger.println(id);
            }
            return null;
        }

        final Set<Long> versionCodes = appFilesToMove.stream()
                .map(UploadFile::getVersionCode).collect(Collectors.toSet());
        return new AppInfo(applicationIds.iterator().next(), new ArrayList<>(versionCodes));
    }

    private static final class AppInfo {
        final String applicationId;
        final List<Long> versionCodes;

        AppInfo(String applicationId, List<Long> versionCodes) {
            this.applicationId = applicationId;
            this.versionCodes = versionCodes;
        }
    }

    @Symbol("androidApkMove")
    @Extension
    public static final class DescriptorImpl extends GooglePlayBuildStepDescriptor<Builder> {
        public static final String defaultFilesPattern = "**/build/outputs/**/*.aab, **/build/outputs/**/*.apk";
        public static final String defaultTrackName = ReleaseTrack.PRODUCTION.getApiValue();
        public static final int defaultRolloutPercent = 100;

        public String getDisplayName() {
            return "Move Android apps to a different release track";
        }

    }

}
