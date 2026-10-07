package com.opendroid.netmon;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Data usage, from Android's NetworkStatsManager when Usage access is granted (accurate, includes
 * history from before install, per app) and from {@link SelfTracker} otherwise. All queries block;
 * run them on {@link #IO}.
 */
final class UsageRepo {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UsageRepo() {}

    static final class Usage {
        long mobileRx, mobileTx, wifiRx, wifiTx;

        long mobile() { return mobileRx + mobileTx; }
        long wifi() { return wifiRx + wifiTx; }
        long total() { return mobile() + wifi(); }

        void add(Usage o) {
            mobileRx += o.mobileRx;
            mobileTx += o.mobileTx;
            wifiRx += o.wifiRx;
            wifiTx += o.wifiTx;
        }
    }

    static final class DayUsage {
        final long dayStart;
        final Usage usage;

        DayUsage(long dayStart, Usage usage) {
            this.dayStart = dayStart;
            this.usage = usage;
        }
    }

    static final class AppUsage {
        final int uid;
        String label;
        String packageName;
        Drawable icon;
        final Usage usage = new Usage();

        AppUsage(int uid) { this.uid = uid; }
    }

    interface Callback<T> {
        void done(T result);
    }

    /** Runs a query on the IO thread and delivers the result on the main thread. */
    static <T> void async(final java.util.concurrent.Callable<T> query, final Callback<T> cb) {
        IO.execute(() -> {
            T r;
            try {
                r = query.call();
            } catch (Exception e) {
                r = null;
            }
            final T result = r;
            MAIN.post(() -> cb.done(result));
        });
    }

    @SuppressWarnings("deprecation")
    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        int mode;
        if (Build.VERSION.SDK_INT >= 29) {
            mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        } else {
            mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // ---- device totals ----

    /** Usage between start and end (epoch ms). */
    static Usage query(Context c, long start, long end) {
        if (hasUsageAccess(c)) {
            Usage u = systemQuery(c, start, end);
            if (u != null) return u;
        }
        return selfQuery(c, start, end);
    }

    @SuppressWarnings("deprecation")
    private static Usage systemQuery(Context c, long start, long end) {
        NetworkStatsManager nsm = c.getSystemService(NetworkStatsManager.class);
        try {
            Usage u = new Usage();
            // A null subscriber id means "all mobile networks" (API 29+).
            NetworkStats.Bucket m = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end);
            if (m != null) {
                u.mobileRx = m.getRxBytes();
                u.mobileTx = m.getTxBytes();
            }
            NetworkStats.Bucket w = nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, start, end);
            if (w != null) {
                u.wifiRx = w.getRxBytes();
                u.wifiTx = w.getTxBytes();
            }
            return u;
        } catch (Exception e) {
            return null;
        }
    }

    private static Usage selfQuery(Context c, long start, long end) {
        Usage u = new Usage();
        for (long d = startOfDay(start); d < end; d = addDays(d, 1)) {
            long[] v = SelfTracker.day(c, d);
            u.mobileRx += v[0];
            u.mobileTx += v[1];
            u.wifiRx += v[2];
            u.wifiTx += v[3];
        }
        return u;
    }

    /** The last n days including today, oldest first. */
    static List<DayUsage> days(Context c, int n) {
        List<DayUsage> out = new ArrayList<>(n);
        long today = startOfDay(System.currentTimeMillis());
        long now = System.currentTimeMillis();
        boolean system = hasUsageAccess(c);
        for (int i = n - 1; i >= 0; i--) {
            long start = addDays(today, -i);
            long end = i == 0 ? now : addDays(start, 1);
            Usage u = system ? systemQuery(c, start, end) : null;
            if (u == null) u = selfQuery(c, start, end);
            out.add(new DayUsage(start, u));
        }
        return out;
    }

    // ---- per app ----

    /** Per-app usage between start and end, largest first. Empty without Usage access. */
    @SuppressWarnings("deprecation")
    static List<AppUsage> apps(Context c, long start, long end) {
        if (!hasUsageAccess(c)) return Collections.emptyList();
        NetworkStatsManager nsm = c.getSystemService(NetworkStatsManager.class);
        Map<Integer, AppUsage> byUid = new HashMap<>();
        collect(nsm, ConnectivityManager.TYPE_MOBILE, start, end, byUid, true);
        collect(nsm, ConnectivityManager.TYPE_WIFI, start, end, byUid, false);

        PackageManager pm = c.getPackageManager();
        List<AppUsage> out = new ArrayList<>();
        for (AppUsage a : byUid.values()) {
            if (a.usage.total() == 0) continue;
            describe(pm, a);
            out.add(a);
        }
        Collections.sort(out, (x, y) -> Long.compare(y.usage.total(), x.usage.total()));
        return out;
    }

    private static void collect(NetworkStatsManager nsm, int type, long start, long end,
                                Map<Integer, AppUsage> byUid, boolean mobile) {
        NetworkStats stats = null;
        try {
            stats = nsm.querySummary(type, null, start, end);
            NetworkStats.Bucket b = new NetworkStats.Bucket();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b);
                AppUsage a = byUid.get(b.getUid());
                if (a == null) {
                    a = new AppUsage(b.getUid());
                    byUid.put(b.getUid(), a);
                }
                if (mobile) {
                    a.usage.mobileRx += b.getRxBytes();
                    a.usage.mobileTx += b.getTxBytes();
                } else {
                    a.usage.wifiRx += b.getRxBytes();
                    a.usage.wifiTx += b.getTxBytes();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (stats != null) stats.close();
        }
    }

    private static void describe(PackageManager pm, AppUsage a) {
        switch (a.uid) {
            case NetworkStats.Bucket.UID_REMOVED:
                a.label = "Removed apps";
                return;
            case NetworkStats.Bucket.UID_TETHERING:
                a.label = "Hotspot & tethering";
                return;
            case Process.SYSTEM_UID:
                a.label = "Android system";
                return;
            case 0:
                a.label = "System (root)";
                return;
            default:
                break;
        }
        String[] pkgs = pm.getPackagesForUid(a.uid);
        if (pkgs == null || pkgs.length == 0) {
            a.label = systemUidName(a.uid);
            return;
        }
        // Shared-uid groups list several packages; prefer one with a launcher icon.
        String pick = pkgs[0];
        for (String p : pkgs) {
            if (pm.getLaunchIntentForPackage(p) != null) {
                pick = p;
                break;
            }
        }
        a.packageName = pick;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pick, 0);
            a.label = pm.getApplicationLabel(info).toString();
            a.icon = pm.getApplicationIcon(info);
        } catch (PackageManager.NameNotFoundException e) {
            a.label = pick;
        }
        if (pkgs.length > 1) a.label += " +" + (pkgs.length - 1);
    }

    /** Names for well-known Android system uids that own no package. */
    private static String systemUidName(int uid) {
        switch (uid) {
            case 1013: return "Media server";
            case 1020: return "Local network discovery (mDNS)";
            case 1021: return "GPS assistance";
            case 1051: return "DNS";
            case 1052: return "Hotspot DNS";
            case 1073: return "Network stack";
            default:
                return uid < Process.FIRST_APPLICATION_UID ? "Android system (uid " + uid + ")" : "Unknown app (uid " + uid + ")";
        }
    }

    // ---- calendar helpers ----

    static long startOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long addDays(long dayStart, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    /** Start of the current billing cycle that begins on cycleDay (1-28) of each month. */
    static long cycleStart(long now, int cycleDay) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startOfDay(now));
        if (c.get(Calendar.DAY_OF_MONTH) < cycleDay) c.add(Calendar.MONTH, -1);
        c.set(Calendar.DAY_OF_MONTH, cycleDay);
        return c.getTimeInMillis();
    }

    static long cycleEnd(long cycleStart) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(cycleStart);
        c.add(Calendar.MONTH, 1);
        return c.getTimeInMillis();
    }
}
