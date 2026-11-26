package com.rdx.loader.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;

import com.featurestic.box.FeaturesticBoxCore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enterprise Anti-Dump, Anti-Hook, Anti-Proxy & SDK Key Guard for RDX LOADER.
 *
 * Protection Layers:
 * 1. Obfuscated SDK Key (RDXLOADER01) & Panel URL - zero plain text string literals in dex.
 * 2. Anti-Proxy / Anti-VPN check - blocks HttpCanary, Reqable, Charles, BurpSuite MITM sniffing.
 * 3. Anti-Frida / Anti-Hook / Tracer check - blocks memory dumping and function hijacking.
 * 4. Background SDK Engine Activation - transparently acquires .so and offsets from panel.
 */
public final class RdxSecurityGuard {

    private static final byte[] K_ENC = new byte[]{9, 31, 3, 23, 20, 26, 31, 30, 9, 107, 106};
    private static final byte[] U_ENC = new byte[]{51, 47, 47, 43, 40, 97, 116, 116, 61, 62, 58, 47, 46, 41, 62, 40, 47, 50, 56, 118, 57, 52, 35, 118, 43, 58, 53, 62, 55, 117, 45, 62, 41, 56, 62, 55, 117, 58, 43, 43, 116, 58, 43, 50, 116, 43, 46, 57, 55, 50, 56, 116, 56, 52, 53, 53, 62, 56, 47};
    private static final byte MASK = 0x5b;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile boolean sAuthenticatedSession = false;

    private RdxSecurityGuard() {}

    private static String decodeBytes(byte[] enc) {
        char[] out = new char[enc.length];
        for (int i = 0; i < enc.length; i++) {
            out[i] = (char) (enc[i] ^ MASK);
        }
        return new String(out);
    }

    public static String getEmbeddedSdkKey() {
        return decodeBytes(K_ENC);
    }

    public static String getSdkPanelUrl() {
        return decodeBytes(U_ENC);
    }

    public static void setSessionAuthenticated(boolean authenticated) {
        sAuthenticatedSession = authenticated;
    }

    public static boolean isSessionAuthenticated() {
        return sAuthenticatedSession;
    }

    /**
     * Checks if the device environment is safe from network sniffing or hooking.
     * Returns null if clean, or an error string if untrusted.
     */
    public static String inspectEnvironment(Context context) {
        if (context == null) return null;

        // 1. Debugger & Tracer check
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            return "Active debugger detected. Launch aborted.";
        }
        if (isTracerAttached()) {
            return "Security violation: Process tracer detected.";
        }

        // 2. Anti-Frida & Hook agent check
        if (hasHookArtifacts()) {
            return "Security violation: Unauthorized hooking framework detected.";
        }

        // 3. Proxy & VPN sniffing check
        if (isProxyOrVpnActive(context)) {
            return "Proxy or VPN detected. Please disable network sniffing tools to continue.";
        }

        return null;
    }

    private static boolean isTracerAttached() {
        try {
            File status = new File("/proc/self/status");
            if (!status.canRead()) return false;
            try (BufferedReader br = new BufferedReader(new FileReader(status))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("TracerPid:")) {
                        String pid = line.substring("TracerPid:".length()).trim();
                        int p = Integer.parseInt(pid);
                        return p > 0;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasHookArtifacts() {
        try {
            File maps = new File("/proc/self/maps");
            if (!maps.canRead()) return false;
            try (BufferedReader br = new BufferedReader(new FileReader(maps))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String lower = line.toLowerCase(java.util.Locale.US);
                    if (lower.contains("frida-agent") ||
                        lower.contains("frida-gadget") ||
                        lower.contains("libfrida") ||
                        lower.contains("libxposed") ||
                        lower.contains("edxp") ||
                        lower.contains("lsposed") ||
                        lower.contains("substrate") ||
                        lower.contains("sandhook")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isProxyOrVpnActive(Context context) {
        try {
            String proxyHost = System.getProperty("http.proxyHost");
            String proxyPort = System.getProperty("http.proxyPort");
            if (proxyHost != null && !proxyHost.trim().isEmpty() && proxyPort != null) {
                return true;
            }
            String httpsProxyHost = System.getProperty("https.proxyHost");
            String httpsProxyPort = System.getProperty("https.proxyPort");
            if (httpsProxyHost != null && !httpsProxyHost.trim().isEmpty() && httpsProxyPort != null) {
                return true;
            }

            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNet = cm.getActiveNetwork();
                if (activeNet != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public interface SdkActivationCallback {
        void onActivated(boolean success, String message);
    }

    /**
     * Activates the Virtual Container Engine with the built-in obfuscated SDK key RDXLOADER01.
     * Fetches dynamic .so plugin URLs, offsets, and security flags into RAM.
     */
    public static void activateSdkEngine(Context context, SdkActivationCallback callback) {
        if (context == null) {
            if (callback != null) callback.onActivated(false, "Context is null");
            return;
        }

        final Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String urlText = getSdkPanelUrl();
                String sdkKey = getEmbeddedSdkKey();
                String serial = PhpAuthManager.getDeviceHwid(appCtx);
                String packageName = appCtx.getPackageName();

                String body = "game=PUBG"
                        + "&user_key=" + URLEncoder.encode(sdkKey, "UTF-8")
                        + "&package=" + URLEncoder.encode(packageName, "UTF-8")
                        + "&serial=" + URLEncoder.encode(serial, "UTF-8");

                URL url = new URL(urlText);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);
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
                    String reason = json.optString("reason", "SDK Activation Failed");
                    String msg = json.optString("message", reason);
                    if (callback != null) {
                        MAIN_HANDLER.post(() -> callback.onActivated(false, msg));
                    }
                    return;
                }

                JSONObject data = json.optJSONObject("data");
                if (data != null) {
                    String pluginUrl = data.optString("plugin_url", "").trim();
                    String soSha256 = data.optString("so_sha256", "").trim();
                    String anogsTarget = data.optString("anogs_target_package", "com.pubg.imobile").trim();
                    String anogsOffsets = data.optString("anogs_ret_offsets", "").trim();
                    String socUrl = data.optString("socu64_url", data.optString("soc_url", "")).trim();

                    appCtx.getSharedPreferences("featurestic_so_security", Context.MODE_PRIVATE)
                            .edit()
                            .putString("server_plugin_url", pluginUrl)
                            .putString("server_so_sha256", soSha256)
                            .putString("server_socu64_url", socUrl)
                            .apply();

                    if ("com.pubg.imobile".equalsIgnoreCase(anogsTarget) && !anogsOffsets.isEmpty()) {
                        try {
                            FeaturesticBoxCore.get().setAnogsRetOffsets("com.pubg.imobile", "", anogsOffsets);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                sAuthenticatedSession = true;
                if (callback != null) {
                    MAIN_HANDLER.post(() -> callback.onActivated(true, "SDK engine activated successfully"));
                }
            } catch (Throwable t) {
                if (callback != null) {
                    MAIN_HANDLER.post(() -> callback.onActivated(false, t.getMessage() != null ? t.getMessage() : "SDK activation error"));
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
