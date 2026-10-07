package com.opendroid.netmon;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

/** A small draggable speed readout drawn over other apps. Tap it to open NetMon. */
final class OverlayController {
    private final Context ctx;
    private final WindowManager wm;
    private TextView view;
    private WindowManager.LayoutParams lp;
    private String lastText;

    OverlayController(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    static boolean permitted(Context c) {
        return Settings.canDrawOverlays(c);
    }

    void show() {
        if (view != null || !permitted(ctx)) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        view = new TextView(ctx);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        view.setLineSpacing(0, 0.95f);
        view.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3141414);
        bg.setCornerRadius(10 * d);
        view.setBackground(bg);
        view.setText("↓ 0 KB/s\n↑ 0 KB/s");

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Prefs.get(ctx).getInt(Prefs.OVERLAY_X, (int) (12 * d));
        lp.y = Prefs.get(ctx).getInt(Prefs.OVERLAY_Y, (int) (120 * d));
        view.setOnTouchListener(new Dragger());
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            view = null;
        }
    }

    void hide() {
        if (view == null) return;
        try {
            wm.removeView(view);
        } catch (RuntimeException ignored) {
        }
        view = null;
        lastText = null;
    }

    void update(double down, double up, boolean bits) {
        if (view == null) return;
        String text = "↓ " + Fmt.speed(down, bits) + "\n↑ " + Fmt.speed(up, bits);
        if (!text.equals(lastText)) {
            view.setText(text);
            lastText = text;
        }
    }

    static void resetPosition(Context c) {
        Prefs.get(c).edit().remove(Prefs.OVERLAY_X).remove(Prefs.OVERLAY_Y).apply();
    }

    private final class Dragger implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        lp.x = Math.max(0, startX + (int) dx);
                        lp.y = Math.max(0, startY + (int) dy);
                        wm.updateViewLayout(view, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        Prefs.get(ctx).edit().putInt(Prefs.OVERLAY_X, lp.x).putInt(Prefs.OVERLAY_Y, lp.y).apply();
                    } else {
                        try {
                            ctx.startActivity(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (RuntimeException ignored) {
                        }
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
