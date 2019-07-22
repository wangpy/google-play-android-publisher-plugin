package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.FilePath;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

public interface JenkinsUtil {
    /**
     * @return The version of this Jenkins plugin, e.g. "1.0" or "1.1-SNAPSHOT" (for dev releases).
     */
    String getPluginVersion();

    /**
     * Executes some program on the machine that this {@link FilePath} exists,
     * so that one can perform local file operations.
     *
     * @see FilePath#act(FilePath.FileCallable)
     */
    <R> R actOnPath(FilePath file, CheckedFunction<File, R> function) throws IOException, InterruptedException;

    AndroidPublisher createPublisherClient(GoogleRobotCredentials credentials, String pluginVersion) throws GeneralSecurityException;
}
