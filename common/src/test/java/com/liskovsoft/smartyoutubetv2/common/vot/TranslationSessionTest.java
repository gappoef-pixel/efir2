package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationSessionTest {
    private VotResult waiting(int remainingSec) {
        VotResult r = new VotResult();
        r.status = VotResult.STATUS_WAITING;
        r.remainingSec = remainingSec;
        return r;
    }

    private VotResult finished() {
        VotResult r = new VotResult();
        r.status = VotResult.STATUS_FINISHED;
        r.audioUrl = "https://vtrans.example/aa.mp3";
        return r;
    }

    @Test
    public void waitsUsingServerCountdown() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        assertEquals(TranslationSession.Decision.WAIT, session.onResult(waiting(29), 1_000));
        assertEquals(29_000, session.nextPollDelayMs());
        assertEquals(29, session.lastRemainingSec());
    }

    @Test
    public void clampsTooShortCountdownToFiveSeconds() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        session.onResult(waiting(1), 1_000);
        assertEquals(5_000, session.nextPollDelayMs());
    }

    @Test
    public void reportsReadyWithAudioUrl() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        assertEquals(TranslationSession.Decision.READY, session.onResult(finished(), 90_000));
        assertEquals("https://vtrans.example/aa.mp3", session.audioUrl());
    }

    @Test
    public void givesUpAfterMaxWait() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        assertEquals(TranslationSession.Decision.GIVE_UP, session.onResult(waiting(5), 600_001));
    }

    @Test
    public void givesUpOnFailedStatus() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        VotResult failed = new VotResult();
        failed.status = VotResult.STATUS_FAILED;
        assertEquals(TranslationSession.Decision.GIVE_UP, session.onResult(failed, 1_000));
    }
}
