package com.sl.wifiguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** رسمة راوتر بأربعة هوائيات: ملوّنة إذا كان الراوتر شغال، ورمادية إذا كان طافي. */
final class RouterIcon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean online;

    RouterIcon(Context c) {
        super(c);
    }

    void setOnline(boolean on) {
        online = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        int antenna = online ? 0xFF3A4A66 : 0xFF2A3344;
        int body = online ? 0xFF1B2638 : 0xFF1A2130;
        int accent = online ? Ui.CYAN : 0xFF55617A;
        float top = h * 0.56f, bottom = h * 0.86f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(antenna);
        float aw = w * 0.05f;
        float[] xs = {0.16f, 0.37f, 0.63f, 0.84f};
        for (float x : xs) {
            r.set(w * x - aw / 2, h * 0.08f, w * x + aw / 2, top + h * 0.04f);
            c.drawRoundRect(r, aw / 2, aw / 2, paint);
        }

        paint.setColor(body);
        r.set(w * 0.06f, top, w * 0.94f, bottom);
        float rad = h * 0.09f;
        c.drawRoundRect(r, rad, rad, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1.5f));
        paint.setColor(accent);
        c.drawRoundRect(r, rad, rad, paint);

        paint.setStyle(Paint.Style.FILL);
        float cy = (top + bottom) / 2, dot = h * 0.03f;
        for (int i = 0; i < 4; i++) {
            paint.setColor(online && i < 3 ? (i == 0 ? Ui.GREEN : Ui.CYAN) : 0xFF3A4458);
            c.drawCircle(w * (0.40f + i * 0.07f), cy, dot, paint);
        }
    }
}
