package com.opendroid.netmon;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** View-building helpers so screens can be written in code without layout XML. */
final class Ui {
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int color(Context c, int res) {
        return c.getColor(res);
    }

    static TextView text(Context c, CharSequence s, float sp, int colorRes) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(c, colorRes));
        return t;
    }

    static TextView medium(Context c, CharSequence s, float sp, int colorRes) {
        TextView t = text(c, s, sp, colorRes);
        t.setTypeface(MEDIUM);
        return t;
    }

    static TextView title(Context c, String s) {
        TextView t = text(c, s, 26, R.color.ink);
        t.setTypeface(BOLD);
        return t;
    }

    /** Small heading inside a card. */
    static TextView label(Context c, String s) {
        return medium(c, s, 13, R.color.ink2);
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static GradientDrawable rounded(int fill, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        GradientDrawable bg = rounded(color(c, R.color.surface), dp(c, 18));
        bg.setStroke(Math.max(1, dp(c, 0.75f)), color(c, R.color.border));
        l.setBackground(bg);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    static View dot(Context c, int color, float sizeDp) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        int s = dp(c, sizeDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = dp(c, 6);
        v.setLayoutParams(lp);
        return v;
    }

    /** "● Label" legend/key item: the dot carries the series color, the text stays in ink. */
    static LinearLayout key(Context c, int color, String label) {
        LinearLayout r = row(c);
        r.addView(dot(c, color, 8));
        r.addView(text(c, label, 12, R.color.ink2));
        return r;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams matchWrap(Context c, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(color(c, R.color.grid));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.75f))));
        return v;
    }

    static TextView button(Context c, String s, boolean primary) {
        TextView b = medium(c, s, 15, primary ? R.color.on_accent : R.color.accent);
        b.setGravity(Gravity.CENTER);
        int ph = dp(c, 20), pv = dp(c, 12);
        b.setPadding(ph, pv, ph, pv);
        b.setBackground(rounded(color(c, primary ? R.color.accent : R.color.accent_wash), dp(c, 24)));
        b.setClickable(true);
        return b;
    }

    static TextView chip(Context c, String s) {
        TextView t = medium(c, s, 13, R.color.ink2);
        int ph = dp(c, 12), pv = dp(c, 7);
        t.setPadding(ph, pv, ph, pv);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(c, 8);
        t.setLayoutParams(lp);
        setChipSelected(t, false);
        return t;
    }

    static void setChipSelected(TextView t, boolean selected) {
        Context c = t.getContext();
        GradientDrawable g = rounded(color(c, selected ? R.color.accent_wash : R.color.surface), dp(c, 16));
        g.setStroke(Math.max(1, dp(c, 0.75f)), color(c, selected ? R.color.accent : R.color.border));
        t.setBackground(g);
        t.setTextColor(color(c, selected ? R.color.accent : R.color.ink2));
    }

    /** "1.25 MB/s" with the unit smaller and secondary. */
    static CharSequence valueUnit(Context c, String value, String unit, float unitScale) {
        SpannableString s = new SpannableString(value + " " + unit);
        int start = value.length() + 1;
        s.setSpan(new RelativeSizeSpan(unitScale), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(color(c, R.color.ink2)), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    static CharSequence bytesValue(Context c, long bytes, float unitScale) {
        String s = Fmt.bytes(bytes);
        int sp = s.indexOf(' ');
        return valueUnit(c, s.substring(0, sp), s.substring(sp + 1), unitScale);
    }
}
