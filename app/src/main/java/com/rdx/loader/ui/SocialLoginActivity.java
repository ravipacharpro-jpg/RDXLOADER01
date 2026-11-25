package com.rdx.loader.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.featurestic.box.compat.auth.MicroGGoogleAuthEngine;
import com.rdx.loader.auth.SocialAccountManager;

import org.json.JSONArray;

import java.util.Locale;

/**
 * Clean full-portrait in-Loader login Activity for Google (MicroG), Twitter/X,
 * Facebook, and Discord. Handles Android 15/16 WindowInsets & IME cleanly with
 * zero black-screen artifacts.
 */
public class SocialLoginActivity extends AppCompatActivity {

    private static final String TAG = "FeaturesticSocial";

    public static final String EXTRA_PROVIDER = "provider";
    public static final String EXTRA_RESULT_LABEL = "result_label";

    private String provider = SocialAccountManager.PROVIDER_GOOGLE;
    private WebView webView;
    private ProgressBar progressBar;
    private TextView btnDone;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean completed = false;
    private volatile boolean exchanging = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#FFFFFF"));
            getWindow().setNavigationBarColor(Color.parseColor("#FFFFFF"));
        }

        String extra = getIntent() != null ? getIntent().getStringExtra(EXTRA_PROVIDER) : null;
        if (extra != null && !extra.trim().isEmpty()) {
            provider = extra.trim().toLowerCase(Locale.US);
        }
        Log.i(TAG, "Opening SocialLoginActivity for provider=" + provider);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottomPad = Math.max(sys.bottom, ime.bottom);
            v.setPadding(sys.left, sys.top, sys.right, bottomPad);
            return WindowInsetsCompat.CONSUMED;
        });

        // Clean Light Apple-Style Top Bar (matches status bar cleanly)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.TRANSPARENT);
        topBar.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView btnBack = new TextView(this);
        btnBack.setText("Back");
        btnBack.setTextColor(Color.WHITE);
        btnBack.setTextSize(20f);
        btnBack.setTypeface(Typeface.DEFAULT_BOLD);
        btnBack.setPadding(dp(8), dp(4), dp(12), dp(4));
        btnBack.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        topBar.addView(btnBack);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tcLp.setMargins(dp(4), 0, dp(8), 0);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Sign in with " + SocialAccountManager.getDisplayName(provider));
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(15.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(SocialAccountManager.PROVIDER_GOOGLE.equals(provider)
                ? "1-time secure setup - Saved for 1-click BGMI login"
                : "Sign in once - Tap Done when logged in");
        tvSub.setTextColor(Color.parseColor("#E8EEF9"));
        tvSub.setTextSize(11.5f);
        titleCol.addView(tvSub);
        topBar.addView(titleCol, tcLp);

        if (!SocialAccountManager.PROVIDER_GOOGLE.equals(provider)) {
            btnDone = new TextView(this);
            btnDone.setText("Done");
            btnDone.setTextColor(Color.WHITE);
            btnDone.setTextSize(13f);
            btnDone.setTypeface(Typeface.DEFAULT_BOLD);
            btnDone.setPadding(dp(14), dp(7), dp(14), dp(7));
            GradientDrawable doneBg = new GradientDrawable();
            doneBg.setColor(Color.parseColor("#16A34A"));
            doneBg.setCornerRadius(dp(16));
            btnDone.setBackground(doneBg);
            btnDone.setOnClickListener(v -> finishSocialWebSuccess(null));
            topBar.addView(btnDone);
        }

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#E2E8F0"));
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(false);
        ws.setLoadWithOverviewMode(false);
        ws.setSupportZoom(false);
        ws.setAllowFileAccess(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setSupportMultipleWindows(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                ws.setAlgorithmicDarkeningAllowed(false);
            } catch (Throwable ignored) {
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            try {
                @SuppressWarnings("deprecation")
                int forceOff = WebSettings.FORCE_DARK_OFF;
                ws.setForceDark(forceOff);
            } catch (Throwable ignored) {
            }
        }

        CookieManager cm = CookieManager.getInstance();
        if (cm != null) {
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, true);
            }
        }

        if (SocialAccountManager.PROVIDER_GOOGLE.equals(provider)) {
            setupGoogleMicroGWebView(ws, cm);
        } else {
            setupStandardSocialWebView(ws, cm);
        }

        root.addView(webView);
        setContentView(root);
    }

    private WebChromeClient createSafeChromeClient() {
        return new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                try {
                    WebView temp = new WebView(SocialLoginActivity.this);
                    temp.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                            if (req != null && req.getUrl() != null && webView != null) {
                                String u = req.getUrl().toString();
                                if (u.startsWith("http://") || u.startsWith("https://")) {
                                    webView.loadUrl(u);
                                }
                            }
                            return true;
                        }
                    });
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(temp);
                    resultMsg.sendToTarget();
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }
        };
    }

    @SuppressLint("AddJavascriptInterface")
    private void setupGoogleMicroGWebView(WebSettings ws, final CookieManager cm) {
        String baseUa = ws.getUserAgentString();
        if (baseUa == null) {
            baseUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36";
        }
        if (!baseUa.contains("MinuteMaid")) {
            ws.setUserAgentString(baseUa + MicroGGoogleAuthEngine.MAGIC_USER_AGENT_SUFFIX);
        }

        if (cm != null) {
            try {
                cm.setCookie("https://accounts.google.com", "oauth_token=; Max-Age=0; Path=/");
                cm.setCookie(MicroGGoogleAuthEngine.EMBEDDED_SETUP_URL, "oauth_token=; Max-Age=0; Path=/");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.flush();
                }
            } catch (Throwable ignored) {
            }
        }

        final Runnable tryCompleteGoogle = () -> {
            if (exchanging || completed) return;
            String oauthToken = extractGoogleOAuthTokenCookie(cm);
            if (oauthToken == null || oauthToken.isEmpty()) {
                return;
            }
            Log.i(TAG, "Captured Google oauth_token cookie (len=" + oauthToken.length() + "), exchanging for Master Token...");
            exchanging = true;
            showBusyOverlay("Saving Google Account...", "Linking permanent 1-click session");
            new Thread(() -> {
                try {
                    MicroGGoogleAuthEngine.AccountRecord rec =
                            MicroGGoogleAuthEngine.exchangeOAuthCookieForMasterToken(
                                    getApplicationContext(), oauthToken);
                    SocialAccountManager.setActiveGoogleAccount(getApplicationContext(), rec.email);
                    Log.i(TAG, "Google account linked successfully: " + rec.email);
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed() || completed) return;
                        completed = true;
                        Toast.makeText(SocialLoginActivity.this,
                                "Google Linked: " + rec.email, Toast.LENGTH_SHORT).show();
                        Intent data = new Intent();
                        data.putExtra(EXTRA_PROVIDER, SocialAccountManager.PROVIDER_GOOGLE);
                        data.putExtra(EXTRA_RESULT_LABEL, rec.email);
                        setResult(RESULT_OK, data);
                        finish();
                    });
                } catch (final Throwable t) {
                    Log.e(TAG, "Google master token exchange failed: " + t.getMessage());
                    exchanging = false;
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(SocialLoginActivity.this,
                                "Google link failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        recreate();
                    });
                }
            }, "Loader-MicroG-Link").start();
        };

        // Poll every 1s for oauth_token in case Google sets it via XHR without full page reload
        final Runnable cookiePoller = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed() || completed || exchanging) return;
                if (extractGoogleOAuthTokenCookie(cm) != null) {
                    tryCompleteGoogle.run();
                    return;
                }
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.postDelayed(cookiePoller, 1500);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface public void closeView() { Log.i(TAG, "mm.closeView() called"); mainHandler.post(tryCompleteGoogle); }
            @JavascriptInterface public void showView() { mainHandler.post(() -> progressBar.setVisibility(View.GONE)); }
            @JavascriptInterface public void skipLogin() { mainHandler.post(() -> { setResult(RESULT_CANCELED); finish(); }); }
            @JavascriptInterface public String getAccounts() {
                JSONArray arr = new JSONArray();
                try {
                    for (MicroGGoogleAuthEngine.AccountRecord r :
                            MicroGGoogleAuthEngine.getSavedAccounts(SocialLoginActivity.this)) {
                        arr.put(r.email);
                    }
                } catch (Throwable ignored) {}
                return arr.toString();
            }
            @JavascriptInterface public String getAllowedDomains() { return "[]"; }
            @JavascriptInterface public String getAndroidId() { return MicroGGoogleAuthEngine.resolveAndroidIdHex(SocialLoginActivity.this); }
            @JavascriptInterface public int getAuthModuleVersionCode() { return MicroGGoogleAuthEngine.GMS_VERSION_CODE; }
            @JavascriptInterface public int getPlayServicesVersionCode() { return MicroGGoogleAuthEngine.GMS_VERSION_CODE; }
            @JavascriptInterface public int getBuildVersionSdk() { return Build.VERSION.SDK_INT; }
            @JavascriptInterface public int getDeviceContactsCount() { return -1; }
            @JavascriptInterface public int getDeviceDataVersionInfo() { return 1; }
            @JavascriptInterface public String getFactoryResetChallenges() { return "[]"; }
            @JavascriptInterface public String getPhoneNumber() { return null; }
            @JavascriptInterface public String getSimSerial() { return null; }
            @JavascriptInterface public int getSimState() { return 0; }
            @JavascriptInterface public boolean hasPhoneNumber() { return false; }
            @JavascriptInterface public boolean hasTelephony() { return false; }
            @JavascriptInterface public boolean isUserOwner() { return true; }
            @JavascriptInterface public String fetchVerifiedPhoneNumber() { return null; }
            @JavascriptInterface public void getDroidGuardResult(String s) {
                Log.i(TAG, "mm.getDroidGuardResult called: " + s);
                mainHandler.post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("try{if(window.setDgResult){window.setDgResult('');}}catch(e){}", null);
                    }
                });
            }
            @JavascriptInterface public void addAccount(String json) { Log.i(TAG, "mm.addAccount: " + json); }
            @JavascriptInterface public void attemptLogin(String a, String p) { Log.i(TAG, "mm.attemptLogin: " + a); }
            @JavascriptInterface public void backupSyncOptIn(String a) {}
            @JavascriptInterface public void clearOldLoginAttempts() {}
            @JavascriptInterface public void fetchIIDToken(String e) {}
            @JavascriptInterface public void goBack() {
                mainHandler.post(() -> {
                    if (webView != null && webView.canGoBack()) webView.goBack();
                });
            }
            @JavascriptInterface public void hideKeyboard() {
                mainHandler.post(() -> {
                    try {
                        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null) imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
                    } catch (Throwable ignored) {}
                });
            }
            @JavascriptInterface public void showKeyboard() {
                mainHandler.post(() -> {
                    try {
                        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
                    } catch (Throwable ignored) {}
                });
            }
            @JavascriptInterface public void launchEmergencyDialer() {}
            @JavascriptInterface public void log(String s) { Log.d(TAG, "mm.log: " + s); }
            @JavascriptInterface public void notifyOnTermsOfServiceAccepted() { Log.i(TAG, "mm.notifyOnTermsOfServiceAccepted"); }
            @JavascriptInterface public void setAccountIdentifier(String a) { Log.i(TAG, "mm.setAccountIdentifier: " + a); }
            @JavascriptInterface public void setAllActionsEnabled(boolean z) {}
            @JavascriptInterface public void setBackButtonEnabled(boolean z) {}
            @JavascriptInterface public void setNewAccountCreated() {}
            @JavascriptInterface public void setPrimaryActionEnabled(boolean z) {}
            @JavascriptInterface public void setPrimaryActionLabel(String s, int i) {}
            @JavascriptInterface public void setSecondaryActionEnabled(boolean z) {}
            @JavascriptInterface public void setSecondaryActionLabel(String s, int i) {}
            @JavascriptInterface public void startAfw() {}
            @JavascriptInterface public void startFido2SignRequest(String r) {
                Log.i(TAG, "mm.startFido2SignRequest -> falling back to password");
                mainHandler.post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("try{if(window.onFido2SignRequestError){window.onFido2SignRequestError('NOT_SUPPORTED');}}catch(e){}", null);
                    }
                });
            }
            @JavascriptInterface public void cancelFido2SignRequest() {}
            @JavascriptInterface public void sendFido2SkUiEvent(String e) {}
        }, "mm");

        webView.setWebChromeClient(createSafeChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request != null ? request.getUrl() : null;
                if (u != null) {
                    String scheme = u.getScheme() != null ? u.getScheme().toLowerCase(Locale.US) : "";
                    if (!"http".equals(scheme) && !"https".equals(scheme)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                if (url == null || exchanging || completed) return;
                Log.i(TAG, "Google WebView finished: " + url);
                try {
                    Uri uri = Uri.parse(url);
                    if ("close".equals(uri.getFragment())
                            || url.startsWith(MicroGGoogleAuthEngine.GOOGLE_SUITE_URL)
                            || url.startsWith(MicroGGoogleAuthEngine.PROGRAMMATIC_AUTH_URL)) {
                        tryCompleteGoogle.run();
                        return;
                    }
                } catch (Throwable ignored) {}
                if (extractGoogleOAuthTokenCookie(cm) != null) {
                    tryCompleteGoogle.run();
                }
            }
        });

        String setupUrl = MicroGGoogleAuthEngine.buildEmbeddedSetupUrl(Locale.getDefault(), null);
        Log.i(TAG, "Loading Google EmbeddedSetup: " + setupUrl);
        webView.loadUrl(setupUrl);
    }

    private void setupStandardSocialWebView(WebSettings ws, final CookieManager cm) {
        String ua = ws.getUserAgentString();
        if (ua != null) {
            ua = ua.replace("; wv", "").replace("Version/4.0 ", "");
            ws.setUserAgentString(ua);
        }

        final String startUrl;
        if (SocialAccountManager.PROVIDER_TWITTER.equals(provider)) {
            startUrl = "https://x.com/i/flow/login";
        } else if (SocialAccountManager.PROVIDER_FACEBOOK.equals(provider)) {
            startUrl = "https://m.facebook.com/";
        } else {
            startUrl = "https://discord.com/login";
        }

        webView.setWebChromeClient(createSafeChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request != null ? request.getUrl() : null;
                if (u != null) {
                    String scheme = u.getScheme() != null ? u.getScheme().toLowerCase(Locale.US) : "";
                    if (!"http".equals(scheme) && !"https".equals(scheme)) {
                        Log.d(TAG, "Blocked external scheme redirect: " + u);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (!completed && url != null && detectSocialSessionActive(cm, url)) {
                    finishSocialWebSuccess(null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                if (completed || url == null) return;
                Log.i(TAG, provider + " WebView finished: " + url);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && cm != null) {
                    try { cm.flush(); } catch (Throwable ignored) {}
                }
                if (detectSocialSessionActive(cm, url)) {
                    finishSocialWebSuccess(null);
                }
            }
        });

        Log.i(TAG, "Loading " + provider + " login URL: " + startUrl);
        webView.loadUrl(startUrl);
    }

    private boolean detectSocialSessionActive(CookieManager cm, String url) {
        try {
            String lowerUrl = url.toLowerCase(Locale.US);
            if (SocialAccountManager.PROVIDER_TWITTER.equals(provider)) {
                String c1 = cm != null ? cm.getCookie("https://x.com") : null;
                String c2 = cm != null ? cm.getCookie("https://twitter.com") : null;
                boolean hasToken = (c1 != null && c1.contains("auth_token="))
                        || (c2 != null && c2.contains("auth_token="));
                if (hasToken && (lowerUrl.contains("/home") || lowerUrl.equals("https://x.com/") || lowerUrl.equals("https://twitter.com/"))) {
                    return true;
                }
            } else if (SocialAccountManager.PROVIDER_FACEBOOK.equals(provider)) {
                String c1 = cm != null ? cm.getCookie("https://m.facebook.com") : null;
                String c2 = cm != null ? cm.getCookie("https://facebook.com") : null;
                boolean hasUser = (c1 != null && c1.contains("c_user="))
                        || (c2 != null && c2.contains("c_user="));
                if (hasUser && !lowerUrl.contains("login.php") && !lowerUrl.contains("checkpoint")) {
                    return true;
                }
            } else if (SocialAccountManager.PROVIDER_DISCORD.equals(provider)) {
                if (lowerUrl.contains("discord.com/channels") || lowerUrl.contains("discord.com/app")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void finishSocialWebSuccess(String label) {
        if (completed) return;
        completed = true;
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.flush();
            }
        } catch (Throwable ignored) {}
        String finalLabel = label != null ? label : SocialAccountManager.getDisplayName(provider) + " Connected";
        SocialAccountManager.markLinked(getApplicationContext(), provider, finalLabel);
        Log.i(TAG, "Social provider linked: " + provider + " (" + finalLabel + ")");
        Toast.makeText(this, SocialAccountManager.getDisplayName(provider) + " Linked!", Toast.LENGTH_SHORT).show();
        Intent data = new Intent();
        data.putExtra(EXTRA_PROVIDER, provider);
        data.putExtra(EXTRA_RESULT_LABEL, finalLabel);
        setResult(RESULT_OK, data);
        finish();
    }

    private void showBusyOverlay(String title, String subtitle) {
        mainHandler.post(() -> {
            try {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                box.setBackgroundColor(Color.WHITE);
                box.setPadding(dp(28), dp(28), dp(28), dp(28));

                ProgressBar pb = new ProgressBar(this);
                pb.setIndeterminate(true);
                box.addView(pb, new LinearLayout.LayoutParams(dp(44), dp(44)));

                TextView t = new TextView(this);
                t.setText(title);
                t.setTextColor(Color.parseColor("#0F172A"));
                t.setTextSize(16f);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tLp.setMargins(0, dp(16), 0, dp(6));
                box.addView(t, tLp);

                TextView s = new TextView(this);
                s.setText(subtitle);
                s.setTextColor(Color.parseColor("#64748B"));
                s.setTextSize(13f);
                s.setGravity(Gravity.CENTER);
                box.addView(s);

                setContentView(box);
            } catch (Throwable ignored) {}
        });
    }

    private String extractGoogleOAuthTokenCookie(CookieManager cm) {
        if (cm == null) return null;
        String[] urls = new String[]{
                MicroGGoogleAuthEngine.EMBEDDED_SETUP_URL,
                MicroGGoogleAuthEngine.PROGRAMMATIC_AUTH_URL,
                MicroGGoogleAuthEngine.EMBEDDED_RE_AUTH_URL,
                "https://accounts.google.com"
        };
        for (String u : urls) {
            try {
                String cookies = cm.getCookie(u);
                if (cookies == null || cookies.isEmpty()) continue;
                String[] parts = cookies.split(";");
                for (String p : parts) {
                    String trimmed = p.trim();
                    if (trimmed.startsWith("oauth_token=")) {
                        String val = trimmed.substring("oauth_token=".length()).trim();
                        if (!val.isEmpty()) return val;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack() && !exchanging) {
            webView.goBack();
            return;
        }
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
