package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.*;

public class SyncDeciderTest {
    @Test
    public void ignoresSmallDrift() {
        assertFalse(SyncDecider.shouldResync(10_000, 10_250, 300));
        assertFalse(SyncDecider.shouldResync(10_000, 9_800, 300));
    }

    @Test
    public void resyncsOnLargeDriftBothDirections() {
        assertTrue(SyncDecider.shouldResync(10_000, 10_400, 300));
        assertTrue(SyncDecider.shouldResync(10_000, 9_500, 300));
    }
}
