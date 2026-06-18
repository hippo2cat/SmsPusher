package com.hippo2cat.smspusher.net;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Queue;

final class FakeTransport implements HttpTransport {
    static final class Request {
        final String method;
        final String baseUrl;
        final String path;
        final Map<String, String> headers;
        final String body;

        Request(String method, String baseUrl, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.baseUrl = baseUrl;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    final ArrayList<Request> requests = new ArrayList<>();
    private final Queue<Response> responses = new ArrayDeque<>();

    void enqueue(int status, String body) {
        responses.add(new Response(status, body));
    }

    @Override
    public Response post(String baseUrl, String path, Map<String, String> headers, String body) throws IOException {
        requests.add(new Request("POST", baseUrl, path, headers, body));
        Response response = responses.poll();
        if (response == null) throw new IOException("No fake response queued");
        return response;
    }

    @Override
    public Response get(String baseUrl, String path, Map<String, String> headers) throws IOException {
        requests.add(new Request("GET", baseUrl, path, headers, ""));
        Response response = responses.poll();
        if (response == null) throw new IOException("No fake response queued");
        return response;
    }
}
