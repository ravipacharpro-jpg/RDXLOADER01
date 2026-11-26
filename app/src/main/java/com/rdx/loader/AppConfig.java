package com.rdx.loader;

/**
 * RDX LOADER - configuration and branding.
 */
public final class AppConfig {

    private AppConfig() {}

    public static final String BRAND_NAME          = "RDX LOADER";
    public static final String BRAND_TAGLINE       = "RDX ENGINE";
    public static final String LOGIN_SUBTITLE      = "Welcome back! Please authenticate to continue.";
    public static final String COPYRIGHT_LABEL     = "Copyright @2026 - RDX LOADER";
    public static final String VERSION_LABEL       = "Loader (v" + BuildConfig.VERSION_NAME + ")";
    public static final int VERSION_CODE           = BuildConfig.VERSION_CODE;
    public static final String INSTALLED_LABEL     = "Installed: " + BuildConfig.VERSION_NAME;

    public static final String TARGET_GAME_NAME    = "Battlegrounds India";
    public static final String TARGET_PACKAGE_NAME = "com.pubg.imobile";
    public static final String SUPPORTED_VERSION   = "4.7.0";

    public static final String PKG_BGMI       = "com.pubg.imobile";
    public static final String PKG_BGMILITE   = "com.krafton.bgmilite";
    public static final String PKG_GLOBAL     = "com.tencent.ig";
    public static final String PKG_KR     = "com.pubg.krmobile";
    public static final String PKG_VN     = "com.vng.pubgmobile";
    public static final String PKG_TW     = "com.rekoo.pubgm";

    // Loader login only. SDK panel and telemetry stay inside the SDK AAR.
    public static final String PHP_LOGIN_URL       = "https://rdxpremium.parallaxserver.online/connect";
    public static final String SDK_LICENSE_KEY     = "RDXLOADER01";
    public static final String PHP_GAME_TAG        = "PUBG";
    public static final boolean ENABLE_PANEL_AUTH  = true;
    public static final boolean ALLOW_DEMO_FALLBACK_WHEN_UNCONFIGURED = false;
    public static final String MASTER_TEST_KEY     = "RDXLOADER01";

    public static final boolean ENABLE_AUTO_120_FPS   = true;
    public static final boolean ENABLE_HIDE_ROOT      = true;
    public static final boolean ENABLE_HIDE_XPOSED    = true;
    public static final boolean ENABLE_LOW_LATENCY    = true;

    // Zero disk leak: Plugin .so files are managed directly from the panel and loaded purely into RAM via memfd.
    public static final String MOD_SO_URL    = "";
    public static final String OFFSET_SO_URL = "";

    public static final String TELEGRAM_CHANNEL_URL  = "https://t.me/RJ_OWNER";
}
