package com.liskovsoft.smartyoutubetv2.common.prefs;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Чистый JVM-тест (без Robolectric/Context) на разовую миграцию маски кнопок
 * плеера. Дефект: на уже установленном приложении маска бралась из сохранённого
 * файла настроек, и добавление PLAYER_BUTTON_TRANSLATION в PLAYER_BUTTON_DEFAULT
 * никак не затрагивало старые установки — пользователь не видел новую кнопку.
 */
public class PlayerTweaksDataMigrationTest {

    // Реальное значение, снятое с тестовой приставки (пауза, качество, субтитры,
    // подписка) — набор кнопок ДО того, как появилась кнопка "Перевод".
    private static final int OLD_REAL_WORLD_MASK = 140_032;

    @Test
    public void oldMaskAtVersionZeroGetsTranslationBitAdded() {
        int migrated = PlayerTweaksData.migratePlayerButtons(OLD_REAL_WORLD_MASK, 0);

        assertTrue("бит перевода должен появиться после миграции",
                (migrated & PlayerTweaksData.PLAYER_BUTTON_TRANSLATION) != 0);
        // Прочие биты не должны быть потеряны.
        assertEquals(OLD_REAL_WORLD_MASK, migrated & ~PlayerTweaksData.PLAYER_BUTTON_TRANSLATION);
    }

    @Test
    public void userDisabledButtonStaysDisabledAtVersionOne() {
        // Пользователь уже прошёл миграцию (версия 1) и осознанно выключил кнопку.
        int maskWithoutTranslation = OLD_REAL_WORLD_MASK; // бит перевода отсутствует

        int migrated = PlayerTweaksData.migratePlayerButtons(maskWithoutTranslation, 1);

        assertEquals("на версии >=1 миграция не должна повторно включать бит",
                maskWithoutTranslation, migrated);
        assertFalse((migrated & PlayerTweaksData.PLAYER_BUTTON_TRANSLATION) != 0);
    }

    @Test
    public void maskAlreadyHavingBitAtVersionZeroStaysIntact() {
        int maskWithTranslation = OLD_REAL_WORLD_MASK | PlayerTweaksData.PLAYER_BUTTON_TRANSLATION;

        int migrated = PlayerTweaksData.migratePlayerButtons(maskWithTranslation, 0);

        assertEquals("маска не должна портиться, если бит уже был выставлен",
                maskWithTranslation, migrated);
    }
}
