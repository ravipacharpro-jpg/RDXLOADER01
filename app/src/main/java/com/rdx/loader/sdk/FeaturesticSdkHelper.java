package com.rdx.loader.sdk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import com.rdx.loader.AppConfig;
import com.featurestic.box.core.env.FeaturesticStorageManager;
import com.featurestic.sdk.FeaturesticBox;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Clean SDK Controller that separates FeaturesticBox Virtual Engine calls from UI code.
 * Handles:
 *   1. Display Over Other Apps (SYSTEM_ALERT_WINDOW / Floating Permission)
 *   2. Android 11-16 All Files Access (MANAGE_EXTERNAL_STORAGE) for /SdCard/Android/obb
 *   3. Android 13+ Notification Permission (POST_NOTIFICATIONS)
 *   4. 4-Layer Host Game Detection & SDK Multi-Layer OBB Sync into /storage/emulated/0/SdCard/Android/obb/<pkg>
 *   5. One-Click Game Launch inside FeaturesticBox with automatic .so plugin staging
 */
public final class FeaturesticSdkHelper {

    private static final String PREF_SETUP = "featurestic_setup_state";

    private FeaturesticSdkHelper() {}

    public static void attachBaseContext(Context base) {
        try {
            FeaturesticBox.doAttachBaseContext(base);
        } catch (Throwable ignored) {
        }
    }

    public static void initEngine(Context context) {
        try {
            FeaturesticBox.setSdkKey(context, AppConfig.SDK_LICENSE_KEY);
            FeaturesticBox.doCreate(context);
            FeaturesticBox.setFeature("hide_root", AppConfig.ENABLE_HIDE_ROOT);
            FeaturesticBox.setFeature("hide_xposed", AppConfig.ENABLE_HIDE_XPOSED);
            FeaturesticBox.setFeature("low_latency_network", AppConfig.ENABLE_LOW_LATENCY);
            if (AppConfig.ENABLE_AUTO_120_FPS) {
                FeaturesticBox.get().setTargetGameFps(120);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Resolves the effective .so plugin URL:
     * Managed directly from the panel and loaded purely into RAM (memfd_create).
     * Zero disk writes, zero storage leakage.
     */
    public static String resolveEffectivePluginSo(Context context) {
        if (context == null) return "";
        try {
            String remoteSo = FeaturesticBox.getRemotePluginUrl(context);
            if (remoteSo != null && !remoteSo.trim().isEmpty()) {
                return remoteSo.trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static String[] resolveEffectivePluginSoList(Context context) {
        String so = resolveEffectivePluginSo(context);
        return so.isEmpty() ? new String[0] : so.split("[,;\\n]+");
    }

    private static boolean isValidElf(File file) {
        if (file == null || !file.isFile() || file.length() < 16) return false;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[4];
            if (fis.read(header) < 4) return false;
            return header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Computes and verifies SHA-256 anti-tamper hash of a local .so file.
     */
    public static boolean verifyFileSha256(File file, String expectedSha) {
        if (file == null || !file.exists() || expectedSha == null || expectedSha.trim().isEmpty()) {
            return true; // No hash enforcement requested
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream is = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    md.update(buffer, 0, read);
                }
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().equalsIgnoreCase(expectedSha.trim());
        } catch (Throwable t) {
            return false;
        }
    }


    public static void requestRuntimePermissionsOnStart(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                java.util.List<String> perms = new java.util.ArrayList<>();
                if (Build.VERSION.SDK_INT >= 33) {
                    if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        perms.add("android.permission.POST_NOTIFICATIONS");
                    }
                    if (activity.checkSelfPermission("android.permission.READ_MEDIA_IMAGES") != PackageManager.PERMISSION_GRANTED) {
                        perms.add("android.permission.READ_MEDIA_IMAGES");
                        perms.add("android.permission.READ_MEDIA_VIDEO");
                        perms.add("android.permission.READ_MEDIA_AUDIO");
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    if (activity.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
                        perms.add("android.permission.BLUETOOTH_CONNECT");
                    }
                }
                if (activity.checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
                    perms.add("android.permission.RECORD_AUDIO");
                }
                if (activity.checkSelfPermission("android.permission.READ_PHONE_STATE") != PackageManager.PERMISSION_GRANTED) {
                    perms.add("android.permission.READ_PHONE_STATE");
                }
                if (activity.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
                    perms.add("android.permission.READ_EXTERNAL_STORAGE");
                    perms.add("android.permission.WRITE_EXTERNAL_STORAGE");
                }
                if (!perms.isEmpty()) {
                    activity.requestPermissions(perms.toArray(new String[0]), 1002);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Checks if "Display over other apps" (Float / Overlay) permission is granted.
     */
    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 23 && context != null) {
            try {
                return Settings.canDrawOverlays(context);
            } catch (Throwable ignored) {
                return true;
            }
        }
        return true;
    }

    /**
     * Opens the system "Display over other apps" settings screen for this Loader.
     */
    public static void requestOverlayPermission(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())
                );
                activity.startActivityForResult(intent, 1003);
            } catch (Throwable ignored) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    activity.startActivityForResult(intent, 1003);
                } catch (Throwable ignored2) {}
            }
        }
    }

    /**
     * Checks if All Files Access (Android 11+) or Legacy Storage Permission (Android 10) is granted.
     */
    public static boolean hasAllFilesPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable ignored) {
                return true;
            }
        }
        if (Build.VERSION.SDK_INT >= 23 && context != null) {
            return context.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * Checks if "Install unknown apps" (REQUEST_INSTALL_PACKAGES) permission is granted.
     * Required on Android 8.0+ to install APKs from this Loader.
     */
    public static boolean canRequestPackageInstalls(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context != null) {
            try {
                return context.getPackageManager()
                        .canRequestPackageInstalls();
            } catch (Throwable ignored) {
                return true;
            }
        }
        return true;
    }

    /**
     * Opens the "Install unknown apps" settings screen for this Loader,
     * so the user can allow installing APKs from this app.
     */
    public static void requestInstallPackagesPermission(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, 1004);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Opens the Android 11+ "Allow access to manage all files" settings screen for this Loader,
     * or requests READ/WRITE_EXTERNAL_STORAGE on Android 10.
     */
    public static void requestAllFilesPermission(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, 1001);
                return;
            } catch (Throwable ignored) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    activity.startActivityForResult(intent, 1001);
                    return;
                } catch (Throwable ignored2) {}
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                activity.requestPermissions(new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"
                }, 1001);
            } catch (Throwable ignored) {}
        }
    }

    public static PackageInfo findHostGameInfo(Context context, String packageName) {
        return FeaturesticBox.findHostGameInfo(context, packageName);
    }

    public static boolean isInstalledOnHost(Context context, String packageName) {
        return FeaturesticBox.isGameInstalledOnHost(context, packageName);
    }

    public static boolean hasHostObbFiles(Context context, String packageName) {
        return !FileCopyTask.findSourceObbFiles(context, packageName).isEmpty();
    }

    public static boolean hasVirtualObbFiles(String packageName) {
        return FileCopyTask.hasValidVirtualObb(packageName);
    }

    public static boolean isGameSetupReady(final Context context, final String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        if (!isInstalledOnHost(context, packageName)) return false;

        boolean installedInBox = false;
        try {
            installedInBox = FeaturesticBox.isInstalled(packageName, 0);
        } catch (Throwable ignored) {
        }
        boolean hasVirtObb = hasVirtualObbFiles(packageName);
        return installedInBox && hasVirtObb;
    }

    public static void markGameSetupState(Context context, String packageName, boolean completed) {
        SharedPreferences sp = context.getSharedPreferences(PREF_SETUP, Context.MODE_PRIVATE);
        sp.edit().putBoolean("setup_done_" + packageName, completed).apply();
    }

    public static void setupGameEnvironment(final Context context, final String packageName, final FeaturesticBox.SetupCallback callback) {
        final Context appCtx = context != null ? context.getApplicationContext() : null;
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                if (callback != null) {
                    mainHandler.post(() -> callback.onProgress(15,
                            "Step 1/3 - Initializing Virtual Container...",
                            "Preparing sandbox for " + packageName));
                }

                if (appCtx != null) {
                    com.featurestic.box.FeaturesticBoxCore.get().ensureInitialized(appCtx);
                    try {
                        com.featurestic.box.utils.compat.CrashShieldAndResourceHealer
                                .healGameResources(appCtx, packageName, 0);
                    } catch (Throwable ignored) {
                    }
                }

                try {
                    String[] pluginFiles = resolveEffectivePluginSoList(appCtx);
                    for (String pluginFile : pluginFiles) {
                        FeaturesticBox.loadCustomLibrary(pluginFile);
                    }
                } catch (Throwable ignored) {
                }

                if (callback != null) {
                    mainHandler.post(() -> callback.onProgress(35,
                            "Step 2/3 - Installing Game and Syncing OBB...",
                            "Cloning APK and copying OBB to /SdCard/Android/obb/" + packageName));
                }

                FileCopyTask task = new FileCopyTask(appCtx);
                task.installOrFixPkg(packageName, (percent, copiedBytes, totalBytes, fileName) -> {
                    if (callback != null) {
                        int overallPct = 40 + (percent * 55 / 100);
                        long copiedMb = copiedBytes / (1024L * 1024L);
                        long totalMb = Math.max(1L, totalBytes / (1024L * 1024L));
                        mainHandler.post(() -> callback.onProgress(overallPct,
                                "Step 2/3 - Copying OBB (" + percent + "%) - " + copiedMb + " / " + totalMb + " MB",
                                fileName));
                    }
                }, (obbSuccess, obbMessage) -> {
                    new Thread(() -> {
                        boolean installed = false;
                        try {
                            installed = FeaturesticBox.isInstalled(packageName, 0);
                        } catch (Throwable ignored) {
                        }
                        boolean obbReady = hasVirtualObbFiles(packageName);

                        if (installed && obbReady) {
                            try {
                                com.featurestic.box.core.env.FeaturesticStorageManager
                                        .syncDataToVirtualIfNeeded(packageName);
                                if (AppConfig.ENABLE_AUTO_120_FPS) {
                                    com.featurestic.box.FeaturesticBoxCore.get().setTargetGameFps(120);
                                }
                                String[] effPlugins = resolveEffectivePluginSoList(appCtx);
                                if (appCtx != null && effPlugins.length > 0) {
                                    try {
                                        java.lang.reflect.Method m = com.featurestic.box.core.RNative.class.getMethod("preparePendingPluginOnWorkerThread", Context.class);
                                        m.invoke(null, appCtx);
                                    } catch (Throwable ignoredPlugin) {
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }

                        final boolean finalSuccess = installed && obbReady;
                        final String finalMsg;
                        if (finalSuccess) {
                            finalMsg = obbMessage != null ? obbMessage : "Game environment ready";
                        } else if (!installed) {
                            finalMsg = obbMessage != null ? obbMessage : "APK clone failed";
                        } else {
                            finalMsg = obbMessage != null ? obbMessage : "OBB copy failed";
                        }

                        if (appCtx != null) {
                            markGameSetupState(appCtx, packageName, finalSuccess);
                        }

                        mainHandler.post(() -> {
                            if (callback != null) {
                                if (finalSuccess) {
                                    callback.onProgress(100, "Setup Complete!",
                                            "APK & OBB synced in /SdCard/Android/obb/" + packageName);
                                }
                                callback.onComplete(finalSuccess, finalMsg);
                            }
                        });
                    }, "Featurestic-Setup-Finalize").start();
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onComplete(false, t.getMessage() != null ? t.getMessage() : "Setup error");
                    }
                });
            }
        }, "Featurestic-Setup-Runner").start();
    }

    /**
     * Launch the virtualized game.
     * Ensures APK is installed in FeaturesticBoxCore and OBB is copied via FileChannel.transferTo()
     * into /storage/emulated/0/SdCard/Android/obb/<pkg>/ before launching.
     */
    public static boolean launchVirtualGame(Context context, String packageName, final Runnable onFinished) {
        try {
            String savedKey = com.rdx.loader.auth.PhpAuthManager.getSavedKey(context);
            if (savedKey != null && !savedKey.isEmpty()) {
                com.rdx.loader.auth.PhpAuthManager.syncLicenseFileForPlugin(savedKey);
            }
        } catch (Throwable ignored) {
        }

        final Context ctx = context.getApplicationContext();
        final String pkg = packageName;
        final Activity act = (context instanceof Activity) ? (Activity) context : null;

        if (act != null && !hasAllFilesPermission(ctx)) {
            act.runOnUiThread(() -> Toast.makeText(ctx,
                    "All Files Access required to copy OBB. Grant permission in Settings.",
                    Toast.LENGTH_LONG).show());
            requestAllFilesPermission(act);
            return false;
        }

        new Thread(() -> {
            try {
                boolean installed = false;
                try {
                    installed = FeaturesticBox.isInstalled(pkg, 0);
                } catch (Throwable ignored) {
                }

                boolean obbReady = hasVirtualObbFiles(pkg);
                if (!installed || !obbReady) {
                    FileCopyTask task = new FileCopyTask(ctx);
                    StringBuilder errOut = new StringBuilder();
                    boolean fixed = task.installOrFixPkgSync(pkg, null, errOut);
                    obbReady = hasVirtualObbFiles(pkg);
                    installed = FeaturesticBox.isInstalled(pkg, 0);
                    if (!fixed || !installed || !obbReady) {
                        final String msg = errOut.length() > 0
                                ? errOut.toString()
                                : "OBB copy failed - check /storage/emulated/0/Android/obb/" + pkg;
                        if (act != null) {
                            act.runOnUiThread(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
                        }
                        return;
                    }
                }

                String[] pluginFiles = resolveEffectivePluginSoList(ctx);
                String soTarget = String.join(";", pluginFiles);

                android.util.Log.d("FeaturesticSO", "Launching game with plugins: " + (pluginFiles.length == 0 ? "NONE" : soTarget));
                boolean launched = FeaturesticBox.startGame(ctx, pkg, 0, soTarget);
                android.util.Log.d("FeaturesticOBB", "startGame launched=" + launched);
                if (!launched && act != null) {
                    act.runOnUiThread(() -> Toast.makeText(ctx,
                            "Failed to launch " + pkg + " inside Virtual Box",
                            Toast.LENGTH_LONG).show());
                }
            } catch (Throwable t) {
                android.util.Log.e("FeaturesticOBB", "launchVirtualGame error: " + t.getMessage());
                if (act != null) {
                    act.runOnUiThread(() -> Toast.makeText(ctx,
                            "Launch error: " + (t.getMessage() != null ? t.getMessage() : "Unknown"),
                            Toast.LENGTH_LONG).show());
                }
            } finally {
                if (onFinished != null) {
                    new Handler(Looper.getMainLooper()).post(onFinished);
                }
            }
        }, "Featurestic-Game-Launch").start();

        return true;
    }

    public static boolean launchVirtualGame(Context context, String packageName) {
        return launchVirtualGame(context, packageName, null);
    }

    /**
     * Standalone OBB sync - copies host OBB into /SdCard/Android/obb/<pkg>.
     * Falls back to saved Android 11-16 SAF tree URI if direct /Android/obb access is blocked.
     */
    public static boolean syncObbNow(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        FileCopyTask task = new FileCopyTask(context);
        if (task.installOrFixPkgSync(packageName, null, null) && hasVirtualObbFiles(packageName)) {
            return true;
        }
        return tryCopyObbFromSavedSaf(context, packageName);
    }

    public static Intent createObbSafPickerIntent(String packageName) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageName != null && !packageName.isEmpty()) {
            Uri initialUri = Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fobb%2F"
                            + Uri.encode(packageName));
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }
        return intent;
    }

    public static boolean tryCopyObbFromSavedSaf(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_SETUP, Context.MODE_PRIVATE);
            String saved = sp.getString("saf_obb_uri_" + packageName, null);
            if (saved != null && !saved.isEmpty()) {
                return copyObbFromSafTreeUri(context, Uri.parse(saved), packageName);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean copyObbFromSafTreeUri(Context context, Uri treeUri, String packageName) {
        if (context == null || treeUri == null || packageName == null || packageName.isEmpty()) return false;
        try {
            try {
                context.getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            context.getSharedPreferences(PREF_SETUP, Context.MODE_PRIVATE)
                    .edit()
                    .putString("saf_obb_uri_" + packageName, treeUri.toString())
                    .apply();

            File dstDir = new File("/storage/emulated/0/SdCard/Android/obb/" + packageName);
            if (!dstDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dstDir.mkdirs();
            }

            String treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            Uri childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
            String[] proj = new String[]{
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE
            };

            boolean copiedAny = false;
            try (android.database.Cursor cursor = context.getContentResolver().query(childrenUri, proj, null, null, null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String docId = cursor.getString(0);
                        String name = cursor.getString(1);
                        long size = cursor.getLong(2);
                        if (name == null || !name.toLowerCase(java.util.Locale.US).endsWith(".obb")) {
                            continue;
                        }
                        File dstFile = new File(dstDir, name);
                        if (dstFile.exists() && size > 0 && dstFile.length() == size) {
                            copiedAny = true;
                            continue;
                        }
                        Uri fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        File tmpFile = new File(dstDir, name + ".tmp");
                        try (java.io.InputStream in = context.getContentResolver().openInputStream(fileUri);
                             java.io.FileOutputStream out = new java.io.FileOutputStream(tmpFile)) {
                            if (in == null) continue;
                            byte[] buf = new byte[1024 * 256];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                out.write(buf, 0, n);
                            }
                            out.flush();
                        }
                        if (dstFile.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            dstFile.delete();
                        }
                        if (tmpFile.renameTo(dstFile) || dstFile.exists()) {
                            copiedAny = true;
                            android.util.Log.i("FeaturesticOBB", "SAF copied OBB: " + dstFile.getAbsolutePath() + " (" + dstFile.length() + " bytes)");
                        }
                    }
                }
            }
            return copiedAny || hasVirtualObbFiles(packageName);
        } catch (Throwable t) {
            android.util.Log.e("FeaturesticOBB", "copyObbFromSafTreeUri error: " + t.getMessage());
            return false;
        }
    }
}
