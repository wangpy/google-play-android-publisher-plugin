package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.googleplayandroidpublisher.internal.responses.FakeHttpResponse;

public class TestHttpTransport extends MockHttpTransport {
    public static final boolean DEBUG = false;

    private Map<String, FakeHttpResponse> responses = new HashMap<>();
    private List<Request> requests = new ArrayList<>();

    @Override
    public LowLevelHttpRequest buildRequest(String method, String url) {
        // when the SDK makes a request, keep track of it so that we can make
        requests.add(new Request(method, url));

        return new MockLowLevelHttpRequest() {
            @Override
            public LowLevelHttpResponse execute() {
                // Iterate through the configured responses, until we find a matching URL
                for (Map.Entry<String, FakeHttpResponse> mockedEntry : responses.entrySet()) {
                    if (url.endsWith(mockedEntry.getKey())) {
                        return mockedEntry.getValue();
                    }
                }

                throw new RuntimeException("Could not find a mocked response for " + method + " to " + url);
            }
        };
    }

    public TestHttpTransport withResponse(String url, FakeHttpResponse response) {
        responses.put(url, response);
        return this;
    }

    public List<Request> getRequests() {
        return requests;
    }

    public void dumpRequests() {
        if (DEBUG) {
            System.out.println("Requests:");

            for (Request request : requests) {
                System.out.println(" - " + request);
            }
        }
    }

    public static class Request {
        public final String method;
        public final String url;

        Request(String method, String url) {
            this.method = method;
            this.url = url;
        }

        @Override
        public String toString() {
            return method + " " + url;
        }
    }
}
