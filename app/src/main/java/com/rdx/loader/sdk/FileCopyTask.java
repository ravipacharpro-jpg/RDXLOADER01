package com.rdx.loader.sdk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.env.FeaturesticStorageManager;
import com.featurestic.box.entity.pm.InstallResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reliable OBB Installer & Copier for FeaturesticLoader (minSdk 24, targetSdk 28).
 * Flow modeled on BlackBox installOrFixPkg + copyObbPkg using FileChannel.transferTo():
 *   1. Ensure FeaturesticBoxCore is available and package is installed for USER_ID = 0.
 *   2. Copy host OBB from /storage/emulated/0/Android/obb/<pkg>/
 *      to virtual /storage/emulated/0/SdCard/Android/obb/<pkg>/
 *      using 16 MB FileChannel.transferTo() chunks + atomic .tmp rename.
 *   3. Falls back to filename probing (Android 11+ listFiles() == null bypass),
 *      persisted SAF document tree URI (Android 13-16 Scoped Storage), and SDK shell/root fallback.
 */
public class FileCopyTask {

    public static final int USER_ID = 0;
    private static final long MIN_VALID_OBB_BYTES = 1024L * 1024L; // 1 MB
    private static final long CHUNK_SIZE = 16L * 1024L * 1024L;   // 16 MB
    private static final long INACTIVITY_TIMEOUT_MS = 60_000L;    // 60s stall watchdog
    private static final String PREF_SAF = "featurestic_saf_uris";

    public interface ObbCallback {
        void onResult(boolean success, String message);
    }

    public interface ProgressCallback {
        void onProgress(int percent, long copiedBytes, long totalBytes, String fileName);
    }

    public interface CopyCallback {
        void onCopyCompleted(boolean success, String message);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public FileCopyTask(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    public static File getExternalStorageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    public static File getHostObbDir(String packageName) {
        return new File(getExternalStorageRoot(), "Android/obb/" + packageName);
    }

    public static File getExternalObbDir(String packageName) {
        return new File(getExternalStorageRoot(), "SdCard/Android/obb/" + packageName);
    }

    public static boolean hasValidVirtualObb(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        String pkg = packageName.trim();
        File root = getExternalStorageRoot();
        File[] candidateDirs = new File[]{
                new File(root, "Sdcard/Android/obb/" + pkg),
                new File(root, "SdCard/Android/obb/" + pkg)
        };

        for (File dir : candidateDirs) {
            if (dir == null || !dir.exists()) continue;
            cleanupTempFiles(dir);
            File[] already = dir.listFiles((d, name) ->
                    name != null && name.toLowerCase(Locale.US).endsWith(".obb"));
            if (already != null) {
                for (File f : already) {
                    if (f != null && f.isFile()) {
                        if (f.length() >= MIN_VALID_OBB_BYTES) {
                            return true;
                        } else if (f.length() == 0L) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void migrateLegacyLowercaseDir(String pkg, File destination) {
        try {
            File root = getExternalStorageRoot();
            File[] legacyDirs = new File[]{
                    new File(root, "Sdcard/Android/obb/" + pkg),
                    new File(root, "SdCard/0/Android/obb/" + pkg)
            };
            String destCanon = destination.getCanonicalPath();
            for (File legacy : legacyDirs) {
                if (!legacy.exists() || !legacy.isDirectory()) continue;
                if (legacy.getCanonicalPath().equals(destCanon)) continue;
                File[] files = legacy.listFiles((dir, name) ->
                        name != null && name.toLowerCase(Locale.US).endsWith(".obb"));
                if (files == null) continue;
                for (File lf : files) {
                    if (lf != null && lf.isFile() && lf.length() >= MIN_VALID_OBB_BYTES) {
                        if (!destination.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            destination.mkdirs();
                        }
                        File target = new File(destination, lf.getName());
                        if (!target.exists() || target.length() != lf.length()) {
                            //noinspection ResultOfMethodCallIgnored
                            lf.renameTo(target);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cleanupTempFiles(File dir) {
        try {
            if (dir == null || !dir.exists() || !dir.isDirectory()) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f != null && f.isFile()) {
                    String n = f.getName().toLowerCase(Locale.US);
                    if (n.endsWith(".obb.tmp") || n.endsWith(".obb.copying")) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isObbCopied(String packageName) {
        return hasValidVirtualObb(packageName);
    }

    public boolean hasHostObb(String packageName) {
        return !findSourceObbFiles(context, packageName).isEmpty();
    }

    public static boolean hasAllFilesPermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (Build.VERSION.SDK_INT >= 23 && ctx != null) {
            return ctx.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static void requestAllFilesPermission(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                } catch (Throwable ignored) {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Throwable ignored2) {
                    }
                }
            }
        }
    }

    public static void saveSafTreeUri(Context ctx, String pkg, Uri treeUri) {
        if (ctx == null || pkg == null || treeUri == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREF_SAF, Context.MODE_PRIVATE);
        sp.edit().putString("tree_" + pkg, treeUri.toString()).apply();
    }

    public static Uri getSavedSafTreeUri(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return null;
        SharedPreferences sp = ctx.getSharedPreferences(PREF_SAF, Context.MODE_PRIVATE);
        String raw = sp.getString("tree_" + pkg, null);
        if (raw == null || raw.isEmpty()) {
            raw = sp.getString("tree_obb_root", null);
        }
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Uri.parse(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void saveSafRootUri(Context ctx, Uri treeUri) {
        if (ctx == null || treeUri == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREF_SAF, Context.MODE_PRIVATE);
        sp.edit().putString("tree_obb_root", treeUri.toString()).apply();
    }

    /**
     * Legacy async wrapper preserved for compatibility.
     */
    public void copyObbFolderAsync(final String packageName, final CopyCallback callback) {
        copyObbPkg(packageName, null, (success, message) -> {
            if (callback != null) {
                callback.onCopyCompleted(success, message);
            }
        });
    }

    /**
     * Installs package in FeaturesticBoxCore (if not installed) and copies OBB to
     * /storage/emulated/0/Sdcard/Android/obb/<pkg>/ (per /storage/emulated/0/obb.txt).
     */
    public void installOrFixPkg(final String pkg, final ProgressCallback progressCallback, final ObbCallback callback) {
        new Thread(() -> {
            try {
                if (FeaturesticBoxCore.get() == null) {
                    postResult(callback, false, "FeaturesticBoxCore unavailable");
                    return;
                }

                if (context != null) {
                    FeaturesticBoxCore.get().ensureInitialized(context);
                }

                if (!FeaturesticBoxCore.get().isInstalled(pkg, USER_ID)) {
                    InstallResult result = FeaturesticBoxCore.get().installPackageAsUser(pkg, USER_ID);
                    if ((result == null || !result.success) && !FeaturesticBoxCore.get().isInstalled(pkg, USER_ID)) {
                        String hostApk = com.featurestic.sdk.FeaturesticBox.findHostApkPath(context, pkg);
                        if (hostApk != null && !hostApk.isEmpty()) {
                            result = FeaturesticBoxCore.get().installPackageAsUser(hostApk, USER_ID);
                        }
                    }
                    if (!FeaturesticBoxCore.get().isInstalled(pkg, USER_ID)
                            && (result == null || !result.success)) {
                        String err = (result != null && result.msg != null && !result.msg.isEmpty())
                                ? result.msg : "Install failed";
                        postResult(callback, false, "Install failed: " + err);
                        return;
                    }
                }

                copyObbPkg(pkg, progressCallback, callback);

            } catch (Throwable e) {
                postResult(callback, false, e.getMessage() == null ? "Install error" : e.getMessage());
            }
        }, "Featurestic-InstallOrFix").start();
    }

    /**
     * Synchronous helper for worker threads (used by launchVirtualGame / setupGameEnvironment).
     */
    public boolean installOrFixPkgSync(final String pkg, final ProgressCallback progressCallback, final StringBuilder outMessage) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        final AtomicReference<String> msgRef = new AtomicReference<>("");
        installOrFixPkg(pkg, progressCallback, (success, message) -> {
            ok.set(success);
            msgRef.set(message != null ? message : "");
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (outMessage != null) outMessage.append("Interrupted");
            return false;
        }
        if (outMessage != null) outMessage.append(msgRef.get());
        return ok.get();
    }

    public void copyObbPkg(final String pkg, final ProgressCallback progressCallback, final ObbCallback callback) {
        File root = Environment.getExternalStorageDirectory();
        File source = new File(root, "Android/obb/" + pkg);
        File destination = new File(root, "SdCard/Android/obb/" + pkg);
        if (!destination.exists()) {
            destination.mkdirs();
        }
        File legacyDest = new File(root, "Sdcard/Android/obb/" + pkg);
        // Migrate any file from legacy lowercase Sdcard into SdCard
        if (legacyDest.isDirectory()) {
            File[] legFiles = legacyDest.listFiles();
            if (legFiles != null) {
                for (File lf : legFiles) {
                    if (lf != null && lf.isFile() && lf.length() >= MIN_VALID_OBB_BYTES) {
                        File target = new File(destination, lf.getName());
                        if (!target.exists() || target.length() != lf.length()) {
                            lf.renameTo(target);
                        }
                    }
                }
            }
        }

        File[] already = destination.listFiles((dir, name) ->
                name != null && name.toLowerCase(Locale.US).endsWith(".obb"));

        if (already != null && already.length > 0) {
            boolean hasValid = false;
            for (File f : already) {
                if (f != null && f.isFile() && f.length() >= MIN_VALID_OBB_BYTES) {
                    hasValid = true;
                    break;
                }
            }
            if (hasValid) {
                if (progressCallback != null) {
                    progressCallback.onProgress(100, 1L, 1L, "OBB already available");
                }
                postResult(callback, true, "OBB already available");
                return;
            }
        }

        final AtomicBoolean finished = new AtomicBoolean(false);
        final Handler obbHandler = new Handler(Looper.getMainLooper());
        obbHandler.postDelayed(() -> {
            if (finished.compareAndSet(false, true)) {
                postResult(callback, false, "OBB copy timed out");
            }
        }, 60000);

        new Thread(() -> {
            try {
                File[] sourceFiles = source.listFiles((dir, name) ->
                        name != null && name.toLowerCase(Locale.US).endsWith(".obb"));

                if (sourceFiles == null || sourceFiles.length == 0) {
                    List<File> probed = findSourceObbFiles(context, pkg);
                    if (!probed.isEmpty()) {
                        sourceFiles = probed.toArray(new File[0]);
                    }
                }

                if (sourceFiles == null || sourceFiles.length == 0) {
                    if (finished.compareAndSet(false, true)) {
                        postResult(callback, false, "Source OBB not found");
                    }
                    return;
                }

                for (File src : sourceFiles) {
                    if (finished.get()) return;

                    File dest = new File(destination, src.getName());
                    long size = src.length();
                    if (dest.isFile() && dest.length() == size && size >= MIN_VALID_OBB_BYTES) {
                        continue;
                    }

                    try (
                            FileChannel in = new FileInputStream(src).getChannel();
                            FileChannel out = new FileOutputStream(dest).getChannel()
                    ) {
                        long position = 0;
                        long lastUiMs = 0;

                        while (position < size) {
                            if (finished.get()) {
                                break;
                            }
                            long transferred = in.transferTo(
                                    position,
                                    Math.min(CHUNK_SIZE, size - position),
                                    out
                            );

                            if (transferred <= 0) {
                                break;
                            }

                            position += transferred;
                            long now = System.currentTimeMillis();
                            if (progressCallback != null && (now - lastUiMs >= 80 || position >= size)) {
                                lastUiMs = now;
                                int pct = (int) Math.min(100L, (position * 100L) / Math.max(1L, size));
                                progressCallback.onProgress(pct, position, size, src.getName());
                            }
                        }
                    }

                }

                if (finished.compareAndSet(false, true)) {
                    postResult(callback, true, "OBB copied successfully");
                }

            } catch (Throwable e) {
                if (finished.compareAndSet(false, true)) {
                    postResult(callback, false, e.getMessage() == null ? "OBB copy failed" : e.getMessage());
                }
            }
        }, "Featurestic-ObbCopy").start();
    }

    private static boolean isDestinationComplete(File destination, List<File> sourceFiles) {
        File[] already = destination.listFiles((dir, name) ->
                name != null && name.toLowerCase(Locale.US).endsWith(".obb"));
        if (already == null || already.length == 0) return false;

        boolean hasValidDest = false;
        for (File f : already) {
            if (f != null && f.isFile() && f.length() >= MIN_VALID_OBB_BYTES) {
                hasValidDest = true;
                break;
            }
        }
        if (!hasValidDest) return false;

        if (sourceFiles != null && !sourceFiles.isEmpty()) {
            for (File src : sourceFiles) {
                File match = new File(destination, src.getName());
                if (!match.isFile() || match.length() != src.length()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Finds host OBB files using:
     *   1. Direct source.listFiles() on /storage/emulated/0/Android/obb/<pkg> (5_6316547276252652180.txt)
     *   2. Known/versionCode filename probing (bypasses Android 11+ listFiles() == null)
     *   3. SDK FeaturesticStorageManager.discoverHostObbFiles()
     */
    public static List<File> findSourceObbFiles(Context ctx, String pkg) {
        Map<String, File> byName = new LinkedHashMap<>();
        if (pkg == null || pkg.trim().isEmpty()) return new ArrayList<>();
        String cleanPkg = pkg.trim();

        File source = getHostObbDir(cleanPkg);
        File[] sourceFiles = source.listFiles((dir, name) ->
                name != null && name.toLowerCase(Locale.US).endsWith(".obb"));
        if (sourceFiles != null) {
            for (File f : sourceFiles) {
                if (f != null && f.isFile() && f.length() >= MIN_VALID_OBB_BYTES) {
                    byName.put(f.getName(), f);
                }
            }
        }

        if (byName.isEmpty() && ctx != null) {
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(cleanPkg, 0);
                if (pi != null && pi.versionCode > 0) {
                    int vc = pi.versionCode;
                    for (int delta = 10; delta >= -50; delta--) {
                        int candidateVc = vc + delta;
                        if (candidateVc <= 0) continue;
                        addIfValid(source, "main." + candidateVc + "." + cleanPkg + ".obb", byName);
                        addIfValid(source, "patch." + candidateVc + "." + cleanPkg + ".obb", byName);
                    }
                    for (int div : new int[]{10, 100}) {
                        int baseVc = vc / div;
                        if (baseVc > 1000) {
                            for (int d = 5; d >= -25; d--) {
                                addIfValid(source, "main." + (baseVc + d) + "." + cleanPkg + ".obb", byName);
                                addIfValid(source, "patch." + (baseVc + d) + "." + cleanPkg + ".obb", byName);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (byName.isEmpty()) {
            try {
                List<File> sdkFound = FeaturesticStorageManager.discoverHostObbFiles(ctx, cleanPkg);
                if (sdkFound != null) {
                    for (File f : sdkFound) {
                        if (f != null && f.isFile() && f.length() >= MIN_VALID_OBB_BYTES && !byName.containsKey(f.getName())) {
                            byName.put(f.getName(), f);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return new ArrayList<>(byName.values());
    }

    private static void addIfValid(File dir, String name, Map<String, File> map) {
        if (map.containsKey(name)) return;
        File f = new File(dir, name);
        if (f.isFile() && f.length() >= MIN_VALID_OBB_BYTES) {
            map.put(name, f);
        }
    }

    private static boolean copyFromSavedSafTree(Context ctx, String pkg, File destination,
                                                AtomicLong lastActivityMs, ProgressCallback progressCallback) {
        Uri treeUri = getSavedSafTreeUri(ctx, pkg);
        if (treeUri == null) return false;
        try {
            String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
            List<String[]> obbDocs = new ArrayList<>(); // {docId, name, sizeStr}

            try (Cursor c = ctx.getContentResolver().query(childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_SIZE
                    }, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String docId = c.getString(0);
                        String name = c.getString(1);
                        long size = c.getLong(2);
                        if (name != null && name.toLowerCase(Locale.US).endsWith(".obb") && size >= MIN_VALID_OBB_BYTES) {
                            if (name.contains(pkg) || treeDocId.endsWith(pkg)) {
                                obbDocs.add(new String[]{docId, name, String.valueOf(size)});
                            }
                        } else if (name != null && name.equals(pkg) && docId != null) {
                            // Tree root is Android/obb, descend into <pkg>
                            Uri subChildren = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
                            try (Cursor sc = ctx.getContentResolver().query(subChildren,
                                    new String[]{
                                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                            DocumentsContract.Document.COLUMN_SIZE
                                    }, null, null, null)) {
                                if (sc != null) {
                                    while (sc.moveToNext()) {
                                        String sDocId = sc.getString(0);
                                        String sName = sc.getString(1);
                                        long sSize = sc.getLong(2);
                                        if (sName != null && sName.toLowerCase(Locale.US).endsWith(".obb") && sSize >= MIN_VALID_OBB_BYTES) {
                                            obbDocs.add(new String[]{sDocId, sName, String.valueOf(sSize)});
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (obbDocs.isEmpty()) return false;

            long totalBytes = 0L;
            for (String[] d : obbDocs) {
                totalBytes += Math.max(1L, Long.parseLong(d[2]));
            }
            long copiedBytes = 0L;
            byte[] buf = new byte[524288];

            for (String[] d : obbDocs) {
                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, d[0]);
                String fileName = d[1];
                long expectedSize = Long.parseLong(d[2]);
                File dest = new File(destination, fileName);
                if (dest.isFile() && dest.length() == expectedSize) {
                    copiedBytes += expectedSize;
                    continue;
                }
                File tmp = new File(destination, fileName + ".tmp");
                if (tmp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                try (InputStream in = ctx.getContentResolver().openInputStream(fileUri);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) return false;
                    int read;
                    long lastUi = 0L;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        copiedBytes += read;
                        lastActivityMs.set(System.currentTimeMillis());
                        long now = System.currentTimeMillis();
                        if (progressCallback != null && (now - lastUi >= 80L || copiedBytes >= totalBytes)) {
                            lastUi = now;
                            int pct = (int) Math.min(100L, (copiedBytes * 100L) / Math.max(1L, totalBytes));
                            progressCallback.onProgress(pct, copiedBytes, totalBytes, fileName);
                        }
                    }
                    out.flush();
                }
                if (tmp.length() >= MIN_VALID_OBB_BYTES) {
                    if (dest.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        dest.delete();
                    }
                    if (!tmp.renameTo(dest)) return false;
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void finish(AtomicBoolean finished, Handler handler, Runnable watchdog,
                        ObbCallback callback, boolean success, String message) {
        if (finished.compareAndSet(false, true)) {
            handler.removeCallbacks(watchdog);
            postResult(callback, success, message);
        }
    }

    private void postResult(final ObbCallback callback, final boolean success, final String message) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onResult(success, message));
    }
}
