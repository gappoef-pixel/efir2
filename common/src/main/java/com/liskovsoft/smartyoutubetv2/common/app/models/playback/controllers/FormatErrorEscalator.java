package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.sharedutils.helpers.Helpers;

/**
 * Лестница попыток для ошибки «форматы не получены» ({@code fromNullable result is null}).
 *
 * <p>Апстрим на эту ошибку молча зовёт {@code reloadVideo()} раз в секунду, ничего не сбрасывая:
 * запрос повторяется в том же состоянии, поэтому устойчивый отказ превращается в вечный
 * крутящийся кружок без единого слова пользователю (см. {@code ErrorFixerController}).
 *
 * <p>Класс решает только «что делать на N-м подряд провале одного и того же ролика»,
 * ничего не знает ни о плеере, ни о сети — поэтому проверяется юнит-тестами.
 * Счётчик привязан к videoId: переход на другой ролик начинает лестницу заново.
 */
public class FormatErrorEscalator {
    public enum Action {
        /** Первый провал: сменить клиент YouTube (внутри сбрасывается кэш poToken). */
        SWITCH_CLIENT,
        /** Второй провал: сбросить общие кэши сервиса (visitorData, тип клиента, poToken целиком). */
        INVALIDATE_CACHE,
        /** Третий и далее: прекратить повторы и честно показать ошибку. */
        GIVE_UP
    }

    private String mVideoId;
    private int mFailureCount;
    private boolean mReloadScheduled;

    /**
     * Зарегистрировать очередной провал и получить действие для него.
     *
     * @param videoId ролик, на котором не получены форматы; {@code null} допустим
     */
    public Action nextAction(String videoId) {
        if (!Helpers.equals(mVideoId, videoId)) {
            mVideoId = videoId;
            mFailureCount = 0;
        }

        mFailureCount++;

        switch (mFailureCount) {
            case 1:
                mReloadScheduled = true;
                return Action.SWITCH_CLIENT;
            case 2:
                mReloadScheduled = true;
                return Action.INVALIDATE_CACHE;
            default:
                // Повторов больше не будет, значит и глотать нечего.
                mReloadScheduled = false;
                return Action.GIVE_UP;
        }
    }

    /** Сколько провалов подряд насчитано по текущему ролику. Нужен для лога. */
    public int getFailureCount() {
        return mFailureCount;
    }

    /**
     * Сбросить лестницу — при успешно полученных форматах и при выборе ролика пользователем.
     *
     * <p>⛔ Ровно один сброс после каждой назначенной попытки проглатывается. Повтор, который
     * назначила сама лестница, возвращается в плеер через {@code onNewVideo()} — тот же вызов,
     * которым пользователь открывает ролик, отличить их снаружи нечем. Без этого счётчик
     * обнулялся бы на каждом повторе и цикл остался бы бесконечным.
     */
    public void reset() {
        if (mReloadScheduled) {
            mReloadScheduled = false;
            return;
        }

        mVideoId = null;
        mFailureCount = 0;
    }
}
