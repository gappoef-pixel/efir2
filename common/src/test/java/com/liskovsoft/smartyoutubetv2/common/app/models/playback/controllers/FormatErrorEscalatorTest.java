package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.FormatErrorEscalator.Action;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FormatErrorEscalatorTest {
    @Test
    public void firstFailureSwitchesClient() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();

        assertEquals(Action.SWITCH_CLIENT, escalator.nextAction("abc"));
    }

    @Test
    public void secondFailureInvalidatesCache() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();
        escalator.nextAction("abc");

        assertEquals(Action.INVALIDATE_CACHE, escalator.nextAction("abc"));
    }

    @Test
    public void thirdFailureGivesUp() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();
        escalator.nextAction("abc");
        escalator.nextAction("abc");

        assertEquals(Action.GIVE_UP, escalator.nextAction("abc"));
    }

    @Test
    public void keepsGivingUpOnFurtherFailures() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();
        escalator.nextAction("abc");
        escalator.nextAction("abc");
        escalator.nextAction("abc");

        assertEquals(Action.GIVE_UP, escalator.nextAction("abc"));
        assertEquals(Action.GIVE_UP, escalator.nextAction("abc"));
    }

    @Test
    public void anotherVideoStartsLadderAnew() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();
        escalator.nextAction("abc");
        escalator.nextAction("abc");
        escalator.nextAction("abc");

        assertEquals(Action.SWITCH_CLIENT, escalator.nextAction("xyz"));
    }

    @Test
    public void resetStartsLadderAnew() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();
        escalator.nextAction("abc");
        escalator.nextAction("abc");

        escalator.reset();

        assertEquals(Action.SWITCH_CLIENT, escalator.nextAction("abc"));
    }

    @Test
    public void nullVideoIdWalksTheLadderWithoutCrashing() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();

        assertEquals(Action.SWITCH_CLIENT, escalator.nextAction(null));
        assertEquals(Action.INVALIDATE_CACHE, escalator.nextAction(null));
        assertEquals(Action.GIVE_UP, escalator.nextAction(null));
    }
}
