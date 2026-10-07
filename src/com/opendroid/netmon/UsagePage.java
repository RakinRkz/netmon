package com.opendroid.netmon;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** The last 30 days: totals, a stacked daily chart and the same numbers as a table. */
final class UsagePage extends Page {
    private static final int DAYS = 30;

    private TextView totMobile, totWifi, totAll, week, source;
    private UsageChartView chart;
    private LinearLayout table;
    private boolean loading;

    UsagePage(MainActivity a) {
        super(a);
    }

    @Override
    View build() {
        Context c = act;
        ScrollView scroll = new ScrollView(c);
        LinearLayout col = Ui.col(c);
        int p = Ui.dp(c, 16);
        col.setPadding(p, Ui.dp(c, 8), p, p);
        scroll.addView(col);

        col.addView(Ui.title(c, "Usage"), Ui.matchWrap(c, 8));
        col.addView(Ui.text(c, "Last 30 days, mobile and Wi-Fi", 14, R.color.ink2), Ui.matchWrap(c, 2));

        LinearLayout totals = Ui.card(c);
        LinearLayout tr = Ui.row(c);
        totMobile = new TextView(c);
        totWifi = new TextView(c);
        totAll = new TextView(c);
        tr.addView(total(c, "Mobile", R.color.series2, totMobile), Ui.weight(1));
        tr.addView(total(c, "Wi-Fi", R.color.series1, totWifi), Ui.weight(1));
        tr.addView(total(c, "Total", 0, totAll), Ui.weight(1));
        totals.addView(tr);
        week = Ui.text(c, "", 13, R.color.ink2);
        totals.addView(week, Ui.matchWrap(c, 10));
        col.addView(totals, Ui.matchWrap(c, 16));

        LinearLayout chartCard = Ui.card(c);
        LinearLayout ch = Ui.row(c);
        ch.addView(Ui.label(c, "Daily usage"));
        ch.addView(Ui.spacer(c));
        ch.addView(Ui.key(c, Ui.color(c, R.color.series1), "Wi-Fi"));
        View g = new View(c);
        ch.addView(g, Ui.lp(Ui.dp(c, 12), 1));
        ch.addView(Ui.key(c, Ui.color(c, R.color.series2), "Mobile"));
        chartCard.addView(ch);
        chart = new UsageChartView(c);
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 220));
        lp.topMargin = Ui.dp(c, 10);
        chartCard.addView(chart, lp);
        chartCard.addView(Ui.text(c, "Tap or drag across the bars for a day's numbers", 12, R.color.muted), Ui.matchWrap(c, 6));
        col.addView(chartCard, Ui.matchWrap(c, 12));

        LinearLayout tableCard = Ui.card(c);
        tableCard.addView(Ui.label(c, "By day"));
        LinearLayout head = tableRow(c, "Day", "Mobile", "Wi-Fi", "Total", true);
        tableCard.addView(head, Ui.matchWrap(c, 10));
        tableCard.addView(Ui.hairline(c), Ui.matchWrap(c, 6));
        table = Ui.col(c);
        tableCard.addView(table);
        col.addView(tableCard, Ui.matchWrap(c, 12));

        source = Ui.text(c, "", 12, R.color.muted);
        source.setGravity(Gravity.CENTER);
        col.addView(source, Ui.matchWrap(c, 12));
        return scroll;
    }

    private LinearLayout total(Context c, String name, int colorRes, TextView value) {
        LinearLayout s = Ui.col(c);
        if (colorRes != 0) s.addView(Ui.key(c, Ui.color(c, colorRes), name));
        else s.addView(Ui.text(c, name, 12, R.color.ink2));
        value.setTextColor(Ui.color(c, R.color.ink));
        value.setTypeface(Ui.MEDIUM);
        value.setTextSize(20);
        s.addView(value, Ui.matchWrap(c, 4));
        return s;
    }

    private LinearLayout tableRow(Context c, String day, String mobile, String wifi, String total, boolean header) {
        LinearLayout r = Ui.row(c);
        int color = header ? R.color.muted : R.color.ink;
        float size = header ? 12 : 14;
        TextView d = header ? Ui.text(c, day, size, color) : Ui.medium(c, day, size, color);
        r.addView(d, Ui.weight(1.4f));
        r.addView(cell(c, mobile, size, header ? R.color.muted : R.color.ink2), Ui.weight(1));
        r.addView(cell(c, wifi, size, header ? R.color.muted : R.color.ink2), Ui.weight(1));
        r.addView(cell(c, total, size, color), Ui.weight(1));
        if (!header) r.setPadding(0, Ui.dp(c, 9), 0, Ui.dp(c, 9));
        return r;
    }

    private TextView cell(Context c, String s, float size, int color) {
        TextView t = Ui.text(c, s, size, color);
        t.setGravity(Gravity.END);
        t.setFontFeatureSettings("tnum");  // aligned digits in columns
        return t;
    }

    @Override
    void onShow() {
        if (loading) return;
        loading = true;
        final Context app = act.getApplicationContext();
        UsageRepo.async(() -> UsageRepo.days(app, DAYS), days -> {
            loading = false;
            if (days != null) render(days, UsageRepo.hasUsageAccess(app));
        });
    }

    private void render(List<UsageRepo.DayUsage> days, boolean system) {
        UsageRepo.Usage sum = new UsageRepo.Usage(), last7 = new UsageRepo.Usage();
        for (int i = 0; i < days.size(); i++) {
            sum.add(days.get(i).usage);
            if (i >= days.size() - 7) last7.add(days.get(i).usage);
        }
        totMobile.setText(Ui.bytesValue(act, sum.mobile(), 0.6f));
        totWifi.setText(Ui.bytesValue(act, sum.wifi(), 0.6f));
        totAll.setText(Ui.bytesValue(act, sum.total(), 0.6f));
        week.setText("Last 7 days: " + Fmt.bytes(last7.total()) + "  (mobile " + Fmt.bytes(last7.mobile())
                + ", Wi-Fi " + Fmt.bytes(last7.wifi()) + ")");
        chart.setData(days);

        table.removeAllViews();
        for (int i = days.size() - 1; i >= 0; i--) {
            UsageRepo.DayUsage d = days.get(i);
            table.addView(tableRow(act, Fmt.dayLabel(d.dayStart), Fmt.bytes(d.usage.mobile()),
                    Fmt.bytes(d.usage.wifi()), Fmt.bytes(d.usage.total()), false));
            if (i > 0) table.addView(Ui.hairline(act));
        }
        source.setText(system
                ? "From Android's network statistics"
                : "Counted by NetMon while running. Grant usage access (Settings tab) for full history.");
    }
}
