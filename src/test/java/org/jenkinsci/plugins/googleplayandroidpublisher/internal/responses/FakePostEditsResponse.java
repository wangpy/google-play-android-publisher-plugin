package org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses;

import com.google.api.services.androidpublisher.model.AppEdit;

/**
 * <pre>POST https://www.googleapis.com/androidpublisher/v3/applications/{appId}/edits</pre>
 *
 * @see com.google.api.services.androidpublisher.model.AppEdit Response type
 * @see com.google.api.services.androidpublisher.AndroidPublisher.Edits#insert Request method
 */
public class FakePostEditsResponse extends FakeHttpResponse<FakePostEditsResponse> {
    public FakePostEditsResponse setEditId(String id) {
        return setSuccessData(new AppEdit().setId(id));
    }
}
