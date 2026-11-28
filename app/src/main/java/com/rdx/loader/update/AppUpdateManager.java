package com.rdx.loader.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.rdx.loader.AppConfig;
import com.rdx.loader.auth.PhpAuthManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class AppUpdateManager {

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static boolean sIsDialogShowing = false;

    private AppUpdateManager() {}

    public static void checkAndShowUpdate(final Activity activity) {
        if (activity == null || activity.isFinishing() || sIsDialogShowing) return;

        String latestVer = PhpAuthManager.getUpdateVersion(activity);
        String downloadUrl = PhpAuthManager.getUpdateUrl(activity);
        boolean forceUpdate = PhpAuthManager.isForceUpdate(activity);

        if (latestVer == null || latestVer.trim().isEmpty() || downloadUrl == null || downloadUrl.trim().isEmpty()) {
            return;
        }

        String currentVer = getCurrentVersionName(activity);
        if (isNewerVersion(currentVer, latestVer)) {
            showUpdateDialog(activity, latestVer, downloadUrl, "Latest security improvements and bug fixes.", forceUpdate);
        }
    }

    public static boolean isNewerVersion(String currentVer, String latestVer) {
        if (currentVer == null || latestVer == null) return false;
        try {
            String[] curParts = currentVer.trim().replace("v", "").split("\\.");
            String[] latParts = latestVer.trim().replace("v", "").split("\\.");
            int length = Math.max(curParts.length, latParts.length);
            for (int i = 0; i < length; i++) {
                int cur = i < curParts.length ? Integer.parseInt(curParts[i].replaceAll("[^0-9]", "")) : 0;
                int lat = i < latParts.length ? Integer.parseInt(latParts[i].replaceAll("[^0-9]", "")) : 0;
                if (lat > cur) return true;
                if (lat < cur) return false;
            }
            return false;
        } catch (Throwable t) {
            return !currentVer.trim().equalsIgnoreCase(latestVer.trim());
        }
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "0.0.1";
        } catch (Throwable t) {
            return "0.0.1";
        }
    }

    public static void showUpdateDialog(final Activity activity, final String latestVer,
                                        final String apkUrl, final String changelog,
                                        final boolean forceUpdate) {
        if (activity == null || activity.isFinishing() || sIsDialogShowing) return;
        sIsDialogShowing = true;

        sMainHandler.post(() -> {
            try {
                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                builder.setCancelable(!forceUpdate);

                LinearLayout root = new LinearLayout(activity);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setPadding(56, 50, 56, 44);

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#141824"));
                bg.setCornerRadius(36f);
                bg.setStroke(3, Color.parseColor("#E53935"));
                root.setBackground(bg);

                TextView tvTitle = new TextView(activity);
                tvTitle.setText(forceUpdate ? "MANDATORY UPDATE REQUIRED" : "NEW UPDATE AVAILABLE");
                tvTitle.setTextColor(Color.parseColor("#E53935"));
                tvTitle.setTextSize(18f);
                tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                tvTitle.setGravity(Gravity.CENTER);
                root.addView(tvTitle);

                TextView tvSub = new TextView(activity);
                tvSub.setText("Version " + latestVer + " is now live on the panel");
                tvSub.setTextColor(Color.parseColor("#94A3B8"));
                tvSub.setTextSize(12f);
                tvSub.setGravity(Gravity.CENTER);
                tvSub.setPadding(0, 10, 0, 24);
                root.addView(tvSub);

                TextView tvNotes = new TextView(activity);
                tvNotes.setText(changelog != null && !changelog.isEmpty() ? changelog : "Please update to continue playing securely.");
                tvNotes.setTextColor(Color.parseColor("#F1F5F9"));
                tvNotes.setTextSize(13f);
                tvNotes.setLineSpacing(6f, 1f);
                tvNotes.setPadding(0, 0, 0, 28);
                root.addView(tvNotes);

                ProgressBar pb = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                pb.setIndeterminate(false);
                pb.setMax(100);
                pb.setProgress(0);
                pb.setVisibility(LinearLayout.GONE);
                root.addView(pb);

                TextView tvProgress = new TextView(activity);
                tvProgress.setTextColor(Color.parseColor("#94A3B8"));
                tvProgress.setTextSize(12f);
                tvProgress.setGravity(Gravity.CENTER);
                tvProgress.setVisibility(LinearLayout.GONE);
                tvProgress.setPadding(0, 8, 0, 16);
                root.addView(tvProgress);

                Button btnUpdate = new Button(activity);
                btnUpdate.setText("DOWNLOAD AND INSTALL");
                btnUpdate.setTextColor(Color.WHITE);
                btnUpdate.setTextSize(14f);
                btnUpdate.setTypeface(null, android.graphics.Typeface.BOLD);

                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setColor(Color.parseColor("#E53935"));
                btnBg.setCornerRadius(20f);
                btnUpdate.setBackground(btnBg);
                root.addView(btnUpdate);

                builder.setView(root);
                AlertDialog dialog = builder.create();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                }
                dialog.setCanceledOnTouchOutside(!forceUpdate);
                dialog.setOnDismissListener(d -> sIsDialogShowing = false);

                btnUpdate.setOnClickListener(v -> {
                    btnUpdate.setEnabled(false);
                    btnUpdate.setText("DOWNLOADING...");
                    pb.setVisibility(LinearLayout.VISIBLE);
                    tvProgress.setVisibility(LinearLayout.VISIBLE);
                    startApkDownload(activity, apkUrl, dialog, pb, tvProgress, btnUpdate);
                });

                dialog.show();

            } catch (Throwable ignored) {
                sIsDialogShowing = false;
            }
        });
    }

    private static void startApkDownload(final Activity activity, final String apkUrl,
                                         final AlertDialog dialog, final ProgressBar pb,
                                         final TextView tvProgress, final Button btnUpdate) {
        new Thread(() -> {
            File tempApk = null;
            try {
                URL url = new URL(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307) {
                    String redirectUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    url = new URL(redirectUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.connect();
                }

                int length = conn.getContentLength();
                InputStream is = conn.getInputStream();

                File cacheDir = activity.getExternalCacheDir();
                if (cacheDir == null) cacheDir = activity.getCacheDir();
                File updatesDir = new File(cacheDir, "updates");
                if (!updatesDir.exists()) updatesDir.mkdirs();

                tempApk = new File(updatesDir, "RDXLoader_Update.apk");
                if (tempApk.exists()) tempApk.delete();

                FileOutputStream fos = new FileOutputStream(tempApk);
                byte[] buffer = new byte[16384];
                int read;
                long totalCopied = 0;
                long lastUiMs = 0;

                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                    totalCopied += read;
                    long now = System.currentTimeMillis();
                    if (now - lastUiMs >= 80 || (length > 0 && totalCopied >= length)) {
                        lastUiMs = now;
                        final int progress = length > 0 ? (int) ((totalCopied * 100) / length) : 0;
                        final long copiedMb = totalCopied / (1024 * 1024);
                        final long totalMb = length > 0 ? length / (1024 * 1024) : 0;
                        sMainHandler.post(() -> {
                            if (pb != null && progress > 0) pb.setProgress(progress);
                            if (tvProgress != null) {
                                if (totalMb > 0) {
                                    tvProgress.setText("Downloading: " + progress + "% (" + copiedMb + "/" + totalMb + " MB)");
                                } else {
                                    tvProgress.setText("Downloading: " + (totalCopied / 1024) + " KB...");
                                }
                            }
                        });
                    }
                }
                fos.flush();
                fos.close();
                is.close();
                conn.disconnect();

                final File finalApk = tempApk;
                sMainHandler.post(() -> {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {}
                    launchApkInstaller(activity, finalApk);
                });

            } catch (Throwable t) {
                if (tempApk != null && tempApk.exists()) tempApk.delete();
                sMainHandler.post(() -> {
                    if (btnUpdate != null) {
                        btnUpdate.setEnabled(true);
                        btnUpdate.setText("RETRY DOWNLOAD");
                    }
                    if (tvProgress != null) {
                        tvProgress.setText("Download failed: " + t.getMessage());
                    }
                    Toast.makeText(activity, "Download error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "RDX-DownloadApk").start();
    }

    private static void launchApkInstaller(Activity activity, File apkFile) {
        if (activity == null || apkFile == null || !apkFile.exists()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(activity, "Installer launch failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
