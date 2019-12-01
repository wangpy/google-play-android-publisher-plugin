package org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses;

import com.google.api.services.androidpublisher.model.Bundle;
import com.google.api.services.androidpublisher.model.BundlesListResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <pre>GET https://www.googleapis.com/androidpublisher/v3/applications/{appId}/edits/{editId}/bundles</pre>
 *
 * @see BundlesListResponse Response type
 * @see Bundle Response inner type
 * @see com.google.api.services.androidpublisher.AndroidPublisher.Edits.Bundles#list Request method
 */
public class FakeListBundlesResponse extends FakeHttpResponse<FakeListBundlesResponse> {
    public FakeListBundlesResponse setEmptyBundles() {
        return setBundles(Collections.emptyList());
    }

    public FakeListBundlesResponse setBundles(int... versionCodes) {
        List<Bundle> bundles = Arrays.stream(versionCodes)
                .mapToObj(value -> new Bundle().setVersionCode(value))
                .collect(Collectors.toList());
        return setBundles(bundles);
    }

    public FakeListBundlesResponse setBundles(List<Bundle> bundles) {
        return setSuccessData(new BundlesListResponse()
                .setKind("androidpublisher#bundlesListResponse")
                .setBundles(bundles));
    }
}
