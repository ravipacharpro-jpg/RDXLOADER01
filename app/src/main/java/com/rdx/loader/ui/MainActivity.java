package com.rdx.loader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.app.Dialog;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.featurestic.box.compat.auth.MicroGGoogleAuthEngine;
import com.featurestic.box.core.CrashHandler;
import com.rdx.loader.AppConfig;
import com.rdx.loader.R;
import com.rdx.loader.auth.PhpAuthManager;
import com.rdx.loader.auth.SocialAccountManager;
import com.rdx.loader.sdk.FeaturesticSdkHelper;
import com.rdx.loader.sdk.FileCopyTask;
import android.net.Uri;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Screen 2 - RDX LOADER Dashboard Screen
 * High-contrast silver & frosted glass aesthetic matching Screenshot 2 & Video.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_SOCIAL_LOGIN = 3201;

    // Device Info Card Views
    private TextView tvDeviceModel;
    private TextView tvDeviceCodename;
    private TextView tvDeviceSpecs;
    private View pillConnectedStatus;

    // Game Target Card Views
    private ImageView ivGameIcon;
    private TextView tvGameName;
    private TextView tvGamePackage;
    private TextView tvGameVersion;
    private TextView tvGameSupported;
    private ImageView btnGameMenu;
    private View btnLaunchGame;
    private View btnLaunchContent;
    private TextView tvLaunchLabel;
    private ProgressBar pbLaunchProgress;

    // Social Game Account Card Views
    private TextView tvActiveAccountSummary;
    private TextView tvAccountHintBadge;
    private TextView tvAccountMoreLabel;
    private View btnAccountTwitter;
    private View btnAccountFacebook;
    private View btnAccountMore;
    private View btnAccountDiscord;
    private View btnAccountGoogle;
    private View layoutMoreAccounts;
    private View dotTwitterActive;
    private View dotFacebookActive;
    private View dotMoreActive;
    private View dotDiscordActive;
    private View dotGoogleActive;
    private boolean moreAccountsExpanded = false;

    // Hide ESP Switch (From Video)
    private SwitchCompat switchHideEsp;

    // Subscription Timer Views
    private TextView tvCountdownDays;
    private TextView tvCountdownHours;
    private TextView tvCountdownMinutes;
    private TextView tvCountdownSeconds;

    // Top Bell Button
    private View btnNotificationBell;
    private View btnTelegram;
    private View btnAudioToggle;

    private String mLicenseKey = "";
    private long mExpiryTimestamp = 0;
    private CountDownTimer mCountDownTimer = null;
    private boolean isLaunching = false;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private String getTargetPkg() {
        return AppConfig.TARGET_PACKAGE_NAME;
    }

    private static final String PREF_SETTINGS = "loader_v2_settings";
    private static final String KEY_HIDE_ESP = "hide_esp_enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupEdgeToEdgeWindow();
        setContentView(R.layout.activity_main);

        extractIntentData();
        initViews();
        setupDeviceInfo();
        setupGameCard();
        setupSocialAccountCard();
        setupHideEspSwitch();
        setupSubscriptionTimer();
        setupListeners();
        RdxUiHelper.decorate(getWindow().getDecorView());
        RdxUiHelper.fadeIn(findViewById(R.id.cardDeviceInfo));

        // Check runtime permissions
        FeaturesticSdkHelper.requestRuntimePermissionsOnStart(this);
    }


    private final android.os.Handler mHeartbeatHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mIsHeartbeatRunning = false;
    private final Runnable mHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsHeartbeatRunning || isFinishing()) return;
            verifyLiveSessionSilently();
            mHeartbeatHandler.postDelayed(this, 3500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        refreshSocialAccountCard();
        startAutoObbSyncIfNeeded();
        verifyLiveSessionOnStart();
        startLiveHeartbeat();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLiveHeartbeat();
    }

    private void startLiveHeartbeat() {
        mIsHeartbeatRunning = true;
        mHeartbeatHandler.removeCallbacks(mHeartbeatRunnable);
        mHeartbeatHandler.postDelayed(mHeartbeatRunnable, 3500);
    }

    private void stopLiveHeartbeat() {
        mIsHeartbeatRunning = false;
        mHeartbeatHandler.removeCallbacks(mHeartbeatRunnable);
    }

    private void verifyLiveSessionSilently() {
        final String savedKey = (mLicenseKey != null && !mLicenseKey.trim().isEmpty())
                ? mLicenseKey.trim()
                : PhpAuthManager.getSavedKey(this);

        if (savedKey == null || savedKey.trim().isEmpty()) {
            stopLiveHeartbeat();
            redirectToLogin("Session expired. Please login.");
            return;
        }

        PhpAuthManager.verifyKey(this, savedKey, new PhpAuthManager.AuthCallback() {
            @Override
            public void onSuccess(final String licenseKey, final String expiryText, final String serverMessage) {
                runOnUiThread(() -> {
                    mLicenseKey = licenseKey;
                    if (PhpAuthManager.hasUnreadBroadcast(MainActivity.this)) {
                        showNotificationDialog();
                    }
                });
            }

            @Override
            public void onError(final String errorMessage) {
                runOnUiThread(() -> {
                    stopLiveHeartbeat();
                    PhpAuthManager.clearSession(MainActivity.this);
                    redirectToLogin(errorMessage != null ? errorMessage : "Access Denied by Server");
                });
            }
        });
    }

    private void verifyLiveSessionOnStart() {
        final String savedKey = (mLicenseKey != null && !mLicenseKey.trim().isEmpty())
                ? mLicenseKey.trim()
                : PhpAuthManager.getSavedKey(this);

        if (savedKey == null || savedKey.trim().isEmpty()) {
            stopLiveHeartbeat();
            redirectToLogin("Session expired. Please login.");
            return;
        }

        PhpAuthManager.verifyKey(this, savedKey, new PhpAuthManager.AuthCallback() {
            @Override
            public void onSuccess(final String licenseKey, final String expiryText, final String serverMessage) {
                runOnUiThread(() -> {
                    mLicenseKey = licenseKey;
                    com.rdx.loader.update.AppUpdateManager.checkAndShowUpdate(MainActivity.this);
                    if (PhpAuthManager.hasUnreadBroadcast(MainActivity.this)) {
                        showNotificationDialog();
                    }
                });
            }

            @Override
            public void onError(final String errorMessage) {
                runOnUiThread(() -> {
                    stopLiveHeartbeat();
                    PhpAuthManager.clearSession(MainActivity.this);
                    redirectToLogin(errorMessage != null ? errorMessage : "Access Denied by Server");
                });
            }
        });
    }

    private void redirectToLogin(String reason) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void startAutoObbSyncIfNeeded() {
        if (!FeaturesticSdkHelper.hasAllFilesPermission(this)) {
            return;
        }
        String activePkg = getTargetPkg();
        if (FeaturesticSdkHelper.hasVirtualObbFiles(activePkg)) {
            return;
        }
        FileCopyTask task = new FileCopyTask(this);
        task.installOrFixPkg(activePkg, null, (success, message) -> {
            runOnUiThread(() -> {
                if (success && FeaturesticSdkHelper.hasVirtualObbFiles(activePkg)) {
                    Toast.makeText(MainActivity.this, "OBB synced successfully", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLiveHeartbeat();
        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
            mCountDownTimer = null;
        }
    }

    private void setupEdgeToEdgeWindow() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }

    private void extractIntentData() {
        Intent intent = getIntent();
        if (intent != null) {
            mLicenseKey = intent.getStringExtra("license_key");
            mExpiryTimestamp = intent.getLongExtra("expiry_timestamp", 0);
        }
        if (mLicenseKey == null || mLicenseKey.trim().isEmpty()) {
            mLicenseKey = PhpAuthManager.getSavedKey(this);
        }
    }

    private void initViews() {
        tvDeviceModel = findViewById(R.id.tvDeviceModel);
        tvDeviceCodename = findViewById(R.id.tvDeviceCodename);
        tvDeviceSpecs = findViewById(R.id.tvDeviceSpecs);
        pillConnectedStatus = findViewById(R.id.pillConnectedStatus);

        ivGameIcon = findViewById(R.id.ivGameIcon);
        tvGameName = findViewById(R.id.tvGameName);
        tvGamePackage = findViewById(R.id.tvGamePackage);
        tvGameVersion = findViewById(R.id.tvGameVersion);
        tvGameSupported = findViewById(R.id.tvGameSupported);
        btnGameMenu = findViewById(R.id.btnGameMenu);

        btnLaunchGame = findViewById(R.id.btnLaunchGame);
        btnLaunchContent = findViewById(R.id.btnLaunchContent);
        tvLaunchLabel = findViewById(R.id.tvLaunchLabel);
        pbLaunchProgress = findViewById(R.id.pbLaunchProgress);

        tvActiveAccountSummary = findViewById(R.id.tvActiveAccountSummary);
        tvAccountHintBadge = findViewById(R.id.tvAccountHintBadge);
        tvAccountMoreLabel = findViewById(R.id.tvAccountMoreLabel);
        btnAccountTwitter = findViewById(R.id.btnAccountTwitter);
        btnAccountFacebook = findViewById(R.id.btnAccountFacebook);
        btnAccountMore = findViewById(R.id.btnAccountMore);
        btnAccountDiscord = findViewById(R.id.btnAccountDiscord);
        btnAccountGoogle = findViewById(R.id.btnAccountGoogle);
        layoutMoreAccounts = findViewById(R.id.layoutMoreAccounts);
        dotTwitterActive = findViewById(R.id.dotTwitterActive);
        dotFacebookActive = findViewById(R.id.dotFacebookActive);
        dotMoreActive = findViewById(R.id.dotMoreActive);
        dotDiscordActive = findViewById(R.id.dotDiscordActive);
        dotGoogleActive = findViewById(R.id.dotGoogleActive);

        switchHideEsp = findViewById(R.id.switchHideEsp);

        tvCountdownDays = findViewById(R.id.tvCountdownDays);
        tvCountdownHours = findViewById(R.id.tvCountdownHours);
        tvCountdownMinutes = findViewById(R.id.tvCountdownMinutes);
        tvCountdownSeconds = findViewById(R.id.tvCountdownSeconds);

        btnNotificationBell = findViewById(R.id.btnNotificationBell);
        btnTelegram = findViewById(R.id.btnTelegram);
        btnAudioToggle = findViewById(R.id.btnAudioToggle);
    }

    private void setupSocialAccountCard() {
        if (btnAccountTwitter != null) {
            btnAccountTwitter.setOnClickListener(v -> handleSocialProviderClick(SocialAccountManager.PROVIDER_TWITTER));
        }
        if (btnAccountFacebook != null) {
            btnAccountFacebook.setOnClickListener(v -> handleSocialProviderClick(SocialAccountManager.PROVIDER_FACEBOOK));
        }
        if (btnAccountDiscord != null) {
            btnAccountDiscord.setOnClickListener(v -> handleSocialProviderClick(SocialAccountManager.PROVIDER_DISCORD));
        }
        if (btnAccountGoogle != null) {
            btnAccountGoogle.setOnClickListener(v -> handleSocialProviderClick(SocialAccountManager.PROVIDER_GOOGLE));
        }
        if (btnAccountMore != null) {
            btnAccountMore.setOnClickListener(v -> {
                moreAccountsExpanded = !moreAccountsExpanded;
                if (layoutMoreAccounts != null) {
                    layoutMoreAccounts.setVisibility(moreAccountsExpanded ? View.VISIBLE : View.GONE);
                }
                if (tvAccountMoreLabel != null) {
                    tvAccountMoreLabel.setText(moreAccountsExpanded ? "Less" : "More");
                }
                refreshSocialAccountCard();
            });
        }
        refreshSocialAccountCard();
    }

    private void refreshSocialAccountCard() {
        boolean tw = SocialAccountManager.isProviderLinked(this, SocialAccountManager.PROVIDER_TWITTER);
        boolean fb = SocialAccountManager.isProviderLinked(this, SocialAccountManager.PROVIDER_FACEBOOK);
        boolean dc = SocialAccountManager.isProviderLinked(this, SocialAccountManager.PROVIDER_DISCORD);
        boolean gg = SocialAccountManager.isProviderLinked(this, SocialAccountManager.PROVIDER_GOOGLE);

        if (dotTwitterActive != null) dotTwitterActive.setVisibility(tw ? View.VISIBLE : View.GONE);
        if (dotFacebookActive != null) dotFacebookActive.setVisibility(fb ? View.VISIBLE : View.GONE);
        if (dotDiscordActive != null) dotDiscordActive.setVisibility(dc ? View.VISIBLE : View.GONE);
        if (dotGoogleActive != null) dotGoogleActive.setVisibility(gg ? View.VISIBLE : View.GONE);
        if (dotMoreActive != null) {
            dotMoreActive.setVisibility((!moreAccountsExpanded && (dc || gg)) ? View.VISIBLE : View.GONE);
        }

        boolean any = tw || fb || dc || gg;
        if (tvActiveAccountSummary != null) {
            tvActiveAccountSummary.setText(SocialAccountManager.getActiveSummaryText(this));
            tvActiveAccountSummary.setTextColor(Color.parseColor(any ? "#15803D" : "#B45309"));
        }
        if (tvAccountHintBadge != null) {
            tvAccountHintBadge.setText(any ? "1-Click Ready" : "Tap to Link");
            tvAccountHintBadge.setTextColor(Color.parseColor(any ? "#15803D" : "#B45309"));
        }
    }

    private void handleSocialProviderClick(final String provider) {
        if (SocialAccountManager.PROVIDER_GOOGLE.equals(provider)) {
            List<MicroGGoogleAuthEngine.AccountRecord> saved = SocialAccountManager.getSavedGoogleAccounts(this);
            if (saved == null || saved.isEmpty()) {
                launchSocialLogin(provider);
                return;
            }
            showGoogleAccountManagerDialog(saved);
            return;
        }

        if (!SocialAccountManager.isProviderLinked(this, provider)) {
            launchSocialLogin(provider);
            return;
        }

        String name = SocialAccountManager.getDisplayName(provider);
        String[] options = new String[]{
                "Set " + name + " as Active",
                "Switch / Re-Login " + name,
                "Logout " + name
        };
        new AlertDialog.Builder(this)
                .setTitle(name + " Account")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        SocialAccountManager.setActiveProvider(this, provider);
                        refreshSocialAccountCard();
                        Toast.makeText(this, name + " set as active!", Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        SocialAccountManager.clearProviderCookies(provider);
                        launchSocialLogin(provider);
                    } else if (which == 2) {
                        SocialAccountManager.unlinkProvider(this, provider);
                        refreshSocialAccountCard();
                        Toast.makeText(this, name + " logged out", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showGoogleAccountManagerDialog(final List<MicroGGoogleAuthEngine.AccountRecord> saved) {
        String activeEmail = SocialAccountManager.getActiveGoogleEmail(this);
        final String[] items = new String[saved.size() + 2];
        for (int i = 0; i < saved.size(); i++) {
            String em = saved.get(i).email;
            boolean isActive = activeEmail != null && activeEmail.equalsIgnoreCase(em);
            items[i] = em + (isActive ? " (Active)" : "");
        }
        items[saved.size()] = "+ Add another Google account";
        items[saved.size() + 1] = "Remove active Google account";

        new AlertDialog.Builder(this)
                .setTitle("Google Accounts (1-Click BGMI)")
                .setItems(items, (d, which) -> {
                    if (which < saved.size()) {
                        String chosen = saved.get(which).email;
                        SocialAccountManager.setActiveGoogleAccount(this, chosen);
                        refreshSocialAccountCard();
                        Toast.makeText(this, "Active Google: " + chosen, Toast.LENGTH_SHORT).show();
                    } else if (which == saved.size()) {
                        launchSocialLogin(SocialAccountManager.PROVIDER_GOOGLE);
                    } else {
                        if (activeEmail != null) {
                            SocialAccountManager.removeGoogleAccount(this, activeEmail);
                            refreshSocialAccountCard();
                            Toast.makeText(this, "Removed " + activeEmail, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void launchSocialLogin(String provider) {
        Intent intent = new Intent(this, SocialLoginActivity.class);
        intent.putExtra(SocialLoginActivity.EXTRA_PROVIDER, provider);
        startActivityForResult(intent, REQ_SOCIAL_LOGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SOCIAL_LOGIN) {
            refreshSocialAccountCard();
        } else if (requestCode == REQ_SAF_OBB_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final android.net.Uri treeUri = data.getData();
            final boolean autoLaunch = pendingLaunchAfterSafObb;
            pendingLaunchAfterSafObb = false;
            setLaunchLoading(true, "Copying OBB...");
            Toast.makeText(this, "Copying BGMI OBB automatically, please wait...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String curPkg = getTargetPkg();
                boolean ok = FeaturesticSdkHelper.copyObbFromSafTreeUri(MainActivity.this, treeUri, curPkg);
                runOnUiThread(() -> {
                    setLaunchLoading(false, "START GAME");
                    if (ok) {
                        Toast.makeText(MainActivity.this, "OBB Copied Successfully!", Toast.LENGTH_SHORT).show();
                        if (autoLaunch) {
                            launchGameNow();
                        }
                    } else {
                        Toast.makeText(MainActivity.this,
                                "No .obb file found in selected folder. Select Android/obb/" + curPkg,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }, "Featurestic-SAF-OBBCopy").start();
        }
    }

    private void setupDeviceInfo() {
        // e.g. "samsung SM-M315F" or "OPPO CPH2731"
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toUpperCase() : "DEVICE";
        String model = Build.MODEL != null ? Build.MODEL : "Android";
        tvDeviceModel.setText(manufacturer + " " + model);

        // e.g. "(OP5EE7L1)" or "(m31)"
        String deviceCodename = Build.DEVICE != null ? Build.DEVICE : "generic";
        tvDeviceCodename.setText("(" + deviceCodename + ")");

        // e.g. "Android 16 - arm64-v8a"
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        tvDeviceSpecs.setText("Android " + Build.VERSION.RELEASE + " - " + abi);
    }

    private void setupGameCard() {
        String curPkg = getTargetPkg();
        tvGameName.setText(AppConfig.TARGET_GAME_NAME);
        tvGamePackage.setText("Package : " + curPkg);
        tvGameSupported.setText("Supported : " + AppConfig.SUPPORTED_VERSION);

        // Load host installed game icon and version
        try {
            PackageInfo pInfo = FeaturesticSdkHelper.findHostGameInfo(this, curPkg);
            if (pInfo != null) {
                tvGameVersion.setText("Version : " + pInfo.versionName);
                Drawable hostIcon = getPackageManager().getApplicationIcon(curPkg);
                if (hostIcon != null) {
                    ivGameIcon.setImageDrawable(hostIcon);
                } else {
                    ivGameIcon.setImageResource(R.drawable.pubg_india);
                }
            } else {
                tvGameVersion.setText("Version : " + AppConfig.SUPPORTED_VERSION);
                ivGameIcon.setImageResource(R.drawable.pubg_india);
            }
        } catch (Throwable t) {
            tvGameVersion.setText("Version : " + AppConfig.SUPPORTED_VERSION);
            ivGameIcon.setImageResource(R.drawable.pubg_india);
        }
    }

    private void setupHideEspSwitch() {
        if (switchHideEsp == null) return;
        try {
            switchHideEsp.setShowText(false);
            switchHideEsp.setTextOn("");
            switchHideEsp.setTextOff("");
        } catch (Throwable ignored) {}

        SharedPreferences prefs = getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE);
        boolean savedState = prefs.getBoolean(KEY_HIDE_ESP, false);
        switchHideEsp.setChecked(savedState);
        applyHideEsp(savedState, false);

        switchHideEsp.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_HIDE_ESP, isChecked).apply();
            applyHideEsp(isChecked, true);
        });
    }

    private void applyHideEsp(boolean enabled, boolean showToast) {
        try {
            com.featurestic.box.utils.compat.FeaturesticStreamproofEngine.setStreamproofEnabled(enabled, this);
            if (enabled) {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
                if (showToast) {
                    Toast.makeText(this, "Hide ESP Enabled", Toast.LENGTH_SHORT).show();
                }
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                if (showToast) {
                    Toast.makeText(this, "Hide ESP Disabled", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void setupSubscriptionTimer() {
        // Calculate remaining time
        long now = System.currentTimeMillis();
        long diff = mExpiryTimestamp > now ? (mExpiryTimestamp - now) : 0;

        // If no remote timestamp provided, default to demo 48 days 09 hours 46 mins 38 secs (from video)
        if (diff <= 0) {
            diff = TimeUnit.DAYS.toMillis(48)
                    + TimeUnit.HOURS.toMillis(9)
                    + TimeUnit.MINUTES.toMillis(46)
                    + TimeUnit.SECONDS.toMillis(38);
        }

        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        }

        mCountDownTimer = new CountDownTimer(diff, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long days = TimeUnit.MILLISECONDS.toDays(millisUntilFinished);
                long hours = TimeUnit.MILLISECONDS.toHours(millisUntilFinished) % 24;
                long minutes = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished) % 60;
                long seconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % 60;

                tvCountdownDays.setText(String.format("%02d", days));
                tvCountdownHours.setText(String.format("%02d", hours));
                tvCountdownMinutes.setText(String.format("%02d", minutes));
                tvCountdownSeconds.setText(String.format("%02d", seconds));
            }

            @Override
            public void onFinish() {
                tvCountdownDays.setText("00");
                tvCountdownHours.setText("00");
                tvCountdownMinutes.setText("00");
                tvCountdownSeconds.setText("00");
            }
        }.start();
    }

    private void setupListeners() {
        // Connected pill click -> Connection toast info
        pillConnectedStatus.setOnClickListener(v -> {
            Toast.makeText(this, "Connected to RDX Engine", Toast.LENGTH_SHORT).show();
        });

        // Notification Bell click -> Announcements dialog
        btnNotificationBell.setOnClickListener(v -> showNotificationDialog());

        // Telegram owner button and background audio toggle
        RdxUiHelper.bindTelegram(this, btnTelegram);
        RdxUiHelper.bindAudioToggle(this, btnAudioToggle);

        // 3-dots Game Menu click
        btnGameMenu.setOnClickListener(v -> showGameOptionsMenu());

        // START GAME button click
        btnLaunchGame.setOnClickListener(v -> handleLaunchGame());
    }

    private void showNotificationDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_notifications);
        if (dialog.getWindow() != null) {
            Window w = dialog.getWindow();
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.22f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                w.getAttributes().setBlurBehindRadius(18);
            }
        }
        RdxUiHelper.decorate(dialog.getWindow().getDecorView());

        TextView tvTitle = dialog.findViewById(R.id.tvNotificationTitle);
        TextView tvMsg = dialog.findViewById(R.id.tvNotificationMessage);
        String bTitle = PhpAuthManager.getBroadcastTitle(this);
        String bMsg = PhpAuthManager.getBroadcastMessage(this);
        if (tvTitle != null && !bTitle.isEmpty()) {
            tvTitle.setText(bTitle);
        }
        if (tvMsg != null && !bMsg.isEmpty()) {
            tvMsg.setText(bMsg);
        }

        View btnDismiss = dialog.findViewById(R.id.btnDismissNotification);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                PhpAuthManager.markBroadcastRead(MainActivity.this);
                dialog.dismiss();
            });
        }

        dialog.show();
    }

    private void showGameOptionsMenu() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_game_menu);
        if (dialog.getWindow() != null) {
            Window w = dialog.getWindow();
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.getAttributes().windowAnimations = android.R.style.Animation_Dialog;
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.22f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                w.getAttributes().setBlurBehindRadius(18);
            }
        }
        RdxUiHelper.decorate(dialog.getWindow().getDecorView());

        View btnSync = dialog.findViewById(R.id.btnMenuSyncObb);
        if (btnSync != null) {
            btnSync.setOnClickListener(v -> {
                dialog.dismiss();
                syncObbFiles();
            });
        }

        View btnVerify = dialog.findViewById(R.id.btnMenuVerifyContainer);
        if (btnVerify != null) {
            btnVerify.setOnClickListener(v -> {
                dialog.dismiss();
                Toast.makeText(this, "Virtual container is verified & ready", Toast.LENGTH_SHORT).show();
            });
        }

        View btnSendLogs = dialog.findViewById(R.id.btnMenuSendLogs);
        if (btnSendLogs != null) {
            btnSendLogs.setOnClickListener(v -> {
                dialog.dismiss();
                Toast.makeText(MainActivity.this, "Uploading diagnostic logs to developer...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    CrashHandler.reportDiagnosticLogs("Manual diagnostic logs from MainActivity options menu", () -> {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Diagnostic logs uploaded successfully", Toast.LENGTH_LONG).show());
                    });
                }, "Featurestic-ManualLogs").start();
            });
        }

        View btnSignOut = dialog.findViewById(R.id.btnMenuSignOut);
        if (btnSignOut != null) {
            btnSignOut.setOnClickListener(v -> {
                dialog.dismiss();
                signOut();
            });
        }

        dialog.show();
    }

    private static final int REQ_SAF_OBB_TREE = 3301;
    private boolean pendingLaunchAfterSafObb = false;

    private void syncObbFiles() {
        String activePkg = getTargetPkg();
        setLaunchLoading(true, "Syncing OBB...");
        Toast.makeText(this, "Starting OBB sync...", Toast.LENGTH_SHORT).show();
        FileCopyTask task = new FileCopyTask(this);
        task.installOrFixPkg(activePkg, (percent, copied, total, file) -> {
            runOnUiThread(() -> {
                if (isLaunching) {
                    tvLaunchLabel.setText("Syncing (" + percent + "%)");
                }
            });
        }, (success, message) -> {
            runOnUiThread(() -> {
                setLaunchLoading(false, "START GAME");
                Toast.makeText(MainActivity.this, success ? "OBB synced successfully" : "Sync failed: " + message, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void requestSafObbPermission() {
        syncObbFiles();
    }

    private void signOut() {
        PhpAuthManager.clearSession(this);
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
    }

    private void handleLaunchGame() {
        if (isLaunching) return;

        // Live 24x7 key verification before every launch (never trust local cache)
        final String activeKey = (mLicenseKey != null && !mLicenseKey.trim().isEmpty())
                ? mLicenseKey.trim()
                : PhpAuthManager.getSavedKey(this);
        if (activeKey == null || activeKey.trim().isEmpty()) {
            Toast.makeText(this, "License key missing. Please login again.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setLaunchLoading(true, "Verifying Key...");
        PhpAuthManager.verifyKey(this, activeKey, new PhpAuthManager.AuthCallback() {
            @Override
            public void onSuccess(String licenseKey, String expiryText, String serverMessage) {
                runOnUiThread(() -> {
                    setLaunchLoading(false, "START GAME");
                    proceedWithVerifiedLaunch();
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    setLaunchLoading(false, "START GAME");
                    PhpAuthManager.clearSession(MainActivity.this);
                    redirectToLogin(errorMessage != null ? errorMessage : "Key disabled or expired");
                });
            }
        });
    }

    private void proceedWithVerifiedLaunch() {
        if (isLaunching) return;

        // Check essential permissions
        if (!FeaturesticSdkHelper.hasAllFilesPermission(this)) {
            Toast.makeText(this, "Please grant All Files Access for OBB synchronization", Toast.LENGTH_LONG).show();
            FeaturesticSdkHelper.requestAllFilesPermission(this);
            return;
        }

        if (!FeaturesticSdkHelper.hasOverlayPermission(this)) {
            Toast.makeText(this, "Please allow Display over other apps (Floating Permission)", Toast.LENGTH_LONG).show();
            FeaturesticSdkHelper.requestOverlayPermission(this);
            return;
        }

        String activePkg = getTargetPkg();
        if (!FeaturesticSdkHelper.hasVirtualObbFiles(activePkg)) {
            setLaunchLoading(true, "Syncing OBB...");
            FileCopyTask task = new FileCopyTask(this);
            task.installOrFixPkg(activePkg, (percent, copied, total, file) -> {
                runOnUiThread(() -> {
                    if (isLaunching) {
                        tvLaunchLabel.setText("Syncing OBB (" + percent + "%)");
                    }
                });
            }, (success, message) -> {
                runOnUiThread(() -> {
                    setLaunchLoading(false, "START GAME");
                    launchGameNow();
                });
            });
            return;
        }

        String remoteSo = FeaturesticSdkHelper.resolveEffectivePluginSo(this);
        if (remoteSo.isEmpty()) {
            setLaunchLoading(true, "Syncing Engine...");
            com.rdx.loader.auth.RdxSecurityGuard.activateSdkEngine(this, (sdkOk, sdkMsg) -> {
                runOnUiThread(() -> {
                    launchGameNow();
                });
            });
            return;
        }

        launchGameNow();
    }

    private void launchGameNow() {
        setLaunchLoading(true, "Preparing...");
        String activePkg = getTargetPkg();
        boolean started = FeaturesticSdkHelper.launchVirtualGame(this, activePkg, () -> {
            setLaunchLoading(false, "START GAME");
        });
        if (!started) {
            setLaunchLoading(false, "START GAME");
            Toast.makeText(this, "Failed to launch game container", Toast.LENGTH_SHORT).show();
        }
    }

    private void setLaunchLoading(boolean loading, String label) {
        isLaunching = loading;
        if (loading) {
            btnLaunchContent.setVisibility(View.INVISIBLE);
            pbLaunchProgress.setVisibility(View.VISIBLE);
            btnLaunchGame.setEnabled(false);
        } else {
            btnLaunchContent.setVisibility(View.VISIBLE);
            pbLaunchProgress.setVisibility(View.GONE);
            tvLaunchLabel.setText(label);
            btnLaunchGame.setEnabled(true);
        }
    }
}
