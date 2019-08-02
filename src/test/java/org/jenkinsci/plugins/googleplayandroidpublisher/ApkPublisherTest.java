package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.services.androidpublisher.AndroidPublisher;
import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Slave;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.DumbSlave;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StringReader;
import java.util.Arrays;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.AndroidUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.JenkinsUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestHttpTransport;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestUtilImpl;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeAssignTrackResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeCommitResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeHttpResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListApksResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePostEditsResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePutApkResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeUploadApkResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;
import static hudson.Util.join;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
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

    @Test
    @WithoutJenkins
    @Ignore("Serialization of response does not work")
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
        FakeHttpResponse response = deserializedTransport.responses.get("/edits");
        assertNotNull(response);
        assertThat(response.getStatusCode(), equalTo(400));
        assertThat(response.getContentLength(), equalTo(18));
    }

    @Test
    @Ignore("Test does not work on a remote slave")
    public void uploadSingleApk_fromSlave_succeeds() throws Exception {
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

        DumbSlave agent = j.createOnlineSlave();
        FreeStyleProject p = j.createFreeStyleProject("uploadApks");
        p.setAssignedNode(agent);

        TestsHelper.setUpCredentials("test-credentials");
        setUpApkFileOnSlave(p, agent);

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

    private void setUpApkFileOnSlave(FreeStyleProject p, Slave agent) throws Exception {
        FilePath workspace = agent.getWorkspaceFor(p);
        FilePath apkDir = workspace.child("build").child("outputs").child("apk");
        FilePath apk = apkDir.child("app.apk");
        apk.copyFrom(getClass().getResourceAsStream("/foo.apk"));
    }
}
