package com.opendroid.netmon;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Number formatting. Byte amounts and byte speeds use 1024 steps (KB, MB, GB);
 * bit speeds use 1000 steps (Kbps, Mbps) as network speeds are usually quoted.
 * Speeds never go below the kilo unit so idle noise doesn't flicker between B/s and KB/s.
 */
final class Fmt {
    private static final String[] BYTE_UNITS = {"B", "KB", "MB", "GB", "TB"};
    private static final String[] BYTE_SPEED_UNITS = {"KB/s", "MB/s", "GB/s"};
    private static final String[] BIT_SPEED_UNITS = {"Kbps", "Mbps", "Gbps"};

    private Fmt() {}

    /** A speed split into a number and unit, e.g. {"1.25", "MB/s"}. */
    static String[] speedParts(double bytesPerSec, boolean bits) {
        double base = bits ? 1000 : 1024;
        double v = (bits ? bytesPerSec * 8 : bytesPerSec) / base;
        String[] units = bits ? BIT_SPEED_UNITS : BYTE_SPEED_UNITS;
        int i = 0;
        while (v >= 999.5 && i < units.length - 1) {
            v /= base;
            i++;
        }
        return new String[] {v < 0.05 ? "0" : number(v), units[i]};
    }

    static String speed(double bytesPerSec, boolean bits) {
        String[] p = speedParts(bytesPerSec, bits);
        return p[0] + " " + p[1];
    }

    /** At most three significant characters, for the status bar icon: "0", "9.9", "12", "999". */
    static String[] iconParts(double bytesPerSec, boolean bits) {
        double base = bits ? 1000 : 1024;
        double v = (bits ? bytesPerSec * 8 : bytesPerSec) / base;
        String[] units = bits ? BIT_SPEED_UNITS : BYTE_SPEED_UNITS;
        int i = 0;
        while (v >= 999.5 && i < units.length - 1) {
            v /= base;
            i++;
        }
        String n;
        if (v < 0.05) n = "0";
        else if (v < 9.95) n = String.format(Locale.US, "%.1f", v);
        else n = String.valueOf(Math.round(v));
        return new String[] {n, units[i]};
    }

    static String bytes(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        int i = 0;
        while (v >= 1000 && i < BYTE_UNITS.length - 1) {
            v /= 1024;
            i++;
        }
        return number(v) + " " + BYTE_UNITS[i];
    }

    /** 3 significant digits: 1.25, 12.5, 125. */
    static String number(double v) {
        if (v < 10) return String.format(Locale.US, "%.2f", v);
        if (v < 100) return String.format(Locale.US, "%.1f", v);
        return String.valueOf(Math.round(v));
    }

    /** Clean axis label: no trailing ".0". */
    static String tick(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        String s = String.format(Locale.US, "%.2f", v);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    static String dayLabel(long dayStart) {
        long today = UsageRepo.startOfDay(System.currentTimeMillis());
        if (dayStart == today) return "Today";
        if (dayStart == UsageRepo.addDays(today, -1)) return "Yesterday";
        return new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(dayStart);
    }

    static String shortDate(long t) {
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(t);
    }

    static int dayOfMonth(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.DAY_OF_MONTH);
    }

    static String duration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        long h = m / 60;
        return h + "h " + (m % 60) + "m";
    }
}
