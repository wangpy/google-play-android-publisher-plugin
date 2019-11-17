package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import java.io.File;
import java.io.IOException;

import org.jenkinsci.plugins.googleplayandroidpublisher.AppFileMetadata;
import org.mockito.stubbing.Answer;
import static org.mockito.Mockito.mock;

public class TestUtilImpl implements JenkinsUtil, AndroidUtil {
    public static final boolean DEBUG = true;

    @Override
    public String getPluginVersion() {
        return "0.0.0-TEST";
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
    public AppFileMetadata getAppFileMetadata(File file) throws IOException {
        boolean isBundle = file.getName().endsWith(".aab");
        String appId = isBundle ? "org.jenkins.bundleAppId" : "org.jenkins.appId";
        int versionCode = isBundle ? 43 : 42;
        String minSdkVersion = isBundle ? "29" : "16";
        return new AppFileMetadata(appId, versionCode, minSdkVersion);
    }
}
