package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.google.api.client.googleapis.testing.auth.oauth2.MockGoogleCredential;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.FilePath;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import net.dongliu.apk.parser.bean.ApkMeta;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

public class TestUtil implements JenkinsUtil, AndroidUtil {
    @Override
    public String getPluginVersion() {
        return "0.0.0-TEST";
    }

    @Override
    public <R> R actOnPath(FilePath file, CheckedFunction<File, R> function) throws IOException {
        return function.apply(new File(file.getRemote()));
    }

    @Override
    public AndroidPublisher createPublisherClient(GoogleRobotCredentials credentials, String pluginVersion) throws GeneralSecurityException {
        // FIXME: let this be shared
        TestHttpTransport fakeTransport = new TestHttpTransport();
        MockGoogleCredential mockCredential = new MockGoogleCredential.Builder().build();
        return new AndroidPublisher.Builder(fakeTransport, mockCredential.getJsonFactory(), mockCredential)
                .setApplicationName("Jenkins-GooglePlayAndroidPublisher-tests")
                .setSuppressAllChecks(true)
                .build();
    }

    @Override
    public int getApkVersionCode(File apk) {
        return 42;
    }

    @Override
    public String getApkPackageName(File apk) {
        return "org.jenkins.appId";
    }

    @Override
    public ApkMeta getApkMetadata(File apk) {
        return mock(ApkMeta.class, RETURNS_DEEP_STUBS);
    }
}
