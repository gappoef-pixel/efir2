package com.liskovsoft.smartyoutubetv2.common.vot;

public class VotResult {
    public static final int STATUS_FAILED = 0;
    public static final int STATUS_FINISHED = 1;
    public static final int STATUS_WAITING = 2;
    public static final int STATUS_LONG_WAITING = 3;
    public static final int STATUS_PART_CONTENT = 5;
    public static final int STATUS_AUDIO_REQUESTED = 6;
    public static final int STATUS_SESSION_REQUIRED = 7;

    public int status;
    public String audioUrl;
    public int remainingSec = -1;
    public String translationId;
    public String detectedLanguage;
    public String message;

    public boolean isReady() {
        return (status == STATUS_FINISHED || status == STATUS_PART_CONTENT) && audioUrl != null && !audioUrl.isEmpty();
    }
}
