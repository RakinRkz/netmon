package com.opendroid.netmon;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** All settings, rebuilt each time the tab is shown so permission states are current. */
final class SettingsPage extends Page {
    private static final String[] ICON_MODES = {"Total speed (download + upload)", "Download speed", "Upload speed"};
    private static final String[] UNITS = {"Bytes  (KB/s, MB/s)", "Bits  (Kbps, Mbps)"};
    private static final int[] WARN_LEVELS = {50, 75, 80, 90};

    private LinearLayout col;
    private boolean overlayPending;

    SettingsPage(MainActivity a) {
        super(a);
    }

    @Override
    View build() {
        ScrollView scroll = new ScrollView(act);
        col = Ui.col(act);
        int p = Ui.dp(act, 16);
        col.setPadding(p, Ui.dp(act, 8), p, p);
        scroll.addView(col);
        return scroll;
    }

    @Override
    void onShow() {
        // Returning from the overlay permission screen finishes turning the floating meter on.
        if (overlayPending) {
            overlayPending = false;
            if (OverlayController.permitted(act)) {
                Prefs.put(act, Prefs.OVERLAY, true);
                SpeedService.refresh();
            }
        }
        render();
    }

    private void changed() {
        SpeedService.refresh();
        render();
    }

    private void render() {
        Context c = act;
        col.removeAllViews();
        col.addView(Ui.title(c, "Settings"), Ui.matchWrap(c, 8));

        LinearLayout meter = section("Status bar meter");
        meter.addView(switchRow("Speed meter", "Live speed in the status bar and notification",
                SpeedService.isRunning(), on -> {
                    if (on) act.startMeter();
                    else act.stopMeter();
                    col.postDelayed(this::render, 300);
                }));
        meter.addView(choiceRow("Status bar shows", ICON_MODES[Prefs.iconMode(c)], () ->
                pick("Status bar shows", ICON_MODES, Prefs.iconMode(c), i -> Prefs.put(c, Prefs.ICON_MODE, i))));
        meter.addView(choiceRow("Units", UNITS[Prefs.useBits(c) ? 1 : 0], () ->
                pick("Units", UNITS, Prefs.useBits(c) ? 1 : 0, i -> Prefs.put(c, Prefs.USE_BITS, i == 1))));
        meter.addView(switchRow("Show on lock screen", "Speed and today's usage stay visible when locked",
                Prefs.lockScreen(c), on -> Prefs.put(c, Prefs.LOCK_SCREEN, on)));
        meter.addView(switchRow("Update while screen is off", "Off saves battery; the meter resumes instantly when you wake the phone",
                Prefs.screenOffUpdates(c), on -> Prefs.put(c, Prefs.SCREEN_OFF_UPDATES, on)));
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            meter.addView(actionRow("Add Quick Settings tile", "Turn the meter on or off from the notification shade",
                    act::addQuickSettingsTile));
        }
        meter.addView(switchRow("Start on boot", "Turn the meter on when the phone restarts",
                Prefs.startOnBoot(c), on -> Prefs.put(c, Prefs.START_ON_BOOT, on)));

        LinearLayout floating = section("Floating meter");
        floating.addView(switchRow("Floating speed meter",
                "A small draggable readout over other apps. Tap it to open NetMon.",
                Prefs.overlay(c) && OverlayController.permitted(c), on -> {
                    if (on && !OverlayController.permitted(c)) {
                        overlayPending = true;
                        Toast.makeText(c, "Allow NetMon to display over other apps", Toast.LENGTH_LONG).show();
                        act.openOverlaySettings();
                        return;
                    }
                    Prefs.put(c, Prefs.OVERLAY, on);
                    if (on && !SpeedService.isRunning()) act.startMeter();
                }));
        floating.addView(actionRow("Reset position", "Move the floating meter back to the top-left", () -> {
            OverlayController.resetPosition(c);
            SpeedService.restartOverlay();
        }));

        LinearLayout limits = section("Mobile data limits");
        limits.addView(choiceRow("Monthly limit", mb(Prefs.monthlyLimitMb(c)), () -> limitDialog("Monthly limit", Prefs.MONTHLY_LIMIT_MB)));
        limits.addView(choiceRow("Billing cycle starts", "Day " + Prefs.cycleDay(c) + " of each month", () -> {
            String[] days = new String[28];
            for (int i = 0; i < 28; i++) days[i] = "Day " + (i + 1);
            pick("Billing cycle starts", days, Prefs.cycleDay(c) - 1, i -> {
                Prefs.put(c, Prefs.CYCLE_DAY, i + 1);
                Prefs.get(c).edit().remove(Prefs.ALERTED_CYCLE).apply();
                SpeedService.clearAlerts(c);
            });
        }));
        limits.addView(choiceRow("Daily limit", mb(Prefs.dailyLimitMb(c)), () -> limitDialog("Daily limit", Prefs.DAILY_LIMIT_MB)));
        String[] warnNames = new String[WARN_LEVELS.length];
        int warnIndex = 2;
        for (int i = 0; i < WARN_LEVELS.length; i++) {
            warnNames[i] = WARN_LEVELS[i] + "% of the limit";
            if (WARN_LEVELS[i] == Prefs.warnPct(c)) warnIndex = i;
        }
        limits.addView(choiceRow("Warn me at", warnNames[warnIndex], () ->
                pick("Warn me at", warnNames, java.util.Arrays.binarySearch(WARN_LEVELS, Prefs.warnPct(c)),
                        i -> Prefs.put(c, Prefs.WARN_PCT, WARN_LEVELS[i]))));

        LinearLayout perms = section("Permissions");
        boolean usage = UsageRepo.hasUsageAccess(c);
        perms.addView(statusRow("Usage access", usage, usage ? "Allowed: full history and per-app usage"
                : "Needed for accurate totals and per-app usage", act::openUsageAccess));
        boolean notif = act.notificationsAllowed();
        perms.addView(statusRow("Notifications", notif, notif ? "Allowed" : "Needed to show speed in the status bar",
                act::fixNotifications));
        boolean overlay = OverlayController.permitted(c);
        perms.addView(statusRow("Display over other apps", overlay, overlay ? "Allowed" : "Needed for the floating meter",
                act::openOverlaySettings));
        boolean battery = act.batteryUnrestricted();
        perms.addView(statusRow("Battery optimization", battery,
                battery ? "Unrestricted: the meter won't be stopped in the background"
                        : "Optimized: the system may stop the meter. Tap to exempt NetMon.",
                act::openBatterySettings));

        LinearLayout about = section("About");
        String version = "1.0";
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        about.addView(infoRow("NetMon " + version,
                "Speeds are sampled once a second from Android's traffic counters. Usage history comes from "
                        + "Android's network statistics when usage access is allowed. NetMon has no internet "
                        + "permission: nothing leaves your phone."));
    }

    // ---- rows ----

    private interface BoolCallback { void run(boolean v); }
    private interface IntCallback { void run(int i); }

    private LinearLayout section(String title) {
        TextView t = Ui.medium(act, title, 13, R.color.accent);
        t.setPadding(Ui.dp(act, 4), 0, 0, 0);
        col.addView(t, Ui.matchWrap(act, 22));
        LinearLayout card = Ui.card(act);
        card.setPadding(Ui.dp(act, 16), Ui.dp(act, 4), Ui.dp(act, 16), Ui.dp(act, 4));
        col.addView(card, Ui.matchWrap(act, 8));
        return card;
    }

    private LinearLayout baseRow(String title, String subtitle) {
        LinearLayout r = Ui.row(act);
        r.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12));
        r.setMinimumHeight(Ui.dp(act, 56));
        LinearLayout texts = Ui.col(act);
        texts.addView(Ui.text(act, title, 16, R.color.ink));
        if (subtitle != null) texts.addView(Ui.text(act, subtitle, 13, R.color.ink2), Ui.matchWrap(act, 2));
        r.addView(texts, Ui.weight(1));
        return r;
    }

    private View switchRow(String title, String subtitle, boolean checked, BoolCallback onChange) {
        LinearLayout r = baseRow(title, subtitle);
        Switch s = new Switch(act);
        s.setChecked(checked);
        s.setPadding(Ui.dp(act, 12), 0, 0, 0);
        s.setOnCheckedChangeListener((b, on) -> {
            onChange.run(on);
            SpeedService.refresh();
        });
        r.addView(s);
        r.setOnClickListener(v -> s.toggle());
        return r;
    }

    private View choiceRow(String title, String value, Runnable onClick) {
        LinearLayout r = baseRow(title, value);
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    private View actionRow(String title, String subtitle, Runnable onClick) {
        return choiceRow(title, subtitle, onClick);
    }

    private View statusRow(String title, boolean ok, String detail, Runnable fix) {
        LinearLayout r = baseRow(title, detail);
        TextView state = Ui.medium(act, ok ? "✓ On" : "⚠ Off", 13, ok ? R.color.ink2 : R.color.ink);
        state.setPadding(Ui.dp(act, 10), Ui.dp(act, 4), Ui.dp(act, 10), Ui.dp(act, 4));
        state.setBackground(Ui.rounded(Ui.color(act, ok ? R.color.grid : R.color.warning), Ui.dp(act, 12)));
        if (!ok) state.setTextColor(0xFF0B0B0B);  // dark ink on the yellow warning chip in both themes
        r.addView(state);
        r.setOnClickListener(v -> fix.run());
        return r;
    }

    private View infoRow(String title, String text) {
        return baseRow(title, text);
    }

    // ---- dialogs ----

    private void pick(String title, String[] items, int checked, IntCallback onPick) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, (d, i) -> {
                    onPick.run(i);
                    d.dismiss();
                    changed();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String mb(long mb) {
        if (mb <= 0) return "Off";
        if (mb >= 1024) {
            double gb = mb / 1024.0;
            return (gb == Math.rint(gb) ? String.valueOf((long) gb) : String.format(Locale.US, "%.1f", gb)) + " GB";
        }
        return mb + " MB";
    }

    private void limitDialog(String title, String key) {
        Context c = act;
        long current = Prefs.get(c).getLong(key, 0);
        LinearLayout box = Ui.col(c);
        int p = Ui.dp(c, 22);
        box.setPadding(p, Ui.dp(c, 8), p, 0);
        EditText input = new EditText(c);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Amount, e.g. 10");
        box.addView(input);
        RadioGroup units = new RadioGroup(c);
        units.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton mbBtn = new RadioButton(c);
        mbBtn.setText("MB");
        mbBtn.setId(View.generateViewId());
        RadioButton gbBtn = new RadioButton(c);
        gbBtn.setText("GB");
        gbBtn.setId(View.generateViewId());
        units.addView(mbBtn);
        units.addView(gbBtn);
        box.addView(units);
        if (current > 0 && current < 1024) {
            input.setText(String.valueOf(current));
            units.check(mbBtn.getId());
        } else {
            if (current > 0) input.setText(mb(current).replace(" GB", ""));
            units.check(gbBtn.getId());
        }
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage("Mobile data only. You'll be warned at " + Prefs.warnPct(c) + "% and when the limit is reached.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        double v = Double.parseDouble(input.getText().toString().trim());
                        long value = Math.round(units.getCheckedRadioButtonId() == gbBtn.getId() ? v * 1024 : v);
                        saveLimit(key, Math.max(0, value));
                    } catch (NumberFormatException e) {
                        Toast.makeText(c, "Enter a number", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Turn off", (d, w) -> saveLimit(key, 0))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveLimit(String key, long mb) {
        Prefs.put(act, key, mb);
        // A new limit deserves fresh alerts.
        Prefs.get(act).edit().remove(Prefs.ALERTED_CYCLE).remove(Prefs.ALERTED_DAY).apply();
        SpeedService.clearAlerts(act);
        changed();
    }
}
