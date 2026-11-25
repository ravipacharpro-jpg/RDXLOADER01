package com.featurestic.box.core;

import android.content.Context;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sends offsets discovered inside the virtualized game to the SDK panel and
 * applies the confirmed offsets returned by the panel.
 */
public final class SdkPanelSyncManager {

    private static final String SYNC_URL =
            "https://featurestic-box-panel.vercel.app/api/telemetry/anogs-offsets";
    private static final String DEFAULT_TARGET_PKG = "com.pubg.imobile";

    private static boolean sSyncStarted = false;

    private SdkPanelSyncManager() {
    }

    public static synchronized void startOnce(Context context) {
        if (sSyncStarted) return;
        sSyncStarted = true;
        Thread worker = new Thread(() -> {
            for (int attempt = 0; attempt < 30; attempt++) {
                String raw = FeaturesticBoxCore.get().getDiscoveredAnogsOffsets();
                if (raw != null && !raw.trim().isEmpty() && raw.contains("|")) {
                    syncFromPanel(raw);
                    return;
                }
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "Featurestic-AnogsOffsetSync");
        worker.setDaemon(true);
        worker.start();
    }

    private static void syncFromPanel(String raw) {
        try {
            String buildId = "";
            String offsets = raw.trim();
            int separator = offsets.indexOf('|');
            if (separator >= 0) {
                buildId = offsets.substring(0, separator).trim();
                offsets = offsets.substring(separator + 1).trim();
            }
            if (offsets.isEmpty()) return;

            String targetPkg = DEFAULT_TARGET_PKG;
            try {
                String virtualPkg = BActivityThread.getAppPackageName();
                if (virtualPkg != null && !virtualPkg.trim().isEmpty()) {
                    targetPkg = virtualPkg.trim();
                }
            } catch (Throwable ignored) {
            }

            JSONObject body = new JSONObject();
            body.put("offsets", offsets);
            body.put("build_id", buildId);
            body.put("pkg", targetPkg);

            HttpURLConnection conn = (HttpURLConnection) new URL(SYNC_URL).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(data);
                } finally {
                    os.close();
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) return;

                InputStream is = conn.getInputStream();
                if (is == null) return;
                StringBuilder responseBody = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseBody.append(line);
                    }
                } finally {
                    reader.close();
                }
                String responseText = responseBody.toString().trim();
                if (responseText.isEmpty()) return;

                JSONObject json = new JSONObject(responseText);
                if (!isSuccessfulStatus(json)) return;

                JSONObject payload = json.optJSONObject("data");
                if (payload == null) payload = json;

                String returnedOffsets = firstNonEmpty(payload,
                        "anogs_ret_offsets", "ret_offsets", "offsets");
                if (returnedOffsets.isEmpty()) return;

                String returnedPkg = firstNonEmpty(payload,
                        "anogs_target_package", "target_pkg", "target_package", "pkg");
                if (returnedPkg.isEmpty()) returnedPkg = targetPkg;

                String returnedBuildId = firstNonEmpty(payload, "anogs_build_id", "build_id");

                FeaturesticBoxCore.get().setAnogsRetOffsets(returnedPkg, returnedBuildId, returnedOffsets);
            } finally {
                conn.disconnect();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSuccessfulStatus(JSONObject json) {
        try {
            if (!json.has("status")) return true;
            Object status = json.opt("status");
            if (status instanceof Boolean) return (Boolean) status;
            String value = String.valueOf(status).trim();
            return Boolean.parseBoolean(value) || "ok".equalsIgnoreCase(value);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String firstNonEmpty(JSONObject json, String... keys) {
        for (String key : keys) {
            try {
                String value = json.optString(key, "").trim();
                if (!value.isEmpty()) return value;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }
}
