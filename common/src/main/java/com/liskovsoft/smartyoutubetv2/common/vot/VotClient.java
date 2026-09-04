package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class VotClient {
    public static final String PATH_TRANSLATE = "/video-translation/translate";
    public static final String PATH_FAIL_AUDIO = "/video-translation/fail-audio-js";
    public static final String PATH_AUDIO = "/video-translation/audio";
    public static final String FILE_ID_FAILED_AUDIO = "web_api_get_all_generating_urls_data_from_iframe";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/147.0.0.0 YaBrowser/" + VotCrypto.COMPONENT_VERSION + " Yowser/2.5 Safari/537.36";

    private final VotTransport mTransport;
    private final String mSessionUuid = VotCrypto.randomUuid();

    public VotClient(VotTransport transport) {
        mTransport = transport;
    }

    public static String videoUrl(String videoId) {
        return "https://youtu.be/" + videoId;
    }

    public VotResult translate(String videoId, double durationSec, String srcLang, String dstLang) throws IOException {
        VotResult result = requestTranslation(videoId, durationSec, srcLang, dstLang);

        if (result.status == VotResult.STATUS_AUDIO_REQUESTED) {
            // Ролик, которого Яндекс раньше не видел: дозаявляем его ровно один раз за попытку.
            // Повторный статус 6 после этого шага ниже уже не перехватывается и уходит как есть.
            claimVideo(videoId, result.translationId);
            result = requestTranslation(videoId, durationSec, srcLang, dstLang);
        }

        if (result.status == VotResult.STATUS_FAILED && result.detectedLanguage != null
                && !result.detectedLanguage.isEmpty() && !result.detectedLanguage.equals(srcLang)) {
            // Яндекс сам распознал язык и вернул его — повторяем ровно один раз
            result = requestTranslation(videoId, durationSec, result.detectedLanguage, dstLang);
        }

        return result;
    }

    private VotResult requestTranslation(String videoId, double durationSec, String srcLang, String dstLang) throws IOException {
        byte[] body = VotProto.encodeTranslateRequest(videoUrl(videoId), durationSec, srcLang, dstLang);

        return VotProto.decodeTranslateResponse(mTransport.post(PATH_TRANSLATE, body, headers(body, PATH_TRANSLATE)));
    }

    private void claimVideo(String videoId, String translationId) throws IOException {
        String url = videoUrl(videoId);
        mTransport.putJson(PATH_FAIL_AUDIO, "{\"video_url\":\"" + url + "\"}");

        byte[] body = VotProto.encodeAudioRequest(translationId, url, FILE_ID_FAILED_AUDIO);
        mTransport.post(PATH_AUDIO, body, headers(body, PATH_AUDIO));
    }

    private Map<String, String> headers(byte[] body, String path) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Vtrans-Signature", VotCrypto.sign(body));
        headers.put("Sec-Vtrans-Sk", "");
        headers.put("Sec-Vtrans-Token", VotCrypto.signToken(mSessionUuid, path) + ":" +
                mSessionUuid + ":" + path + ":" + VotCrypto.COMPONENT_VERSION);
        headers.put("User-Agent", USER_AGENT);
        return headers;
    }
}
