package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationRestartLimiterTest {
    @Test
    public void allowsUpToConfiguredRestarts() {
        TranslationRestartLimiter limiter = new TranslationRestartLimiter(3);
        assertTrue(limiter.tryRestart());
        assertTrue(limiter.tryRestart());
        assertTrue(limiter.tryRestart());
        assertFalse(limiter.tryRestart());
    }

    @Test
    public void resetAllowsRestartsAgain() {
        TranslationRestartLimiter limiter = new TranslationRestartLimiter(1);
        assertTrue(limiter.tryRestart());
        assertFalse(limiter.tryRestart());

        limiter.reset();

        assertTrue(limiter.tryRestart());
    }

    @Test
    public void zeroMaxNeverAllowsRestart() {
        TranslationRestartLimiter limiter = new TranslationRestartLimiter(0);
        assertFalse(limiter.tryRestart());
    }
}
