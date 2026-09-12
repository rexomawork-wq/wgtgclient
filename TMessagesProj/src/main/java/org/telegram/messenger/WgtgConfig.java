package org.telegram.messenger;

import android.content.SharedPreferences;

public final class WgtgConfig {
    private static final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("wgtg", 0);
    public static volatile boolean ghostMode = prefs.getBoolean("ghostMode", false);
    public static volatile boolean confirmMedia = prefs.getBoolean("confirmMedia", false);

    public static boolean preserveDeleted(int account) {
        return prefs.getBoolean("preserveDeleted" + account, false);
    }

    public static void setPreserveDeleted(int account, boolean enabled) {
        prefs.edit().putBoolean("preserveDeleted" + account, enabled).apply();
    }

    public static void setGhostMode(boolean enabled) {
        ghostMode = enabled;
        prefs.edit().putBoolean("ghostMode", enabled).apply();
    }

    public static void setConfirmMedia(boolean enabled) {
        confirmMedia = enabled;
        prefs.edit().putBoolean("confirmMedia", enabled).apply();
    }
}
