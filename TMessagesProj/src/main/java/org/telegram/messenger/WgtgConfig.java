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
    public static final int TRANSITION_DROP = 4;
    public static final int TRANSITION_ZOOM = 5;
    public static final int TRANSITION_LIFT = 6;
    public static volatile int messageTransition = Math.max(0, Math.min(6, prefs.getInt("messageTransition", 0)));
    public static volatile int chatTransition = Math.max(0, Math.min(6, prefs.getInt("chatTransition", 0)));
    public static volatile int animationDuration = Math.max(120, Math.min(500, prefs.getInt("animationDuration", 240)));
    public static volatile int deletedOpacity = Math.max(20, Math.min(100, prefs.getInt("deletedOpacity", 65)));

    public static void setAnimationDuration(int duration) {
        animationDuration = Math.max(120, Math.min(500, duration));
        prefs.edit().putInt("animationDuration", animationDuration).apply();
    }

    public static void setDeletedOpacity(int opacity) {
        deletedOpacity = Math.max(20, Math.min(100, opacity));
        prefs.edit().putInt("deletedOpacity", deletedOpacity).apply();
    }

    // -1 inherits the global bold preference; 0 explicitly disables formatting for this chat.
    public static int chatFormat(int account, long dialog) {
        int style = prefs.getInt("format_" + account + "_" + dialog, -1);
        return style < 0 ? (autoBold ? 1 : 0) : Math.min(4, style);
    }

    public static void setChatFormat(int account, long dialog, int style) {
        prefs.edit().putInt("format_" + account + "_" + dialog, Math.max(-1, Math.min(4, style))).apply();
    }

    public static void setMessageTransition(int style) {
        messageTransition = Math.max(0, Math.min(6, style));
        prefs.edit().putInt("messageTransition", messageTransition).apply();
    }

    public static void setChatTransition(int style) {
        chatTransition = Math.max(0, Math.min(6, style));
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

    public static boolean preserveEdits(int account) {
        return prefs.getBoolean("preserveEdits" + account, false);
    }

    public static void setPreserveEdits(int account, boolean enabled) {
        prefs.edit().putBoolean("preserveEdits" + account, enabled).apply();
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
