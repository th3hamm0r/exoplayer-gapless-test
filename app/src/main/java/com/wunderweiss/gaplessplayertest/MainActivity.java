package com.wunderweiss.gaplessplayertest;

import android.net.Uri;
import android.os.AsyncTask;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.DynamicConcatenatingMediaSource;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private ExoPlayer exoPlayer;

    private final String[] assets = new String[]{
            "1.mp3",
            "2.mp3",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.button_start_stop_exoplayer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (exoPlayer == null) {
                    L.d(TAG, "start ExoPlayer");
                    startExoPlayer();
                } else {
                    L.d(TAG, "stop ExoPlayer");
                    maybeStopExoPlayer();
                }
            }
        });

        findViewById(R.id.button_start_stop_customplayer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (customPlayer == null) {
                    L.d(TAG, "start CustomPlayer");
                    startCustomPlayer();
                } else {
                    L.d(TAG, "stop CustomPlayer");
                    maybeStopCustomPlayer();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        maybeStopExoPlayer();
        maybeStopCustomPlayer();
    }

    private void startExoPlayer() {
        maybeStopExoPlayer();

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(new AdaptiveTrackSelection.Factory(new DefaultBandwidthMeter()));
        exoPlayer = ExoPlayerFactory.newSimpleInstance(new DefaultRenderersFactory(this), trackSelector, new DefaultLoadControl());
        exoPlayer.addListener(new Player.EventListener() {
            @Override
            public void onTimelineChanged(Timeline timeline, Object manifest) {
                L.d(TAG, "ExoPlayer - onTimelineChanged");
            }

            @Override
            public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
                L.d(TAG, "ExoPlayer - onTracksChanged");
            }

            @Override
            public void onLoadingChanged(boolean isLoading) {
                L.d(TAG, "ExoPlayer - onLoadingChanged: isLoading=%s, getCurrentPosition=%d, getBufferedPosition=%d",
                        isLoading, exoPlayer.getCurrentPosition(), exoPlayer.getBufferedPosition());
            }

            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                L.d(TAG, "ExoPlayer - onPlayerStateChanged: playWhenReady=%s, playbackState=%s, getCurrentPosition=%d, getBufferedPosition=%d",
                        playWhenReady, playbackState, exoPlayer.getCurrentPosition(), exoPlayer.getBufferedPosition());
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {
                L.d(TAG, "ExoPlayer - onRepeatModeChanged");
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                L.d(TAG, "ExoPlayer - onPlayerError: error=%s, getCurrentPosition=%d, getBufferedPosition=%d",
                        error, exoPlayer.getCurrentPosition(), exoPlayer.getBufferedPosition());
            }

            @Override
            public void onPositionDiscontinuity() {
                L.d(TAG, "ExoPlayer - onPositionDiscontinuity: windowIndex=%d, periodIndex=%d, getCurrentPosition=%d, getBufferedPosition=%d",
                        exoPlayer.getCurrentWindowIndex(), exoPlayer.getCurrentPeriodIndex(), exoPlayer.getCurrentPosition(), exoPlayer.getBufferedPosition());
            }

            @Override
            public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
                L.d(TAG, "ExoPlayer - onPlaybackParametersChanged");
            }
        });

        DynamicConcatenatingMediaSource mediaSource = new DynamicConcatenatingMediaSource();

        for (String asset : assets) {
            mediaSource.addMediaSource(createMediaSource(Uri.parse("asset:///" + asset)));
        }

        exoPlayer.prepare(mediaSource);
        exoPlayer.setPlayWhenReady(true);
    }

    private void maybeStopExoPlayer() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
    }

    private MediaSource createMediaSource(Uri uri) {
        return new ExtractorMediaSource(
                uri,
                new DefaultDataSourceFactory(this, Util.getUserAgent(this, "gaplessplayertest")),
                new DefaultExtractorsFactory(),
                null,
                null
        );
    }

    private AsyncTask customPlayer;

    private void startCustomPlayer() {
        maybeStopCustomPlayer();

        customPlayer = new CustomPlayer(this, assets).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void maybeStopCustomPlayer() {
        if (customPlayer != null) {
            customPlayer.cancel(true);
            customPlayer = null;
        }
    }
}
