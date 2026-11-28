package com.rdx.loader.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.rdx.loader.AppConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal & Zoro/Box PHP Panel Authenticator:
 * Sends POST game=PUBG&user_key=<key>&serial=<uuid>
 * Parses JSON responses:
 *   - {"status": true, "data": {"token": "...", "rng": ..., "EXP": "2026-12-31"}}
 *   - {"status": false, "reason": "..."}
 */
public class PhpAuthManager {

    private static final String PREF_NAME = "featurestic_apple_auth";
    private static final String KEY_SAVED_LICENSE = "saved_license_key";
    private static final String KEY_SAVED_EXPIRY = "saved_expiry";
    private static final String KEY_REMEMBER = "remember_key";
    private static final String KEY_FALLBACK_HWID = "fallback_hwid";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface AuthCallback {
        void onSuccess(String licenseKey, String expiryText, String serverMessage);
        void onError(String errorMessage);
    }

    private static String normalizeUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String u = rawUrl.trim();
        if (u.regionMatches(true, 0, "https://", 0, 8)) {
            u = "https://" + u.substring(8);
        } else if (u.regionMatches(true, 0, "http://", 0, 7)) {
            u = "http://" + u.substring(7);
        } else if (!u.contains("://") && !u.isEmpty()) {
            u = "https://" + u;
        }
        if (u.startsWith("https://github.com/") && u.contains("/blob/")) {
            u = u.replace("https://github.com/", "https://raw.githubusercontent.com/")
                 .replaceFirst("/blob/", "/");
        }
        return u;
    }

    public static void verifyKey(final Context context, final String rawKey, final AuthCallback callback) {
        final String key = rawKey != null ? rawKey.trim() : "";
        if (key.isEmpty()) {
            postError(callback, "Please enter a valid license key");
            return;
        }

        String envError = RdxSecurityGuard.inspectEnvironment(context);
        if (envError != null) {
            postError(callback, envError);
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String panelBase = normalizeUrl(AppConfig.PHP_LOGIN_URL);
                if (panelBase.isEmpty()) {
                    panelBase = "https://featurestic-key-panel.vercel.app/connect";
                }
                while (panelBase.endsWith("/")) {
                    panelBase = panelBase.substring(0, panelBase.length() - 1);
                }

                String serial = computePanelSerialUuid(context, key);
                String body = "game=PUBG"
                        + "&user_key=" + URLEncoder.encode(key, "UTF-8")
                        + "&serial=" + URLEncoder.encode(serial, "UTF-8")
                        + "&slug=" + URLEncoder.encode(AppConfig.LOADER_WORKSPACE_SLUG, "UTF-8");

                URL url = new URL(panelBase);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Accept", "application/json");

                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                }

                JSONObject json = new JSONObject(sb.toString());
                boolean ok = json.optBoolean("status", false);
                if (!ok) {
                    String reason = json.optString("reason", "");
                    String msg = json.optString("message", "");
                    String err = !msg.isEmpty() ? msg : (!reason.isEmpty() ? reason : "Key rejected by server");
                    postError(callback, err);
                    return;
                }

                JSONObject data = json.optJSONObject("data");
                final String expiry = data != null ? data.optString("exp", "Active") : "Active";
                final String msg = data != null ? data.optString("message", "Authenticated") : "Authenticated";

                saveSession(context, key, expiry, true);
                syncLicenseFileForPlugin(key);
                RdxSecurityGuard.setSessionAuthenticated(true);

                // Background activation of Virtual Container SDK engine with embedded RDXLOADER01 key
                RdxSecurityGuard.activateSdkEngine(context, (sdkOk, sdkMsg) -> {
                });

                MAIN_HANDLER.post(() -> callback.onSuccess(key, expiry, msg));
            } catch (Throwable t) {
                postError(callback, "Server verification failed: " + (t.getMessage() != null ? t.getMessage() : "Network error"));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static void postError(final AuthCallback callback, final String msg) {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(msg);
            }
        });
    }

    /**
     * Computes the exact UUID serial expected by Zoro / Box PHP Panels (Login.h):
     * UUID.nameUUIDFromBytes((userKey + androidId + Build.MODEL + Build.BRAND).getBytes())
     */
    public static String computePanelSerialUuid(Context context, String userKey) {
        try {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null) androidId = "";
            String seed = (userKey != null ? userKey : "") + androidId + Build.MODEL + Build.BRAND;
            return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
        } catch (Throwable t) {
            return getDeviceHwid(context);
        }
    }

    public static String getDeviceHwid(Context context) {
        try {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null) androidId = "";
            String seed = androidId + Build.MODEL + Build.BRAND;
            return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString().toUpperCase();
        } catch (Throwable t) {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String id = sp.getString(KEY_FALLBACK_HWID, null);
            if (id == null) {
                id = UUID.randomUUID().toString().toUpperCase();
                sp.edit().putString(KEY_FALLBACK_HWID, id).apply();
            }
            return id;
        }
    }

    public static void saveSession(Context context, String key, String expiry, boolean remember) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putString(KEY_SAVED_LICENSE, key)
                .putString(KEY_SAVED_EXPIRY, expiry)
                .putBoolean(KEY_REMEMBER, remember)
                .apply();
        syncLicenseFileForPlugin(key);
    }

    public static void syncLicenseFileForPlugin(String key) {
        if (key == null || key.trim().isEmpty()) return;
        String cleanKey = key.trim();
        String[] pkgs = new String[]{
                AppConfig.PKG_BGMI,
                AppConfig.PKG_GLOBAL,
                AppConfig.PKG_KR,
                AppConfig.PKG_VN,
                AppConfig.PKG_TW
        };
        String[] bases = new String[]{
                "/storage/emulated/0/SdCard/Android/obb",
                "/storage/emulated/0/Android/obb"
        };
        for (String base : bases) {
            for (String pkg : pkgs) {
                try {
                    java.io.File dir = new java.io.File(base, pkg);
                    if (!dir.exists()) dir.mkdirs();
                    if (dir.isDirectory()) {
                        String[] licNames = new String[]{"key.lic", "teamdark.lic", "license.lic", "user.lic"};
                        for (String licName : licNames) {
                            java.io.File lic = new java.io.File(dir, licName);
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(lic)) {
                                fos.write(cleanKey.getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static String getSavedKey(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_SAVED_LICENSE, "");
    }

    public static String getSavedExpiry(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_SAVED_EXPIRY, "Active Session");
    }

    public static void clearSession(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_SAVED_LICENSE).remove(KEY_SAVED_EXPIRY).apply();
    }
}
