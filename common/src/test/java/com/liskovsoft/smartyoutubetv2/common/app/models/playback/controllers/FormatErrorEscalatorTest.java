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

    /** Номер провала уходит в лог — по нему в поле видно, на какой ступени встали. */
    @Test
    public void reportsFailureCountForLogging() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();
        assertEquals(0, escalator.getFailureCount());

        escalator.nextAction("abc");
        assertEquals(1, escalator.getFailureCount());

        escalator.reset();
        escalator.reset();
        assertEquals(0, escalator.getFailureCount());
    }

    @Test
    public void anotherVideoStartsLadderAnew() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();
        escalator.nextAction("abc");
        escalator.nextAction("abc");
        escalator.nextAction("abc");

        assertEquals(Action.SWITCH_CLIENT, escalator.nextAction("xyz"));
    }

    /**
     * Повтор, который назначает сама лестница, приходит обратно через
     * {@code onNewVideo()} — то есть через тот же вызов, что и выбор ролика
     * пользователем. Если бы этот сброс проходил, счётчик обнулялся бы на каждом
     * повторе и цикл остался бы бесконечным (проверено на приставке 06.09).
     */
    @Test
    public void reloadScheduledByTheLadderDoesNotResetIt() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();
        escalator.nextAction("abc");

        escalator.reset();

        assertEquals(Action.INVALIDATE_CACHE, escalator.nextAction("abc"));
    }

    @Test
    public void resetAfterTheScheduledReloadStartsLadderAnew() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();
        escalator.nextAction("abc");

        escalator.reset(); // сброс от нашего же повтора — проглатывается
        escalator.reset(); // а этот уже от пользователя

        assertEquals(Action.SWITCH_CLIENT, escalator.nextAction("abc"));
    }

    /** После отказа повторов больше нет, поэтому первый же сброс — пользовательский. */
    @Test
    public void resetAfterGiveUpStartsLadderAnew() {
        FormatErrorEscalator escalator = new FormatErrorEscalator();
        escalator.nextAction("abc");
        escalator.reset();
        escalator.nextAction("abc");
        escalator.reset();
        assertEquals(Action.GIVE_UP, escalator.nextAction("abc"));

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
