package com.opendroid.netmon;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed access to the app's settings. Every setting is read live, so no change listeners are needed. */
final class Prefs {
    static final String ENABLED = "enabled";
    static final String START_ON_BOOT = "start_on_boot";
    static final String USE_BITS = "use_bits";
    static final String SPEED_ICON = "speed_icon";
    static final String ICON_MODE = "icon_mode";
    static final String LOCK_SCREEN = "lock_screen";
    static final String SCREEN_OFF_UPDATES = "screen_off_updates";
    static final String OVERLAY = "overlay";
    static final String OVERLAY_X = "overlay_x";
    static final String OVERLAY_Y = "overlay_y";
    static final String MONTHLY_LIMIT_MB = "monthly_limit_mb";
    static final String DAILY_LIMIT_MB = "daily_limit_mb";
    static final String CYCLE_DAY = "cycle_day";
    static final String WARN_PCT = "warn_pct";
    static final String ALERTED_CYCLE = "alerted_cycle";
    static final String ALERTED_DAY = "alerted_day";
    static final String ASKED_NOTIFICATIONS = "asked_notifications";

    static final int ICON_TOTAL = 0, ICON_DOWNLOAD = 1, ICON_UPLOAD = 2;

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return get(c).getBoolean(ENABLED, true); }
    static boolean startOnBoot(Context c) { return get(c).getBoolean(START_ON_BOOT, true); }
    static boolean useBits(Context c) { return get(c).getBoolean(USE_BITS, false); }
    /** Status bar icon shows the live speed (true) or a plain NetMon glyph (false). */
    static boolean speedIcon(Context c) { return get(c).getBoolean(SPEED_ICON, true); }
    static int iconMode(Context c) { return get(c).getInt(ICON_MODE, ICON_TOTAL); }
    static boolean lockScreen(Context c) { return get(c).getBoolean(LOCK_SCREEN, true); }
    static boolean screenOffUpdates(Context c) { return get(c).getBoolean(SCREEN_OFF_UPDATES, false); }
    static boolean overlay(Context c) { return get(c).getBoolean(OVERLAY, false); }
    static long monthlyLimitMb(Context c) { return get(c).getLong(MONTHLY_LIMIT_MB, 0); }
    static long dailyLimitMb(Context c) { return get(c).getLong(DAILY_LIMIT_MB, 0); }
    static int cycleDay(Context c) { return get(c).getInt(CYCLE_DAY, 1); }
    static int warnPct(Context c) { return get(c).getInt(WARN_PCT, 80); }

    static void put(Context c, String key, boolean v) { get(c).edit().putBoolean(key, v).apply(); }
    static void put(Context c, String key, int v) { get(c).edit().putInt(key, v).apply(); }
    static void put(Context c, String key, long v) { get(c).edit().putLong(key, v).apply(); }
    static void put(Context c, String key, String v) { get(c).edit().putString(key, v).apply(); }
}
