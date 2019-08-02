package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.services.androidpublisher.AndroidPublisher;
import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import java.io.File;
import java.util.Arrays;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.AndroidUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.JenkinsUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestHttpTransport;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestUtilImpl;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeAssignTrackResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeCommitResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListApksResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePostEditsResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePutApkResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeUploadApkResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import static hudson.Util.join;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ApkPublisherTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    private AndroidUtil androidUtil = spy(TestUtilImpl.class);
    private JenkinsUtil jenkinsUtil = spy(TestUtilImpl.class);

    private TestHttpTransport transport = new TestHttpTransport();

    @Before
    public void setUp() throws Exception {
        // Create fake AndroidPublisher client
        AndroidPublisher androidClient = TestsHelper.createAndroidPublisher(transport);
        when(jenkinsUtil.createPublisherClient(any(), anyString())).thenReturn(androidClient);

        Util.setAndroidUtil(androidUtil);
        Util.setJenkinsUtil(jenkinsUtil);
    }

    @After
    public void tearDown() {
        transport.dumpRequests();
    }

    @Test
    public void whenApkFileMissing_buildFails() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("uploadApks");

        ApkPublisher publisher = new ApkPublisher();
        publisher.trackName = "production";
        p.getPublishersList().add(publisher);

        // Cannot upload to Google Play:
        // - Path or pattern to APK file was not specified
        QueueTaskFuture<FreeStyleBuild> scheduled = p.scheduleBuild2(0);
        j.assertBuildStatus(Result.FAILURE, scheduled);
        j.assertLogContains("Path or pattern to APK file was not specified", scheduled.get());
    }

    @Test
    public void uploadSingleApk_succeeds() throws Exception {
        transport
                .withResponse("/edits",
                        new FakePostEditsResponse().setEditId("the-edit-id"))
                .withResponse("/edits/the-edit-id/apks",
                        new FakeListApksResponse().setEmptyApks())
                .withResponse("/edits/the-edit-id/apks?uploadType=resumable",
                        new FakeUploadApkResponse().willContinue())
                .withResponse("google.local/uploading/foo/apk",
                        new FakePutApkResponse().success(42, "the:sha"))
                .withResponse("/edits/the-edit-id/tracks/production",
                        new FakeAssignTrackResponse().success("production", 42))
                .withResponse("/edits/the-edit-id:commit",
                        new FakeCommitResponse().success())
        ;

        FreeStyleProject p = j.createFreeStyleProject("uploadApks");

        TestsHelper.setUpCredentials("test-credentials");
        setUpApkFile(p);

        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.apkFilesPattern = "**/*.apk";
        publisher.trackName = "production";

        p.getPublishersList().add(publisher);

        // Authenticating to Google Play API...
        // - Credential:     test-credentials
        // - Application ID: org.jenkins.appId
        //
        // Uploading 1 APK(s) with application ID: org.jenkins.appId
        //
        //       APK file: build/outputs/apk/app.apk
        //     SHA-1 hash: da39a3ee5e6b4b0d3255bfef95601890afd80709
        //    versionCode: 42
        //  minSdkVersion: 16
        //
        // Setting rollout to target 100% of production track users
        // The production release track will now contain APK(s) with version code(s): 42
        //
        // Applying changes to Google Play...
        // Changes were successfully applied to Google Play

        TestsHelper.assertResultWithLogLines(j, p, Result.SUCCESS,
                "Uploading 1 APK(s) with application ID: org.jenkins.appId",
                "APK file: " + join(Arrays.asList("build", "outputs", "apk", "app.apk"), File.separator),
                "versionCode: 42",
                "Setting rollout to target 100% of production track users",
                "The production release track will now contain APK(s) with version code(s): 42",
                "Changes were successfully applied to Google Play"
        );
    }

    private void setUpApkFile(FreeStyleProject p) throws Exception {
        FilePath workspace = j.jenkins.getWorkspaceFor(p);
        FilePath apkDir = workspace.child("build").child("outputs").child("apk");
        FilePath apk = apkDir.child("app.apk");
        apk.copyFrom(getClass().getResourceAsStream("/foo.apk"));
    }
}
