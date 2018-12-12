package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.services.androidpublisher.model.Track;
import com.google.api.services.androidpublisher.model.TrackRelease;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import static hudson.Util.join;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.PERCENTAGE_FORMATTER;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.ALPHA;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.BETA;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.PRODUCTION;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.ROLLOUT;

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

    protected abstract int getNewestVersionCodeAllowed(Collection<Integer> versionCodes);

    protected abstract boolean shouldReducingRolloutPercentageCauseFailure();

    /**
     * Assigns a list of APKs with the given version codes to a release track.
     *
     * @param versionCodes One or more version codes to assign.
     * @param track The track to which the APKs should be assigned.
     * @param rolloutFraction The rollout fraction, if track is a staged rollout.
     */
    protected void assignApksToTrack(Collection<Integer> versionCodes, ReleaseTrack track,
            double rolloutFraction, TrackRelease release) throws IOException, UploadException {
        // Determine which version codes should be unassigned
        final int newestVersionCodeAllowed = getNewestVersionCodeAllowed(versionCodes);

        // Prepare to assign the APK(s) to the desired track
        final Track trackToAssign = new Track()
                .setTrack(track.getApiValue())
                .setReleases(Collections.singletonList(release));

        if (track == PRODUCTION) {
            // Remove older APKs from the beta track
            unassignOlderApks(BETA, newestVersionCodeAllowed);

            // If there's an existing rollout, we need to clear it out so a new production/rollout APK can be added
            final Track rolloutTrack = fetchTrack(ROLLOUT);
            double currentFraction = rolloutFraction;

            if (rolloutTrack != null && rolloutTrack.getReleases() != null && !rolloutTrack.getReleases().isEmpty()) {
                currentFraction = rolloutTrack.getReleases().get(0).getUserFraction();
                logger.println(String.format("Removing existing staged rollout APK(s): %s",
                        join(rolloutTrack.getReleases().get(0).getVersionCodes(), ", ")));
                rolloutTrack.setReleases(Collections.<TrackRelease>emptyList());
                editService.tracks().update(applicationId, editId, rolloutTrack.getTrack(), rolloutTrack).execute();
            }

            // Check whether we want a new staged rollout
            if (rolloutFraction < 1) {
                // Override the track name
                trackToAssign.setTrack(ROLLOUT.getApiValue());

                // Check whether we also need to override the desired rollout percentage
                if (currentFraction > rolloutFraction) {
                    if (shouldReducingRolloutPercentageCauseFailure()) {
                        throw new UploadException(String.format("Staged rollout percentage cannot be reduced from " +
                                "%s%% to the configured %s%%",
                                PERCENTAGE_FORMATTER.format(currentFraction * 100),
                                PERCENTAGE_FORMATTER.format(rolloutFraction * 100)));
                    }
                    logger.println(String.format("Staged rollout percentage will remain at %s%% rather than the " +
                                    "configured %s%% because there were APK(s) already in a staged rollout, and " +
                                    "Google Play makes it impossible to reduce the rollout percentage in this case",
                            PERCENTAGE_FORMATTER.format(currentFraction * 100),
                            PERCENTAGE_FORMATTER.format(rolloutFraction * 100)));
                    rolloutFraction = currentFraction;

                    for (TrackRelease releaseToAssign : trackToAssign.getReleases()) {
                        releaseToAssign.setUserFraction(currentFraction);
                    }
                }
            }
        } else if (rolloutFraction < 1) {
            logger.println("Ignoring staged rollout percentage as it only applies to production releases");
        }

        // Updates to the INTERNAL track does not require removing lower version code APKs from the
        // Alpha/Beta track.
        if (track == BETA || track == ALPHA) {
            // Remove older APKs from the alpha track
            unassignOlderApks(ALPHA, newestVersionCodeAllowed);
        }

        // Assign the new APK(s) to the desired track
        if (trackToAssign.getTrack().equals(ROLLOUT.getApiValue())) {
            logger.println(String.format("Assigning APK(s) to be rolled out to %s%% of production users...",
                            PERCENTAGE_FORMATTER.format(rolloutFraction * 100)));
        } else {
            logger.println(String.format("Assigning APK(s) to %s release track...", track));
        }
        Track updatedTrack =
                editService.tracks().update(applicationId, editId, trackToAssign.getTrack(), trackToAssign).execute();
        logger.println(String.format("The %s release track will now contain APK(s) with version code(s): %s%n", track,
                join(updatedTrack.getReleases().get(0).getVersionCodes(), ", ")));
    }

    /** @return The desired track fetched from the API, or {@code null} if the track has no APKs assigned. */
    private Track fetchTrack(ReleaseTrack track) throws IOException {
        final List<Track> existingTracks = editService.tracks().list(applicationId, editId).execute().getTracks();
        for (Track t : existingTracks) {
            if (t.getTrack().equals(track.getApiValue())) {
                return t;
            }
        }
        return null;
    }

    /**
     * Removes old version codes from the given track on the server, if it exists.
     *
     * @param track The track whose assigned versions should be changed.
     * @param maxVersionCode The maximum allowed version code; all lower than this will be removed from the track.
     */
    private void unassignOlderApks(ReleaseTrack track, int maxVersionCode) throws IOException {
        final Track trackToAssign = fetchTrack(track);
        if (trackToAssign == null || trackToAssign.getReleases() == null || trackToAssign.getReleases().isEmpty()) {
            return;
        }

        for (TrackRelease release : trackToAssign.getReleases()) {
            List<Long> versionCodes = new ArrayList<>(release.getVersionCodes());
            for (Iterator<Long> it = versionCodes.iterator(); it.hasNext(); ) {
                if (it.next() < maxVersionCode) {
                    it.remove();
                }
            }
            release.setVersionCodes(versionCodes);
        }

        editService.tracks().update(applicationId, editId, trackToAssign.getTrack(), trackToAssign).execute();
    }

}
