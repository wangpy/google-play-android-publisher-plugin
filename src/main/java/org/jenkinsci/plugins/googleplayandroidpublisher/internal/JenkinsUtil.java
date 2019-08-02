package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import java.security.GeneralSecurityException;

public interface JenkinsUtil {
    /**
     * @return The version of this Jenkins plugin, e.g. "1.0" or "1.1-SNAPSHOT" (for dev releases).
     */
    String getPluginVersion();

    AndroidPublisher createPublisherClient(GoogleRobotCredentials credentials, String pluginVersion) throws GeneralSecurityException;
}
