package com.opendroid.netmon;

import android.content.Context;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Live speed, the last-minute graph, this session, today and the billing cycle. */
final class DashboardPage extends Page implements SpeedMonitor.Listener {
    private static final long USAGE_REFRESH_MS = 10_000;
    private static final long MB = 1024L * 1024;

    private TextView network, downValue, upValue, sessionTitle, sessDown, sessUp, sessPeak;
    private TextView todayMobile, todayMobileSplit, todayWifi, todayWifiSplit, todayTotal;
    private TextView cycleTitle, cycleValue, cycleNote, wifiCycle, toggle, source;
    private View meter;
    private LinearLayout meterRow, banners;
    private SpeedGraphView graph;
    private long lastUsageLoad;
    private boolean loading;

    DashboardPage(MainActivity a) {
        super(a);
    }

    @Override
    View build() {
        Context c = act;
        ScrollView scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        LinearLayout col = Ui.col(c);
        int p = Ui.dp(c, 16);
        col.setPadding(p, Ui.dp(c, 8), p, p);
        scroll.addView(col);

        // Header
        LinearLayout header = Ui.row(c);
        header.addView(Ui.title(c, "NetMon"));
        header.addView(Ui.spacer(c));
        network = Ui.medium(c, "", 13, R.color.ink2);
        int ph = Ui.dp(c, 12), pv = Ui.dp(c, 6);
        network.setPadding(ph, pv, ph, pv);
        GradientDrawable chip = Ui.rounded(Ui.color(c, R.color.surface), Ui.dp(c, 16));
        chip.setStroke(Math.max(1, Ui.dp(c, 0.75f)), Ui.color(c, R.color.border));
        network.setBackground(chip);
        header.addView(network);
        col.addView(header, Ui.matchWrap(c, 8));

        banners = Ui.col(c);
        col.addView(banners, Ui.matchWrap(c, 0));

        // Speed tiles
        LinearLayout speeds = Ui.row(c);
        downValue = new TextView(c);
        upValue = new TextView(c);
        speeds.addView(speedTile(c, "Download", R.color.series1, downValue), Ui.weight(1));
        View gap = new View(c);
        speeds.addView(gap, Ui.lp(Ui.dp(c, 12), 1));
        speeds.addView(speedTile(c, "Upload", R.color.series2, upValue), Ui.weight(1));
        col.addView(speeds, Ui.matchWrap(c, 16));

        // Live graph
        LinearLayout graphCard = Ui.card(c);
        LinearLayout gh = Ui.row(c);
        gh.addView(Ui.label(c, "Last 60 seconds"));
        gh.addView(Ui.spacer(c));
        gh.addView(Ui.key(c, Ui.color(c, R.color.series1), "Download"));
        View kgap = new View(c);
        gh.addView(kgap, Ui.lp(Ui.dp(c, 12), 1));
        gh.addView(Ui.key(c, Ui.color(c, R.color.series2), "Upload"));
        graphCard.addView(gh);
        graph = new SpeedGraphView(c);
        LinearLayout.LayoutParams glp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 190));
        glp.topMargin = Ui.dp(c, 10);
        graphCard.addView(graph, glp);
        col.addView(graphCard, Ui.matchWrap(c, 12));

        // Session
        LinearLayout session = Ui.card(c);
        sessionTitle = Ui.label(c, "This session");
        session.addView(sessionTitle);
        LinearLayout sr = Ui.row(c);
        sessDown = new TextView(c);
        sessUp = new TextView(c);
        sessPeak = new TextView(c);
        sr.addView(stat(c, "Downloaded", sessDown), Ui.weight(1));
        sr.addView(stat(c, "Uploaded", sessUp), Ui.weight(1));
        sr.addView(stat(c, "Peak download", sessPeak), Ui.weight(1));
        session.addView(sr, Ui.matchWrap(c, 10));
        col.addView(session, Ui.matchWrap(c, 12));

        // Today
        LinearLayout today = Ui.card(c);
        LinearLayout th = Ui.row(c);
        th.addView(Ui.label(c, "Today"));
        th.addView(Ui.spacer(c));
        todayTotal = Ui.medium(c, "", 13, R.color.ink2);
        th.addView(todayTotal);
        today.addView(th);
        todayMobile = new TextView(c);
        todayMobileSplit = Ui.text(c, "", 12, R.color.muted);
        todayWifi = new TextView(c);
        todayWifiSplit = Ui.text(c, "", 12, R.color.muted);
        LinearLayout tr = Ui.row(c);
        tr.addView(usageStat(c, "Mobile", R.color.series2, todayMobile, todayMobileSplit), Ui.weight(1));
        tr.addView(usageStat(c, "Wi-Fi", R.color.series1, todayWifi, todayWifiSplit), Ui.weight(1));
        today.addView(tr, Ui.matchWrap(c, 10));
        col.addView(today, Ui.matchWrap(c, 12));

        // Billing cycle
        LinearLayout cycle = Ui.card(c);
        cycleTitle = Ui.label(c, "Mobile data this cycle");
        cycle.addView(cycleTitle);
        cycleValue = new TextView(c);
        cycleValue.setTypeface(Ui.MEDIUM);
        cycleValue.setTextColor(Ui.color(c, R.color.ink));
        cycleValue.setTextSize(24);
        cycle.addView(cycleValue, Ui.matchWrap(c, 6));
        meterRow = Ui.row(c);
        meter = new View(c);
        meterRow.addView(meter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 8)));
        cycle.addView(meterRow, Ui.matchWrap(c, 10));
        cycleNote = Ui.text(c, "", 13, R.color.ink2);
        cycle.addView(cycleNote, Ui.matchWrap(c, 8));
        wifiCycle = Ui.text(c, "", 13, R.color.muted);
        cycle.addView(wifiCycle, Ui.matchWrap(c, 2));
        cycle.setOnClickListener(v -> act.select(MainActivity.TAB_SETTINGS));
        col.addView(cycle, Ui.matchWrap(c, 12));

        toggle = Ui.button(c, "", false);
        toggle.setOnClickListener(v -> {
            if (SpeedService.isRunning()) act.stopMeter();
            else act.startMeter();
            v.postDelayed(this::renderToggle, 300);
        });
        col.addView(toggle, Ui.matchWrap(c, 16));

        source = Ui.text(c, "", 12, R.color.muted);
        source.setGravity(Gravity.CENTER);
        col.addView(source, Ui.matchWrap(c, 12));
        return scroll;
    }

    private LinearLayout speedTile(Context c, String name, int colorRes, TextView value) {
        LinearLayout t = Ui.card(c);
        t.addView(Ui.key(c, Ui.color(c, colorRes), name));
        value.setTextColor(Ui.color(c, R.color.ink));
        value.setTypeface(Ui.MEDIUM);
        value.setTextSize(34);
        value.setSingleLine(true);
        t.addView(value, Ui.matchWrap(c, 6));
        return t;
    }

    private LinearLayout stat(Context c, String name, TextView value) {
        LinearLayout s = Ui.col(c);
        value.setTextColor(Ui.color(c, R.color.ink));
        value.setTypeface(Ui.MEDIUM);
        value.setTextSize(17);
        s.addView(value);
        s.addView(Ui.text(c, name, 12, R.color.muted));
        return s;
    }

    private LinearLayout usageStat(Context c, String name, int colorRes, TextView value, TextView split) {
        LinearLayout s = Ui.col(c);
        s.addView(Ui.key(c, Ui.color(c, colorRes), name));
        value.setTextColor(Ui.color(c, R.color.ink));
        value.setTypeface(Ui.MEDIUM);
        value.setTextSize(22);
        s.addView(value, Ui.matchWrap(c, 4));
        s.addView(split);
        return s;
    }

    @Override
    void onShow() {
        SpeedMonitor.get(act).addListener(this);
        renderBanners();
        renderToggle();
        renderSpeed(SpeedMonitor.get(act));
        lastUsageLoad = 0;
        loadUsage();
    }

    @Override
    void onHide() {
        SpeedMonitor.get(act).removeListener(this);
    }

    @Override
    public void onTick(SpeedMonitor m) {
        renderSpeed(m);
        if (System.currentTimeMillis() - lastUsageLoad > USAGE_REFRESH_MS) loadUsage();
    }

    private void renderSpeed(SpeedMonitor m) {
        boolean bits = Prefs.useBits(act);
        String[] d = Fmt.speedParts(m.down(), bits), u = Fmt.speedParts(m.up(), bits);
        downValue.setText(Ui.valueUnit(act, d[0], d[1], 0.45f));
        upValue.setText(Ui.valueUnit(act, u[0], u[1], 0.45f));
        network.setText(m.networkName());
        graph.setData(m, bits);
        sessionTitle.setText("This session  ·  " + Fmt.duration(System.currentTimeMillis() - m.sessionStart()));
        sessDown.setText(Fmt.bytes(m.sessionRx()));
        sessUp.setText(Fmt.bytes(m.sessionTx()));
        sessPeak.setText(Fmt.speed(m.peakDown(), bits));
    }

    private void renderToggle() {
        boolean on = SpeedService.isRunning();
        toggle.setText(on ? "Stop status bar meter" : "Start status bar meter");
    }

    private void renderBanners() {
        banners.removeAllViews();
        if (!act.notificationsAllowed()) {
            banners.addView(banner("Notifications are off, so the speed can't appear in the status bar.",
                    "Allow", act::fixNotifications), Ui.matchWrap(act, 12));
        }
        if (!UsageRepo.hasUsageAccess(act)) {
            banners.addView(banner("Allow usage access for accurate daily totals, history from before install and per-app usage.",
                    "Grant", act::openUsageAccess), Ui.matchWrap(act, 12));
        }
    }

    private View banner(String message, String action, Runnable onClick) {
        LinearLayout b = Ui.card(act);
        b.setBackground(Ui.rounded(Ui.color(act, R.color.accent_wash), Ui.dp(act, 18)));
        b.addView(Ui.text(act, message, 14, R.color.ink));
        TextView btn = Ui.button(act, action, true);
        btn.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 10);
        lp.gravity = Gravity.END;
        b.addView(btn, lp);
        return b;
    }

    private void loadUsage() {
        if (loading) return;
        loading = true;
        lastUsageLoad = System.currentTimeMillis();
        final Context app = act.getApplicationContext();
        final int cycleDay = Prefs.cycleDay(app);
        UsageRepo.async(() -> {
            long now = System.currentTimeMillis();
            long cs = UsageRepo.cycleStart(now, cycleDay);
            return new Object[] {
                    UsageRepo.query(app, UsageRepo.startOfDay(now), now),
                    UsageRepo.query(app, cs, now),
                    cs,
                    UsageRepo.hasUsageAccess(app)};
        }, r -> {
            loading = false;
            if (r == null) return;
            renderUsage((UsageRepo.Usage) r[0], (UsageRepo.Usage) r[1], (Long) r[2], (Boolean) r[3]);
        });
    }

    private void renderUsage(UsageRepo.Usage today, UsageRepo.Usage cycle, long cycleStart, boolean system) {
        todayMobile.setText(Ui.bytesValue(act, today.mobile(), 0.6f));
        todayMobileSplit.setText("↓ " + Fmt.bytes(today.mobileRx) + "  ↑ " + Fmt.bytes(today.mobileTx));
        todayWifi.setText(Ui.bytesValue(act, today.wifi(), 0.6f));
        todayWifiSplit.setText("↓ " + Fmt.bytes(today.wifiRx) + "  ↑ " + Fmt.bytes(today.wifiTx));
        todayTotal.setText("Total " + Fmt.bytes(today.total()));

        long limit = Prefs.monthlyLimitMb(act) * MB;
        long used = cycle.mobile();
        String resets = "Resets " + Fmt.shortDate(UsageRepo.cycleEnd(cycleStart));
        if (limit > 0) {
            cycleValue.setText(Ui.valueUnit(act, Fmt.bytes(used), "of " + Fmt.bytes(limit), 0.6f));
            int pct = (int) Math.min(100, used * 100 / limit);
            int warn = Prefs.warnPct(act);
            int fillColor = used >= limit ? R.color.critical : pct >= warn ? R.color.warning : R.color.accent;
            setMeter(pct, Ui.color(act, fillColor));
            meterRow.setVisibility(View.VISIBLE);
            String state = used >= limit ? "⚠ Limit reached" : pct >= warn ? "⚠ " + pct + "% used" : pct + "% used";
            cycleNote.setText(state + "  ·  " + Fmt.bytes(Math.max(0, limit - used)) + " left  ·  " + resets);
        } else {
            cycleValue.setText(Ui.bytesValue(act, used, 0.6f));
            meterRow.setVisibility(View.GONE);
            cycleNote.setText("No limit set. Tap to set a monthly limit  ·  " + resets);
        }
        cycleTitle.setText("Mobile data since " + Fmt.shortDate(cycleStart));
        wifiCycle.setText("Wi-Fi in the same period: " + Fmt.bytes(cycle.wifi()));
        source.setText(system
                ? "Usage figures from Android's network statistics"
                : "Usage counted by NetMon while it runs. Grant usage access for complete figures.");
    }

    private void setMeter(int pct, int fillColor) {
        float r = Ui.dp(act, 4);
        GradientDrawable track = Ui.rounded(Ui.color(act, R.color.grid), r);
        GradientDrawable fill = Ui.rounded(fillColor, r);
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        clip.setLevel(pct * 100);
        meter.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[] {track, clip}));
    }
}
