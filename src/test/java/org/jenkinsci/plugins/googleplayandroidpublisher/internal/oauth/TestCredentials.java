package org.jenkinsci.plugins.googleplayandroidpublisher.internal.oauth;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.testing.auth.oauth2.MockGoogleCredential;
import com.google.jenkins.plugins.credentials.oauth.GoogleOAuth2ScopeRequirement;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.GeneralSecurityException;

public class TestCredentials extends GoogleRobotCredentials {
    public TestCredentials(String projectId) {
        super(projectId, new TestCredentialsModule());
    }

    @Override
    public Credential getGoogleCredential(GoogleOAuth2ScopeRequirement requirement) throws GeneralSecurityException {
        GoogleCredential credential = new MockGoogleCredential.Builder()
                .setTransport(getModule().getHttpTransport())
                .setJsonFactory(getModule().getJsonFactory())
                .setClientSecrets("id", "secret")
                .build();
        credential.setRefreshToken("refresh");
        return credential;
    }

    @NonNull
    @Override
    public String getUsername() {
        return getProjectId();
    }

    @Override
    public CredentialsScope getScope() {
        return CredentialsScope.GLOBAL;
    }
}
