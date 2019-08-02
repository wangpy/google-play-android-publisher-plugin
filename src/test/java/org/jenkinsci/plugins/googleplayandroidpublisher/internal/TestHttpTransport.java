package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeHttpResponse;

public class TestHttpTransport extends MockHttpTransport implements Serializable {
    private static final boolean DEBUG = TestUtilImpl.DEBUG;

    public final Map<String, FakeHttpResponse> responses = new HashMap<>();
    private List<RemoteCall> remoteCalls = new ArrayList<>();

    @Override
    public LowLevelHttpRequest buildRequest(String method, String url) {
        if (DEBUG) System.out.println("Building request: " + method + " " + url + " on " + this);

        // Iterate through the configured responses, until we find a matching URL
        FakeHttpResponse response = null;
        for (Map.Entry<String, FakeHttpResponse> mockedEntry : responses.entrySet()) {
            if (url.endsWith(mockedEntry.getKey())) {
                response = mockedEntry.getValue();
            }
        }

        if (response == null) {
            throw new RuntimeException("Could not find a mocked response for " + method + " to " + url);
        }

        MockLowLevelHttpRequest request = new FakeHttpRequest(response);
        remoteCalls.add(new RemoteCall(method, url, request, response));
        return request;
    }

    /**
     * Register a {@code response} to handle a request to the {@code url}.
     *
     * @param url A substring that should match the <b>end</b> of the remote URL endpoint
     * @param response The {@link FakeHttpResponse} that will be returned
     * @return {@code this} to enable method call chaining.
     */
    public TestHttpTransport withResponse(String url, FakeHttpResponse response) {
        if (DEBUG) {
            System.out.println("Adding response: " + url + " => " + response);
        }
        responses.put(url, response);
        return this;
    }

    public List<RemoteCall> getRemoteCalls() {
        return remoteCalls;
    }

    public void dumpRequests() {
        if (DEBUG) {
            System.out.println("Attempted requests:");

            for (RemoteCall request : remoteCalls) {
                System.out.println(" - " + request);
            }
        }
    }

    public static class RemoteCall implements Serializable {
        public final String method;
        public final String url;
        public final MockLowLevelHttpRequest request;
        private final FakeHttpResponse response;

        RemoteCall(String method, String url, MockLowLevelHttpRequest request, FakeHttpResponse response) {
            this.method = method;
            this.url = url;
            this.request = request;
            this.response = response;
        }

        @Nullable
        public FakeHttpResponse getResponse() {
            return response;
        }

        @Override
        public String toString() {
            return method + " " + url;
        }
    }

    static class FakeHttpRequest extends MockLowLevelHttpRequest implements Serializable {
        final FakeHttpResponse response;

        FakeHttpRequest(FakeHttpResponse response) {
            this.response = response;
        }

        @Override
        public LowLevelHttpResponse execute() {
            return this.response;
        }
    }
}
