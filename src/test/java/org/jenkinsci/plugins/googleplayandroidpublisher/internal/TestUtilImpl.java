package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import java.io.File;

import org.mockito.stubbing.Answer;
import static org.mockito.Mockito.mock;

public class TestUtilImpl implements JenkinsUtil, AndroidUtil {
    public static final boolean DEBUG = true;

    private String apkAppId = "org.jenkins.appId";
    private String bundleAppId = "org.jenkins.bundleAppId";

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
    public AppFileMetadata getAppFileMetadata(File file) {
        if (file.getName().endsWith(".aab")) {
            return new BundleFileMetadata(bundleAppId, 43, "29");
        }
        return new ApkFileMetadata(apkAppId, 42, "16");
    }

    public void setApkAppId(String apkAppId) {
        this.apkAppId = apkAppId;
    }

    public void setBundleAppId(String bundleAppId) {
        this.bundleAppId = bundleAppId;
    }

}
