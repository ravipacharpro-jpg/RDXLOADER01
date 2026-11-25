package com.rdx.loader.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.CookieManager;

import com.featurestic.box.compat.auth.MicroGGoogleAuthEngine;

import java.util.List;

/**
 * Manages linked social accounts (Google via MicroG, Twitter/X, Facebook, Discord)
 * inside FeaturesticLoader-V2 so virtualized games authenticate with 1-click directly
 * using the active account selected in the Loader.
 */
public final class SocialAccountManager {

    public static final String PREF_NAME = "featurestic_social_accounts";

    public static final String PROVIDER_GOOGLE = "google";
    public static final String PROVIDER_TWITTER = "twitter";
    public static final String PROVIDER_FACEBOOK = "facebook";
    public static final String PROVIDER_DISCORD = "discord";

    private static final String KEY_ACTIVE_PROVIDER = "active_provider";

    private SocialAccountManager() {}

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isGoogleLinked(Context context) {
        try {
            return MicroGGoogleAuthEngine.getActiveAccount(context) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String getActiveGoogleEmail(Context context) {
        try {
            MicroGGoogleAuthEngine.AccountRecord acc = MicroGGoogleAuthEngine.getActiveAccount(context);
            return acc != null ? acc.email : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static List<MicroGGoogleAuthEngine.AccountRecord> getSavedGoogleAccounts(Context context) {
        return MicroGGoogleAuthEngine.getSavedAccounts(context);
    }

    public static void setActiveGoogleAccount(Context context, String email) {
        MicroGGoogleAuthEngine.setActiveAccount(context, email);
        if (email != null && !email.trim().isEmpty()) {
            prefs(context).edit()
                    .putBoolean("linked_google", true)
                    .putString("label_google", email.trim())
                    .putString(KEY_ACTIVE_PROVIDER, PROVIDER_GOOGLE)
                    .apply();
        }
    }

    public static void removeGoogleAccount(Context context, String email) {
        MicroGGoogleAuthEngine.removeAccount(context, email);
        MicroGGoogleAuthEngine.AccountRecord next = MicroGGoogleAuthEngine.getActiveAccount(context);
        SharedPreferences.Editor ed = prefs(context).edit();
        if (next != null && next.email != null) {
            ed.putBoolean("linked_google", true);
            ed.putString("label_google", next.email);
        } else {
            ed.putBoolean("linked_google", false);
            ed.remove("label_google");
            if (PROVIDER_GOOGLE.equals(getActiveProvider(context))) {
                ed.remove(KEY_ACTIVE_PROVIDER);
            }
        }
        ed.apply();
    }

    public static boolean isProviderLinked(Context context, String provider) {
        if (PROVIDER_GOOGLE.equals(provider)) {
            return isGoogleLinked(context);
        }
        return prefs(context).getBoolean("linked_" + provider, false);
    }

    public static String getProviderLabel(Context context, String provider) {
        if (PROVIDER_GOOGLE.equals(provider)) {
            return getActiveGoogleEmail(context);
        }
        return prefs(context).getString("label_" + provider, null);
    }

    public static void markLinked(Context context, String provider, String label) {
        String safeLabel = (label != null && !label.trim().isEmpty())
                ? label.trim() : getDisplayName(provider) + " Connected";
        prefs(context).edit()
                .putBoolean("linked_" + provider, true)
                .putString("label_" + provider, safeLabel)
                .putString(KEY_ACTIVE_PROVIDER, provider)
                .apply();
    }

    public static void setActiveProvider(Context context, String provider) {
        if (provider != null && isProviderLinked(context, provider)) {
            prefs(context).edit().putString(KEY_ACTIVE_PROVIDER, provider).apply();
        }
    }

    public static String getActiveProvider(Context context) {
        String active = prefs(context).getString(KEY_ACTIVE_PROVIDER, null);
        if (active != null && isProviderLinked(context, active)) {
            return active;
        }
        if (isGoogleLinked(context)) return PROVIDER_GOOGLE;
        if (isProviderLinked(context, PROVIDER_TWITTER)) return PROVIDER_TWITTER;
        if (isProviderLinked(context, PROVIDER_FACEBOOK)) return PROVIDER_FACEBOOK;
        if (isProviderLinked(context, PROVIDER_DISCORD)) return PROVIDER_DISCORD;
        return null;
    }

    public static boolean hasAnyLinkedAccount(Context context) {
        return getActiveProvider(context) != null;
    }

    public static void unlinkProvider(Context context, String provider) {
        if (PROVIDER_GOOGLE.equals(provider)) {
            List<MicroGGoogleAuthEngine.AccountRecord> all = getSavedGoogleAccounts(context);
            for (MicroGGoogleAuthEngine.AccountRecord r : all) {
                if (r != null && r.email != null) {
                    MicroGGoogleAuthEngine.removeAccount(context, r.email);
                }
            }
        } else {
            clearProviderCookies(provider);
        }
        SharedPreferences.Editor ed = prefs(context).edit();
        ed.putBoolean("linked_" + provider, false);
        ed.remove("label_" + provider);
        if (provider.equals(prefs(context).getString(KEY_ACTIVE_PROVIDER, null))) {
            ed.remove(KEY_ACTIVE_PROVIDER);
        }
        ed.apply();
    }

    public static void clearProviderCookies(String provider) {
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm == null) return;
            String[] urls;
            if (PROVIDER_TWITTER.equals(provider)) {
                urls = new String[]{"https://x.com", "https://twitter.com", "https://mobile.twitter.com", "https://api.twitter.com"};
            } else if (PROVIDER_FACEBOOK.equals(provider)) {
                urls = new String[]{"https://m.facebook.com", "https://facebook.com", "https://www.facebook.com"};
            } else if (PROVIDER_DISCORD.equals(provider)) {
                urls = new String[]{"https://discord.com", "https://discordapp.com"};
            } else {
                urls = new String[]{"https://accounts.google.com"};
            }
            for (String u : urls) {
                String existing = cm.getCookie(u);
                if (existing == null || existing.isEmpty()) continue;
                String[] parts = existing.split(";");
                for (String p : parts) {
                    int eq = p.indexOf('=');
                    if (eq > 0) {
                        String name = p.substring(0, eq).trim();
                        if (!name.isEmpty()) {
                            cm.setCookie(u, name + "=; Max-Age=0; Path=/");
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    public static String getDisplayName(String provider) {
        if (PROVIDER_GOOGLE.equals(provider)) return "Google";
        if (PROVIDER_TWITTER.equals(provider)) return "Twitter / X";
        if (PROVIDER_FACEBOOK.equals(provider)) return "Facebook";
        if (PROVIDER_DISCORD.equals(provider)) return "Discord";
        return "Account";
    }

    public static String getActiveSummaryText(Context context) {
        String active = getActiveProvider(context);
        if (active == null) return "Not Linked";
        String label = getProviderLabel(context, active);
        if (PROVIDER_GOOGLE.equals(active) && label != null && !label.isEmpty()) {
            return "Google - " + label;
        }
        return getDisplayName(active) + " - Active";
    }
}
