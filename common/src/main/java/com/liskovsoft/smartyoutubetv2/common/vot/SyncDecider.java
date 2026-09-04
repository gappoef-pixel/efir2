package com.liskovsoft.smartyoutubetv2.common.vot;

public final class SyncDecider {
    private SyncDecider() {}

    public static boolean shouldResync(long mainPosMs, long votPosMs, long thresholdMs) {
        return Math.abs(mainPosMs - votPosMs) > thresholdMs;
    }
}
