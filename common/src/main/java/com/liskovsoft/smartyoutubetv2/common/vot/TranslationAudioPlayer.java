package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;
import android.net.Uri;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;

public class TranslationAudioPlayer {
    private final Context mContext;
    private SimpleExoPlayer mPlayer;
    private Runnable mErrorListener;

    public TranslationAudioPlayer(Context context) {
        mContext = context;
    }

    public void setErrorListener(Runnable listener) {
        mErrorListener = listener;
    }

    public void play(String audioUrl, long positionMs) {
        release();

        mPlayer = ExoPlayerFactory.newSimpleInstance(mContext);
        mPlayer.addListener(new Player.EventListener() {
            @Override
            public void onPlayerError(ExoPlaybackException error) {
                if (mErrorListener != null) {
                    mErrorListener.run();
                }
            }
        });

        DefaultHttpDataSourceFactory dataSourceFactory =
                new DefaultHttpDataSourceFactory("Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        mPlayer.prepare(new ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(Uri.parse(audioUrl)));
        mPlayer.seekTo(positionMs);
        mPlayer.setPlayWhenReady(true);
    }

    public void pause() {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(false);
        }
    }

    public void resume() {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(true);
        }
    }

    public void seekTo(long positionMs) {
        if (mPlayer != null) {
            mPlayer.seekTo(positionMs);
        }
    }

    public void setSpeed(float speed) {
        if (mPlayer != null) {
            mPlayer.setPlaybackParameters(new PlaybackParameters(speed));
        }
    }

    public long positionMs() {
        return mPlayer != null ? mPlayer.getCurrentPosition() : 0;
    }

    public boolean isPlaying() {
        return mPlayer != null && mPlayer.getPlayWhenReady();
    }

    public void release() {
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }
}
