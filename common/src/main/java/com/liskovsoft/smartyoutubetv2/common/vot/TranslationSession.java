package com.liskovsoft.smartyoutubetv2.common.vot;

public class TranslationSession {
    public enum Decision { WAIT, READY, GIVE_UP }

    private static final long MIN_POLL_DELAY_MS = 5_000;

    private final long mMaxWaitMs;
    private long mStartMs;
    private long mNextPollDelayMs = MIN_POLL_DELAY_MS;
    private int mLastRemainingSec = -1;
    private String mAudioUrl;

    public TranslationSession(long maxWaitMs) {
        mMaxWaitMs = maxWaitMs;
    }

    public void start(long nowMs) {
        mStartMs = nowMs;
        mAudioUrl = null;
        mNextPollDelayMs = MIN_POLL_DELAY_MS;
        mLastRemainingSec = -1;
    }

    public Decision onResult(VotResult result, long nowMs) {
        if (result.isReady()) {
            mAudioUrl = result.audioUrl;
            return Decision.READY;
        }

        if (result.status == VotResult.STATUS_FAILED) {
            return Decision.GIVE_UP;
        }

        if (nowMs - mStartMs > mMaxWaitMs) {
            return Decision.GIVE_UP;
        }

        mLastRemainingSec = result.remainingSec;
        mNextPollDelayMs = Math.max(result.remainingSec * 1000L, MIN_POLL_DELAY_MS);

        return Decision.WAIT;
    }

    public long nextPollDelayMs() {
        return mNextPollDelayMs;
    }

    public int lastRemainingSec() {
        return mLastRemainingSec;
    }

    public String audioUrl() {
        return mAudioUrl;
    }
}
