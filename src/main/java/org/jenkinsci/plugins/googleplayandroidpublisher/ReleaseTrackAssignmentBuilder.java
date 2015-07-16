package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import net.dongliu.apk.parser.exception.ParserException;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipException;

import static com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials.getCredentialsListBox;
import static hudson.Util.fixEmptyAndTrim;
import static hudson.Util.tryParseNumber;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.PRODUCTION;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.fromConfigValue;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.getConfigValues;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.REGEX_VARIABLE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.expand;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getPublisherErrorMessage;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getVersionCode;

public class ReleaseTrackAssignmentBuilder extends GooglePlayBuilder {

    public static final DecimalFormat PERCENTAGE_FORMATTER = new DecimalFormat("#.#");

    /** Allowed percentage values when doing a staged rollout to production. */
    private static final double[] ROLLOUT_PERCENTAGES = { 0.5, 1, 5, 10, 20, 50, 100 };
    private static final double DEFAULT_PERCENTAGE = 100;

    @DataBoundSetter
    private Boolean fromVersionCode;

    @DataBoundSetter
    private String applicationId;

    @DataBoundSetter
    private String versionCodes;

    @DataBoundSetter
    private String apkFilesPattern;

    @DataBoundSetter
    private String trackName;

    @DataBoundSetter
    private String rolloutPercentage;

    @DataBoundConstructor
    public ReleaseTrackAssignmentBuilder() {}

    public boolean isFromVersionCode() {
        return fromVersionCode == null || fromVersionCode;
    }

    public String getApplicationId() {
        return applicationId;
    }

    private String getExpandedApplicationId(EnvVars env) {
        return expand(env, getApplicationId());
    }

    public String getVersionCodes() {
        return versionCodes;
    }

    private String getExpandedVersionCodes(EnvVars env) {
        return expand(env, getVersionCodes());
    }

    public String getApkFilesPattern() {
        return fixEmptyAndTrim(apkFilesPattern);
    }

    private String getExpandedApkFilesPattern(EnvVars env) {
        return expand(env, getApkFilesPattern());
    }

    public String getTrackName() {
        return fixEmptyAndTrim(trackName);
    }

    private String getCanonicalTrackName(EnvVars env) {
        String name = expand(env, getTrackName());
        if (name == null) {
            return null;
        }
        return name.toLowerCase(Locale.ENGLISH);
    }

    public String getRolloutPercentage() {
        return fixEmptyAndTrim(rolloutPercentage);
    }

    private double getRolloutPercentageValue(EnvVars env) {
        String pct = getRolloutPercentage();
        if (pct != null) {
            // Allow % characters in the config
            pct = pct.replace("%", "");
        }
        // If no valid numeric value was set, we will roll out to 100%
        return tryParseNumber(expand(env, pct), DEFAULT_PERCENTAGE).doubleValue();
    }

    private boolean isConfigValid(PrintStream logger, EnvVars env) {
        final List<String> errors = new ArrayList<String>();

        // Check whether the relevant values were provided, based on the method chosen
        if (isFromVersionCode()) {
            if (getExpandedApplicationId(env) == null) {
                errors.add("No application ID was specified");
            }
            if (getExpandedVersionCodes(env) == null) {
                errors.add("No version codes were specified");
            }
        } else if (getExpandedApkFilesPattern(env) == null) {
            errors.add("Path or pattern to APK file(s) was not specified");
        }

        // Track name is also required
        final String trackName = getCanonicalTrackName(env);
        final ReleaseTrack track = fromConfigValue(trackName);
        if (trackName == null) {
            errors.add("Release track was not specified");
        } else if (track == null) {
            errors.add(String.format("'%s' is not a valid release track", trackName));
        } else if (track == PRODUCTION) {
            // Check for valid rollout percentage
            double pct = getRolloutPercentageValue(env);
            if (Arrays.binarySearch(ROLLOUT_PERCENTAGES, pct) < 0) {
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
    public boolean perform(AbstractBuild build, Launcher launcher,
            BuildListener listener) throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();

        // Check that the job has been configured correctly
        final EnvVars env = build.getEnvironment(listener);
        if (!isConfigValid(logger, env)) {
            return false;
        }

        String applicationId;
        Collection<Integer> versionCodeList = new TreeSet<Integer>();
        if (isFromVersionCode()) {
            applicationId = getExpandedApplicationId(env);
            String codes = getExpandedVersionCodes(env);
            for (String s : codes.split("[,\\s]+")) {
                int versionCode = tryParseNumber(s.trim(), -1).intValue();
                if (versionCode != -1) {
                    versionCodeList.add(versionCode);
                }
            }
        } else {
            AppInfo info = getApplicationInfoForApks(build, logger, getExpandedApkFilesPattern(env));
            if (info == null) {
                return false;
            }
            applicationId = info.applicationId;
            versionCodeList.addAll(info.versionCodes);
        }

        // Assign the APKs to the desired track
        try {
            GoogleRobotCredentials credentials = getCredentialsHandler().getServiceAccountCredentials();
            return build.getWorkspace()
                    .act(new TrackAssignmentTask(listener, credentials, applicationId, versionCodeList,
                            fromConfigValue(getCanonicalTrackName(env)), getRolloutPercentageValue(env)));
        } catch (UploadException e) {
            logger.println(String.format("Upload failed: %s", getPublisherErrorMessage(e)));
            logger.println("- No changes have been applied to the Google Play account");
        }
        return false;
    }

    private AppInfo getApplicationInfoForApks(AbstractBuild build, PrintStream logger, String apkFilesPattern) throws IOException, InterruptedException {
        // Find the APK filename(s) which match the pattern after variable expansion
        final FilePath ws = build.getWorkspace();
        List<String> relativePaths = ws.act(new FindFilesTask(apkFilesPattern));
        if (relativePaths.isEmpty()) {
            logger.println(String.format("No APK files matching the pattern '%s' could be found", apkFilesPattern));
            return null;
        }

        // Read the metadata from each APK file
        final Set<String> applicationIds = new HashSet<String>();
        final List<Integer> versionCodes = new ArrayList<Integer>();
        for (String path : relativePaths) {
            FilePath apk = ws.child(path);
            final int versionCode;
            try {
                applicationIds.add(Util.getApplicationId(apk));
                versionCode = getVersionCode(apk);
                versionCodes.add(versionCode);
            } catch (ZipException e) {
                throw new IOException(String.format("File does not appear to be a valid APK: %s", apk.getRemote()), e);
            } catch (ParserException e) {
                logger.println(String.format("File does not appear to be a valid APK: %s\n- %s",
                        apk.getRemote(), e.getMessage()));
                throw e;
            }
            logger.println(String.format("Found APK file with version code %d: %s", versionCode, path));
        }

        // If there are multiple APKs, ensure that all have the same application ID
        if (applicationIds.size() != 1) {
            logger.println("Multiple APKs were found but they have inconsistent application IDs:");
            for (String id : applicationIds) {
                logger.print("- ");
                logger.println(id);
            }
            return null;
        }

        return new AppInfo(applicationIds.iterator().next(), versionCodes);
    }

    private static final class AppInfo {
        final String applicationId;
        final List<Integer> versionCodes;

        AppInfo(String applicationId, List<Integer> versionCodes) {
            this.applicationId = applicationId;
            this.versionCodes = versionCodes;
        }
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Move Android APKs to a different release track";
        }

        public ListBoxModel doFillGoogleCredentialsIdItems() {
            ListBoxModel credentials = getCredentialsListBox(GooglePlayPublisher.class);
            if (credentials.isEmpty()) {
                credentials.add("(No Google Play account credentials have been added to Jenkins)", null);
            }
            return credentials;
        }

        public FormValidation doCheckGoogleCredentialsId(@QueryParameter String value) {
            // Complain if no credentials exist
            ListBoxModel credentials = getCredentialsListBox(GooglePlayPublisher.class);
            if (credentials.isEmpty()) {
                // TODO: Can we link to the credentials page from this message?
                return FormValidation.error("You must add at least one Google Service Account via the Credentials page");
            }

            // Otherwise, attempt to load the given credential to see whether it has been set up correctly
            try {
                new CredentialsHandler(value).getServiceAccountCredentials();
            } catch (UploadException e) {
                return FormValidation.error(e.getMessage());
            }

            // Everything is fine
            return FormValidation.ok();
        }

        public FormValidation doCheckApkFilesPattern(@QueryParameter String value) {
            if (fixEmptyAndTrim(value) == null) {
                return FormValidation.error("An APK file path or pattern is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTrackName(@QueryParameter String value) {
            if (fixEmptyAndTrim(value) == null) {
                return FormValidation.error("A release track is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRolloutPercentage(@QueryParameter String value) {
            value = fixEmptyAndTrim(value);
            if (value == null || value.matches(REGEX_VARIABLE)) {
                return FormValidation.ok();
            }

            final double lowest = ROLLOUT_PERCENTAGES[0];
            final double highest = DEFAULT_PERCENTAGE;
            double pct = tryParseNumber(value.replace("%", ""), highest).doubleValue();
            if (Double.compare(pct, lowest) < 0 || Double.compare(pct, DEFAULT_PERCENTAGE) > 0) {
                return FormValidation.error("Percentage value must be between %s and %s%%",
                        PERCENTAGE_FORMATTER.format(lowest), PERCENTAGE_FORMATTER.format(highest));
            }
            return FormValidation.ok();
        }

        public ComboBoxModel doFillTrackNameItems() {
            return new ComboBoxModel(getConfigValues());
        }

        public ComboBoxModel doFillRolloutPercentageItems() {
            ComboBoxModel list = new ComboBoxModel();
            for (double pct : ROLLOUT_PERCENTAGES) {
                list.add(String.format("%s%%", PERCENTAGE_FORMATTER.format(pct)));
            }
            return list;
        }

        public boolean isApplicable(Class<? extends AbstractProject> c) {
            return true;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req, formData);
        }

    }

}
