package com.sl.wifiguard;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** أدوات واجهة مشتركة: الألوان والبطاقات والأزرار. الواجهة كلها تُبنى بالكود بدون ملفات layout. */
final class Ui {
    static final int BG = 0xFF0B1220;
    static final int PANEL = 0xFF131C2E;
    static final int BORDER = 0xFF22304A;
    static final int WHITE = 0xFFF2F6FF;
    static final int MUTED = 0xFF8FA0BD;
    static final int CYAN = 0xFF22D3EE;
    static final int BLUE = 0xFF3B82F6;
    static final int GREEN = 0xFF34D399;
    static final int GOLD = 0xFFFBBF24;
    static final int RED = 0xFFF87171;

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout column(Context c) {
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

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        l.setBackground(shape(c, PANEL, BORDER, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    static TextView button(Context c, String s, int color, View.OnClickListener l) {
        TextView t = text(c, s, 15, color, true);
        t.setGravity(Gravity.CENTER);
        int p = dp(c, 12);
        t.setPadding(p, p, p, p);
        t.setBackground(shape(c, (color & 0x00FFFFFF) | 0x22000000, color, 14));
        t.setOnClickListener(l);
        return t;
    }

    static EditText input(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextColor(WHITE);
        e.setHintTextColor(MUTED);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(inputType);
        int p = dp(c, 12);
        e.setPadding(p, p, p, p);
        e.setBackground(shape(c, BG, BORDER, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(c, 8);
        e.setLayoutParams(lp);
        return e;
    }

    static LinearLayout.LayoutParams top(Context c, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(c, marginDp);
        return lp;
    }

    static LinearLayout.LayoutParams weight(Context c, int sideMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lp.leftMargin = dp(c, sideMarginDp);
        lp.rightMargin = dp(c, sideMarginDp);
        return lp;
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    /** يمنع لقطات الشاشة وظهور المحتوى في قائمة التطبيقات الأخيرة. */
    static void secureWindow(Activity a) {
        a.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
    }
}
