package com.rdx.loader.ui;

import android.app.Activity;
import android.app.Application;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.rdx.loader.R;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Full-screen looping raw-video background for RDX LOADER.
 * Audio starts muted and can be toggled from any activity.
 */
public final class RdxVideoBackground {

    private static final String PREF_NAME = "rdx_video_settings";
    private static final String KEY_MUTED = "audio_muted";
    private static final Map<Activity, PlayerHolder> HOLDERS = new WeakHashMap<>();
    private static Application sApp;
    private static boolean sInstalled;
    private static boolean sMuted = false;

    private RdxVideoBackground() {}

    private static final class PlayerHolder {
        TextureView textureView;
        MediaPlayer player;
        boolean prepared;
        boolean releasing;
    }

    public static synchronized void install(Application app) {
        if (app == null || sInstalled) return;
        sApp = app;
        sMuted = app.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE).getBoolean(KEY_MUTED, false);
        sInstalled = true;

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                if (isLoaderScreen(activity)) attach(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
                PlayerHolder holder = getHolder(activity);
                if (holder != null && holder.player != null && holder.prepared) {
                    try {
                        if (!holder.player.isPlaying()) holder.player.start();
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {
                PlayerHolder holder = getHolder(activity);
                if (holder != null && holder.player != null) {
                    try {
                        if (holder.player.isPlaying()) holder.player.pause();
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                release(activity);
            }
        });
    }

    private static boolean isLoaderScreen(Activity activity) {
        if (activity == null) return false;
        String name = activity.getClass().getName();
        return name.endsWith(".ui.LoginActivity")
                || name.endsWith(".ui.MainActivity")
                || name.endsWith(".ui.AccountSetupActivity")
                || name.endsWith(".ui.SocialLoginActivity");
    }

    private static synchronized PlayerHolder getHolder(Activity activity) {
        return activity == null ? null : HOLDERS.get(activity);
    }

    private static synchronized void attach(Activity activity) {
        if (activity == null || getHolder(activity) != null) return;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.setBackgroundColor(Color.TRANSPARENT);

            FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);

            final TextureView texture = new TextureView(activity);
            texture.setOpaque(false);
            decor.addView(texture, 0, fill);

            MediaPlayer player = new MediaPlayer();
            player.setLooping(true);
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            applyVolume(player);

            final PlayerHolder holder = new PlayerHolder();
            holder.textureView = texture;
            holder.player = player;
            HOLDERS.put(activity, holder);

            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                    try {
                        player.setSurface(new Surface(surface));
                        prepare(activity, holder);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                    try {
                        player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                    try {
                        player.setSurface(null);
                    } catch (Throwable ignored) {
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void prepare(Activity activity, PlayerHolder holder) {
        if (holder == null || holder.prepared || holder.releasing) return;
        holder.prepared = true;
        try {
            AssetFileDescriptor afd = activity.getResources().openRawResourceFd(R.raw.rdx_bg);
            if (afd == null) return;
            holder.player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            holder.player.setOnPreparedListener(mp -> {
                try {
                    applyVolume(mp);
                    mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    mp.start();
                } catch (Throwable ignored) {
                }
            });
            holder.player.setOnErrorListener((mp, what, extra) -> true);
            holder.player.prepareAsync();
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void release(Activity activity) {
        PlayerHolder holder = getHolder(activity);
        if (holder == null) return;
        holder.releasing = true;
        HOLDERS.remove(activity);
        try {
            if (holder.player != null) {
                if (holder.player.isPlaying()) holder.player.stop();
                holder.player.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyVolume(MediaPlayer player) {
        if (player == null) return;
        float volume = sMuted ? 0f : 1f;
        try {
            player.setVolume(volume, volume);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized boolean isMuted() {
        return sMuted;
    }

    public static synchronized boolean toggleMute() {
        setMuted(!sMuted);
        return sMuted;
    }

    public static synchronized void setMuted(boolean muted) {
        sMuted = muted;
        if (sApp != null) {
            sApp.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE)
                    .edit().putBoolean(KEY_MUTED, muted).apply();
        }
        for (PlayerHolder holder : HOLDERS.values()) {
            applyVolume(holder.player);
        }
    }
}
