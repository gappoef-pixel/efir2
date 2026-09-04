package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public interface VotTransport {
    byte[] post(String path, byte[] body, Map<String, String> headers) throws IOException;

    void putJson(String path, String json) throws IOException;

    class OkHttpVotTransport implements VotTransport {
        private static final MediaType PROTOBUF = MediaType.parse("application/protobuf");
        private static final MediaType JSON = MediaType.parse("application/json");
        private static final String BASE_URL = "https://api.browser.yandex.ru";
        private static final Charset UTF8 = Charset.forName("UTF-8");
        private static final int ERROR_SNIPPET_MAX_LENGTH = 200;
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
                    throw new IOException(buildErrorMessage(response.code(), readBodySafely(response)));
                }
                return response.body().bytes();
            }
        }

        @Override
        public void putJson(String path, String json) throws IOException {
            Request request = new Request.Builder()
                    .url(BASE_URL + path)
                    .put(RequestBody.create(JSON, json))
                    .build();

            try (Response response = mClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException(buildErrorMessage(response.code(), readBodySafely(response)));
                }
            }
        }

        private static byte[] readBodySafely(Response response) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return null;
            }
            try {
                return responseBody.bytes();
            } catch (IOException e) {
                return null;
            }
        }

        /** Package-private для unit-тестов: строит текст ошибки без обращения к сети. */
        static String buildErrorMessage(int code, byte[] body) {
            StringBuilder message = new StringBuilder("VOT request failed: ").append(code);
            if (body != null && body.length > 0) {
                String text = new String(body, UTF8);
                String snippet = text.length() > ERROR_SNIPPET_MAX_LENGTH
                        ? text.substring(0, ERROR_SNIPPET_MAX_LENGTH)
                        : text;
                message.append(": ").append(snippet);
            }
            return message.toString();
        }
    }
}
