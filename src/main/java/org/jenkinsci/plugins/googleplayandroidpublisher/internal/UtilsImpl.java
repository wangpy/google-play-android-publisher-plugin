package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.googleplayandroidpublisher.AndroidPublisherScopeRequirement;
import org.jenkinsci.plugins.googleplayandroidpublisher.Util;

import java.security.GeneralSecurityException;

public class UtilsImpl implements JenkinsUtil, AndroidUtil {
    private static UtilsImpl sInstance;

    public static UtilsImpl getInstance() {
        if (sInstance == null) sInstance = new UtilsImpl();
        return sInstance;
    }

    // region JenkinsUtil

    @Override
    public String getPluginVersion() {
        final String version = Jenkins.getInstance().getPluginManager().whichPlugin(Util.class).getVersion();
        int index = version.indexOf(' ');
        return (index == -1) ? version : version.substring(0, index);
    }

    @Override
    public AndroidPublisher createPublisherClient(GoogleRobotCredentials credentials, String pluginVersion)
            throws GeneralSecurityException {
        final Credential credential = credentials.getGoogleCredential(new AndroidPublisherScopeRequirement());
        final HttpRequestInitializer requestInitializer = applyHttpConnectionTimeouts(credential);
        return new AndroidPublisher.Builder(credential.getTransport(), credential.getJsonFactory(), requestInitializer)
                .setApplicationName(String.format("Jenkins-GooglePlayAndroidPublisher/%s", pluginVersion))
                .build();
    }

    // Adapted from https://developers.google.com/api-client-library/java/google-api-java-client/errors#timeouts
    static HttpRequestInitializer applyHttpConnectionTimeouts(final HttpRequestInitializer delegate) {
        return httpRequest -> {
            delegate.initialize(httpRequest);
            httpRequest.setConnectTimeout(30 * 1000);   // Allow more time for the initial connection
            httpRequest.setReadTimeout(60 * 60 * 1000); // AABs can be huge, so allow for long upload requests
        };
    }

    // endregion
}
