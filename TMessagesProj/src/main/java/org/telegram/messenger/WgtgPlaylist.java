package org.telegram.messenger;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;

public final class WgtgPlaylist {
    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("wgtg_playlist_" + account, 0);
    }

    public static ArrayList<Long> getSources(int account) {
        ArrayList<Long> result = new ArrayList<>();
        String value = prefs(account).getString("sources", "");
        if (!value.isEmpty()) for (String item : value.split(",")) {
            try { long id = Long.parseLong(item); if (id != 0 && !result.contains(id)) result.add(id); } catch (Exception ignored) { }
        }
        return result;
    }

    public static void setSources(int account, ArrayList<Long> sources) {
        StringBuilder value = new StringBuilder();
        for (long source : sources) { if (value.length() > 0) value.append(','); value.append(source); }
        prefs(account).edit().putString("sources", value.toString()).apply();
    }
}
