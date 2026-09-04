package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VotCryptoTest {
    @Test
    public void signMatchesReferenceVector() {
        byte[] body = VotHex.decode(
            "1a1c68747470733a2f2f796f7574752e62652f64517734773957675863512801310000000000a06a4038014202656e720272757801800102");
        assertEquals("824fba5fa60152b6d41c83aef1825deee0986c9317c852a0afafc4446b1feb54", VotCrypto.sign(body));
    }

    @Test
    public void signTokenMatchesReferenceVector() {
        assertEquals("b22606e3cf71ee0d8949a2202419efaace870a3d2e4ed27c8f77ba5296994d4b",
            VotCrypto.signToken("0123456789ABCDEF0123456789ABCDEF", "/video-translation/translate"));
    }

    @Test
    public void randomUuidHas32HexChars() {
        assertEquals(32, VotCrypto.randomUuid().length());
        assertEquals(true, VotCrypto.randomUuid().matches("[0-9A-F]{32}"));
    }
}
