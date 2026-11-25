package com.rdx.loader.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.rdx.loader.AppConfig;
import com.rdx.loader.R;
import com.rdx.loader.auth.PhpAuthManager;
import com.rdx.loader.sdk.FeaturesticSdkHelper;

/**
 * Screen 1 - RDX LOADER Login Screen
 * High-contrast silver & frosted glass aesthetic matching Screenshot 1.
 */
public class LoginActivity extends AppCompatActivity {

    private EditText etLicenseKey;
    private ImageView btnPasteKey;
    private ImageView btnTogglePassword;
    private View pillPermissions;
    private TextView tvPermissionsStatus;
    private View btnLogin;
    private View btnLoginContent;
    private TextView tvLoginBtnText;
    private ProgressBar pbLoginLoading;
    private View btnAudioToggleLogin;

    private boolean isPasswordVisible = false;
    private boolean isVerifying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If panel auth is disabled, skip straight to game loader
        if (!AppConfig.ENABLE_PANEL_AUTH) {
            Intent intent = new Intent(this, com.rdx.loader.ui.MainActivity.class);
            intent.putExtra("license_key", "TEST-KEY");
            intent.putExtra("exp_label", "Test Session");
            intent.putExtra("expiry_text", "Test Session");
            intent.putExtra("expiry_timestamp", 0L);
            startActivity(intent);
            finish();
            return;
        }

        setupEdgeToEdgeWindow();
        setContentView(R.layout.activity_login);

        initViews();
        setupListeners();
        RdxUiHelper.decorate(getWindow().getDecorView());
        setupKeyboardListener();
        checkAndRefreshPermissions();

        // Auto-fill saved key if available
        String saved = PhpAuthManager.getSavedKey(this);
        if (saved != null && !saved.trim().isEmpty()) {
            etLicenseKey.setText(saved.trim());
            etLicenseKey.setSelection(etLicenseKey.getText().length());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndRefreshPermissions();
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

    private void setupKeyboardListener() {
        final View root = findViewById(R.id.rootLoginLayout);
        final View bottomSheet = findViewById(R.id.bottomSheetCard);
        final View heroSection = findViewById(R.id.llHeroSection);

        if (root == null || bottomSheet == null) return;

        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            private int lastDiff = 0;

            @Override
            public void onGlobalLayout() {
                Rect r = new Rect();
                root.getWindowVisibleDisplayFrame(r);
                int screenHeight = root.getRootView().getHeight();
                int keypadHeight = screenHeight - r.bottom;

                // When keyboard is open (takes up > 15% of screen height)
                if (keypadHeight > screenHeight * 0.15) {
                    if (Math.abs(lastDiff - keypadHeight) > 10) {
                        lastDiff = keypadHeight;
                        // Animate bottom card smoothly up above the soft keyboard
                        bottomSheet.animate()
                                .translationY(-keypadHeight + 24)
                                .setDuration(200)
                                .start();
                        if (heroSection != null) {
                            heroSection.animate()
                                    .alpha(0.0f)
                                    .translationY(-keypadHeight * 0.35f)
                                    .setDuration(160)
                                    .start();
                        }
                    }
                } else {
                    // Keyboard is closed: animate back to resting position
                    if (lastDiff != 0) {
                        lastDiff = 0;
                        bottomSheet.animate()
                                .translationY(0)
                                .setDuration(200)
                                .start();
                        if (heroSection != null) {
                            heroSection.animate()
                                    .alpha(1.0f)
                                    .translationY(0)
                                    .setDuration(200)
                                    .start();
                        }
                    }
                }
            }
        });
    }

    private void initViews() {
        etLicenseKey = findViewById(R.id.etLicenseKey);
        btnPasteKey = findViewById(R.id.btnPasteKey);
        btnTogglePassword = findViewById(R.id.btnTogglePassword);
        pillPermissions = findViewById(R.id.pillPermissions);
        tvPermissionsStatus = findViewById(R.id.tvPermissionsStatus);
        btnLogin = findViewById(R.id.btnLogin);
        btnLoginContent = findViewById(R.id.btnLoginContent);
        tvLoginBtnText = findViewById(R.id.tvLoginBtnText);
        pbLoginLoading = findViewById(R.id.pbLoginLoading);
        btnAudioToggleLogin = findViewById(R.id.btnAudioToggleLogin);
    }

    private void setupListeners() {
        RdxUiHelper.bindAudioToggle(this, btnAudioToggleLogin);
        // Paste key from clipboard
        btnPasteKey.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                    ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                    if (item != null && item.getText() != null) {
                        String pasted = item.getText().toString().trim();
                        if (!pasted.isEmpty()) {
                            etLicenseKey.setText(pasted);
                            etLicenseKey.setSelection(etLicenseKey.getText().length());
                            Toast.makeText(this, "License Key Pasted", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                }
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "Could not paste: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // Toggle password show / hide
        btnTogglePassword.setOnClickListener(v -> {
            isPasswordVisible = !isPasswordVisible;
            if (isPasswordVisible) {
                etLicenseKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                btnTogglePassword.setAlpha(0.6f);
            } else {
                etLicenseKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                btnTogglePassword.setAlpha(1.0f);
            }
            etLicenseKey.setSelection(etLicenseKey.getText().length());
        });

        // Permissions pill click -> prompt missing permissions
        pillPermissions.setOnClickListener(v -> promptRequiredPermissions());

        // Log In button click
        btnLogin.setOnClickListener(v -> performLogin());
    }

    private void checkAndRefreshPermissions() {
        boolean filesOk = FeaturesticSdkHelper.hasAllFilesPermission(this);
        boolean overlayOk = FeaturesticSdkHelper.hasOverlayPermission(this);
        boolean installOk = FeaturesticSdkHelper.canRequestPackageInstalls(this);

        String filesStr = filesOk ? "FILES: ON" : "FILES: OFF";
        String installStr = installOk ? "INSTALL: ON" : "INSTALL: OFF";
        String overlayStr = overlayOk ? "OVERLAY: ON" : "OVERLAY: OFF";

        tvPermissionsStatus.setText(filesStr + "  -  " + installStr + "  -  " + overlayStr);

        if (filesOk && installOk && overlayOk) {
            tvPermissionsStatus.setTextColor(Color.parseColor("#15803D"));
        } else {
            tvPermissionsStatus.setTextColor(Color.parseColor("#B45309")); // amber warning
        }
    }

    private void promptRequiredPermissions() {
        if (!FeaturesticSdkHelper.hasAllFilesPermission(this)) {
            Toast.makeText(this, "Grant 'All Files Access' for virtual game OBB sync", Toast.LENGTH_LONG).show();
            FeaturesticSdkHelper.requestAllFilesPermission(this);
        } else if (!FeaturesticSdkHelper.hasOverlayPermission(this)) {
            Toast.makeText(this, "Grant 'Display over other apps' permission", Toast.LENGTH_LONG).show();
            FeaturesticSdkHelper.requestOverlayPermission(this);
        } else if (!FeaturesticSdkHelper.canRequestPackageInstalls(this)) {
            Toast.makeText(this, "Allow 'Install unknown apps' to clone game into sandbox", Toast.LENGTH_LONG).show();
            FeaturesticSdkHelper.requestInstallPackagesPermission(this);
        } else {
            Toast.makeText(this, "All required container permissions are active!", Toast.LENGTH_SHORT).show();
        }
    }

    private void performLogin() {
        if (isVerifying) return;

        String key = etLicenseKey.getText() != null ? etLicenseKey.getText().toString().trim() : "";
        if (key.isEmpty()) {
            Toast.makeText(this, "Please enter your license key", Toast.LENGTH_SHORT).show();
            return;
        }

        final String finalKey = key;
        setLoading(true);

        PhpAuthManager.verifyKey(this, finalKey, new PhpAuthManager.AuthCallback() {
            @Override
            public void onSuccess(final String licenseKey, final String expiryText, final String serverMessage) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, "Login Successful! (" + licenseKey + ")", Toast.LENGTH_SHORT).show();
                    proceedToMain(licenseKey, expiryText, 0);
                });
            }

            @Override
            public void onError(final String error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, error != null ? error : "Authentication failed", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void proceedToMain(String key, String expLabel, long expiryTimestamp) {
        Class<?> nextActivity = com.rdx.loader.auth.SocialAccountManager.hasAnyLinkedAccount(this)
                ? MainActivity.class
                : AccountSetupActivity.class;
        Intent intent = new Intent(this, nextActivity);
        intent.putExtra("license_key", key);
        intent.putExtra("exp_label", expLabel);
        intent.putExtra("expiry_text", expLabel);
        intent.putExtra("expiry_timestamp", expiryTimestamp);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        isVerifying = loading;
        if (loading) {
            btnLoginContent.setVisibility(View.INVISIBLE);
            pbLoginLoading.setVisibility(View.VISIBLE);
            btnLogin.setEnabled(false);
        } else {
            btnLoginContent.setVisibility(View.VISIBLE);
            pbLoginLoading.setVisibility(View.GONE);
            btnLogin.setEnabled(true);
        }
    }
}
