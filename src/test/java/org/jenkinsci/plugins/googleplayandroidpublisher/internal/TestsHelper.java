package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.google.api.client.googleapis.testing.auth.oauth2.MockGoogleCredential;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.LocalizedText;
import com.google.api.services.androidpublisher.model.Track;
import com.google.api.services.androidpublisher.model.TrackRelease;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.queue.QueueTaskFuture;
import jenkins.model.ParameterizedJobMixIn;
import junit.framework.AssertionFailedError;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.oauth.TestCredentials;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertNotNull;

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
     * Attempts to return the body of an HTTP request that was made.
     *
     * @param urlSuffix Suffix of the URL whose body should be returned.
     * @param <T> Type of the body.
     * @return The HTTP request body as an instance of type T; throws if the request was not made.
     */
    public static <T> T getRequestBodyForUrl(TestHttpTransport transport, String urlSuffix, Class<T> cls) throws IOException {
        String json = transport.getRemoteCalls().stream()
            .filter(remoteCall -> remoteCall.url.endsWith(urlSuffix))
            .findFirst()
            .orElseThrow(() -> new AssertionFailedError("Expected call to URL: " + urlSuffix))
            .request
            .getContentAsString();
        return JacksonFactory.getDefaultInstance().createJsonParser(json).parse(cls);
    }

    public static Track track(String name, TrackRelease... releases) {
        return new Track().setTrack(name).setReleases(Arrays.asList(releases));
    }

    public static TrackRelease release(long versionCode, String... languages) {
        TrackRelease release = new TrackRelease();
        release.setVersionCodes(Collections.singletonList(versionCode));
        List<LocalizedText> releaseNotes = null;
        if (languages.length > 0) {
            releaseNotes = new ArrayList<>();
            for (String lang : languages) {
                releaseNotes.add(new LocalizedText().setLanguage(lang).setText("Notes: " + lang));
            }
        }
        release.setReleaseNotes(releaseNotes);
        return release;
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
            QueueTaskFuture<? extends Run> scheduledBuild,
            String... lines) throws Exception {
        for (String line : lines) {
            jenkinsRule.assertLogContains(line, scheduledBuild.get());
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
            ParameterizedJobMixIn.ParameterizedJob<?, ? extends Run> project,
            Result result,
            String... lines) throws Exception {
        QueueTaskFuture<? extends Run> future = project.scheduleBuild2(0);
        assertNotNull(future);
        jenkinsRule.assertBuildStatus(result, future);
        assertLogLines(jenkinsRule, future, lines);
    }
}
