package com.opendroid.netmon;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.Map;

/**
 * Fallback usage history for when Usage access isn't granted: per-day Mobile/Wi-Fi byte counts
 * built from the traffic deltas the sampler sees. "Wi-Fi" is internet traffic that isn't mobile.
 * Traffic while the screen was off lands in the day it's next read on; only traffic while the app
 * wasn't running at all is missed.
 */
final class SelfTracker {
    private static final String FILE = "selftrack";
    private static final long FLUSH_MS = 30_000;
    private static final int KEEP_DAYS = 62;

    private static boolean loaded;
    private static long dayKey;
    private static final long[] day = new long[4];  // mobile rx, mobile tx, wifi rx, wifi tx
    private static long lastFlush;

    private SelfTracker() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static void load(Context c) {
        if (loaded) return;
        SharedPreferences p = prefs(c);
        dayKey = UsageRepo.startOfDay(System.currentTimeMillis());
        long[] d = read(p, dayKey);
        System.arraycopy(d, 0, day, 0, 4);
        loaded = true;
    }

    /** Adds traffic (bytes since the previous call) to today's counts. */
    static synchronized void add(Context c, long mobileRx, long mobileTx, long wifiRx, long wifiTx) {
        load(c);
        long today = UsageRepo.startOfDay(System.currentTimeMillis());
        if (today != dayKey) {
            write(c);
            dayKey = today;
            long[] d = read(prefs(c), dayKey);
            System.arraycopy(d, 0, day, 0, 4);
        }
        day[0] += mobileRx;
        day[1] += mobileTx;
        day[2] += wifiRx;
        day[3] += wifiTx;

        long now = SystemClock.elapsedRealtime();
        if (now - lastFlush > FLUSH_MS) write(c);
    }

    static synchronized void flush(Context c) {
        if (loaded) write(c);
    }

    /** {mobileRx, mobileTx, wifiRx, wifiTx} for the day starting at dayStart. */
    static synchronized long[] day(Context c, long dayStart) {
        load(c);
        if (dayStart == dayKey) return day.clone();
        return read(prefs(c), dayStart);
    }

    private static void write(Context c) {
        lastFlush = SystemClock.elapsedRealtime();
        SharedPreferences.Editor e = prefs(c).edit()
                .putString(key(dayKey), day[0] + "," + day[1] + "," + day[2] + "," + day[3]);
        long oldest = UsageRepo.addDays(dayKey, -KEEP_DAYS);
        for (Map.Entry<String, ?> entry : prefs(c).getAll().entrySet()) {
            String k = entry.getKey();
            if (k.startsWith("d_")) {
                try {
                    if (Long.parseLong(k.substring(2)) < oldest) e.remove(k);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        e.apply();
    }

    private static String key(long dayStart) { return "d_" + dayStart; }

    private static long[] read(SharedPreferences p, long dayStart) {
        long[] out = new long[4];
        String s = p.getString(key(dayStart), null);
        if (s == null) return out;
        String[] parts = s.split(",");
        for (int i = 0; i < 4 && i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
