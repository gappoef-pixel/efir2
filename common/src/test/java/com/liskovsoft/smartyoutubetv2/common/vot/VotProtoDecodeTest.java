package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.*;

public class VotProtoDecodeTest {
    @Test
    public void decodesFinishedResponse() {
        VotResult r = VotProto.decodeTranslateResponse(VotHex.decode(
            "0a3868747470733a2f2f767472616e732e73332d707269766174652e6d64732e79616e6465782e6e65742f7474732f70726f642f61612e6d7033110000000000a06a4020013a057469643432"));
        assertEquals(VotResult.STATUS_FINISHED, r.status);
        assertEquals("https://vtrans.s3-private.mds.yandex.net/tts/prod/aa.mp3", r.audioUrl);
        assertEquals("tid42", r.translationId);
        assertTrue(r.isReady());
    }

    @Test
    public void decodesWaitingResponse() {
        VotResult r = VotProto.decodeTranslateResponse(VotHex.decode("2002281d3a057469643432"));
        assertEquals(VotResult.STATUS_WAITING, r.status);
        assertEquals(29, r.remainingSec);
        assertNull(r.audioUrl);
        assertFalse(r.isReady());
    }

    @Test
    public void skipsUnknownFields() {
        // поле 99 (varint) перед статусом не должно ломать разбор
        VotResult r = VotProto.decodeTranslateResponse(VotHex.decode("d80607" + "2002281d"));
        assertEquals(VotResult.STATUS_WAITING, r.status);
        assertEquals(29, r.remainingSec);
    }
}
