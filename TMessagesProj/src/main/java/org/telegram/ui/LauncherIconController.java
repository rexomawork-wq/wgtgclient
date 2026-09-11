package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        // Keep a launcher entry available while switching aliases.
        pm.setComponentEnabledSetting(icon.getComponentName(ctx), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (LauncherIcon i : LauncherIcon.values()) {
            if (i != icon) {
                pm.setComponentEnabledSetting(i.getComponentName(ctx), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        }
    }

    public static boolean useWgtgNotificationIcon() {
        return ApplicationLoader.applicationContext.getSharedPreferences("wgtg_branding", Context.MODE_PRIVATE)
                .getBoolean("notification_wgtg", false);
    }

    public static void setUseWgtgNotificationIcon(boolean enabled) {
        ApplicationLoader.applicationContext.getSharedPreferences("wgtg_branding", Context.MODE_PRIVATE)
                .edit().putBoolean("notification_wgtg", enabled).apply();
    }

    // Notification builders must use this resource instead of the fixed notification drawable.
    public static int getNotificationSmallIcon() {
        return useWgtgNotificationIcon() ? R.drawable.wgtg_notification : R.drawable.notification;
    }

    public enum LauncherIcon {
        DEFAULT("DefaultIcon", R.color.wgtg_launcher_background, R.drawable.wgtg_launcher_foreground, R.string.AppIconDefault),
        NIGHT_WGTG("NightWgtgIcon", R.color.wgtg_night_background, R.drawable.wgtg_icon_foreground, R.string.WgtgIconNight),
        RGB_WGTG("RgbWgtgIcon", R.drawable.wgtg_rgb_background, R.drawable.wgtg_icon_foreground, R.string.WgtgIconRgb),
        VINTAGE("VintageIcon", R.drawable.icon_6_background_sa, R.mipmap.icon_6_foreground_sa, R.string.AppIconVintage),
        AQUA("AquaIcon", R.drawable.icon_4_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconAqua),
        PREMIUM("PremiumIcon", R.drawable.icon_3_background_sa, R.mipmap.icon_3_foreground_sa, R.string.AppIconPremium, true),
        TURBO("TurboIcon", R.drawable.icon_5_background_sa, R.mipmap.icon_5_foreground_sa, R.string.AppIconTurbo, true),
        NOX("NoxIcon", R.mipmap.icon_2_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconNox, true);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
