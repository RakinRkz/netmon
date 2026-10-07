package com.opendroid.netmon;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Data used per app, by period and network, largest first. */
final class AppsPage extends Page {
    private static final String[] PERIODS = {"Today", "7 days", "30 days", "This cycle"};
    private static final String[] NETWORKS = {"All networks", "Mobile", "Wi-Fi"};
    private static final int ALL = 0, MOBILE = 1, WIFI = 2;

    private int period = 0, network = ALL;
    private final TextView[] periodChips = new TextView[PERIODS.length];
    private final TextView[] networkChips = new TextView[NETWORKS.length];
    private TextView summary;
    private LinearLayout legend;
    private ListView list;
    private LinearLayout empty;
    private final Adapter adapter = new Adapter();
    private List<UsageRepo.AppUsage> all = Collections.emptyList();
    private int generation;

    AppsPage(MainActivity a) {
        super(a);
    }

    @Override
    View build() {
        Context c = act;
        LinearLayout col = Ui.col(c);
        int p = Ui.dp(c, 16);
        col.setPadding(p, Ui.dp(c, 8), p, 0);
        col.addView(Ui.title(c, "Apps"), Ui.matchWrap(c, 8));

        col.addView(chipRow(c, PERIODS, periodChips, i -> { period = i; load(); }), Ui.matchWrap(c, 12));
        col.addView(chipRow(c, NETWORKS, networkChips, i -> { network = i; filter(); }), Ui.matchWrap(c, 8));
        LinearLayout sr = Ui.row(c);
        summary = Ui.text(c, "", 13, R.color.ink2);
        sr.addView(summary, Ui.weight(1));
        legend = Ui.row(c);
        legend.addView(Ui.key(c, Ui.color(c, R.color.series2), "Mobile"));
        View lg = new View(c);
        legend.addView(lg, Ui.lp(Ui.dp(c, 10), 1));
        legend.addView(Ui.key(c, Ui.color(c, R.color.series1), "Wi-Fi"));
        sr.addView(legend);
        col.addView(sr, Ui.matchWrap(c, 12));

        list = new ListView(c);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 16));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            UsageRepo.AppUsage a = adapter.items.get(pos);
            if (a.packageName == null) return;
            try {
                act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.packageName)));
            } catch (RuntimeException ignored) {
            }
        });
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = Ui.card(c);
        empty.addView(Ui.medium(c, "Usage access needed", 16, R.color.ink));
        empty.addView(Ui.text(c, "Android only shares per-app data usage with apps you allow under Usage access. "
                + "NetMon reads the totals only; nothing leaves your phone.", 14, R.color.ink2), Ui.matchWrap(c, 6));
        TextView grant = Ui.button(c, "Open Usage access", true);
        grant.setOnClickListener(v -> act.openUsageAccess());
        empty.addView(grant, Ui.matchWrap(c, 14));
        LinearLayout.LayoutParams elp = Ui.matchWrap(c, 8);
        col.addView(empty, elp);

        selectChip(periodChips, period);
        selectChip(networkChips, network);
        return col;
    }

    private interface IntCallback {
        void run(int i);
    }

    private View chipRow(Context c, String[] names, TextView[] chips, IntCallback onPick) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        LinearLayout r = Ui.row(c);
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            chips[i] = Ui.chip(c, names[i]);
            chips[i].setOnClickListener(v -> {
                selectChip(chips, index);
                onPick.run(index);
            });
            r.addView(chips[i]);
        }
        h.addView(r);
        return h;
    }

    private void selectChip(TextView[] chips, int index) {
        for (int i = 0; i < chips.length; i++) Ui.setChipSelected(chips[i], i == index);
    }

    @Override
    void onShow() {
        load();
    }

    private void load() {
        final Context app = act.getApplicationContext();
        boolean access = UsageRepo.hasUsageAccess(app);
        empty.setVisibility(access ? View.GONE : View.VISIBLE);
        list.setVisibility(access ? View.VISIBLE : View.GONE);
        if (!access) {
            summary.setText("");
            legend.setVisibility(View.GONE);
            return;
        }
        summary.setText("Loading…");
        final int gen = ++generation;
        final long now = System.currentTimeMillis();
        final long start;
        switch (period) {
            case 0: start = UsageRepo.startOfDay(now); break;
            case 1: start = UsageRepo.addDays(UsageRepo.startOfDay(now), -6); break;
            case 2: start = UsageRepo.addDays(UsageRepo.startOfDay(now), -29); break;
            default: start = UsageRepo.cycleStart(now, Prefs.cycleDay(app)); break;
        }
        UsageRepo.async(() -> UsageRepo.apps(app, start, now), result -> {
            if (gen != generation) return;  // a newer request superseded this one
            all = result != null ? result : Collections.<UsageRepo.AppUsage>emptyList();
            filter();
        });
    }

    private long value(UsageRepo.AppUsage a) {
        return network == MOBILE ? a.usage.mobile() : network == WIFI ? a.usage.wifi() : a.usage.total();
    }

    private void filter() {
        List<UsageRepo.AppUsage> items = new ArrayList<>();
        long sum = 0;
        for (UsageRepo.AppUsage a : all) {
            if (value(a) > 0) {
                items.add(a);
                sum += value(a);
            }
        }
        Collections.sort(items, (x, y) -> Long.compare(value(y), value(x)));
        legend.setVisibility(network == ALL && !items.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.items = items;
        adapter.max = items.isEmpty() ? 1 : value(items.get(0));
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        summary.setText(items.isEmpty()
                ? "No data used in this period"
                : items.size() + (items.size() == 1 ? " app" : " apps") + "  ·  " + Fmt.bytes(sum) + " total");
    }

    private final class Adapter extends BaseAdapter {
        List<UsageRepo.AppUsage> items = Collections.emptyList();
        long max = 1;

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).uid; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Holder h = convert != null ? (Holder) convert.getTag() : new Holder(parent.getContext());
            UsageRepo.AppUsage a = items.get(pos);
            if (a.icon != null) h.icon.setImageDrawable(a.icon);
            else h.icon.setImageResource(R.drawable.ic_apps);
            h.icon.setImageTintList(a.icon != null ? null
                    : android.content.res.ColorStateList.valueOf(Ui.color(act, R.color.muted)));
            h.name.setText(a.label);
            long v = value(a);
            h.value.setText(Fmt.bytes(v));
            long rx = network == MOBILE ? a.usage.mobileRx : network == WIFI ? a.usage.wifiRx : a.usage.mobileRx + a.usage.wifiRx;
            long tx = network == MOBILE ? a.usage.mobileTx : network == WIFI ? a.usage.wifiTx : a.usage.mobileTx + a.usage.wifiTx;
            h.detail.setText("↓ " + Fmt.bytes(rx) + "   ↑ " + Fmt.bytes(tx));
            h.bar.set(network == WIFI ? 0 : a.usage.mobile(), network == MOBILE ? 0 : a.usage.wifi(), max);
            return h.root;
        }
    }

    private final class Holder {
        final LinearLayout root;
        final ImageView icon;
        final TextView name, value, detail;
        final AppBar bar;

        Holder(Context c) {
            root = Ui.row(c);
            root.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
            icon = new ImageView(c);
            int s = Ui.dp(c, 40);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.rightMargin = Ui.dp(c, 14);
            root.addView(icon, ilp);

            LinearLayout mid = Ui.col(c);
            LinearLayout top = Ui.row(c);
            name = Ui.medium(c, "", 15, R.color.ink);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(name, Ui.weight(1));
            value = Ui.medium(c, "", 15, R.color.ink);
            value.setPadding(Ui.dp(c, 8), 0, 0, 0);
            top.addView(value);
            mid.addView(top);
            bar = new AppBar(c);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 6));
            blp.topMargin = Ui.dp(c, 6);
            mid.addView(bar, blp);
            detail = Ui.text(c, "", 12, R.color.muted);
            mid.addView(detail, Ui.matchWrap(c, 4));
            root.addView(mid, Ui.weight(1));
            root.setTag(this);
        }
    }

    /** Proportional bar: mobile then Wi-Fi segments, separated by a 2px gap, scaled to the top app. */
    private static final class AppBar extends View {
        private long mobile, wifi, max = 1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int cMobile, cWifi, cTrack;
        private final float dp;

        AppBar(Context c) {
            super(c);
            cMobile = Ui.color(c, R.color.series2);
            cWifi = Ui.color(c, R.color.series1);
            cTrack = Ui.color(c, R.color.grid);
            dp = c.getResources().getDisplayMetrics().density;
        }

        void set(long mobile, long wifi, long max) {
            this.mobile = mobile;
            this.wifi = wifi;
            this.max = Math.max(1, max);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), rad = h / 2;
            paint.setColor(cTrack);
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, rad, rad, paint);
            float wm = w * mobile / max, ww = w * wifi / max;
            float x = 0;
            if (wm > 0) {
                paint.setColor(cMobile);
                r.set(0, 0, Math.max(wm, h), h);
                canvas.drawRoundRect(r, rad, rad, paint);
                x = r.right + 2 * dp;
            }
            if (ww > 0) {
                paint.setColor(cWifi);
                r.set(x, 0, Math.min(w, x + Math.max(ww, h)), h);
                canvas.drawRoundRect(r, rad, rad, paint);
            }
        }
    }
}
