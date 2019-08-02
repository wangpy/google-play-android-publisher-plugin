package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.FilePath;
import java.io.File;
import java.io.IOException;
import net.dongliu.apk.parser.bean.ApkMeta;
import org.mockito.stubbing.Answer;
import static org.mockito.Mockito.mock;

public class TestUtilImpl implements JenkinsUtil, AndroidUtil {
    public static final boolean DEBUG = true;

    @Override
    public String getPluginVersion() {
        return "0.0.0-TEST";
    }

    @Override
    public <R> R actOnPath(FilePath file, CheckedFunction<File, R> function) throws IOException {
        return function.apply(new File(file.getRemote()));
    }

    @Override
    public AndroidPublisher createPublisherClient(GoogleRobotCredentials credentials, String pluginVersion) {
        // Example:
        //     AndroidPublisher androidClient = TestsHelper.createAndroidPublisher(transport);
        //     when(jenkinsUtil.createPublisherClient(any(), anyString())).thenReturn(androidClient);
        return mock(AndroidPublisher.class, (Answer) invocationOnMock -> {
            throw new UnsupportedOperationException(
                    "This should be implemented using `TestHelper.createAndroidPublisher()` for now.");
        });
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
        return ApkMeta.newBuilder()
                .setPackageName("org.jenkins.appId")
                .setVersionCode(42L)
                .setMinSdkVersion("16")
                .build();
    }
}
