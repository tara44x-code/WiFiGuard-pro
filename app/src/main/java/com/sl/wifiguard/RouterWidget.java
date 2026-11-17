package com.sl.wifiguard;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/** ويدجت الشاشة الرئيسية: اسم الراوتر، شغال أو لا، وعدد الأجهزة المتصلة. */
public class RouterWidget extends AppWidgetProvider {

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("wifi_guard_widget", Context.MODE_PRIVATE);
    }

    /** يُستدعى كل ما يقرأ التطبيق أو التنبيهات حالة الراوتر. */
    static void save(Context c, String name, boolean online, int devices) {
        prefs(c).edit()
                .putString("name", name)
                .putBoolean("online", online)
                .putInt("devices", devices)
                .putLong("time", System.currentTimeMillis())
                .apply();
        refresh(c);
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, RouterWidget.class));
        for (int id : ids) m.updateAppWidget(id, views(c));
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, views(c));
    }

    private static RemoteViews views(Context c) {
        SharedPreferences p = prefs(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_router);
        long time = p.getLong("time", 0);
        if (time == 0) {
            v.setTextViewText(R.id.w_name, "WiFi Guard");
            v.setTextViewText(R.id.w_status, "افتح الراوتر من التطبيق مرة");
            v.setTextColor(R.id.w_status, Ui.MUTED);
            v.setTextViewText(R.id.w_time, "");
        } else {
            boolean online = p.getBoolean("online", false);
            v.setTextViewText(R.id.w_name, p.getString("name", "الراوتر"));
            v.setTextViewText(R.id.w_status, online ? "● شغال  •  " + p.getInt("devices", 0) + " جهاز" : "○ ما يرد");
            v.setTextColor(R.id.w_status, online ? Ui.GREEN : Ui.RED);
            v.setTextViewText(R.id.w_time, "آخر تحديث " + DeviceStore.ago(time));
        }
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_root, open);
        return v;
    }
}
