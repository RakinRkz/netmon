package com.opendroid.netmon;

/** A "nice" y-axis (0, 250, 500, 750 KB/s ...) in a human unit chosen from the data's maximum. */
final class Scale {
    /** Bytes (or bytes/s) per display unit. */
    final double unitBytes;
    final String unit;
    /** Tick step and axis top, in display units. */
    final double step, top;

    private Scale(double unitBytes, String unit, double step, double top) {
        this.unitBytes = unitBytes;
        this.unit = unit;
        this.step = step;
        this.top = top;
    }

    double topBytes() { return top * unitBytes; }

    int ticks() { return (int) Math.round(top / step); }

    String label(int i) { return Fmt.tick(step * i) + " " + unit; }

    /** Speed axis; never smaller than 0–10 KB/s so an idle connection doesn't magnify noise. */
    static Scale speed(double maxBytesPerSec, boolean bits) {
        double base = bits ? 1000 : 1024;
        String[] units = bits ? new String[] {"Kbps", "Mbps", "Gbps"} : new String[] {"KB/s", "MB/s", "GB/s"};
        double perUnit = bits ? base / 8 : base;  // bytes/s per "K" unit
        double max = Math.max(maxBytesPerSec, 10 * perUnit);
        int i = 0;
        while (max / perUnit >= 1000 && i < units.length - 1) {
            perUnit *= base;
            i++;
        }
        return make(max / perUnit, perUnit, units[i]);
    }

    /** Data amount axis (1024 steps), at least 0–10 MB. */
    static Scale bytes(double maxBytes) {
        String[] units = {"MB", "GB", "TB"};
        double perUnit = 1024 * 1024;
        double max = Math.max(maxBytes, 10 * perUnit);
        int i = 0;
        while (max / perUnit >= 1000 && i < units.length - 1) {
            perUnit *= 1024;
            i++;
        }
        return make(max / perUnit, perUnit, units[i]);
    }

    private static Scale make(double max, double perUnit, String unit) {
        double raw = max / 3;  // aim for about three intervals
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double n = raw / mag;
        double step = (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag;
        double top = Math.ceil(max / step - 1e-9) * step;
        return new Scale(perUnit, unit, step, top);
    }
}
