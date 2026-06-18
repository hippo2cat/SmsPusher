package com.hippo2cat.smspusher.net;

import java.io.IOException;
import java.util.Map;

public interface HttpTransport {
    Response get(String baseUrl, String path, Map<String, String> headers) throws IOException;

    Response post(String baseUrl, String path, Map<String, String> headers, String body) throws IOException;

    final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
