package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.services.androidpublisher.model.Apk;
import com.google.api.services.androidpublisher.model.Bundle;
import com.google.api.services.androidpublisher.model.TrackRelease;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.model.TaskListener;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static hudson.Util.join;

class TrackAssignmentTask extends TrackPublisherTask<Boolean> {

    private final List<Long> versionCodes;

    TrackAssignmentTask(TaskListener listener, GoogleRobotCredentials credentials, String applicationId,
                        Collection<Long> versionCodes, ReleaseTrack track, double rolloutPercentage) {
        super(listener, credentials, applicationId, track, rolloutPercentage);
        this.versionCodes = new ArrayList<>(versionCodes);
    }

    protected Boolean execute() throws IOException {
        // Open an edit via the Google Play API, thereby ensuring that our credentials etc. are working
        logger.println(String.format("Authenticating to Google Play API...%n- Credential:     %s%n- Application ID: %s",
                getCredentialName(), applicationId));
        createEdit(applicationId);

        // Log some useful information
        logger.println(String.format("Assigning %d version(s) with application ID %s to %s release track",
                versionCodes.size(), applicationId, track));

        // Check that all version codes to assign actually exist already on the server
        // (We could remove this block since Google Play does this check nowadays, but its error messages are
        //  slightly misleading, as they always refer to APK files, even if we're trying to assign AAB files)
        ArrayList<Long> missingVersionCodes = new ArrayList<>(versionCodes);
        List<Apk> existingApks = editService.apks().list(applicationId, editId).execute().getApks();
        if (existingApks == null) existingApks = Collections.emptyList();
        for (Apk apk : existingApks) {
            missingVersionCodes.remove(Long.valueOf(apk.getVersionCode()));
        }
        List<Bundle> existingBundles = editService.bundles().list(applicationId, editId).execute().getBundles();
        if (existingBundles == null) existingBundles = Collections.emptyList();
        for (Bundle bundle : existingBundles) {
            missingVersionCodes.remove(Long.valueOf(bundle.getVersionCode()));
        }
        if (!missingVersionCodes.isEmpty()) {
            logger.println(String.format("Assignment will fail, as these versions do not exist on Google Play: %s",
                    join(missingVersionCodes, ", ")));
            return false;
        }

        // Assign the version codes to the configured track
        TrackRelease release = Util.buildRelease(versionCodes, rolloutFraction, null);
        assignAppFilesToTrack(track, rolloutFraction, release);

        // Commit the changes
        try {
            logger.println("Applying changes to Google Play...");
            editService.commit(applicationId, editId).execute();
        } catch (SocketTimeoutException e) {
            // TODO: Check, in a new session, whether the given version codes are now in the desired track
            logger.println(String.format("- An error occurred while applying changes: %s", e));
            return false;
        }

        // If committing didn't throw an exception, everything worked fine
        logger.println("Changes were successfully applied to Google Play");
        return true;
    }

}
