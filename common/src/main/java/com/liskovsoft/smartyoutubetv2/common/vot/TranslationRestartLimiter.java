package com.liskovsoft.smartyoutubetv2.common.vot;

/**
 * Ограничивает число последовательных перезапусков дорожки перевода после протухшей
 * ссылки на mp3 (см. {@link TranslationAudioPlayer#setErrorListener}). Без потолка
 * карусель «ошибка → перезапрос → ошибка» может крутиться бесконечно, если Яндекс вдруг
 * начнёт стабильно отдавать «готово» с нерабочей ссылкой — {@link TranslationSession}
 * при каждом {@code start()} обнуляет свой собственный дедлайн ожидания, так что он этот
 * случай не ловит.
 */
public class TranslationRestartLimiter {
    private final int mMaxRestarts;
    private int mRestartCount;

    public TranslationRestartLimiter(int maxRestarts) {
        mMaxRestarts = maxRestarts;
    }

    /**
     * @return {@code true}, если попытка разрешена (счётчик увеличен на 1);
     *         {@code false}, если лимит уже исчерпан (счётчик не меняется).
     */
    public boolean tryRestart() {
        if (mRestartCount >= mMaxRestarts) {
            return false;
        }

        mRestartCount++;
        return true;
    }

    /** Сбросить счётчик — вызывать при успешном старте озвучки и при выключении перевода. */
    public void reset() {
        mRestartCount = 0;
    }
}
