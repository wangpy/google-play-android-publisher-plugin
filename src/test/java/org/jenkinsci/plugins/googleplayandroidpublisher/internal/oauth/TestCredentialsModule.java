package org.jenkinsci.plugins.googleplayandroidpublisher.internal.oauth;

import com.google.api.client.googleapis.testing.auth.oauth2.MockGoogleCredential;
import com.google.api.client.http.HttpTransport;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentialsModule;

public class TestCredentialsModule extends GoogleRobotCredentialsModule {
    @Override
    public HttpTransport getHttpTransport() {
        return MockGoogleCredential.newMockHttpTransportWithSampleTokenResponse();
    }
}
