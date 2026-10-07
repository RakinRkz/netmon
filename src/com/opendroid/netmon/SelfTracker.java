package com.opendroid.netmon;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.Map;

/**
 * Fallback usage history for when Usage access isn't granted: per-day Mobile/Wi-Fi byte counts
 * built from the traffic counter deltas the sampler sees. Wi-Fi is approximated as total minus
 * mobile. Counter readings persist across pauses, so traffic while the screen was off still lands
 * in the day it's next read on; only traffic while the app wasn't running at all is missed.
 */
final class SelfTracker {
    private static final String FILE = "selftrack";
    private static final long FLUSH_MS = 30_000;
    private static final int KEEP_DAYS = 62;

    private static boolean loaded;
    private static long lastBoot, lastRx, lastTx, lastMRx, lastMTx;
    private static long dayKey;
    private static final long[] day = new long[4];  // mobile rx, mobile tx, wifi rx, wifi tx
    private static long lastFlush;

    private SelfTracker() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Wall-clock time of boot, rounded so the same boot always gives the same value. */
    private static long bootId() {
        return (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000;
    }

    private static void load(Context c) {
        if (loaded) return;
        SharedPreferences p = prefs(c);
        lastBoot = p.getLong("boot", -1);
        lastRx = p.getLong("rx", 0);
        lastTx = p.getLong("tx", 0);
        lastMRx = p.getLong("mrx", 0);
        lastMTx = p.getLong("mtx", 0);
        dayKey = UsageRepo.startOfDay(System.currentTimeMillis());
        long[] d = read(p, dayKey);
        System.arraycopy(d, 0, day, 0, 4);
        loaded = true;
    }

    static synchronized void onCounters(Context c, long rx, long tx, long mrx, long mtx) {
        load(c);
        long boot = bootId();
        long drx, dtx, dmrx, dmtx;
        if (lastBoot == -1) {
            drx = dtx = dmrx = dmtx = 0;  // first reading ever: only a baseline
        } else if (Math.abs(boot - lastBoot) > 2 || rx < lastRx || tx < lastTx) {
            // Boot time drifts slightly with clock adjustments; more than a couple of minutes means a reboot.
            drx = rx; dtx = tx; dmrx = mrx; dmtx = mtx;  // counters restarted from zero
        } else {
            drx = rx - lastRx;
            dtx = tx - lastTx;
            dmrx = Math.max(0, mrx - lastMRx);
            dmtx = Math.max(0, mtx - lastMTx);
        }
        lastBoot = boot;
        lastRx = rx; lastTx = tx; lastMRx = mrx; lastMTx = mtx;

        long today = UsageRepo.startOfDay(System.currentTimeMillis());
        if (today != dayKey) {
            write(c);
            dayKey = today;
            long[] d = read(prefs(c), dayKey);
            System.arraycopy(d, 0, day, 0, 4);
        }
        day[0] += dmrx;
        day[1] += dmtx;
        day[2] += Math.max(0, drx - dmrx);
        day[3] += Math.max(0, dtx - dmtx);

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
                .putLong("boot", lastBoot).putLong("rx", lastRx).putLong("tx", lastTx)
                .putLong("mrx", lastMRx).putLong("mtx", lastMTx)
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
