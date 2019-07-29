package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeHttpResponse;

public class TestHttpTransport extends MockHttpTransport {
    private static final boolean DEBUG = TestUtilImpl.DEBUG;

    private Map<String, FakeHttpResponse> responses = new HashMap<>();
    private List<RemoteCall> remoteCalls = new ArrayList<>();

    @Override
    public LowLevelHttpRequest buildRequest(String method, String url) {
        // when the SDK makes a request, keep track of it so that we can make assertions on the requests later.
        final AtomicReference<FakeHttpResponse> futureResponse = new AtomicReference<>();
        MockLowLevelHttpRequest request = new MockLowLevelHttpRequest() {
            @Override
            public LowLevelHttpResponse execute() {
                // Iterate through the configured responses, until we find a matching URL
                for (Map.Entry<String, FakeHttpResponse> mockedEntry : responses.entrySet()) {
                    if (url.endsWith(mockedEntry.getKey())) {
                        FakeHttpResponse fakeResponse = mockedEntry.getValue();
                        futureResponse.set(fakeResponse);
                        return fakeResponse;
                    }
                }

                throw new RuntimeException("Could not find a mocked response for " + method + " to " + url);
            }
        };

        remoteCalls.add(new RemoteCall(method, url, request, futureResponse));

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

    public static class RemoteCall {
        public final String method;
        public final String url;
        public final MockLowLevelHttpRequest request;
        private final AtomicReference<FakeHttpResponse> response;

        RemoteCall(String method, String url, MockLowLevelHttpRequest request, AtomicReference<FakeHttpResponse> response) {
            this.method = method;
            this.url = url;
            this.request = request;
            this.response = response;
        }

        @Nullable
        public FakeHttpResponse getResponse() {
            return response.get();
        }

        @Override
        public String toString() {
            return method + " " + url;
        }
    }
}
