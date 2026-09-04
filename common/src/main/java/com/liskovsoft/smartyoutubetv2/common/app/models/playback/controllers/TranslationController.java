package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.smartyoutubetv2.common.vot.SyncDecider;
import com.liskovsoft.smartyoutubetv2.common.vot.TranslationAudioPlayer;
import com.liskovsoft.smartyoutubetv2.common.vot.TranslationRestartLimiter;
import com.liskovsoft.smartyoutubetv2.common.vot.TranslationSession;
import com.liskovsoft.smartyoutubetv2.common.vot.VotClient;
import com.liskovsoft.smartyoutubetv2.common.vot.VotResult;
import com.liskovsoft.smartyoutubetv2.common.vot.VotTransport;

import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * Кнопка «Перевод»: дёргает VOT (video-online-translation Яндекса) и синхронизирует
 * получившуюся русскую озвучку со вторым, приглушённым, экземпляром ExoPlayer.
 *
 * ⚠️ Все обращения к {@link TranslationAudioPlayer} обязаны идти с главного потока. В
 * вендоренном ExoPlayer 2.10.6 {@code verifyApplicationThread()} НЕ бросает исключение при
 * нарушении — он лишь пишет {@code Log.w} со стектрейсом, поэтому вызов с чужого потока не
 * упадёт явно, а даст тихое неопределённое поведение (гонки внутри SimpleExoPlayer, которые
 * трудно поймать юнит-тестами и которые вылезают уже на устройстве). Поэтому дисциплина
 * потоков соблюдается всё равно: сетевой вызов {@link VotClient#translate} уходит на
 * Schedulers.io(), а всё, что трогает TranslationAudioPlayer/getPlayer(), — после
 * observeOn(AndroidSchedulers.mainThread()).
 */
public class TranslationController extends BasePlayerController {
    private static final long MAX_WAIT_MS = 600_000;
    private static final long SYNC_INTERVAL_MS = 1_000;
    private static final long SYNC_THRESHOLD_MS = 300;
    private static final int MAX_CONSECUTIVE_RESTARTS = 3;

    private final TranslationSession mSession = new TranslationSession(MAX_WAIT_MS);
    private final TranslationRestartLimiter mRestartLimiter = new TranslationRestartLimiter(MAX_CONSECUTIVE_RESTARTS);
    private VotClient mClient;
    private TranslationAudioPlayer mAudioPlayer;
    private Disposable mPollAction;
    private Disposable mSyncAction;
    private String mVideoId;
    private boolean mEnabled;

    @Override
    public void onVideoLoaded(Video item) {
        // новый ролик — прошлый перевод не переносим
        stopTranslation();
        mVideoId = item != null ? item.videoId : null;
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId != R.id.action_translation) {
            return;
        }

        if (mEnabled) {
            stopTranslation();
            getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_OFF);
        } else if (startTranslation()) {
            getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_ON);
        }
        // startTranslation() вернул false (эфир/нет videoId/плеер отсоединён) — она сама
        // уже привела кнопку в нужное состояние (или её незачем трогать), лишний
        // BUTTON_ON сюда ставить нельзя: он синхронно затирал бы её собственный OFF.
    }

    /**
     * @return {@code true}, если перевод действительно запущен (опрос ушёл),
     *         {@code false} — ранний выход (нет videoId, плеер отсоединён, либо эфир,
     *         который не поддерживается).
     */
    private boolean startTranslation() {
        if (mVideoId == null || getPlayer() == null) {
            return false;
        }

        Video video = getVideo();
        if (video != null && video.isLive) {
            // у Яндекса для эфиров отдельный API с пингами сессии — в этом заходе не поддерживаем
            MessageHelpers.showMessage(getContext(), R.string.vot_live_unsupported);
            getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_OFF);
            return false;
        }

        mEnabled = true;
        mClient = new VotClient(new VotTransport.OkHttpVotTransport());
        mSession.start(System.currentTimeMillis());
        poll(0);
        return true;
    }

    private void poll(long delayMs) {
        disposePoll();

        double duration = getPlayer().getDurationMs() / 1000d;
        String videoId = mVideoId;

        mPollAction = Observable.timer(delayMs, TimeUnit.MILLISECONDS)
                .observeOn(Schedulers.io())
                .map(ignored -> mClient.translate(videoId, duration, "en",
                        VotData.instance(getContext()).getTargetLanguage()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onPollResult, error -> onPollError());
    }

    private void onPollResult(VotResult result) {
        if (!mEnabled) {
            return;
        }

        switch (mSession.onResult(result, System.currentTimeMillis())) {
            case READY:
                startAudio(mSession.audioUrl());
                break;
            case WAIT:
                if (mSession.lastRemainingSec() > 0 && !isDetached()) {
                    MessageHelpers.showMessage(getContext(),
                            getContext().getString(R.string.vot_preparing, formatWait(mSession.lastRemainingSec())));
                }
                poll(mSession.nextPollDelayMs());
                break;
            case GIVE_UP:
                if (!isDetached()) {
                    MessageHelpers.showMessage(getContext(), R.string.vot_failed);
                }
                stopTranslation();
                if (!isDetached()) {
                    getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_OFF);
                }
                break;
        }
    }

    private String formatWait(int remainingSec) {
        return remainingSec >= 60 ? (remainingSec / 60 + " мин") : (remainingSec + " с");
    }

    private void onPollError() {
        if (!isDetached()) {
            MessageHelpers.showMessage(getContext(), R.string.vot_failed);
        }
        stopTranslation();
        if (!isDetached()) {
            getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_OFF);
        }
    }

    private void startAudio(String audioUrl) {
        if (isDetached()) {
            return;
        }

        mAudioPlayer = new TranslationAudioPlayer(getContext());
        mAudioPlayer.setErrorListener(this::onAudioError);
        mAudioPlayer.play(audioUrl, getPlayer().getPositionMs());
        mAudioPlayer.setSpeed(getPlayer().getSpeed());
        getPlayer().setVolume(VotData.instance(getContext()).getOriginalVolume());
        MessageHelpers.showMessage(getContext(), R.string.vot_enabled);
        startSyncTicker();
        // озвучка стартовала успешно — прошлые протухания (если были) не копим дальше
        mRestartLimiter.reset();
    }

    /**
     * Ссылка на mp3 живёт 2 часа (X-Amz-Expires=7200): на длинном ролике второй плеер
     * получит ошибку. Перезапрашиваем перевод (он уже в кэше Яндекса, ответ ~0,4 с) и
     * продолжаем с текущей позиции — но не бесконечно: {@link #mRestartLimiter}
     * ограничивает число последовательных попыток на случай, если Яндекс начнёт стабильно
     * отдавать «готово» с нерабочей ссылкой (иначе была бы бесконечная карусель
     * ошибка → перезапрос → ошибка с приглушённым оригиналом всё это время).
     */
    private void onAudioError() {
        if (!mEnabled || isDetached()) {
            return;
        }

        if (mAudioPlayer != null) {
            mAudioPlayer.release();
            mAudioPlayer = null;
        }
        disposeSync();

        if (!mRestartLimiter.tryRestart()) {
            MessageHelpers.showMessage(getContext(), R.string.vot_failed);
            stopTranslation();
            if (!isDetached()) {
                getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_OFF);
            }
            return;
        }

        mSession.start(System.currentTimeMillis());
        poll(0);
    }

    private void startSyncTicker() {
        disposeSync();

        mSyncAction = Observable.interval(SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> {
                    if (mAudioPlayer == null || getPlayer() == null) {
                        return;
                    }
                    if (SyncDecider.shouldResync(getPlayer().getPositionMs(), mAudioPlayer.positionMs(), SYNC_THRESHOLD_MS)) {
                        mAudioPlayer.seekTo(getPlayer().getPositionMs());
                    }
                });
    }

    @Override
    public void onPlay() {
        if (mAudioPlayer != null && !isDetached()) {
            mAudioPlayer.seekTo(getPlayer().getPositionMs());
            mAudioPlayer.resume();
        }
    }

    @Override
    public void onPause() {
        if (mAudioPlayer != null) {
            mAudioPlayer.pause();
        }
    }

    @Override
    public void onSeekEnd() {
        if (mAudioPlayer != null && !isDetached()) {
            mAudioPlayer.seekTo(getPlayer().getPositionMs());
        }
    }

    @Override
    public void onSpeedChanged(float speed) {
        if (mAudioPlayer != null) {
            mAudioPlayer.setSpeed(speed);
        }
    }

    @Override
    public void onEngineReleased() {
        stopTranslation();
    }

    @Override
    public void onFinish() {
        stopTranslation();
    }

    private void stopTranslation() {
        mEnabled = false;
        disposePoll();
        disposeSync();
        mRestartLimiter.reset();

        if (mAudioPlayer != null) {
            mAudioPlayer.release();
            mAudioPlayer = null;
        }

        if (getPlayer() != null) {
            getPlayer().setVolume(getPlayerData().getPlayerVolume());
        }
    }

    private void disposePoll() {
        if (mPollAction != null && !mPollAction.isDisposed()) {
            mPollAction.dispose();
        }
    }

    private void disposeSync() {
        if (mSyncAction != null && !mSyncAction.isDisposed()) {
            mSyncAction.dispose();
        }
    }

    /**
     * {@code getContext()}/{@code getPlayer()} в {@link BasePlayerController} резолвятся через
     * {@code WeakReference} и могут превратиться в {@code null} уже после того, как сетевой
     * ответ VOT ушёл в очередь (пользователь успел выйти из плеера, пока ждал перевод 1–5 минут).
     * Единая точка проверки перед использованием этих геттеров в асинхронных колбэках.
     */
    private boolean isDetached() {
        return getContext() == null || getPlayer() == null;
    }
}
