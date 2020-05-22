package org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses;

import com.google.api.services.androidpublisher.model.Track;
import com.google.api.services.androidpublisher.model.TracksListResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * <pre>GET https://www.googleapis.com/androidpublisher/v3/applications/{appId}/edits/{editId}/tracks</pre>
 *
 * @see TracksListResponse Response type
 * @see Track Response inner type
 * @see com.google.api.services.androidpublisher.AndroidPublisher.Edits.Tracks#list Request method
 */
public class FakeListTracksResponse extends FakeHttpResponse<FakeListTracksResponse> {
    public FakeListTracksResponse setTracks(List<Track> tracks) {
        List<Track> uniqueTracks = new ArrayList<>(new LinkedHashSet<>(tracks));
        return setSuccessData(new TracksListResponse()
                .setKind("androidpublisher#tracksListResponse")
                .setTracks(uniqueTracks));
    }
}
