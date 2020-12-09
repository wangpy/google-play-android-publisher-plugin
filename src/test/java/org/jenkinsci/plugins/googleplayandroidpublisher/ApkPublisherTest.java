package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsParameterDefinition;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.Track;
import com.google.api.services.androidpublisher.model.TrackRelease;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotPrivateKeyCredentials;
import hudson.FilePath;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.Slave;
import hudson.model.StringParameterDefinition;
import hudson.slaves.DumbSlave;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.JenkinsUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestHttpTransport;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestUtilImpl;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeAssignTrackResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeCommitResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListApksResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListBundlesResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListTracksResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePostEditsResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePutApkResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePutBundleResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeUploadApkResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeUploadBundleResponse;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import static hudson.Util.join;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestConstants.DEFAULT_APK;
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestConstants.DEFAULT_BUNDLE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper.assertResultWithLogLines;
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper.createAndroidPublisher;
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper.getRequestBodyForUrl;
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper.setUpCredentials;
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper.track;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ApkPublisherTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestUtilImpl androidUtil;

    private TestHttpTransport transport;

    @Before
    public void setUp() throws Exception {
        androidUtil = new TestUtilImpl();
        Util.setAndroidUtil(androidUtil);

        JenkinsUtil jenkinsUtil = spy(TestUtilImpl.class);
        Util.setJenkinsUtil(jenkinsUtil);

        // Create fake AndroidPublisher client
        transport = new TestHttpTransport();
        AndroidPublisher androidClient = createAndroidPublisher(transport);
        when(jenkinsUtil.createPublisherClient(any(), anyString())).thenReturn(androidClient);
    }

    @After
    public void tearDown() {
        transport.dumpRequests();
        Util.setAndroidUtil(null);
        Util.setJenkinsUtil(null);
    }

    @Test
    public void configRoundtripWorks() throws Exception {
        // Given that a few credentials have been set up
        setUpCredentials("credential-a");
        setUpCredentials("credential-b");
        setUpCredentials("credential-c");

        // And we have a job configured with the APK publisher, which includes all possible configuration options
        FreeStyleProject project = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        // Choose the second credential, so that when the config page loads, we can differentiate between the dropdown
        // working as expected vs just appearing to work because the first credential would be selected by default
        publisher.setGoogleCredentialsId("credential-b");
        publisher.setFilesPattern("**/builds/*.apk, *.aab");
        publisher.setDeobfuscationFilesPattern("**/proguard/*.txt");
        publisher.setNativeDebugSymbolFilesPattern("**/native/*.zip");
        publisher.setExpansionFilesPattern("${EXP_FILES}");
        publisher.setUsePreviousExpansionFilesIfMissing(true);
        publisher.setTrackName("alpha");
        publisher.setRolloutPercentage("12.3456789");
        publisher.setRecentChangeList(new ApkPublisher.RecentChanges[] {
            new ApkPublisher.RecentChanges("en", "Hello!"),
            new ApkPublisher.RecentChanges("de", "Hallo!"),
        });
        project.getPublishersList().add(publisher);

        // When we open and save the configuration page for this job
        project = j.configRoundtrip(project);

        // Then the publisher object should have been serialised and deserialised, without any changes
        j.assertEqualDataBoundBeans(publisher, project.getPublishersList().get(0));
    }

    @Test
    public void uploadingWithoutTrackNameFails() throws Exception {
        // Given a job where the track name is not provided
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        setUpCredentials("test-credentials");
        setUpTransportForApk();
        setUpApkFile(p);

        // When a build occurs
        // Then it should fail as the track name has not been specified
        assertResultWithLogLines(j, p, Result.FAILURE, "Release track was not specified");
    }

    @Test
    public void uploadingWithEmptyTrackNameFails() throws Exception {
        // Given a job where the track name is empty (e.g. saved without entering a value, or an empty parameter value)
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setTrackName("");
        publisher.setRolloutPercentage("100");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        setUpCredentials("test-credentials");
        setUpTransportForApk();
        setUpApkFile(p);

        // When a build occurs
        // Then it should fail as the track name has not been specified
        assertResultWithLogLines(j, p, Result.FAILURE, "Release track was not specified");
    }

    @Test
    public void uploadingWithoutRolloutPercentageFails() throws Exception {
        // Given a job where the rollout percentage is not provided
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setTrackName("production");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        setUpCredentials("test-credentials");
        setUpTransportForApk();
        setUpApkFile(p);

        // When a build occurs
        // Then it should fail as the rollout percentage has not been specified
        assertResultWithLogLines(j, p, Result.FAILURE, "Rollout percentage was not specified");
    }

    @Test
    public void uploadingWithEmptyRolloutPercentageFails() throws Exception {
        // Given a job where the percentage is empty (e.g. saved without entering a value, or an empty parameter value)
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        setUpCredentials("test-credentials");
        setUpTransportForApk();
        setUpApkFile(p);

        // When a build occurs
        // Then it should fail as the rollout percentage has not been specified
        assertResultWithLogLines(j, p, Result.FAILURE, "Rollout percentage was not specified");
    }

    @Test
    public void uploadingWithInvalidRolloutPercentageFails() throws Exception {
        // Given a job where the rollout percentage can't be parsed
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("everyone");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        setUpCredentials("test-credentials");
        setUpTransportForApk();
        setUpApkFile(p);

        // When a build occurs
        // Then it should fail as the rollout percentage is not valid
        assertResultWithLogLines(j, p, Result.FAILURE, "'everyone' is not a valid rollout percentage");
    }

    @Test
    public void uploadingWithoutMatchingFilesFails() throws Exception {
        // Given a job with the default configuration
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("100");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        // But no files exist in the workspace
        setUpCredentials("test-credentials");
        setUpTransportForApk();

        // When a build occurs
        // Then it should fail as no matching files exist
        assertResultWithLogLines(j, p, Result.FAILURE,
            "No AAB or APK files matching the pattern '**/build/outputs/**/*.aab, **/build/outputs/**/*.apk' could be found"
        );
    }

    @Test
    public void uploadingExistingApkFails() throws Exception {
        // Given that some version codes already exist on Google Play
        setUpTransportForApk();
        transport.withResponse("/edits/the-edit-id/apks",
                        new FakeListApksResponse().setApks(Collections.singletonList(DEFAULT_APK)));
        transport.withResponse("/edits/the-edit-id/bundles",
                new FakeListBundlesResponse().setBundles(Collections.singletonList(DEFAULT_BUNDLE)));

        // And we have a freestyle job which will attempt to upload an existing APK
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setFilesPattern("**/*.apk");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("100");
        p.getPublishersList().add(publisher);

        setUpCredentials("test-credentials");
        setUpApkFile(p);

        // When a build occurs, it should fail as the APK file already exists
        assertResultWithLogLines(j, p, Result.FAILURE,
                "Uploading 1 file(s) with application ID: org.jenkins.appId",
                "APK file: " + join(Arrays.asList("build", "outputs", "apk", "app.apk"), File.separator),
                "versionCode: 42",
                "This file already exists in the Google Play account; it cannot be uploaded again",
                "Upload to Google Play failed"
        );
    }

    @Test
    public void uploadingApkSucceeds() throws Exception {
        setUpTransportForApk();

        FreeStyleProject p = j.createFreeStyleProject("uploadApks");

        setUpCredentials("test-credentials");
        setUpApkFile(p);

        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setFilesPattern("**/*.apk");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("100");

        p.getPublishersList().add(publisher);

        // Authenticating to Google Play API...
        // - Credential:     test-credentials
        // - Application ID: org.jenkins.appId
        //
        // Uploading 1 file(s) with application ID: org.jenkins.appId
        //
        //       APK file: build/outputs/apk/app.apk
        //     SHA-1 hash: da39a3ee5e6b4b0d3255bfef95601890afd80709
        //    versionCode: 42
        //  minSdkVersion: 16
        //
        // Setting rollout to target 100% of 'production' track users
        // The 'production' release track will now contain the version code(s): 42
        //
        // Applying changes to Google Play...
        // Changes were successfully applied to Google Play

        assertResultWithLogLines(j, p, Result.SUCCESS,
                "Uploading 1 file(s) with application ID: org.jenkins.appId",
                "APK file: " + join(Arrays.asList("build", "outputs", "apk", "app.apk"), File.separator),
                "versionCode: 42",
                "Setting rollout to target 100% of 'production' track users",
                "The 'production' release track will now contain the version code(s): 42",
                "Changes were successfully applied to Google Play"
        );

        // And we should have set completed status when updating the track
        Track track = getRequestBodyForUrl(
                transport, "/org.jenkins.appId/edits/the-edit-id/tracks/production", Track.class
        );
        TrackRelease release = track.getReleases().get(0);
        assertEquals("completed", release.getStatus());
        assertNull(release.getUserFraction());
    }

    @Test
    public void uploadingApkWithMinimalConfigurationUsesDefaults() throws Exception {
        // Given a job, whose publisher has a credential, track name, and rollout percentage, but no other configuration
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("100");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        setUpCredentials("test-credentials");
        setUpTransportForApk();
        setUpApkFile(p);

        // When a build occurs
        // Then it should find the APK using the default pattern, and upload to 100% of users
        assertResultWithLogLines(j, p, Result.SUCCESS,
            "Setting rollout to target 100% of 'production' track users",
            "The 'production' release track will now contain the version code(s): 42",
            "Changes were successfully applied to Google Play"
        );
    }

    @Test
    public void uploadSingleApk_inFolder_succeeds() throws Exception {
        setUpTransportForApk();

        // Given a folder, which has Google Play credentials attached
        Folder folder = j.createProject(Folder.class, "some-folder");
        setUpCredentials("folder-credentials", folder);

        // And given there's a job in the folder which wants to use those credentials
        FreeStyleProject p = folder.createProject(FreeStyleProject.class, "some-job-in-a-folder");
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("folder-credentials");
        publisher.setFilesPattern("**/*.apk");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("100");
        p.getPublishersList().add(publisher);
        setUpApkFile(p);

        // When a build occurs, it should succeed
        assertResultWithLogLines(j, p, Result.SUCCESS,
                "Uploading 1 file(s) with application ID: org.jenkins.appId",
                "APK file: " + join(Arrays.asList("build", "outputs", "apk", "app.apk"), File.separator),
                "versionCode: 42",
                "Setting rollout to target 100% of 'production' track users",
                "The 'production' release track will now contain the version code(s): 42",
                "Changes were successfully applied to Google Play"
        );
    }

    @Test
    public void uploadingApkWithParametersSucceeds() throws Exception {
        // Given a job with various parameters
        FreeStyleProject p = j.createFreeStyleProject();

        ParametersDefinitionProperty pdp = new ParametersDefinitionProperty(
            new CredentialsParameterDefinition(
                "GP_CREDENTIAL", null, "test-credentials",
                    GoogleRobotPrivateKeyCredentials.class.getName(), true
            ),
            new StringParameterDefinition("APK_PATTERN", "**/app.apk"),
            new StringParameterDefinition("TRACK_NAME", "production"),
            new StringParameterDefinition("ROLLOUT_PCT", "12.5%")
        );
        p.addProperty(pdp);

        // And a publisher which uses those parameters
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("${GP_CREDENTIAL}");
        publisher.setFilesPattern("${APK_PATTERN}");
        publisher.setTrackName("${TRACK_NAME}");
        publisher.setRolloutPercentage("${ROLLOUT_PCT}");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        setUpCredentials("test-credentials");
        setUpTransportForApk();
        setUpApkFile(p);

        // When a build occurs, it should apply the default parameter values
        assertResultWithLogLines(j, p, Result.SUCCESS,
            "- Credential:     test-credentials",
            "Setting rollout to target 12.5% of 'production' track users",
            "Changes were successfully applied to Google Play"
        );

        // And we should have set in-progress status when updating the track
        Track track = getRequestBodyForUrl(
                transport, "/org.jenkins.appId/edits/the-edit-id/tracks/production", Track.class
        );
        TrackRelease release = track.getReleases().get(0);
        assertEquals("inProgress", release.getStatus());
        assertEquals(0.125, release.getUserFraction(), 0.0001);
    }

    @Test
    public void uploadingApkAsDraftSucceeds() throws Exception {
        // Given a job, configured to upload as a draft
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setFilesPattern("**/*.apk");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("0%");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        setUpCredentials("test-credentials");
        setUpTransportForApk();
        setUpApkFile(p);

        // When a build occurs, it should upload as a draft
        assertResultWithLogLines(j, p, Result.SUCCESS,
            "New 'production' draft release created, with the version code(s): 42",
            "Changes were successfully applied to Google Play"
        );

        // And we should have set draft status when updating the track
        Track track = getRequestBodyForUrl(
            transport, "/org.jenkins.appId/edits/the-edit-id/tracks/production", Track.class
        );
        TrackRelease release = track.getReleases().get(0);
        assertEquals("draft", release.getStatus());
        assertNull(release.getUserFraction());
    }

    @Test
    public void uploadingApkWithPipelineWithoutTrackNameFails() throws Exception {
        // Given a Pipeline where the track name is not provided
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials'";

        // When a build occurs
        // Then it should fail as the track name has not been specified
        uploadApkWithPipelineAndAssertFailure(stepDefinition, "Release track was not specified");
    }

    @Test
    public void uploadingApkWithPipelineWithEmptyTrackNameFails() throws Exception {
        // Given a Pipeline where the track name is empty (e.g. an empty parameter value)
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials'";

        // When a build occurs
        // Then it should fail as the track name has not been specified
        uploadApkWithPipelineAndAssertFailure(stepDefinition, "Release track was not specified");
    }

    @Test
    public void uploadingApkWithPipelineWithoutRolloutPercentageFails() throws Exception {
        // Given a Pipeline where the rollout percentage is not provided
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials',\n" +
                "  trackName: 'production'";

        // When a build occurs
        // Then it should fail as the rollout percentage has not been specified
        uploadApkWithPipelineAndAssertFailure(stepDefinition, "Rollout percentage was not specified");
    }

    @Test
    public void uploadingApkWithPipelineWithEmptyRolloutPercentageFails() throws Exception {
        // Given a Pipeline where the rollout percentage is empty (e.g. an empty parameter value)
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials',\n" +
                "  trackName: 'production',\n" +
                "  rolloutPercentage: ''";

        // When a build occurs
        // Then it should fail as the rollout percentage has not been specified
        uploadApkWithPipelineAndAssertFailure(stepDefinition, "Rollout percentage was not specified");
    }

    @Test
    public void uploadingApkToCustomTrackSucceeds() throws Exception {
        // Given a job, whose publisher wants to upload to a custom release track
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setTrackName("DogFood"); // case should not matter
        publisher.setRolloutPercentage("100");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        setUpCredentials("test-credentials");
        setUpTransportForApk("dogfood");
        setUpApkFile(p);

        // When a build occurs
        // Then the APK should be successfully uploaded and assigned to the custom track
        assertResultWithLogLines(j, p, Result.SUCCESS,
            "Setting rollout to target 100% of 'dogfood' track users",
            "The 'dogfood' release track will now contain the version code(s): 42",
            "Changes were successfully applied to Google Play"
        );
    }

    @Test
    public void uploadingApkToUnpublishedCustomTrackSucceeds() throws Exception {
        // Given a job, whose publisher wants to upload to a custom release track
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setTrackName("dogfood");
        publisher.setRolloutPercentage("100");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        // But the initial 'tracks' response won't include the track, as it doesn't yet have any releases
        setUpCredentials("test-credentials");
        setUpTransportForApk("dogfood", false);
        setUpApkFile(p);

        // When a build occurs
        // Then the APK should be successfully assigned to the custom track
        // And we should have seen the warning about the track not being returned by Google Play
        assertResultWithLogLines(j, p, Result.SUCCESS,
            "Release track 'dogfood' could not be found",
            "Setting rollout to target 100% of 'dogfood' track users",
            "The 'dogfood' release track will now contain the version code(s): 42",
            "Changes were successfully applied to Google Play"
        );
    }

    @Test
    public void uploadingApkToNonExistentCustomTrackFails() throws Exception {
        // Given a job, whose publisher wants to upload to a custom release track,
        // But the track does not exist on the backend
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setTrackName("non-existent-track");
        publisher.setRolloutPercentage("100");
        p.getPublishersList().add(publisher);

        // And the prerequisites are in place
        setUpCredentials("test-credentials");
        setUpTransportForApk();
        setUpApkFile(p);

        // When a build occurs
        // Then it should fail with a message about the missing track
        assertResultWithLogLines(j, p, Result.FAILURE, "Release track 'non-existent-track' could not be found");
    }

    @Test
    public void uploadingApkWithPipelineSucceeds() throws Exception {
        // Given a Pipeline with only the required parameters
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials',\n" +
                "  trackName: 'production',\n" +
                "  rolloutPercentage: '100'";

        uploadApkWithPipelineAndAssertSuccess(
            stepDefinition,
            "Setting rollout to target 100% of 'production' track users",
            "The 'production' release track will now contain the version code(s): 42"
        );
    }

    @Test
    public void uploadingApkWithPipelineWithRolloutPercentSucceeds() throws Exception {
        // Given a step with a deprecated `rolloutPercent` value
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials',\n" +
                "  trackName: 'production',\n" +
                "  rolloutPercent: 12.34";

        // When a build occurs, it should roll out to that percentage
        uploadApkWithPipelineAndAssertSuccess(
            stepDefinition,
            "Setting rollout to target 12.34% of 'production' track users",
            "The 'production' release track will now contain the version code(s): 42"
        );
    }

    @Test
    public void uploadingApkWithPipelineWithBothRolloutFormatsUsesRolloutPercentage() throws Exception {
        // Given a step with both the deprecated `rolloutPercent`, and a verbose `rolloutPercentage` value
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials',\n" +
                "  trackName: 'production',\n" +
                "  rolloutPercent: 12.3456,\n" +
                "  rolloutPercentage: '56.789%'";

        // When a build occurs, it should prefer the string `rolloutPercentage` value
        uploadApkWithPipelineAndAssertSuccess(
            stepDefinition,
            "Setting rollout to target 56.789% of 'production' track users",
            "The 'production' release track will now contain the version code(s): 42"
        );
    }

    @Test
    public void uploadingApkWithPipelineAsDraftSucceeds() throws Exception {
        // Given a step with the rollout percentage set to zero
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials',\n" +
                "  trackName: 'production',\n" +
                "  rolloutPercentage: '0.0'";

        // When a build occurs, it should upload as a draft
        uploadApkWithPipelineAndAssertSuccess(
            stepDefinition,
            "New 'production' draft release created, with the version code(s): 42"
        );

        // And we should have set draft status when updating the track
        Track track = getRequestBodyForUrl(
            transport, "/org.jenkins.appId/edits/the-edit-id/tracks/production", Track.class
        );
        TrackRelease release = track.getReleases().get(0);
        assertEquals("draft", release.getStatus());
        assertNull(release.getUserFraction());
    }

    @Test
    public void uploadingApkWithPipelineToCustomTrackSucceeds() throws Exception {
        // Given a step that wants to upload to a custom release track
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials',\n" +
                "  trackName: 'dogfood',\n" +
                "  rolloutPercentage: '100'";

        // And the backend will recognise the custom track
        setUpTransportForApk("dogfood");

        // When a build occurs
        // Then the APK should be successfully uploaded and assigned to the custom track
        uploadApkWithPipelineAndAssertSuccess(
            stepDefinition,
            "Setting rollout to target 100% of 'dogfood' track users",
            "The 'dogfood' release track will now contain the version code(s): 42"
        );
    }

    @Test
    public void uploadingApkWithPipelineToNonExistentCustomTrackFails() throws Exception {
        // Given a step that wants to upload to a custom release track
        // But the track does not exist on the backend
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials',\n" +
                "  trackName: 'non-existent-track',\n" +
                "  rolloutPercentage: '100'";


        // And the backend does not know about the custom track
        setUpTransportForApk();

        // When a build occurs
        // Then it should fail with a message about the missing track
        uploadApkWithPipelineAndAssertFailure(stepDefinition, "Release track 'non-existent-track' could not be found");
    }

    @Test
    public void uploadingApkWithPipelineWithInAppUpdatePrioritySucceeds() throws Exception{
        // Given a step with in-app update priority
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials',\n" +
                "  trackName: 'production',\n"+
                "  rolloutPercentage: '100',\n"+
                "  inAppUpdatePriority: '1'";

        uploadApkWithPipelineAndAssertSuccess(stepDefinition,
                "Setting in-app update priority to 1");
    }

    @Test
    public void uploadingWithPipelineWithInvalidInAppUpdatePriorityFails() throws Exception{
        // Given a step with in-app update priority
        // But whose value is not a valid integer
        String stepDefinition = "androidApkUpload googleCredentialsId: 'test-credentials',\n" +
                "  trackName: 'production',\n"+
                "  rolloutPercentage: '100',\n"+
                "  inAppUpdatePriority: 'fake'";

        // When a build occurs, it should roll out to that percentage
        uploadApkWithPipelineAndAssertFailure(
                stepDefinition,
                "'fake' is not a valid update priority"
        );
    }

    private void uploadApkWithPipelineAndAssertFailure(
        String stepDefinition, String... expectedLogLines
    ) throws Exception {
        uploadApkWithPipelineAndAssertResult(stepDefinition, Result.FAILURE, expectedLogLines);
    }

    private void uploadApkWithPipelineAndAssertSuccess(
        String stepDefinition, String... expectedLogLines
    ) throws Exception {
        String[] commonLogLines = {
            "Uploading 1 file(s) with application ID: org.jenkins.appId",
            "APK file: " + join(Arrays.asList("build", "outputs", "apk", "app.apk"), File.separator),
            "versionCode: 42",
            "Changes were successfully applied to Google Play"
        };
        String[] allExpectedLogLines = Stream.concat(Arrays.stream(commonLogLines), Arrays.stream(expectedLogLines))
            .toArray(String[]::new);
        uploadApkWithPipelineAndAssertResult(stepDefinition, Result.SUCCESS, allExpectedLogLines);
    }

    private void uploadApkWithPipelineAndAssertResult(
        String stepDefinition, Result expectedResult, String... expectedLogLines
    ) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("" +
            "node {\n" +
            "  writeFile text: 'this-is-a-dummy-apk', file: 'build/outputs/apk/app.apk'\n" +
            "  " + stepDefinition + "\n" +
            "}", true
        ));

        setUpCredentials("test-credentials");
        if (transport.responses.isEmpty()) {
            setUpTransportForApk();
        }

        assertResultWithLogLines(j, p, expectedResult, expectedLogLines);
    }

    @Test
    public void uploadingExistingBundleFails() throws Exception {
        // Given that some version codes already exist on Google Play
        setUpTransportForApk();
        transport.withResponse("/edits/the-edit-id/apks",
                new FakeListApksResponse().setApks(Collections.singletonList(DEFAULT_APK)));
        transport.withResponse("/edits/the-edit-id/bundles",
                new FakeListBundlesResponse().setBundles(Collections.singletonList(DEFAULT_BUNDLE)));

        // And we have a freestyle job which will attempt to upload an existing bundle
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setFilesPattern("**/*.aab");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("100");
        p.getPublishersList().add(publisher);

        setUpCredentials("test-credentials");
        setUpBundleFile(p);

        // When a build occurs, it should fail as the bundle file already exists
        assertResultWithLogLines(j, p, Result.FAILURE,
                "Uploading 1 file(s) with application ID: org.jenkins.bundleAppId",
                "AAB file: " + join(Arrays.asList("build", "outputs", "bundle", "release", "bundle.aab"), File.separator),
                "versionCode: 43",
                "This file already exists in the Google Play account; it cannot be uploaded again",
                "Upload to Google Play failed"
        );
    }

    @Test
    public void uploadBundle_succeeds() throws Exception {
        setUpTransportForBundle();

        FreeStyleProject p = j.createFreeStyleProject("uploadBundles");

        setUpCredentials("test-credentials");
        setUpBundleFile(p);

        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setFilesPattern("**/*.aab");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("100");

        p.getPublishersList().add(publisher);

        // Authenticating to Google Play API...
        // - Credential:     test-credentials
        // - Application ID: org.jenkins.bundleAppId
        //
        // Uploading 1 file(s) with application ID: org.jenkins.bundleAppId
        //
        //       AAB file: build/outputs/bundle/release/bundle.aab
        //     SHA-1 hash: da39a3ee5e6b4b0d3255bfef95601890afd80709
        //    versionCode: 43
        //  minSdkVersion: 29
        //
        // Setting rollout to target 100% of 'production' track users
        // The 'production' release track will now contain the version code(s): 43
        //
        // Applying changes to Google Play...
        // Changes were successfully applied to Google Play

        assertResultWithLogLines(j, p, Result.SUCCESS,
                "Uploading 1 file(s) with application ID: org.jenkins.bundleAppId",
                "AAB file: " + join(Arrays.asList("build", "outputs", "bundle", "release", "bundle.aab"), File.separator),
                "versionCode: 43",
                "minSdkVersion: 29",
                "Setting rollout to target 100% of 'production' track users",
                "The 'production' release track will now contain the version code(s): 43",
                "Changes were successfully applied to Google Play"
        );
    }

    @Test
    public void givenMultipleFileTypesBundlesArePreferred() throws Exception {
        // Given a freestyle job which will attempt to upload all files in the workspace
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setFilesPattern("**/*");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("100");
        p.getPublishersList().add(publisher);

        setUpCredentials("test-credentials");
        setUpTransportForBundle();

        // And there are both AAB and APK files in the workspace
        setUpBundleFile(p);
        setUpApkFile(p);

        // And both have the same application ID
        String appId = "com.example.test";
        androidUtil.setApkAppId(appId);
        androidUtil.setBundleAppId(appId);

        // When a build occurs, then we should see a warning about multiple files
        // And the AAB upload should succeed, without uploading the APK
        assertResultWithLogLines(j, p, Result.SUCCESS,
            "Both AAB and APK files were found; only the AAB files will be uploaded",
            "Uploading 1 file(s) with application ID: com.example.test",
            "AAB file: " + join(Arrays.asList("build", "outputs", "bundle", "release", "bundle.aab"), File.separator),
            "The 'production' release track will now contain the version code(s): 43"
        );
    }

    @Test
    public void uploadBundleWithPipeline_succeeds() throws Exception {
        // Given a Pipeline with only the required parameters
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("" +
                "node {\n" +
                "  writeFile text: 'this-is-a-dummy-bundle', file: 'build/outputs/bundle/release/bundle.aab'\n" +
                "  androidApkUpload googleCredentialsId: 'test-credentials',\n" + //
                "                   trackName: 'production',\n" +
                "                   rolloutPercentage: '100'\n" +
                "}", true
        ));

        setUpCredentials("test-credentials");
        setUpTransportForBundle();

        // When a build occurs, it should succeed
        assertResultWithLogLines(j, p, Result.SUCCESS,
                "Uploading 1 file(s) with application ID: org.jenkins.bundleAppId",
                "AAB file: " + join(Arrays.asList("build", "outputs", "bundle", "release", "bundle.aab"), File.separator),
                "versionCode: 43",
                "Setting rollout to target 100% of 'production' track users",
                "The 'production' release track will now contain the version code(s): 43",
                "Changes were successfully applied to Google Play"
        );
    }

    @Test
    @WithoutJenkins
    public void responsesCanBeSerialized() throws IOException, ClassNotFoundException {
        transport.withResponse("/edits",
                new FakePostEditsResponse().setError(400, "error"));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(transport);
        byte[] bytes = bos.toByteArray();
        oos.close();

        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        ObjectInputStream ois = new ObjectInputStream(bis);
        Object deserialized = ois.readObject();
        ois.close();

        assertThat(deserialized, instanceOf(TestHttpTransport.class));
        TestHttpTransport deserializedTransport = (TestHttpTransport) deserialized;

        assertThat(deserializedTransport.responses, hasKey("/edits"));
        TestHttpTransport.SimpleResponse response = deserializedTransport.responses.get("/edits");
        assertNotNull(response);
        assertThat(response.statusCode, equalTo(400));
        assertEquals(response.jsonContent, "{\"error\": \"error\"}");
    }

    @Test
    @Ignore("AndroidUtil override from test does not carry over to the DumbSlave")
    public void uploadSingleApk_fromSlave_succeeds() throws Exception {
        setUpTransportForApk();

        DumbSlave agent = j.createOnlineSlave();
        FreeStyleProject p = j.createFreeStyleProject("uploadApks");
        p.setAssignedNode(agent);

        setUpCredentials("test-credentials");
        setUpApkFileOnSlave(p, agent);

        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.setFilesPattern("**/*.apk");
        publisher.setTrackName("production");
        publisher.setRolloutPercentage("100");

        p.getPublishersList().add(publisher);

        // Authenticating to Google Play API...
        // - Credential:     test-credentials
        // - Application ID: org.jenkins.appId
        //
        // Uploading 1 file(s) with application ID: org.jenkins.appId
        //
        //       APK file: build/outputs/apk/app.apk
        //     SHA-1 hash: da39a3ee5e6b4b0d3255bfef95601890afd80709
        //    versionCode: 42
        //  minSdkVersion: 16
        //
        // Setting rollout to target 100% of 'production' track users
        // The 'production' release track will now contain the version code(s): 42
        //
        // Applying changes to Google Play...
        // Changes were successfully applied to Google Play

        assertResultWithLogLines(j, p, Result.SUCCESS,
                "Uploading 1 file(s) with application ID: org.jenkins.appId",
                "APK file: " + join(Arrays.asList("build", "outputs", "apk", "app.apk"), File.separator),
                "versionCode: 42",
                "Setting rollout to target 100% of 'production' track users",
                "The 'production' release track will now contain the version code(s): 42",
                "Changes were successfully applied to Google Play"
        );
    }

    private void setUpTransportForApk() {
        setUpTransportForApk("production");
    }

    private void setUpTransportForApk(String trackName) {
        setUpTransportForApk(trackName, true);
    }

    private void setUpTransportForApk(String trackName, boolean includeTrackInList) {
        transport
                .withResponse("/edits",
                        new FakePostEditsResponse().setEditId("the-edit-id"))
                .withResponse("/edits/the-edit-id/apks",
                        new FakeListApksResponse().setEmptyApks())
                .withResponse("/edits/the-edit-id/bundles",
                        new FakeListBundlesResponse().setEmptyBundles())
                .withResponse("/edits/the-edit-id/tracks",
                        new FakeListTracksResponse().setTracks(
                            new ArrayList<Track>() {{
                                add(track("production"));
                                add(track("beta"));
                                add(track("alpha"));
                                add(track("internal"));
                                if (includeTrackInList) {
                                    add(track(trackName));
                                }
                            }}
                        ))
                .withResponse("/edits/the-edit-id/apks?uploadType=resumable",
                        new FakeUploadApkResponse().willContinue())
                .withResponse("google.local/uploading/foo/apk",
                        new FakePutApkResponse().success(42, "the:sha"))
                .withResponse("/edits/the-edit-id/tracks/" + trackName,
                        new FakeAssignTrackResponse().success(trackName, 42))
                .withResponse("/edits/the-edit-id:commit",
                        new FakeCommitResponse().success())
        ;
    }

    private void setUpTransportForBundle() {
        setUpTransportForBundle("production");
    }

    private void setUpTransportForBundle(String trackName) {
        transport
                .withResponse("/edits",
                        new FakePostEditsResponse().setEditId("the-edit-id"))
                .withResponse("/edits/the-edit-id/apks",
                        new FakeListApksResponse().setEmptyApks())
                .withResponse("/edits/the-edit-id/bundles",
                        new FakeListBundlesResponse().setEmptyBundles())
                .withResponse("/edits/the-edit-id/tracks",
                        new FakeListTracksResponse().setTracks(
                            new ArrayList<Track>() {{
                                add(track("production"));
                                add(track("beta"));
                                add(track("alpha"));
                                add(track("internal"));
                                add(track(trackName));
                            }}
                        ))
                .withResponse("/edits/the-edit-id/bundles?ackBundleInstallationWarning=true&uploadType=resumable",
                        new FakeUploadBundleResponse().willContinue())
                .withResponse("google.local/uploading/foo/bundle",
                        new FakePutBundleResponse().success(43, "the:sha"))
                .withResponse("/edits/the-edit-id/tracks/production",
                        new FakeAssignTrackResponse().success("production", 43))
                .withResponse("/edits/the-edit-id:commit",
                        new FakeCommitResponse().success())
        ;
    }

    /** Places a dummy file APK into the job's workspace under the typical Gradle output path: build/outputs/apk/ */
    private void setUpApkFile(FreeStyleProject p) throws Exception {
        FilePath workspace = j.jenkins.getWorkspaceFor(p);
        FilePath dir = workspace.child("build/outputs/apk");
        dir.mkdirs();
        FilePath file = dir.child("app.apk");
        file.touch(0);
    }

    /** Places a dummy APK file into the jobs' workspace under the typical Gradle output path: build/outputs/apk/ */
    private void setUpApkFileOnSlave(FreeStyleProject p, Slave agent) throws Exception {
        FilePath workspace = agent.getWorkspaceFor(p);
        FilePath apkDir = workspace.child("build").child("outputs").child("apk");
        FilePath apk = apkDir.child("app.apk");
        apk.copyFrom(getClass().getResourceAsStream("/foo.apk"));
    }

    /** Places a dummy AAB file into the job's workspace under the typical Gradle output path: build/outputs/bundle/ */
    private void setUpBundleFile(FreeStyleProject p) throws Exception {
        FilePath workspace = j.jenkins.getWorkspaceFor(p);
        FilePath dir = workspace.child("build/outputs/bundle/release");
        dir.mkdirs();
        FilePath file = dir.child("bundle.aab");
        file.touch(0);
    }
}
