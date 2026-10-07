package com.opendroid.netmon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Last-minute speed chart: download and upload as 2px lines over 10% washes, newest sample on the
 * right edge. Touch and drag to scrub: a crosshair and tooltip show that second's values.
 */
final class SpeedGraphView extends View {
    private static final int N = SpeedMonitor.HISTORY;

    private final float[] down = new float[N];
    private final float[] up = new float[N];
    private int count;
    private boolean bits;
    private int touchIndex = -1;

    private final float dp;
    private final int cDown, cUp, cSurface, cTipBg, cTipInk;
    private final Paint grid = new Paint(), axis = new Paint(), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), tipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    SpeedGraphView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        cDown = Ui.color(c, R.color.series1);
        cUp = Ui.color(c, R.color.series2);
        cSurface = Ui.color(c, R.color.surface);
        cTipBg = Ui.color(c, R.color.tooltip_bg);
        cTipInk = Ui.color(c, R.color.tooltip_ink);
        grid.setColor(Ui.color(c, R.color.grid));
        grid.setStrokeWidth(Math.max(1, 0.75f * dp));
        axis.setColor(Ui.color(c, R.color.axis));
        axis.setStrokeWidth(Math.max(1, 0.75f * dp));
        label.setColor(Ui.color(c, R.color.muted));
        label.setTextSize(11 * dp * c.getResources().getConfiguration().fontScale);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        tipText.setTextSize(12 * dp * c.getResources().getConfiguration().fontScale);
        tipText.setColor(cTipInk);
        setContentDescription("Speed over the last 60 seconds");
    }

    void setData(SpeedMonitor m, boolean bits) {
        count = m.history(down, up);
        this.bits = bits;
        if (touchIndex >= count) touchIndex = -1;
        invalidate();
    }

    private float left, right, top, bottom, step;

    private float x(int i) {
        // Newest sample sits on the right edge; older ones extend leftwards.
        return right - (count - 1 - i) * step;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float labelH = label.getTextSize() * 1.4f;
        left = 0;
        right = w - 6 * dp;
        top = labelH + 2 * dp;
        bottom = h - labelH - 4 * dp;
        step = (right - left) / (N - 1);

        float max = 0;
        for (int i = 0; i < count; i++) max = Math.max(max, Math.max(down[i], up[i]));
        Scale s = Scale.speed(max, bits);
        double topBytes = s.topBytes();
        float plotH = bottom - top;

        // Gridlines with their labels sitting just above, left-aligned.
        int ticks = s.ticks();
        for (int t = 1; t <= ticks; t++) {
            float y = bottom - (float) (t * s.step * s.unitBytes / topBytes) * plotH;
            canvas.drawLine(left, y, right, y, grid);
            canvas.drawText(s.label(t), left, y - 4 * dp, label);
        }
        canvas.drawLine(left, bottom, right, bottom, axis);

        float baseY = bottom + label.getTextSize() + 4 * dp;
        label.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("60s ago", left, baseY, label);
        label.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("30s", left + (right - left) / 2, baseY, label);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("now", right, baseY, label);
        label.setTextAlign(Paint.Align.LEFT);

        if (count < 2) return;
        drawSeries(canvas, up, cUp, topBytes, plotH);
        drawSeries(canvas, down, cDown, topBytes, plotH);

        if (touchIndex >= 0) {
            drawScrub(canvas, topBytes, plotH);
        } else {
            endDot(canvas, x(count - 1), y(up[count - 1], topBytes, plotH), cUp);
            endDot(canvas, x(count - 1), y(down[count - 1], topBytes, plotH), cDown);
        }
    }

    private float y(float v, double topBytes, float plotH) {
        return bottom - (float) (v / topBytes) * plotH;
    }

    private void drawSeries(Canvas canvas, float[] v, int color, double topBytes, float plotH) {
        path.reset();
        for (int i = 0; i < count; i++) {
            float px = x(i), py = y(v[i], topBytes, plotH);
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        line.setColor(color);
        canvas.drawPath(path, line);
        path.lineTo(x(count - 1), bottom);
        path.lineTo(x(0), bottom);
        path.close();
        fill.setColor(color);
        fill.setAlpha(26);  // ~10% wash
        canvas.drawPath(path, fill);
    }

    private void endDot(Canvas canvas, float cx, float cy, int color) {
        dot.setColor(cSurface);
        canvas.drawCircle(cx, cy, 6 * dp, dot);  // 2px surface ring
        dot.setColor(color);
        canvas.drawCircle(cx, cy, 4 * dp, dot);
    }

    private void drawScrub(Canvas canvas, double topBytes, float plotH) {
        float cx = x(touchIndex);
        canvas.drawLine(cx, top, cx, bottom, axis);
        endDot(canvas, cx, y(up[touchIndex], topBytes, plotH), cUp);
        endDot(canvas, cx, y(down[touchIndex], topBytes, plotH), cDown);

        int ago = count - 1 - touchIndex;
        String l0 = ago == 0 ? "Now" : ago + "s ago";
        String l1 = "Download  " + Fmt.speed(down[touchIndex], bits);
        String l2 = "Upload  " + Fmt.speed(up[touchIndex], bits);
        float pad = 8 * dp, lineH = tipText.getTextSize() * 1.35f, keyW = 12 * dp;
        float tw = Math.max(tipText.measureText(l0), keyW + Math.max(tipText.measureText(l1), tipText.measureText(l2)));
        float bw = tw + pad * 2, bh = lineH * 3 + pad * 1.2f;
        float bx = cx + 10 * dp;
        if (bx + bw > getWidth()) bx = cx - 10 * dp - bw;
        bx = Math.max(0, bx);
        float by = top;
        rect.set(bx, by, bx + bw, by + bh);
        dot.setColor(cTipBg);
        canvas.drawRoundRect(rect, 8 * dp, 8 * dp, dot);

        float ty = by + pad + tipText.getTextSize() * 0.9f;
        tipText.setFakeBoldText(true);
        canvas.drawText(l0, bx + pad, ty, tipText);
        tipText.setFakeBoldText(false);
        ty += lineH;
        dot.setColor(cDown);
        canvas.drawCircle(bx + pad + 4 * dp, ty - tipText.getTextSize() * 0.35f, 4 * dp, dot);
        canvas.drawText(l1, bx + pad + keyW, ty, tipText);
        ty += lineH;
        dot.setColor(cUp);
        canvas.drawCircle(bx + pad + 4 * dp, ty - tipText.getTextSize() * 0.35f, 4 * dp, dot);
        canvas.drawText(l2, bx + pad + keyW, ty, tipText);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (count < 2) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                int i = count - 1 - Math.round((right - e.getX()) / step);
                touchIndex = Math.max(0, Math.min(count - 1, i));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchIndex = -1;
                invalidate();
                return true;
            default:
                return false;
        }
    }
}
