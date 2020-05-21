package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.LocalizedText;
import com.google.api.services.androidpublisher.model.Track;
import com.google.api.services.androidpublisher.model.TrackRelease;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.DumbSlave;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.AndroidUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.JenkinsUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestHttpTransport;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestUtilImpl;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeAssignTrackResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeCommitResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListApksResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListBundlesResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListTracksResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePostEditsResponse;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper.getRequestBodyForUrl;
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper.release;
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper.track;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ReleaseTrackAssignmentBuilderTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    private AndroidUtil mockAndroid = mock(AndroidUtil.class);
    private JenkinsUtil jenkinsUtil = spy(TestUtilImpl.class);

    private TestHttpTransport transport = new TestHttpTransport();

    @Before
    public void setUp() throws Exception {
        when(mockAndroid.getApkVersionCode(any())).thenReturn(42);
        when(mockAndroid.getApkPackageName(any())).thenReturn("org.jenkins.appId");

        // Create fake AndroidPublisher client
        AndroidPublisher androidClient = TestsHelper.createAndroidPublisher(transport);
        when(jenkinsUtil.createPublisherClient(any(), anyString())).thenReturn(androidClient);

        Util.setAndroidUtil(mockAndroid);
        Util.setJenkinsUtil(jenkinsUtil);
    }

    @After
    public void tearDown() throws Exception {
        transport.dumpRequests();
    }

    @Test
    public void configRoundtripWorks() throws Exception {
        // Given that a few credentials have been set up
        TestsHelper.setUpCredentials("credential-a");
        TestsHelper.setUpCredentials("credential-b");
        TestsHelper.setUpCredentials("credential-c");

        // And we have a job configured with the builder, which includes all possible configuration options
        FreeStyleProject project = j.createFreeStyleProject();

        ReleaseTrackAssignmentBuilder builder = new ReleaseTrackAssignmentBuilder();
        // Choose the second credential, so that when the config page loads, we can differentiate between the dropdown
        // working as expected vs just appearing to work because the first credential would be selected by default
        builder.setGoogleCredentialsId("credential-b");
        builder.setFromVersionCode(false);
        builder.setApplicationId("org.jenkins.appId");
        builder.setVersionCodes("42");
        builder.setFilesPattern("**/*.apk");
        builder.setTrackName("production");
        builder.setRolloutPercentage("5");
        project.getBuildersList().add(builder);

        // When we open and save the configuration page for this job
        project = j.configRoundtrip(project);

        // Then the publisher object should have been serialised and deserialised, without any changes
        j.assertEqualDataBoundBeans(builder, project.getBuildersList().get(0));
    }

    @Test
    public void moveApkTrack_whenVersionCodeDoesNotExist_buildFails() throws Exception {
        transport
                .withResponse("/edits",
                        new FakePostEditsResponse().setEditId("the-edit-id"))
                .withResponse("/edits/the-edit-id/apks",
                        new FakeListApksResponse().setEmptyApks())
                .withResponse("/edits/the-edit-id/bundles",
                        new FakeListBundlesResponse().setEmptyBundles())
        ;

        FreeStyleProject p = j.createFreeStyleProject("moveReleaseTrack");

        ReleaseTrackAssignmentBuilder builder = createBuilder();

        p.getBuildersList().add(builder);
        QueueTaskFuture<FreeStyleBuild> scheduled = p.scheduleBuild2(0);
        j.assertBuildStatus(Result.FAILURE, scheduled);
        j.assertLogContains("Assignment will fail, as these versions do not exist on Google Play: 42", scheduled.get());
    }

    @Test
    public void moveApkTrack_succeeds() throws Exception {
        setUpTransportForSuccess();

        FreeStyleProject p = j.createFreeStyleProject("moveReleaseTrack");

        ReleaseTrackAssignmentBuilder builder = createBuilder();

        // Authenticating to Google Play API...
        // - Credential:     test-credentials
        // - Application ID: org.jenkins.appId
        // Assigning 1 version(s) with application ID org.jenkins.appId to production release track
        // Setting rollout to target 100% of production track users
        // The production release track will now contain the version code(s): 42
        //
        // Applying changes to Google Play...
        // Changes were successfully applied to Google Play
        // Finished: SUCCESS

        p.getBuildersList().add(builder);
        QueueTaskFuture<FreeStyleBuild> scheduled = p.scheduleBuild2(0);
        j.assertBuildStatusSuccess(scheduled);

        TestsHelper.assertLogLines(j, scheduled,
                "Assigning 1 version(s) with application ID org.jenkins.appId to production release track",
                "Setting rollout to target 5% of production track users",
                "The production release track will now contain the version code(s): 42",
                "Changes were successfully applied to Google Play"
        );

        // And we should have included the existing release notes when updating the new track
        Track track = getRequestBodyForUrl(
            transport, "/org.jenkins.appId/edits/the-edit-id/tracks/production", Track.class
        );
        List<LocalizedText> releaseNotes = track.getReleases().get(0).getReleaseNotes();
        assertNotNull(releaseNotes);
        assertEquals(2, releaseNotes.size());
        assertEquals("Notes: en_GB", releaseNotes.get(0).getText());
        assertEquals("Notes: de_DE", releaseNotes.get(1).getText());
    }

    @Test
    public void movingApkTrackAsDraftSucceeds() throws Exception {
        // Given a job, configured to upload as a draft
        FreeStyleProject p = j.createFreeStyleProject();
        ReleaseTrackAssignmentBuilder builder = createBuilder();
        builder.setRolloutPercentage("0%");
        p.getBuildersList().add(builder);

        // And the prerequisites are in place
        TestsHelper.setUpCredentials("test-credentials");
        setUpTransportForSuccess();

        // When a build occurs, it should create the release in the target track as a draft
        TestsHelper.assertResultWithLogLines(j, p, Result.SUCCESS,
            "New production draft release created, with the version code(s): 42",
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
    public void moveApkTrackWithPipeline_succeeds() throws Exception {
        String stepDefinition =
            "  androidApkMove googleCredentialsId: 'test-credentials',\n" +
            "    fromVersionCode: true,\n" +
            "    applicationId: 'org.jenkins.appId',\n" +
            "    versionCodes: '42'";

        moveApkTrackWithPipelineAndAssertSuccess(
            stepDefinition, "Setting rollout to target 100% of production track users"
        );
    }

    @Test
    public void moveApkTrackWithPipeline_withRolloutPercentage() throws Exception {
        // Given a step with a `rolloutPercentage` value
        String stepDefinition =
            "androidApkMove googleCredentialsId: 'test-credentials',\n" +
            "  fromVersionCode: true,\n" +
            "  applicationId: 'org.jenkins.appId',\n" +
            "  versionCodes: '42',\n" +
            "  rolloutPercentage: '56.789'";

        // When a build occurs, it should roll out to that percentage
        moveApkTrackWithPipelineAndAssertSuccess(
            stepDefinition, "Setting rollout to target 56.789% of production track users"
        );
    }

    @Test
    public void moveApkTrackWithPipeline_withRolloutPercent() throws Exception {
        // Given a step with a deprecated `rolloutPercent` value
        String stepDefinition =
            "androidApkMove googleCredentialsId: 'test-credentials',\n" +
            "  fromVersionCode: true,\n" +
            "  applicationId: 'org.jenkins.appId',\n" +
            "  versionCodes: '42',\n" +
            "  rolloutPercent: 12.34";

        // When a build occurs, it should roll out to that percentage
        moveApkTrackWithPipelineAndAssertSuccess(
            stepDefinition, "Setting rollout to target 12.34% of production track users"
        );
    }

    @Test
    public void moveApkTrackWithPipeline_withBothRolloutFormats_usesRolloutPercentage() throws Exception {
        // Given a step with both the deprecated `rolloutPercent`, and a verbose `rolloutPercentage` value
        String stepDefinition =
            "androidApkMove googleCredentialsId: 'test-credentials',\n" +
            "  fromVersionCode: true,\n" +
            "  applicationId: 'org.jenkins.appId',\n" +
            "  versionCodes: '42',\n" +
            "  rolloutPercent: 12.3456,\n" +
            "  rolloutPercentage: '56.789%'";

        // When a build occurs, it should prefer the string `rolloutPercentage` value
        moveApkTrackWithPipelineAndAssertSuccess(
            stepDefinition, "Setting rollout to target 56.789% of production track users"
        );
    }

    @Test
    public void uploadingApkWithPipelineAsDraftSucceeds() throws Exception {
        // Given a step with the rollout percentage set to zero
        String stepDefinition =
            "androidApkMove googleCredentialsId: 'test-credentials',\n" +
            "  fromVersionCode: true,\n" +
            "  applicationId: 'org.jenkins.appId',\n" +
            "  versionCodes: '42',\n" +
            "  rolloutPercentage: '0%'";

        // When a build occurs, it should upload as a draft
        moveApkTrackWithPipelineAndAssertSuccess(
            stepDefinition,
            "New production draft release created, with the version code(s): 42"
        );

        // And we should have set draft status when updating the track
        Track track = getRequestBodyForUrl(
            transport, "/org.jenkins.appId/edits/the-edit-id/tracks/production", Track.class
        );
        TrackRelease release = track.getReleases().get(0);
        assertEquals("draft", release.getStatus());
        assertNull(release.getUserFraction());
    }

    private void moveApkTrackWithPipelineAndAssertSuccess(String stepDefinition, String... expectedLines) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("" +
            "node {\n" +
            "  " + stepDefinition + "\n" +
            "}", true
        ));

        TestsHelper.setUpCredentials("test-credentials");
        setUpTransportForSuccess();

        String[] commonLines = {
            "Assigning 1 version(s) with application ID org.jenkins.appId to production release track",
            "Changes were successfully applied to Google Play"
        };
        String[] allExpectedLogLines = Stream.concat(Arrays.stream(commonLines), Arrays.stream(expectedLines))
                .toArray(String[]::new);
        TestsHelper.assertResultWithLogLines(j, p, Result.SUCCESS, allExpectedLogLines);
    }

    @Test
    @Ignore("Test does not work on a remote slave")
    public void moveApkTrack_fromSlave_succeeds() throws Exception {
        transport
                .withResponse("/edits",
                        new FakePostEditsResponse().setEditId("the-edit-id"))
                .withResponse("/edits/the-edit-id/apks",
                        new FakeListApksResponse().setApks(42))
                .withResponse("/edits/the-edit-id/bundles",
                        new FakeListBundlesResponse().setEmptyBundles())
                .withResponse("/edits/the-edit-id/tracks/production",
                        new FakeAssignTrackResponse().success("production", 42))
                .withResponse("/edits/the-edit-id:commit",
                        new FakeCommitResponse().success())
        ;

        DumbSlave agent = j.createOnlineSlave();
        FreeStyleProject p = j.createFreeStyleProject("moveReleaseTrack");
        p.setAssignedNode(agent);

        ReleaseTrackAssignmentBuilder builder = createBuilder();

        // Authenticating to Google Play API...
        // - Credential:     test-credentials
        // - Application ID: org.jenkins.appId
        // Assigning 1 version(s) with application ID org.jenkins.appId to production release track
        // Setting rollout to target 100% of production track users
        // The production release track will now contain the version code(s): 42
        //
        // Applying changes to Google Play...
        // Changes were successfully applied to Google Play
        // Finished: SUCCESS

        p.getBuildersList().add(builder);
        QueueTaskFuture<FreeStyleBuild> scheduled = p.scheduleBuild2(0);
        j.assertBuildStatusSuccess(scheduled);

        TestsHelper.assertLogLines(j, scheduled,
                "Assigning 1 version(s) with application ID org.jenkins.appId to production release track",
                "Setting rollout to target 5% of production track users",
                "The production release track will now contain the version code(s): 42",
                "Changes were successfully applied to Google Play"
        );
    }

    private void setUpTransportForSuccess() {
        transport
            .withResponse("/edits",
                    new FakePostEditsResponse().setEditId("the-edit-id"))
            .withResponse("/edits/the-edit-id/apks",
                    new FakeListApksResponse().setApks(42))
            .withResponse("/edits/the-edit-id/bundles",
                    new FakeListBundlesResponse().setEmptyBundles())
            .withResponse("/edits/the-edit-id/tracks",
                    new FakeListTracksResponse().setTracks(
                        new ArrayList<Track>() {{
                            add(track("production"));
                            add(track("beta", release(42, "en_GB", "de_DE")));
                        }}
                    ))
            .withResponse("/edits/the-edit-id/tracks/production",
                    new FakeAssignTrackResponse().success("production", 42))
            .withResponse("/edits/the-edit-id:commit",
                    new FakeCommitResponse().success())
        ;
    }

    private ReleaseTrackAssignmentBuilder createBuilder() throws Exception {
        ReleaseTrackAssignmentBuilder builder = new ReleaseTrackAssignmentBuilder();
        TestsHelper.setUpCredentials("test-credentials");
        builder.setGoogleCredentialsId("test-credentials");
        builder.setApplicationId("org.jenkins.appId");
        builder.setVersionCodes("42");
        builder.setRolloutPercentage("5%");
        builder.setTrackName("production");
        return builder;
    }
}