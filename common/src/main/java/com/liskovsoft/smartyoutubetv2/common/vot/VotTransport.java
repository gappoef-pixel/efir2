package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public interface VotTransport {
    byte[] post(String path, byte[] body, Map<String, String> headers) throws IOException;

    class OkHttpVotTransport implements VotTransport {
        private static final MediaType PROTOBUF = MediaType.parse("application/protobuf");
        private static final String BASE_URL = "https://api.browser.yandex.ru";
        private final OkHttpClient mClient = new OkHttpClient();

        @Override
        public byte[] post(String path, byte[] body, Map<String, String> headers) throws IOException {
            Request.Builder builder = new Request.Builder()
                    .url(BASE_URL + path)
                    .post(RequestBody.create(PROTOBUF, body));

            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }

            try (Response response = mClient.newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("VOT request failed: " + response.code());
                }
                return response.body().bytes();
            }
        }
    }
}
