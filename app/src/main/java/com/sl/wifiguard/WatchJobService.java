package com.sl.wifiguard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.List;

/**
 * فحص بالخلفية كل ~15 دقيقة (أقل مدة يسمحها أندرويد): يقرأ أجهزة راوترات Tenda
 * وينبّه عند اتصال جهاز جديد، ويحدّث الويدجت.
 */
public class WatchJobService extends JobService {
    private static final int JOB_ID = 4401;
    private static final String CHANNEL = "new_devices";
    static final String PREF_ALERTS = "alerts_new_devices";

    static void schedule(Context c, boolean on) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!on) {
            js.cancel(JOB_ID);
            return;
        }
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, WatchJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                scan(getApplicationContext());
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static void scan(Context c) {
        if (!c.getSharedPreferences("wifi_guard_settings", Context.MODE_PRIVATE).getBoolean(PREF_ALERTS, false)) return;
        String ssid = NetworkEngine.snapshot(c).ssid;
        for (RouterProfile p : Vault.routers(c)) {
            if (!"tenda".equals(p.vendor)) continue;
            String url = null;
            // بالخلفية ما نرسل كلمة المرور للعنوان المحلي إلا إذا تأكدنا أننا على شبكة البيت.
            if (!p.homeSsid.isEmpty() && p.homeSsid.equals(ssid)) {
                url = p.localUrl;
            } else if (p.remoteEnabled && !p.remoteUrl.isEmpty()) {
                url = p.remoteUrl;
            }
            if (url == null) continue;
            try {
                TendaDriver d = new TendaDriver(url);
                if (!d.login(p.username, p.password)) continue;
                List<NetDevice> devices = d.devices();
                List<NetDevice> fresh = DeviceStore.merge(c, p.id, devices);
                int online = 0;
                for (NetDevice n : devices) if (n.online) online++;
                RouterWidget.save(c, p.name, true, online);
                for (NetDevice n : fresh) notifyNew(c, p, n);
            } catch (Exception e) {
                RouterWidget.save(c, p.name, false, 0);
            }
        }
    }

    private static void notifyNew(Context c, RouterProfile p, NetDevice d) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "أجهزة جديدة",
                NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("جهاز جديد اتصل بـ " + p.name)
                .setContentText(d.icon() + " " + d.displayName() + (d.ip.isEmpty() ? "" : " • " + d.ip))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(d.mac.hashCode(), n);
    }
}
