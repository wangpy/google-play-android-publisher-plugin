package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.client.googleapis.testing.auth.oauth2.MockGoogleCredential;
import com.google.api.services.androidpublisher.AndroidPublisher;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.AndroidUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.JenkinsUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestHttpTransport;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestUtilImpl;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeAssignTrackResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeCommitResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListApksResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePostEditsResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ReleaseTrackAssignmentTest {
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
    public void moveApkTrack_whenVersionCodeDoesNotExist_buildFails() throws Exception {
        transport
                .withResponse("/edits",
                        new FakePostEditsResponse().setEditId("the-edit-id"))
                .withResponse("/edits/the-edit-id/apks",
                        new FakeListApksResponse().setEmptyApks())
        ;

        FreeStyleProject p = j.createFreeStyleProject("moveReleaseTrack");

        ReleaseTrackAssignmentBuilder builder = createBuilder();

        p.getBuildersList().add(builder);
        QueueTaskFuture<FreeStyleBuild> scheduled = p.scheduleBuild2(0);
        j.assertBuildStatus(Result.FAILURE, scheduled);
        j.assertLogContains("Could not assign APK(s) 42 to production, as these APKs do not exist: 42", scheduled.get());
    }

    @Test
    public void moveApkTrack_succeeds() throws Exception {
        transport
                .withResponse("/edits",
                        new FakePostEditsResponse().setEditId("the-edit-id"))
                .withResponse("/edits/the-edit-id/apks",
                        new FakeListApksResponse().setApks(42))
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
        // Assigning 1 APK(s) with application ID org.jenkins.appId to production release track
        // Setting rollout to target 100% of production track users
        // The production release track will now contain APK(s) with version code(s): 42
        //
        // Applying changes to Google Play...
        // Changes were successfully applied to Google Play
        // Finished: SUCCESS

        p.getBuildersList().add(builder);
        QueueTaskFuture<FreeStyleBuild> scheduled = p.scheduleBuild2(0);
        j.assertBuildStatusSuccess(scheduled);

        TestsHelper.assertLogLines(j, scheduled,
                "Assigning 1 APK(s) with application ID org.jenkins.appId to production release track",
                "Setting rollout to target 5% of production track users",
                "The production release track will now contain APK(s) with version code(s): 42",
                "Changes were successfully applied to Google Play"
        );
    }

    private ReleaseTrackAssignmentBuilder createBuilder() throws Exception {
        ReleaseTrackAssignmentBuilder builder = new ReleaseTrackAssignmentBuilder();
        TestsHelper.setUpCredentials("test-credentials");
        builder.setGoogleCredentialsId("test-credentials");
        builder.applicationId = "org.jenkins.appId";
        builder.versionCodes = "42";
        builder.rolloutPercentage = "5";
        builder.trackName = "production";
        return builder;
    }
}