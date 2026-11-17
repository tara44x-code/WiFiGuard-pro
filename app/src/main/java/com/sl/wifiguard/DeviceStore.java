package com.sl.wifiguard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ذاكرة الأجهزة لكل راوتر على الهاتف: الاسم المختار، أول وآخر ظهور، وسياسة الجهاز
 * (قطع النت، وقت السماح، حظر المواقع الإباحية).
 */
final class DeviceStore {
    static final class Policy {
        boolean blocked;
        /** وقت السماح بالنت مثل 07:00-23:00، أو فارغ = طول اليوم. */
        String allowed = "";
        boolean adultFilter;

        boolean any() {
            return blocked || !allowed.isEmpty() || adultFilter;
        }
    }

    private DeviceStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("wifi_guard_devices", Context.MODE_PRIVATE);
    }

    private static JSONObject load(Context c, String routerId) {
        try {
            return new JSONObject(prefs(c).getString(routerId, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, String routerId, JSONObject all) {
        prefs(c).edit().putString(routerId, all.toString()).apply();
    }

    private static JSONObject entry(JSONObject all, String mac) throws Exception {
        JSONObject e = all.optJSONObject(mac);
        if (e == null) {
            e = new JSONObject();
            all.put(mac, e);
        }
        return e;
    }

    /**
     * يحدّث أول وآخر ظهور ويملأ الاسم المختار. يرجع الأجهزة الجديدة
     * (فارغة في أول فحص لهذا الراوتر، حتى لا تُعتبر كل الأجهزة جديدة).
     */
    static synchronized List<NetDevice> merge(Context c, String routerId, List<NetDevice> devices) {
        JSONObject all = load(c, routerId);
        boolean firstRun = all.length() == 0;
        long now = System.currentTimeMillis();
        List<NetDevice> fresh = new ArrayList<>();
        try {
            for (NetDevice d : devices) {
                boolean known = all.has(d.mac);
                JSONObject e = entry(all, d.mac);
                if (!known) e.put("first", now);
                if (d.online) e.put("last", now);
                if (!d.routerName.isEmpty()) e.put("name", d.routerName);
                d.alias = e.optString("alias", "");
                d.firstSeen = e.optLong("first", now);
                d.lastSeen = e.optLong("last", 0);
                d.isNew = !known && !firstRun;
                if (d.isNew) fresh.add(d);
            }
        } catch (Exception ignored) {
        }
        save(c, routerId, all);
        return fresh;
    }

    /** الأجهزة المحفوظة اللي ما ظهرت بالقائمة الحالية (غير متصلة). */
    static List<NetDevice> offline(Context c, String routerId, List<NetDevice> current) {
        JSONObject all = load(c, routerId);
        List<NetDevice> out = new ArrayList<>();
        Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            String mac = keys.next();
            boolean present = false;
            for (NetDevice d : current) if (d.mac.equals(mac)) present = true;
            if (present) continue;
            JSONObject e = all.optJSONObject(mac);
            if (e == null) continue;
            NetDevice d = new NetDevice();
            d.mac = mac;
            d.routerName = e.optString("name", "");
            d.alias = e.optString("alias", "");
            d.firstSeen = e.optLong("first", 0);
            d.lastSeen = e.optLong("last", 0);
            out.add(d);
        }
        return out;
    }

    static synchronized void setAlias(Context c, String routerId, String mac, String alias) {
        JSONObject all = load(c, routerId);
        try {
            entry(all, mac).put("alias", alias);
        } catch (Exception ignored) {
        }
        save(c, routerId, all);
    }

    static Policy policy(Context c, String routerId, String mac) {
        Policy p = new Policy();
        JSONObject e = load(c, routerId).optJSONObject(mac);
        if (e != null) {
            p.blocked = e.optBoolean("blocked", false);
            p.allowed = e.optString("allowed", "");
            p.adultFilter = e.optBoolean("adult", false);
        }
        return p;
    }

    static synchronized void setPolicy(Context c, String routerId, String mac, Policy p) {
        JSONObject all = load(c, routerId);
        try {
            JSONObject e = entry(all, mac);
            e.put("blocked", p.blocked);
            e.put("allowed", p.allowed);
            e.put("adult", p.adultFilter);
        } catch (Exception ignored) {
        }
        save(c, routerId, all);
    }

    static String ago(long time) {
        if (time <= 0) return "غير معروف";
        long min = (System.currentTimeMillis() - time) / 60000;
        if (min < 2) return "الآن";
        if (min < 60) return "قبل " + min + " دقيقة";
        long h = min / 60;
        if (h < 24) return "قبل " + h + " ساعة";
        return "قبل " + (h / 24) + " يوم";
    }
}
