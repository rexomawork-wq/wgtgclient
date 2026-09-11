package org.telegram.messenger;

import android.content.SharedPreferences;

public final class WgtgConfig {
    private static final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("wgtg", 0);
    public static volatile int nicknameMode = prefs.getInt("nicknameMode", 0);
    public static volatile int nicknameColor = prefs.getInt("nicknameColor", 0xff38d9b5);

    public static void setNickname(int mode, int color) {
        nicknameMode = mode;
        nicknameColor = color | 0xff000000;
        prefs.edit().putInt("nicknameMode", mode).putInt("nicknameColor", nicknameColor).apply();
    }

    public static boolean preserveDeleted(int account) {
        return prefs.getBoolean("preserveDeleted" + account, false);
    }

    public static void setPreserveDeleted(int account, boolean enabled) {
        prefs.edit().putBoolean("preserveDeleted" + account, enabled).apply();
    }
}
