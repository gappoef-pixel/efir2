package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class VotClientTest {
    private static class FakeTransport implements VotTransport {
        String lastPath;
        byte[] lastBody;
        Map<String, String> lastHeaders;
        byte[] response = VotHex.decode("2002281d3a057469643432");

        @Override
        public byte[] post(String path, byte[] body, Map<String, String> headers) {
            lastPath = path;
            lastBody = body;
            lastHeaders = new HashMap<>(headers);
            return response;
        }

        @Override
        public void putJson(String path, String json) {
            // не используется в этом тесте
        }
    }

    private static class ScriptedTransport implements VotTransport {
        final java.util.List<byte[]> responses = new java.util.ArrayList<>();
        final java.util.List<String> calls = new java.util.ArrayList<>();
        final java.util.List<byte[]> bodies = new java.util.ArrayList<>();

        @Override
        public byte[] post(String path, byte[] body, Map<String, String> headers) {
            calls.add("POST " + path);
            bodies.add(body);
            return responses.remove(0);
        }

        @Override
        public void putJson(String path, String json) {
            calls.add("PUT " + path + " " + json);
        }
    }

    @Test
    public void buildsYoutuBeUrl() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", VotClient.videoUrl("dQw4w9WgXcQ"));
    }

    @Test
    public void sendsSignedRequestAndParsesResponse() throws Exception {
        FakeTransport transport = new FakeTransport();
        VotResult result = new VotClient(transport).translate("dQw4w9WgXcQ", 213d, "en", "ru");

        assertEquals("/video-translation/translate", transport.lastPath);
        assertEquals(
            "1a1c68747470733a2f2f796f7574752e62652f64517734773957675863512801310000000000a06a4038014202656e720272757801800102",
            VotHex.encode(transport.lastBody));
        assertEquals(VotCrypto.sign(transport.lastBody), transport.lastHeaders.get("Vtrans-Signature"));
        assertNotNull(transport.lastHeaders.get("Sec-Vtrans-Token"));
        assertEquals(VotResult.STATUS_WAITING, result.status);
        assertEquals(29, result.remainingSec);
    }

    @Test
    public void claimsNewVideoOnAudioRequestedStatus() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        // 1-й ответ: status 6 + translationId, 2-й: пустой ответ на /audio, 3-й: ожидание
        transport.responses.add(VotHex.decode("2006" + "3a057469643432"));
        transport.responses.add(new byte[0]);
        transport.responses.add(VotHex.decode("2002281d3a057469643432"));

        VotResult result = new VotClient(transport).translate("dQw4w9WgXcQ", 213d, "en", "ru");

        assertEquals("PUT /video-translation/fail-audio-js {\"video_url\":\"https://youtu.be/dQw4w9WgXcQ\"}",
                transport.calls.get(1));
        assertEquals("POST /video-translation/audio", transport.calls.get(2));
        assertEquals(
            "0a057469643432121c68747470733a2f2f796f7574752e62652f645177347739576758635132320a307765625f6170695f6765745f616c6c5f67656e65726174696e675f75726c735f646174615f66726f6d5f696672616d65",
            VotHex.encode(transport.bodies.get(1)));
        assertEquals(VotResult.STATUS_WAITING, result.status);
    }

    @Test
    public void retriesOnceWithDetectedLanguage() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        // 1-й ответ: отказ с подсказкой языка "de", 2-й: ожидание
        transport.responses.add(VotHex.decode("2000" + "42026465"));
        transport.responses.add(VotHex.decode("2002281d"));

        VotResult result = new VotClient(transport).translate("dQw4w9WgXcQ", 213d, "en", "ru");

        // второй запрос ушёл уже с языком de
        assertEquals("4202" + "6465", VotHex.encode(transport.bodies.get(1)).substring(
                VotHex.encode(transport.bodies.get(1)).indexOf("4202"),
                VotHex.encode(transport.bodies.get(1)).indexOf("4202") + 8));
        assertEquals(VotResult.STATUS_WAITING, result.status);
    }
}
