package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VotProtoEncodeTest {
    @Test
    public void encodesReferenceRequestByteForByte() {
        byte[] body = VotProto.encodeTranslateRequest("https://youtu.be/dQw4w9WgXcQ", 213d, "en", "ru");
        assertEquals(
            "1a1c68747470733a2f2f796f7574752e62652f64517734773957675863512801310000000000a06a4038014202656e720272757801800102",
            VotHex.encode(body));
        assertEquals(56, body.length);
    }
}
