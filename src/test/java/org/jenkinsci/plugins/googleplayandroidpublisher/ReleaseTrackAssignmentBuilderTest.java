package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.services.androidpublisher.AndroidPublisher;
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
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePostEditsResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
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