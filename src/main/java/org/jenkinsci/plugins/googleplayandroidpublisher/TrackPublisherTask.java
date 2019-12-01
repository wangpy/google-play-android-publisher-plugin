package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.services.androidpublisher.model.Track;
import com.google.api.services.androidpublisher.model.TrackRelease;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Collections;
import static hudson.Util.join;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.PERCENTAGE_FORMATTER;

abstract class TrackPublisherTask<V> extends AbstractPublisherTask<V> {

    protected final String applicationId;
    protected final ReleaseTrack track;
    protected final double rolloutFraction;

    TrackPublisherTask(TaskListener listener, GoogleRobotCredentials credentials, String applicationId,
                       ReleaseTrack track, double rolloutPercentage) {
        super(listener, credentials);
        this.applicationId = applicationId;
        this.track = track;
        this.rolloutFraction = rolloutPercentage / 100d;
    }

    /**
     * Assigns a release, which contains a list of version codes, to a release track.
     *
     * @param track The track to which the version codes should be assigned.
     * @param rolloutFraction The rollout fraction, if track is a staged rollout.
     */
    void assignAppFilesToTrack(ReleaseTrack track, double rolloutFraction, TrackRelease release) throws IOException {
        // Prepare to assign the release to the desired track
        final Track trackToAssign = new Track()
                .setTrack(track.getApiValue())
                .setReleases(Collections.singletonList(release));

        // Assign the new file(s) to the desired track
        logger.println(String.format("Setting rollout to target %s%% of %s track users",
                        PERCENTAGE_FORMATTER.format(rolloutFraction * 100), track));
        Track updatedTrack =
                editService.tracks().update(applicationId, editId, trackToAssign.getTrack(), trackToAssign).execute();
        logger.println(String.format("The %s release track will now contain the version code(s): %s%n", track,
                join(updatedTrack.getReleases().get(0).getVersionCodes(), ", ")));
    }

}
