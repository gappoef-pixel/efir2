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
        // Эталонная реализация протокола (yavot-py) шлёт именно "application/x-protobuf",
        // а не "application/protobuf" — сервис Яндекса на второй вариант отвечает 415.
        // Вынесено в константы, чтобы опечатку в строке ловил юнит-тест (см. VotTransportTest),
        // а не полевая проверка на приставке: сам MediaType не виден фальшивому VotTransport в тестах VotClient.
        static final String CONTENT_TYPE_PROTOBUF = "application/x-protobuf";
        static final String CONTENT_TYPE_JSON = "application/json";

        private static final MediaType PROTOBUF = MediaType.parse(CONTENT_TYPE_PROTOBUF);
        private static final MediaType JSON = MediaType.parse(CONTENT_TYPE_JSON);
        private static final String BASE_URL = "https://api.browser.yandex.ru";
        private static final Charset UTF8 = Charset.forName("UTF-8");
        private static final int ERROR_SNIPPET_MAX_LENGTH = 200;
        private final OkHttpClient mClient = new OkHttpClient();

        @Override
        public byte[] post(String path, byte[] body, Map<String, String> headers) throws IOException {
            Request.Builder builder = new Request.Builder()
                    .url(BASE_URL + path)
                    .post(RequestBody.create(PROTOBUF, body));

            addCommonHeaders(builder);

            // Заголовки от VotClient (подпись, токен, User-Agent) идут последними и имеют приоритет:
            // header() перезаписывает значение по имени, поэтому дубликатов не будет.
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
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
            Request.Builder builder = new Request.Builder()
                    .url(BASE_URL + path)
                    .put(RequestBody.create(JSON, json));

            addCommonHeaders(builder);

            Request request = builder.build();

            try (Response response = mClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException(buildErrorMessage(response.code(), readBodySafely(response)));
                }
            }
        }

        /**
         * Базовый набор заголовков эталонной реализации протокола (yavot-py), помимо Content-Type
         * (тот выставляется отдельно через {@link MediaType} в {@link RequestBody#create}) и
         * заголовков от {@code VotClient} (подпись/токен/User-Agent, добавляются вызывающим кодом отдельно).
         */
        private static void addCommonHeaders(Request.Builder builder) {
            builder.header("Accept", CONTENT_TYPE_PROTOBUF);
            builder.header("Accept-Language", "en");
            builder.header("Pragma", "no-cache");
            builder.header("Cache-Control", "no-cache");
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
