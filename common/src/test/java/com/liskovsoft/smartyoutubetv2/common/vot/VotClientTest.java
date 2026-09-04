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
}
