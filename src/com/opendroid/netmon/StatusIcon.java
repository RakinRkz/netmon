package com.opendroid.netmon;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;

/**
 * Draws the speed as a two-line status bar icon ("1.2" over "MB/s"). The system tints small icons
 * to its own color and only uses the alpha channel, so the bitmap is plain white on transparent.
 */
final class StatusIcon {
    private static final int SIZE = 96;
    private static final Paint VALUE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint UNIT = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        VALUE.setTypeface(condensed);
        VALUE.setColor(Color.WHITE);
        VALUE.setTextAlign(Paint.Align.CENTER);
        UNIT.setTypeface(condensed);
        UNIT.setColor(Color.WHITE);
        UNIT.setTextAlign(Paint.Align.CENTER);
    }

    private static String lastKey;
    private static Icon lastIcon;

    private StatusIcon() {}

    static synchronized Icon make(double bytesPerSec, boolean bits) {
        String[] p = Fmt.iconParts(bytesPerSec, bits);
        String key = p[0] + "|" + p[1];
        if (key.equals(lastKey)) return lastIcon;

        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float cx = SIZE / 2f;

        // Digits get ~57% of the height, the unit ~30%; both squeezed horizontally to fit.
        // Cap height of this face is ~0.71 of the text size.
        VALUE.setTextSize(SIZE * 0.57f / 0.71f);
        fit(VALUE, p[0], SIZE);
        canvas.drawText(p[0], cx, SIZE * 0.58f, VALUE);

        UNIT.setTextSize(SIZE * 0.31f / 0.71f);
        fit(UNIT, p[1], SIZE);
        canvas.drawText(p[1], cx, SIZE * 0.97f, UNIT);

        lastKey = key;
        lastIcon = Icon.createWithBitmap(bmp);
        return lastIcon;
    }

    private static void fit(Paint paint, String text, float width) {
        paint.setTextScaleX(1f);
        float w = paint.measureText(text);
        if (w > width) paint.setTextScaleX(width / w);
    }
}
