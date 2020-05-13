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
import java.util.Arrays;
import java.util.Collections;

import org.jenkinsci.plugins.googleplayandroidpublisher.internal.JenkinsUtil;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestHttpTransport;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestUtilImpl;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestsHelper;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeAssignTrackResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeCommitResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListApksResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeListBundlesResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePostEditsResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePutApkResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakePutBundleResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeUploadApkResponse;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeUploadBundleResponse;
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
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestConstants.DEFAULT_APK;
import static org.jenkinsci.plugins.googleplayandroidpublisher.internal.TestConstants.DEFAULT_BUNDLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
        AndroidPublisher androidClient = TestsHelper.createAndroidPublisher(transport);
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
        TestsHelper.setUpCredentials("credential-a");
        TestsHelper.setUpCredentials("credential-b");
        TestsHelper.setUpCredentials("credential-c");

        // And we have a job configured with the APK publisher, which includes all possible configuration options
        FreeStyleProject project = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        // Choose the second credential, so that when the config page loads, we can differentiate between the dropdown
        // working as expected vs just appearing to work because the first credential would be selected by default
        publisher.setGoogleCredentialsId("credential-b");
        publisher.setFilesPattern("**/builds/*.apk, *.aab");
        publisher.setDeobfuscationFilesPattern("**/proguard/*.txt");
        publisher.setExpansionFilesPattern("**/exp/*.obb");
        publisher.setUsePreviousExpansionFilesIfMissing(true);
        publisher.setTrackName("alpha");
        publisher.setRolloutPercentage("${ROLLOUT}");
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
    public void whenApkFileMissing_buildFails() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("uploadApks");

        ApkPublisher publisher = new ApkPublisher();
        publisher.trackName = "production";
        p.getPublishersList().add(publisher);

        QueueTaskFuture<FreeStyleBuild> scheduled = p.scheduleBuild2(0);
        j.assertBuildStatus(Result.FAILURE, scheduled);
        String error = "No AAB or APK files matching the pattern '**/build/outputs/**/*.aab, **/build/outputs/**/*.apk' could be found";
        j.assertLogContains(error, scheduled.get());
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
        publisher.filesPattern = "**/*.apk";
        publisher.trackName = "production";
        p.getPublishersList().add(publisher);

        TestsHelper.setUpCredentials("test-credentials");
        setUpApkFile(p);

        // When a build occurs, it should fail as the APK file already exists
        TestsHelper.assertResultWithLogLines(j, p, Result.FAILURE,
                "Uploading 1 file(s) with application ID: org.jenkins.appId",
                "APK file: " + join(Arrays.asList("build", "outputs", "apk", "app.apk"), File.separator),
                "versionCode: 42",
                "This file already exists in the Google Play account; it cannot be uploaded again",
                "Upload to Google Play failed"
        );
    }

    @Test
    public void uploadSingleApk_succeeds() throws Exception {
        setUpTransportForApk();

        FreeStyleProject p = j.createFreeStyleProject("uploadApks");

        TestsHelper.setUpCredentials("test-credentials");
        setUpApkFile(p);

        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.filesPattern = "**/*.apk";
        publisher.trackName = "production";

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
        // Setting rollout to target 100% of production track users
        // The production release track will now contain the version code(s): 42
        //
        // Applying changes to Google Play...
        // Changes were successfully applied to Google Play

        TestsHelper.assertResultWithLogLines(j, p, Result.SUCCESS,
                "Uploading 1 file(s) with application ID: org.jenkins.appId",
                "APK file: " + join(Arrays.asList("build", "outputs", "apk", "app.apk"), File.separator),
                "versionCode: 42",
                "Setting rollout to target 100% of production track users",
                "The production release track will now contain the version code(s): 42",
                "Changes were successfully applied to Google Play"
        );
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
        publisher.filesPattern = "**/*.aab";
        publisher.trackName = "production";
        p.getPublishersList().add(publisher);

        TestsHelper.setUpCredentials("test-credentials");
        setUpBundleFile(p);

        // When a build occurs, it should fail as the bundle file already exists
        TestsHelper.assertResultWithLogLines(j, p, Result.FAILURE,
                "Uploading 1 file(s) with application ID: org.jenkins.bundleAppId",
                "AAB file: " + join(Arrays.asList("build", "outputs", "bundle", "release", "bundle.aab"), File.separator),
                "versionCode: 43",
                "This file already exists in the Google Play account; it cannot be uploaded again",
                "Upload to Google Play failed"
        );
    }

    @Test
    public void uploadSingleBundle_succeeds() throws Exception {
        setUpTransportForBundle();

        FreeStyleProject p = j.createFreeStyleProject("uploadBundles");

        TestsHelper.setUpCredentials("test-credentials");
        setUpBundleFile(p);

        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.filesPattern = "**/*.aab";
        publisher.trackName = "production";

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
        // Setting rollout to target 100% of production track users
        // The production release track will now contain the version code(s): 43
        //
        // Applying changes to Google Play...
        // Changes were successfully applied to Google Play

        TestsHelper.assertResultWithLogLines(j, p, Result.SUCCESS,
                "Uploading 1 file(s) with application ID: org.jenkins.bundleAppId",
                "AAB file: " + join(Arrays.asList("build", "outputs", "bundle", "release", "bundle.aab"), File.separator),
                "versionCode: 43",
                "minSdkVersion: 29",
                "Setting rollout to target 100% of production track users",
                "The production release track will now contain the version code(s): 43",
                "Changes were successfully applied to Google Play"
        );
    }

    @Test
    public void givenMultipleFileTypesBundlesArePreferred() throws Exception {
        // Given a freestyle job which will attempt to upload all files in the workspace
        FreeStyleProject p = j.createFreeStyleProject();
        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.filesPattern = "**/*";
        publisher.trackName = "production";
        p.getPublishersList().add(publisher);

        TestsHelper.setUpCredentials("test-credentials");
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
        TestsHelper.assertResultWithLogLines(j, p, Result.SUCCESS,
            "Both AAB and APK files were found; only the AAB files will be uploaded",
            "Uploading 1 file(s) with application ID: com.example.test",
            "AAB file: " + join(Arrays.asList("build", "outputs", "bundle", "release", "bundle.aab"), File.separator),
            "The production release track will now contain the version code(s): 43"
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

        TestsHelper.setUpCredentials("test-credentials");
        setUpApkFileOnSlave(p, agent);

        ApkPublisher publisher = new ApkPublisher();
        publisher.setGoogleCredentialsId("test-credentials");
        publisher.filesPattern = "**/*.apk";
        publisher.trackName = "production";

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
        // Setting rollout to target 100% of production track users
        // The production release track will now contain the version code(s): 42
        //
        // Applying changes to Google Play...
        // Changes were successfully applied to Google Play

        TestsHelper.assertResultWithLogLines(j, p, Result.SUCCESS,
                "Uploading 1 file(s) with application ID: org.jenkins.appId",
                "APK file: " + join(Arrays.asList("build", "outputs", "apk", "app.apk"), File.separator),
                "versionCode: 42",
                "Setting rollout to target 100% of production track users",
                "The production release track will now contain the version code(s): 42",
                "Changes were successfully applied to Google Play"
        );
    }

    private void setUpTransportForApk() {
        transport
                .withResponse("/edits",
                        new FakePostEditsResponse().setEditId("the-edit-id"))
                .withResponse("/edits/the-edit-id/apks",
                        new FakeListApksResponse().setEmptyApks())
                .withResponse("/edits/the-edit-id/bundles",
                        new FakeListBundlesResponse().setEmptyBundles())
                .withResponse("/edits/the-edit-id/apks?uploadType=resumable",
                        new FakeUploadApkResponse().willContinue())
                .withResponse("google.local/uploading/foo/apk",
                        new FakePutApkResponse().success(42, "the:sha"))
                .withResponse("/edits/the-edit-id/tracks/production",
                        new FakeAssignTrackResponse().success("production", 42))
                .withResponse("/edits/the-edit-id:commit",
                        new FakeCommitResponse().success())
        ;
    }

    private void setUpTransportForBundle() {
        transport
                .withResponse("/edits",
                        new FakePostEditsResponse().setEditId("the-edit-id"))
                .withResponse("/edits/the-edit-id/apks",
                        new FakeListApksResponse().setEmptyApks())
                .withResponse("/edits/the-edit-id/bundles",
                        new FakeListBundlesResponse().setEmptyBundles())
                .withResponse("/edits/the-edit-id/bundles?uploadType=resumable",
                        new FakeUploadBundleResponse().willContinue())
                .withResponse("google.local/uploading/foo/bundle",
                        new FakePutBundleResponse().success(43, "the:sha"))
                .withResponse("/edits/the-edit-id/tracks/production",
                        new FakeAssignTrackResponse().success("production", 43))
                .withResponse("/edits/the-edit-id:commit",
                        new FakeCommitResponse().success())
        ;
    }

    private void setUpApkFile(FreeStyleProject p) throws Exception {
        FilePath workspace = j.jenkins.getWorkspaceFor(p);
        FilePath dir = workspace.child("build/outputs/apk");
        dir.mkdirs();
        FilePath file = dir.child("app.apk");
        file.touch(0);
    }

    private void setUpApkFileOnSlave(FreeStyleProject p, Slave agent) throws Exception {
        FilePath workspace = agent.getWorkspaceFor(p);
        FilePath apkDir = workspace.child("build").child("outputs").child("apk");
        FilePath apk = apkDir.child("app.apk");
        apk.copyFrom(getClass().getResourceAsStream("/foo.apk"));
    }

    private void setUpBundleFile(FreeStyleProject p) throws Exception {
        FilePath workspace = j.jenkins.getWorkspaceFor(p);
        FilePath dir = workspace.child("build/outputs/bundle/release");
        dir.mkdirs();
        FilePath file = dir.child("bundle.aab");
        file.touch(0);
    }
}
