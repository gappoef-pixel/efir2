package com.liskovsoft.smartyoutubetv2.common.vot;

import java.nio.charset.Charset;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class VotCrypto {
    public static final String COMPONENT_VERSION = "26.4.1.1026";
    private static final String HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private VotCrypto() {}

    public static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(UTF8), "HmacSHA256"));
            return VotHex.encode(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public static String signToken(String sessionUuid, String path) {
        return sign((sessionUuid + ":" + path + ":" + COMPONENT_VERSION).getBytes(UTF8));
    }

    public static String randomUuid() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(HEX_UPPER[RANDOM.nextInt(16)]);
        }
        return sb.toString();
    }
}
