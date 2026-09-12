package org.telegram.messenger;

import android.content.SharedPreferences;

public final class WgtgConfig {
    private static final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("wgtg", 0);
    public static volatile boolean ghostMode = prefs.getBoolean("ghostMode", false);
    public static volatile boolean confirmMedia = prefs.getBoolean("confirmMedia", false);
    public static volatile boolean smoothMessages = prefs.getBoolean("smoothMessages", true);
    public static volatile boolean autoBold = prefs.getBoolean("autoBold", false);
    public static final int TRANSITION_DEFAULT = 0;
    public static final int TRANSITION_FADE = 1;
    public static final int TRANSITION_SLIDE = 2;
    public static final int TRANSITION_SCALE = 3;
    public static volatile int messageTransition = Math.max(0, Math.min(3, prefs.getInt("messageTransition", 0)));
    public static volatile int chatTransition = Math.max(0, Math.min(3, prefs.getInt("chatTransition", 0)));

    public static void setMessageTransition(int style) {
        messageTransition = Math.max(0, Math.min(3, style));
        prefs.edit().putInt("messageTransition", messageTransition).apply();
    }

    public static void setChatTransition(int style) {
        chatTransition = Math.max(0, Math.min(3, style));
        prefs.edit().putInt("chatTransition", chatTransition).apply();
    }

    public static void setSmoothMessages(boolean enabled) {
        smoothMessages = enabled;
        prefs.edit().putBoolean("smoothMessages", enabled).apply();
    }

    public static void setAutoBold(boolean enabled) {
        autoBold = enabled;
        prefs.edit().putBoolean("autoBold", enabled).apply();
    }

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
