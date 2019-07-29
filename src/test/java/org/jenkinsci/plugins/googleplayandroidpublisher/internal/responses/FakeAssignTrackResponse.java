package org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses;


import com.google.api.services.androidpublisher.model.Track;
import com.google.api.services.androidpublisher.model.TrackRelease;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <pre>https://www.googleapis.com/androidpublisher/v3/applications/{appId}/edits/{editId}/tracks/production</pre>
 *
 * @see com.google.api.services.androidpublisher.model.Track Response type
 * @see com.google.api.services.androidpublisher.AndroidPublisher.Edits.Tracks#update Request method
 */
public class FakeAssignTrackResponse extends FakeHttpResponse<FakeAssignTrackResponse> {
    public FakeAssignTrackResponse success(String trackName) {
        return success(trackName, Collections.emptyList());
    }

    public FakeAssignTrackResponse success(String trackName, long versionCode) {
        TrackRelease release = new TrackRelease().setVersionCodes(Collections.singletonList(versionCode));
        return success(trackName, Collections.singletonList(release));
    }

    public FakeAssignTrackResponse success(String trackName, List<TrackRelease> releases) {
        return setSuccessData(new Track().setTrack(trackName).setReleases(releases));
    }
}
