package org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses;

import com.google.api.services.androidpublisher.model.Apk;
import com.google.api.services.androidpublisher.model.ApksListResponse;
import java.util.Collections;
import java.util.List;

/**
 * <pre>GET https://www.googleapis.com/androidpublisher/v3/applications/{appId}/edits/{editId}/apks</pre>
 *
 * @see com.google.api.services.androidpublisher.model.ApksListResponse Response type
 * @see com.google.api.services.androidpublisher.model.Apk Response inner type
 * @see com.google.api.services.androidpublisher.AndroidPublisher.Edits.Apks#list Request method
 */
public class FakeListApksResponse extends FakeHttpResponse<FakeListApksResponse> {
    public FakeListApksResponse setEmptyApks() {
        return setApks(Collections.emptyList());
    }

    public FakeListApksResponse setApks(List<Apk> apks) {
        return setSuccessData(new ApksListResponse()
                .setKind("androidpublisher#apksListResponse")
                .setApks(apks));
    }
}
