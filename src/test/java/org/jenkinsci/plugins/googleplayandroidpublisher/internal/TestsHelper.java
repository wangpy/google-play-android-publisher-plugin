package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.google.api.client.googleapis.testing.auth.oauth2.MockGoogleCredential;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.oauth.TestCredentials;
import org.jvnet.hudson.test.JenkinsRule;

import javax.annotation.Nonnull;
import java.io.IOException;

public class TestsHelper {
    public static void setUpCredentials(String name) {
        GoogleRobotCredentials fakeCredentials = new TestCredentials(name);
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(fakeCredentials);
    }

    public static void setUpCredentials(String name, Folder folder) throws IOException {
        Iterable<CredentialsStore> stores = CredentialsProvider.lookupStores(folder);
        for (CredentialsStore store : stores) {
            if (store.getProvider() instanceof FolderCredentialsProvider && store.getContext() == folder) {
                GoogleRobotCredentials fakeCredentials = new TestCredentials(name);
                store.addCredentials(Domain.global(), fakeCredentials);
                return;
            }
        }
        throw new IllegalStateException("Credentials store does not exist for folder: " + folder.getFullName());
    }

    @Nonnull
    private static CredentialsStore getFolderCredentialsStore(Folder folder) {
        Iterable<CredentialsStore> stores = CredentialsProvider.lookupStores(folder);
        for (CredentialsStore store : stores) {
            if (store.getProvider() instanceof FolderCredentialsProvider && store.getContext() == folder) {
                return store;
            }
        }
        throw new IllegalStateException("Credentials store does not exist for folder: " + folder.getFullName());
    }

    /**
     * Create the {@link AndroidPublisher} with a {@link TestHttpTransport}.
     *
     * @param transport The {@link TestHttpTransport} that will be used to orchestrate the remote AndroidPublisher API
     * calls.
     */
    public static AndroidPublisher createAndroidPublisher(TestHttpTransport transport) {
        MockGoogleCredential mockCredential = new MockGoogleCredential.Builder().build();
        return new AndroidPublisher.Builder(transport, mockCredential.getJsonFactory(), mockCredential)
                .setApplicationName("Jenkins-GooglePlayAndroidPublisher-tests")
                .setSuppressAllChecks(true)
                .build();
    }

    /**
     * Verify that the build console log contains the specified {@code lines}.
     *
     * @param jenkinsRule The {@code @Rule} {@link JenkinsRule}
     * @param scheduledBuild The build that was scheduled with {@link hudson.model.FreeStyleProject#scheduleBuild2}
     * @param lines The line(s) to verify on the executed job
     */
    public static void assertLogLines(
            JenkinsRule jenkinsRule,
            QueueTaskFuture<FreeStyleBuild> scheduledBuild,
            String... lines) throws Exception {
        FreeStyleBuild build = scheduledBuild.get();
        for (String line : lines) {
            jenkinsRule.assertLogContains(line, build);
        }
    }

    /**
     * Schedule and execute the project, verifying that the build finishes with the given {@code result} and the console
     * log contains the specified {@code lines}.
     *
     * @param jenkinsRule The {@code @Rule} {@link JenkinsRule}
     * @param project The project
     * @param result Which {@link Result} to expect
     * @param lines The line(s) to verify on the executed job
     */
    public static void assertResultWithLogLines(
            JenkinsRule jenkinsRule,
            FreeStyleProject project,
            Result result,
            String... lines) throws Exception {
        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        jenkinsRule.assertBuildStatus(result, future);
        assertLogLines(jenkinsRule, future, lines);
    }
}
