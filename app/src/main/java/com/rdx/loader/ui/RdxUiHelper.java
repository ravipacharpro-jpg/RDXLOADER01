package com.rdx.loader.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Toast;

import com.rdx.loader.AppConfig;
import com.rdx.loader.R;

/**
 * Glassy button styling, press feedback and icon helpers for RDX LOADER.
 */
public final class RdxUiHelper {

    private RdxUiHelper() {}

    public static void decorate(View root) {
        if (root == null) return;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                decorate(group.getChildAt(i));
            }
        }
        String idName = idName(root);
        if (idName == null || idName.endsWith("Content")) return;

        if (idName.startsWith("pill")) {
            if (root.getBackground() == null) {
                root.setBackgroundResource(R.drawable.bg_rdx_status);
            }
        } else if (idName.startsWith("btn") || idName.startsWith("card")) {
            if (root.getBackground() == null) {
                root.setBackgroundResource(R.drawable.bg_rdx_glass_button);
            }
        } else {
            return;
        }

        root.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
            return false;
        });

        if (idName.equals("btnLaunchGame") || idName.equals("btnLogin")) {
            root.startAnimation(AnimationUtils.loadAnimation(root.getContext(), R.anim.rdx_pulse));
        }
    }

    public static void fadeIn(View view) {
        if (view == null) return;
        view.startAnimation(AnimationUtils.loadAnimation(view.getContext(), R.anim.rdx_fade_in));
    }

    public static void bindAudioToggle(Activity activity, View button) {
        refreshAudioButtons(activity);
        if (button == null) return;
        button.setOnClickListener(v -> {
            RdxVideoBackground.toggleMute();
            refreshAudioButtons(activity);
            Toast.makeText(activity,
                    RdxVideoBackground.isMuted() ? "Background audio off" : "Background audio on",
                    Toast.LENGTH_SHORT).show();
        });
    }

    public static void refreshAudioButtons(Activity activity) {
        if (activity == null) return;
        refreshAudioButtons(activity.getWindow().getDecorView());
    }

    private static void refreshAudioButtons(View root) {
        if (root == null) return;
        String name = idName(root);
        if (name != null && name.toLowerCase().contains("audio") && root instanceof ImageView) {
            ((ImageView) root).setImageResource(
                    RdxVideoBackground.isMuted() ? R.drawable.ic_audio_off : R.drawable.ic_audio_on);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                refreshAudioButtons(group.getChildAt(i));
            }
        }
    }

    public static void bindTelegram(Activity activity, View button) {
        if (activity == null || button == null) return;
        button.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(AppConfig.TELEGRAM_CHANNEL_URL)));
            } catch (Throwable ignored) {
                Toast.makeText(activity, "Unable to open Telegram", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String idName(View view) {
        if (view == null || view.getId() == View.NO_ID) return null;
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
