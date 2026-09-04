package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class VotClient {
    public static final String PATH_TRANSLATE = "/video-translation/translate";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/147.0.0.0 YaBrowser/26.4.1.1026 Yowser/2.5 Safari/537.36";

    private final VotTransport mTransport;
    private final String mSessionUuid = VotCrypto.randomUuid();

    public VotClient(VotTransport transport) {
        mTransport = transport;
    }

    public static String videoUrl(String videoId) {
        return "https://youtu.be/" + videoId;
    }

    public VotResult translate(String videoId, double durationSec, String srcLang, String dstLang) throws IOException {
        byte[] body = VotProto.encodeTranslateRequest(videoUrl(videoId), durationSec, srcLang, dstLang);

        Map<String, String> headers = new HashMap<>();
        headers.put("Vtrans-Signature", VotCrypto.sign(body));
        headers.put("Sec-Vtrans-Sk", "");
        headers.put("Sec-Vtrans-Token", VotCrypto.signToken(mSessionUuid, PATH_TRANSLATE) + ":" +
                mSessionUuid + ":" + PATH_TRANSLATE + ":" + VotCrypto.COMPONENT_VERSION);
        headers.put("User-Agent", USER_AGENT);

        return VotProto.decodeTranslateResponse(mTransport.post(PATH_TRANSLATE, body, headers));
    }
}
