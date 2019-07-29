package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.google.api.client.googleapis.testing.auth.oauth2.MockGoogleCredential;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import net.dongliu.apk.parser.bean.ApkMeta;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.AndroidUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.JenkinsUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestHttpTransport;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestUtil;
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
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ApkPublisherTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    private AndroidUtil mockAndroid = mock(AndroidUtil.class);
    private JenkinsUtil jenkinsUtil = spy(TestUtil.class);

    private TestHttpTransport transport = new TestHttpTransport();

    @Before
    public void setUp() throws Exception {
        ApkMeta mockMetadata = mock(ApkMeta.class);
        when(mockMetadata.getPackageName()).thenReturn("org.jenkins.appId");
        when(mockMetadata.getVersionCode()).thenReturn((long) 42);
        when(mockMetadata.getMinSdkVersion()).thenReturn("16");

        when(mockAndroid.getApkVersionCode(any())).thenReturn(42);
        when(mockAndroid.getApkPackageName(any())).thenReturn("org.jenkins.appId");
        when(mockAndroid.getApkMetadata(any())).thenReturn(mockMetadata);

        // Create fake AndroidPublisher client
        MockGoogleCredential mockCredential = new MockGoogleCredential.Builder().build();
        AndroidPublisher androidClient = new AndroidPublisher.Builder(transport, mockCredential.getJsonFactory(), mockCredential)
                .setApplicationName("Jenkins-GooglePlayAndroidPublisher-tests")
                .setSuppressAllChecks(true)
                .build();

        when(jenkinsUtil.createPublisherClient(any(), anyString())).thenReturn(androidClient);

        Util.setAndroidUtil(mockAndroid);
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

        setUpCredentials("test-credentials");
        setUpApkFile(p);

        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.apkFilesPattern = "**/*.apk";
        publisher.trackName = "production";

        p.getPublishersList().add(publisher);
        j.assertBuildStatusSuccess(p.scheduleBuild2(0));
    }

    private void setUpCredentials(String name) throws Exception {
        GoogleRobotCredentials fakeCredentials = mock(GoogleRobotCredentials.class);
        when(fakeCredentials.getId()).thenReturn(name);
        when(fakeCredentials.forRemote(any())).thenReturn(fakeCredentials);
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(fakeCredentials);
    }

    private void setUpApkFile(FreeStyleProject p) throws Exception {
        FilePath workspace = j.jenkins.getWorkspaceFor(p);
        FilePath apkDir = workspace.child("build").child("outputs").child("apk");
        FilePath apk = apkDir.child("app.apk");
        apk.copyFrom(getClass().getResourceAsStream("/foo.apk"));
    }
}
