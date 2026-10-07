package com.opendroid.netmon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * Daily usage as stacked columns: Wi-Fi at the base, mobile on top, a 2px surface gap between
 * segments and a 4px rounded cap. Tap or drag across the bars to read a day's values.
 */
final class UsageChartView extends View {
    private List<UsageRepo.DayUsage> days = Collections.emptyList();
    private int selected = -1;

    private final float dp;
    private final int cWifi, cMobile, cTipBg, cTipInk;
    private final Paint grid = new Paint(), axis = new Paint(), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), tip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];

    private float left, right, top, bottom, slot;

    UsageChartView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        float fs = c.getResources().getConfiguration().fontScale;
        cWifi = Ui.color(c, R.color.series1);
        cMobile = Ui.color(c, R.color.series2);
        cTipBg = Ui.color(c, R.color.tooltip_bg);
        cTipInk = Ui.color(c, R.color.tooltip_ink);
        grid.setColor(Ui.color(c, R.color.grid));
        grid.setStrokeWidth(Math.max(1, 0.75f * dp));
        axis.setColor(Ui.color(c, R.color.axis));
        axis.setStrokeWidth(Math.max(1, 0.75f * dp));
        label.setColor(Ui.color(c, R.color.muted));
        label.setTextSize(11 * dp * fs);
        tip.setColor(cTipBg);
        tipText.setColor(cTipInk);
        tipText.setTextSize(12 * dp * fs);
        setContentDescription("Daily data usage, Wi-Fi and mobile");
    }

    void setData(List<UsageRepo.DayUsage> days) {
        this.days = days;
        selected = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = days.size();
        float w = getWidth(), h = getHeight();
        float labelH = label.getTextSize() * 1.4f;
        left = 0;
        right = w;
        top = labelH + 2 * dp;
        bottom = h - labelH - 4 * dp;
        float plotH = bottom - top;

        long max = 0;
        for (UsageRepo.DayUsage d : days) max = Math.max(max, d.usage.total());
        Scale s = Scale.bytes(max);
        double topBytes = s.topBytes();

        for (int t = 1; t <= s.ticks(); t++) {
            float y = bottom - (float) (t * s.step * s.unitBytes / topBytes) * plotH;
            canvas.drawLine(left, y, right, y, grid);
            label.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(s.label(t), left, y - 4 * dp, label);
        }
        if (n == 0) {
            canvas.drawLine(left, bottom, right, bottom, axis);
            return;
        }

        slot = (right - left) / n;
        float bw = Math.min(24 * dp, slot * 0.64f);
        float gap = 2 * dp;
        float r = Math.min(4 * dp, bw / 2);
        for (int i = 0; i < n; i++) {
            UsageRepo.DayUsage d = days.get(i);
            float cx = left + slot * (i + 0.5f);
            float x0 = cx - bw / 2, x1 = cx + bw / 2;
            float hw = (float) (d.usage.wifi() / topBytes) * plotH;
            float hm = (float) (d.usage.mobile() / topBytes) * plotH;
            int alpha = selected < 0 || selected == i ? 255 : 90;
            boolean both = hw > 0.5f && hm > 0.5f;
            if (hw > 0.5f) {
                bar.setColor(cWifi);
                bar.setAlpha(alpha);
                column(canvas, x0, bottom - hw, x1, bottom, hm > 0.5f ? 0 : r);
            }
            if (hm > 0.5f) {
                float base = bottom - hw - (both ? gap : 0);
                bar.setColor(cMobile);
                bar.setAlpha(alpha);
                column(canvas, x0, base - hm, x1, base, r);
            }
        }
        canvas.drawLine(left, bottom, right, bottom, axis);

        // Date labels every 7 days counting back from today, so "Today" always has one.
        float baseY = bottom + label.getTextSize() + 4 * dp;
        for (int i = n - 1; i >= 0; i -= 7) {
            String text = i == n - 1 ? "Today" : Fmt.shortDate(days.get(i).dayStart);
            float cx = left + slot * (i + 0.5f);
            float tw = label.measureText(text);
            float tx = Math.max(left, Math.min(right - tw, cx - tw / 2));
            label.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(text, tx, baseY, label);
        }

        if (selected >= 0 && selected < n) drawTip(canvas, days.get(selected), left + slot * (selected + 0.5f));
    }

    /** A column with a rounded data end and a square base (radius 0 squares the top too). */
    private void column(Canvas canvas, float x0, float y0, float x1, float y1, float r) {
        r = Math.min(r, (y1 - y0) / 2);
        radii[0] = radii[1] = radii[2] = radii[3] = r;
        radii[4] = radii[5] = radii[6] = radii[7] = 0;
        rect.set(x0, y0, x1, y1);
        path.reset();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.drawPath(path, bar);
    }

    private void drawTip(Canvas canvas, UsageRepo.DayUsage d, float cx) {
        String[] lines = {
                Fmt.dayLabel(d.dayStart) + "  ·  " + Fmt.bytes(d.usage.total()),
                "Mobile  " + Fmt.bytes(d.usage.mobile()),
                "Wi-Fi  " + Fmt.bytes(d.usage.wifi()),
        };
        int[] keys = {0, cMobile, cWifi};
        float pad = 8 * dp, lineH = tipText.getTextSize() * 1.35f, keyW = 12 * dp;
        float tw = tipText.measureText(lines[0]);
        for (int i = 1; i < 3; i++) tw = Math.max(tw, keyW + tipText.measureText(lines[i]));
        float bw = tw + pad * 2, bh = lineH * 3 + pad * 1.2f;
        float bx = Math.max(0, Math.min(getWidth() - bw, cx - bw / 2));
        rect.set(bx, 0, bx + bw, bh);
        canvas.drawRoundRect(rect, 8 * dp, 8 * dp, tip);
        float ty = pad + tipText.getTextSize() * 0.9f;
        for (int i = 0; i < 3; i++) {
            float tx = bx + pad;
            tipText.setFakeBoldText(i == 0);
            if (keys[i] != 0) {
                bar.setColor(keys[i]);
                bar.setAlpha(255);
                canvas.drawCircle(tx + 4 * dp, ty - tipText.getTextSize() * 0.35f, 4 * dp, bar);
                tx += keyW;
            }
            canvas.drawText(lines[i], tx, ty, tipText);
            ty += lineH;
        }
        tipText.setFakeBoldText(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (days.isEmpty() || slot <= 0) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            int i = (int) ((e.getX() - left) / slot);
            selected = Math.max(0, Math.min(days.size() - 1, i));
            invalidate();
            return true;
        }
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
    }
}
