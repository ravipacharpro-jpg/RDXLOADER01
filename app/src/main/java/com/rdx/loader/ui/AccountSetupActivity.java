package com.rdx.loader.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.rdx.loader.R;
import com.rdx.loader.auth.SocialAccountManager;

/**
 * First-Time Onboarding Screen shown after license key login when no game
 * account is linked yet. Displays a clean 2x2 circular icon grid (Google,
 * Facebook, Twitter/X, Discord); linking any 1 account unlocks the Dashboard.
 */
public class AccountSetupActivity extends AppCompatActivity {

    private static final int REQ_SOCIAL_LOGIN = 3101;

    private String licenseKey = "";
    private String expiryText = "";
    private long expiryTimestamp = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupEdgeToEdgeWindow();

        Intent src = getIntent();
        if (src != null) {
            licenseKey = src.getStringExtra("license_key");
            expiryText = src.getStringExtra("expiry_text");
            expiryTimestamp = src.getLongExtra("expiry_timestamp", 0L);
        }

        if (SocialAccountManager.hasAnyLinkedAccount(this)) {
            openDashboard();
            return;
        }

        setContentView(R.layout.activity_account_setup);

        findViewById(R.id.btnSetupGoogle).setOnClickListener(v -> launchProvider(SocialAccountManager.PROVIDER_GOOGLE));
        findViewById(R.id.btnSetupFacebook).setOnClickListener(v -> launchProvider(SocialAccountManager.PROVIDER_FACEBOOK));
        findViewById(R.id.btnSetupTwitter).setOnClickListener(v -> launchProvider(SocialAccountManager.PROVIDER_TWITTER));
        findViewById(R.id.btnSetupDiscord).setOnClickListener(v -> launchProvider(SocialAccountManager.PROVIDER_DISCORD));
        View skipBtn = findViewById(R.id.btnSkipAccountSetup);
        if (skipBtn != null) {
            skipBtn.setOnClickListener(v -> openDashboard());
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
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }
    }

    private void launchProvider(String provider) {
        Intent intent = new Intent(this, SocialLoginActivity.class);
        intent.putExtra(SocialLoginActivity.EXTRA_PROVIDER, provider);
        startActivityForResult(intent, REQ_SOCIAL_LOGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SOCIAL_LOGIN && SocialAccountManager.hasAnyLinkedAccount(this)) {
            openDashboard();
        }
    }

    private void openDashboard() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("license_key", licenseKey);
        intent.putExtra("expiry_text", expiryText);
        intent.putExtra("expiry_timestamp", expiryTimestamp);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
