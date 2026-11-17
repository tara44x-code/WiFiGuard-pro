package com.sl.wifiguard;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** الشاشة الرئيسية: الرئيسية، الشبكات، الراوترات، الأجهزة، الإعدادات. */
public class MainActivity extends BaseActivity {
    private interface Toggle {
        void set(boolean on);
    }

    private static final String[] TITLES = {"الرئيسية", "الشبكات", "الراوترات", "الأجهزة", "الإعدادات"};
    private static final String[] ICONS = {"⌂", "≋", "⇄", "▣", "⚙"};
    private static final int[] LOCK_SECONDS = {0, 30, 60, 300};
    private static final String[] LOCK_LABELS = {"فوراً", "بعد 30 ثانية", "بعد دقيقة", "بعد 5 دقائق"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> found = new ArrayList<>();
    private List<NetworkEngine.AccessPoint> aps = new ArrayList<>();
    private SharedPreferences prefs;
    private LinearLayout content, nav;
    private ScrollView scroll;
    private LocalDiscovery discovery;
    private int page;
    private boolean scanningDevices, scanningNet, historyWritten, receiverOn;
    private String note = "";

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!scanningNet) return;
            scanningNet = false;
            note = "";
            loadNetworks();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("wifi_guard_settings", MODE_PRIVATE);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = Ui.column(this);
        int p = Ui.dp(this, 16);
        content.setPadding(p, p, p, p);
        scroll.addView(content, new ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        nav = Ui.row(this);
        nav.setBackgroundColor(Ui.PANEL);
        root.addView(nav, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        setContentView(root);
        showPage(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverOn) {
            IntentFilter f = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(scanReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(scanReceiver, f);
            }
            receiverOn = true;
        }
        if (!Vault.isLocked()) showPage(page);
    }

    @Override
    protected void onPause() {
        if (receiverOn) {
            unregisterReceiver(scanReceiver);
            receiverOn = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.cancel();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        showPage(page);
    }

    // ---------------------------------------------------------------- التنقل

    private void showPage(int index) {
        page = index;
        content.removeAllViews();
        nav.removeAllViews();
        for (int k = 0; k < TITLES.length; k++) {
            final int idx = k;
            boolean active = k == index;
            TextView t = Ui.text(this, ICONS[k] + "\n" + TITLES[k], 11, active ? Ui.CYAN : Ui.MUTED, active);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            t.setOnClickListener(v -> {
                showPage(idx);
                scroll.scrollTo(0, 0);
                if (idx == 1 && prefs.getBoolean("auto_refresh", true)) scanNetworks();
            });
            nav.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        }
        switch (index) {
            case 1: networks(); break;
            case 2: routers(); break;
            case 3: devices(); break;
            case 4: settings(); break;
            default: home();
        }
    }

    // ---------------------------------------------------------------- عناصر مشتركة

    private void heading(String title, String sub) {
        content.addView(Ui.text(this, title, 24, Ui.WHITE, true));
        TextView s = Ui.text(this, sub, 13, Ui.MUTED, false);
        LinearLayout.LayoutParams lp = Ui.top(this, 2);
        lp.bottomMargin = Ui.dp(this, 14);
        content.addView(s, lp);
    }

    private LinearLayout card(String title) {
        LinearLayout c = Ui.card(this);
        if (title != null) c.addView(Ui.text(this, title, 13, Ui.MUTED, true));
        content.addView(c);
        return c;
    }

    private void pair(LinearLayout parent, String key, String value) {
        LinearLayout r = Ui.row(this);
        r.addView(Ui.text(this, key, 13, Ui.MUTED, false), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView v = Ui.text(this, value == null || value.isEmpty() ? "غير محدد" : value, 14, Ui.WHITE, true);
        v.setTextIsSelectable(true);
        r.addView(v);
        parent.addView(r, Ui.top(this, 8));
    }

    private void btn(LinearLayout parent, String label, int color, View.OnClickListener l) {
        parent.addView(Ui.button(this, label, color, l), Ui.top(this, 10));
    }

    private void info(LinearLayout parent, String s) {
        parent.addView(Ui.text(this, s, 13, Ui.MUTED, false), Ui.top(this, 6));
    }

    private void toggle(LinearLayout parent, String label, boolean checked, final Toggle action) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(Ui.WHITE);
        sw.setTextSize(14);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((button, on) -> action.set(on));
        parent.addView(sw, Ui.top(this, 10));
    }

    private LinearLayout form() {
        LinearLayout f = Ui.column(this);
        int p = Ui.dp(this, 20);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        f.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return f;
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("WiFiGuard", text));
        Ui.toast(this, "تم النسخ");
    }

    private void share(String title, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, title));
    }

    private static int colorForScore(int score) {
        return score >= 75 ? Ui.GREEN : score >= 50 ? Ui.GOLD : Ui.RED;
    }

    // ---------------------------------------------------------------- الرئيسية

    private void home() {
        final NetworkEngine.Snapshot s = NetworkEngine.snapshot(this);
        heading("WiFi GUARD", "لوحة مراقبة الشبكة والتحكم بالراوتر");

        LinearLayout c = card("الشبكة الحالية");
        c.addView(Ui.text(this, s.connected ? s.ssid : "غير متصل حالياً بشبكة Wi-Fi", 20, Ui.WHITE, true),
                Ui.top(this, 4));
        if (s.connected) {
            c.addView(Ui.text(this, s.score + " / 100", 32, colorForScore(s.score), true));
            info(c, "مؤشر تقريبي مبني على نوع التشفير فقط — تقييم أولي وليس اختبار اختراق");
        }
        if (!s.message.isEmpty()) c.addView(Ui.text(this, s.message, 13, Ui.GOLD, false), Ui.top(this, 6));

        if (s.connected) {
            LinearLayout d = card("تفاصيل الاتصال");
            pair(d, "نوع الحماية", s.security);
            pair(d, "قوة الإشارة", NetworkEngine.strength(s.rssi) + " • " + s.rssi + " dBm");
            pair(d, "التردد", s.frequency + " MHz • " + NetworkEngine.band(s.frequency));
            pair(d, "سرعة الرابط", s.linkMbps + " Mbps");
            pair(d, "عنوان IP", s.ip);
            pair(d, "الراوتر / البوابة", s.gateway);
            pair(d, "خادم DNS", s.dns);

            LinearLayout a = card("تحليل الأمان");
            a.addView(Ui.text(this, NetworkEngine.explain(s.security), 14, Ui.WHITE, false), Ui.top(this, 6));
            info(a, "نوع التشفير لا يكفي للحكم على قوة كلمة المرور.");

            LinearLayout q = card("الوصول السريع");
            btn(q, "فتح لوحة الراوتر الحالي  ⇄", Ui.CYAN, v -> openGateway(s.gateway));
            btn(q, "مشاركة تقرير الشبكة", Ui.BLUE, v -> share("مشاركة تقرير الشبكة", report(s)));
            btn(q, "نسخ البيانات", Ui.MUTED, v -> copy(report(s)));

            if (prefs.getBoolean("save_history", false) && !historyWritten) {
                historyWritten = true;
                writeHistory(s);
            }
        }
        btn(content, "تحديث حالة الشبكة   ↻", Ui.GREEN, v -> {
            historyWritten = false;
            showPage(0);
        });
    }

    private String report(NetworkEngine.Snapshot s) {
        return "WiFi Guard | تقرير الشبكة\nالتاريخ: " + DateFormat.getDateTimeInstance().format(new Date())
                + "\nSSID: " + s.ssid
                + "\nBSSID: " + s.bssid
                + "\nالتشفير: " + s.security
                + "\nالقوة: " + s.rssi + " dBm\nالتردد: " + s.frequency + " MHz\nالنطاق: " + NetworkEngine.band(s.frequency)
                + "\nIP: " + s.ip
                + "\nGateway: " + s.gateway
                + "\nDNS: " + s.dns
                + "\nالدرجة التقريبية: " + s.score + " / 100"
                + "\nملاحظة: يعتمد التقييم على نوع التشفير وليس اختبار كلمة المرور.";
    }

    private void writeHistory(NetworkEngine.Snapshot s) {
        try {
            JSONArray old = new JSONArray(prefs.getString("history", "[]"));
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, old.length() - 49); i < old.length(); i++) out.put(old.get(i));
            JSONObject o = new JSONObject();
            o.put("date", DateFormat.getDateTimeInstance().format(new Date()));
            o.put("ssid", s.ssid);
            o.put("security", s.security);
            o.put("score", s.score);
            out.put(o);
            prefs.edit().putString("history", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- الشبكات

    private void networks() {
        heading("الشبكات", "مراقبة الشبكات التي يلتقطها هاتفك");
        if (!NetworkEngine.permissionGranted(this)) {
            LinearLayout c = card("مطلوب إذن الوصول للشبكات");
            info(c, "أندرويد يتطلب إذن الموقع لعرض أسماء شبكات الواي فاي القريبة. لا تُرسل هذه المعلومات إلى أي خادم.");
            btn(c, "منح الأذونات", Ui.CYAN, v -> requestWifiPermissions());
            return;
        }
        if (!NetworkEngine.locationEnabled(this)) {
            LinearLayout c = card("خدمة الموقع متوقفة");
            info(c, "أندرويد يحتاج تشغيل الموقع حتى يسمح بإظهار الشبكات القريبة.");
            btn(c, "فتح إعدادات الموقع", Ui.GOLD,
                    v -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        }
        btn(content, "تحديث قائمة الشبكات   ↻", Ui.GREEN, v -> scanNetworks());
        if (aps.isEmpty()) aps = NetworkEngine.nearby(this);
        int weak = 0;
        for (NetworkEngine.AccessPoint ap : aps) if (ap.weak) weak++;
        String line = aps.size() + " شبكة" + (weak > 0 ? "  •  " + weak + " بحاجة انتباه" : "");
        content.addView(Ui.text(this, note.isEmpty() ? line : line + "\n" + note, 13, Ui.MUTED, false), Ui.top(this, 10));
        content.addView(new View(this), new LinearLayout.LayoutParams(1, Ui.dp(this, 10)));
        if (aps.isEmpty()) {
            info(card("لا توجد نتائج حالياً"), "اضغط تحديث؛ قد يؤخر النظام النتائج بسبب حدود فحص Wi-Fi.");
        }
        for (final NetworkEngine.AccessPoint ap : aps) {
            LinearLayout c = card(null);
            c.addView(Ui.text(this, ap.ssid, 16, Ui.WHITE, true));
            c.addView(Ui.text(this, ap.security + "  •  " + NetworkEngine.band(ap.frequency) + "  •  " + ap.rssi + " dBm",
                    13, ap.weak ? Ui.RED : Ui.GREEN, false), Ui.top(this, 4));
            c.setOnClickListener(v -> showNetworkDetails(ap));
        }
        info(card("معلومة"), "الشبكات الظاهرة هنا لا تعني أنك متصل بها؛ الإشارات والأنواع مأخوذة من نتائج أندرويد وقد تكون مخزنة مؤقتاً.");
    }

    private void showNetworkDetails(NetworkEngine.AccessPoint ap) {
        final String text = "الاسم: " + ap.ssid + "\nBSSID: " + ap.bssid + "\nالتشفير: " + ap.security
                + "\nالإشارة " + NetworkEngine.strength(ap.rssi) + " — " + ap.rssi + " dBm"
                + "\nالتردد: " + ap.frequency + " MHz (" + NetworkEngine.band(ap.frequency) + ")"
                + "\n\n" + NetworkEngine.explain(ap.security);
        new AlertDialog.Builder(this)
                .setTitle(ap.ssid)
                .setMessage(text)
                .setPositiveButton("تم", null)
                .setNeutralButton("نسخ", (d, w) -> copy(text))
                .show();
    }

    private void requestWifiPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    "android.permission.NEARBY_WIFI_DEVICES"}, 7);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 7);
        }
    }

    @SuppressWarnings("deprecation")
    private void scanNetworks() {
        if (!NetworkEngine.permissionGranted(this) || scanningNet) return;
        boolean started = false;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            started = wm != null && wm.startScan();
        } catch (Exception ignored) {
        }
        if (started) {
            scanningNet = true;
            note = "جار تحديث الشبكات…";
            ui.postDelayed(() -> {
                if (scanningNet) {
                    scanningNet = false;
                    note = "قد يتأخر نظام أندرويد بتحديث المسح؛ هذه أحدث نتيجة متاحة.";
                    loadNetworks();
                }
            }, 7000);
            if (page == 1) showPage(1);
        } else {
            note = "أندرويد قيّد تكرار المسح؛ تُعرض آخر نتائج متاحة.";
            loadNetworks();
        }
    }

    private void loadNetworks() {
        aps = NetworkEngine.nearby(this);
        if (page == 1 && !isFinishing()) showPage(1);
    }

    // ---------------------------------------------------------------- الراوترات

    private void routers() {
        heading("الراوترات", "راوتراتك المحفوظة — البيانات مشفّرة على هذا الهاتف فقط");
        List<RouterProfile> list = Vault.routers(this);
        if (list.isEmpty()) {
            info(card("لا يوجد راوتر محفوظ"), "أضف راوترك مرة واحدة مع اسم المستخدم وكلمة المرور، وبعدها تفتح لوحته بضغطة ويدخل التطبيق تلقائياً.");
        }
        for (final RouterProfile p : list) {
            LinearLayout c = card(null);
            c.addView(Ui.text(this, p.name, 17, Ui.WHITE, true));
            c.addView(Ui.text(this, RouterDetect.name(p.vendor) + "  •  " + p.localUrl, 12, Ui.MUTED, false), Ui.top(this, 2));
            if (!p.remoteUrl.isEmpty()) {
                c.addView(Ui.text(this, "عن بعد: " + p.remoteUrl, 12, Ui.MUTED, false));
            }
            LinearLayout r = Ui.row(this);
            r.addView(Ui.button(this, "محلي", Ui.CYAN, v -> openRouter(p, false)), Ui.weight(this, 3));
            if (!p.remoteUrl.isEmpty()) {
                r.addView(Ui.button(this, "عن بعد", Ui.GREEN, v -> openRouter(p, true)), Ui.weight(this, 3));
            }
            r.addView(Ui.button(this, "تعديل", Ui.MUTED, v -> editRouter(p, null)), Ui.weight(this, 3));
            r.addView(Ui.button(this, "حذف", Ui.RED, v -> confirmDelete(p)), Ui.weight(this, 3));
            c.addView(r, Ui.top(this, 10));
        }
        final String gw = NetworkEngine.snapshot(this).gateway;
        btn(content, "إضافة راوتر  +", Ui.GREEN, v -> editRouter(null, gw.isEmpty() ? null : "http://" + gw));
        LinearLayout h = card("التحكم من بعيد");
        LinearLayout.LayoutParams lp = Ui.top(this, 14);
        lp.bottomMargin = Ui.dp(this, 12);
        h.setLayoutParams(lp);
        info(h, "يشتغل فقط إذا فعّلت في الراوتر Remote Management أو DDNS أو VPN، واكتب عنوانه في خانة «عنوان التحكم من بعيد». "
                + "الأفضل أمنياً: VPN أو https، ولا تترك كلمة المرور الافتراضية admin إذا كانت اللوحة مفتوحة على الإنترنت.");
    }

    private void openRouter(RouterProfile p, boolean remote) {
        startActivity(new Intent(this, RouterActivity.class)
                .putExtra(RouterActivity.EXTRA_ID, p.id)
                .putExtra(RouterActivity.EXTRA_REMOTE, remote));
    }

    private void openGateway(String gateway) {
        if (gateway == null || gateway.isEmpty()) {
            Ui.toast(this, "لم أجد عنوان الراوتر");
            return;
        }
        for (RouterProfile p : Vault.routers(this)) {
            if (gateway.equals(Uri.parse(p.localUrl).getHost())) {
                openRouter(p, false);
                return;
            }
        }
        editRouter(null, "http://" + gateway);
    }

    private void confirmDelete(final RouterProfile p) {
        new AlertDialog.Builder(this)
                .setTitle("حذف «" + p.name + "»؟")
                .setMessage("ستُحذف بيانات الدخول المحفوظة لهذا الراوتر من الهاتف.")
                .setPositiveButton("حذف", (d, w) -> {
                    try {
                        Vault.delete(this, p.id);
                    } catch (Exception e) {
                        Ui.toast(this, "تعذّر الحذف");
                    }
                    showPage(2);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void editRouter(RouterProfile existing, String presetLocal) {
        final RouterProfile p = existing != null ? existing : new RouterProfile();
        LinearLayout f = form();
        final EditText name = Ui.input(this, "اسم الراوتر (مثلاً: البيت)", InputType.TYPE_CLASS_TEXT);
        final EditText local = Ui.input(this, "العنوان المحلي: 192.168.1.1",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText remote = Ui.input(this, "عنوان التحكم من بعيد (اختياري)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText user = Ui.input(this, "اسم المستخدم", InputType.TYPE_CLASS_TEXT);
        final EditText pass = Ui.input(this, "كلمة مرور لوحة الراوتر",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        name.setText(p.name);
        local.setText(existing == null && presetLocal != null ? presetLocal : p.localUrl);
        remote.setText(p.remoteUrl);
        user.setText(p.username);
        pass.setText(p.password);
        final String[] vendor = {p.vendor};
        final TextView vb = Ui.button(this, "النوع: " + RouterDetect.name(vendor[0]), Ui.BLUE, null);
        vb.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("نوع الراوتر")
                .setItems(RouterDetect.NAMES, (d, w) -> {
                    vendor[0] = RouterDetect.KEYS[w];
                    vb.setText("النوع: " + RouterDetect.NAMES[w]);
                })
                .show());
        f.addView(name);
        f.addView(local);
        f.addView(remote);
        f.addView(user);
        f.addView(pass);
        f.addView(vb, Ui.top(this, 10));
        ScrollView sv = new ScrollView(this);
        sv.addView(f);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "إضافة راوتر" : "تعديل الراوتر")
                .setView(sv)
                .setPositiveButton("حفظ", null)
                .setNegativeButton("إلغاء", null)
                .create();
        dlg.show();
        // نربط الزر يدوياً حتى لا يُغلق الحوار إذا كانت البيانات ناقصة.
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String l = RouterProfile.normalize(local.getText().toString());
            if (l.isEmpty()) {
                Ui.toast(this, "اكتب عنوان الراوتر المحلي");
                return;
            }
            String n = name.getText().toString().trim();
            p.name = n.isEmpty() ? String.valueOf(Uri.parse(l).getHost()) : n;
            p.localUrl = l;
            p.remoteUrl = RouterProfile.normalize(remote.getText().toString());
            p.username = user.getText().toString().trim();
            p.password = pass.getText().toString();
            p.vendor = vendor[0];
            try {
                Vault.put(this, p);
                dlg.dismiss();
                showPage(2);
            } catch (Exception e) {
                Ui.toast(this, "تعذّر الحفظ: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- الأجهزة

    private void devices() {
        heading("الأجهزة", "استكشاف استجابة الأجهزة على شبكتك المحلية");
        final NetworkEngine.Snapshot s = NetworkEngine.snapshot(this);
        if (!s.connected || s.ip.isEmpty()) {
            info(card("شبكة محلية غير متاحة"), "اتصل بالراوتر عبر Wi-Fi وبعنوان محلي لبدء اكتشاف الأجهزة.");
            return;
        }
        LinearLayout st = card("حالة الفحص");
        if (scanningDevices) {
            st.addView(Ui.text(this, "الفحص جارٍ…", 16, Ui.CYAN, true), Ui.top(this, 4));
            info(st, "يتم فحص عناوين الشبكة المحلية. لا تغلق الصفحة حتى تظهر النتائج.");
        } else {
            st.addView(Ui.text(this, "عناوين استجابت: " + found.size(), 16, Ui.WHITE, true), Ui.top(this, 4));
            btn(st, "بدء اكتشاف الأجهزة", Ui.GREEN, v -> beginDeviceScan(s.ip));
        }
        for (String host : found) {
            LinearLayout c = card(null);
            String tag = host.equals(s.ip) ? "هاتفك" : host.equals(s.gateway) ? "بوابة الراوتر" : "جهاز متجاوب";
            LinearLayout r = Ui.row(this);
            r.addView(Ui.text(this, host, 16, Ui.WHITE, true), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            r.addView(Ui.text(this, tag, 12, host.equals(s.gateway) ? Ui.CYAN : Ui.MUTED, false));
            c.addView(r);
        }
        LinearLayout n = card("حدود الفحص");
        info(n, "يتم تجربة استجابة الشبكة ضمن نطاق محلي واحد، بحد أقصى 254 عنواناً. عدم ظهور الأجهزة لا يعني عدم اتصالها.");
        info(n, "هذه ليست قائمة الراوتر الرسمية؛ للحصول على جميع العملاء المتصلين استخدم لوحة إدارة الراوتر من تبويب «الراوترات».");
    }

    private void beginDeviceScan(String localIp) {
        if (scanningDevices) return;
        scanningDevices = true;
        if (discovery != null) discovery.cancel();
        discovery = new LocalDiscovery();
        discovery.scan(localIp, hosts -> {
            scanningDevices = false;
            found.clear();
            found.addAll(hosts);
            if (page == 3 && !isFinishing()) showPage(3);
        });
        showPage(3);
    }

    // ---------------------------------------------------------------- الإعدادات

    private void settings() {
        heading("الإعدادات", "خصوصية واضحة وتحكم بسيط");

        LinearLayout sec = card("القفل والحماية");
        btn(sec, "تغيير الرمز السري", Ui.CYAN, v -> startActivity(
                new Intent(this, LockActivity.class).putExtra(LockActivity.EXTRA_CHANGE, true)));
        if (Build.VERSION.SDK_INT >= 28) {
            toggle(sec, "فتح بالبصمة بدل الرمز", Vault.biometricEnabled(this), on -> Vault.setBiometricEnabled(this, on));
        }
        int current = 1;
        for (int i = 0; i < LOCK_SECONDS.length; i++) {
            if (LOCK_SECONDS[i] == Vault.lockTimeoutSeconds(this)) current = i;
        }
        btn(sec, "القفل عند الخروج من التطبيق: " + LOCK_LABELS[current], Ui.MUTED, v -> new AlertDialog.Builder(this)
                .setTitle("القفل التلقائي")
                .setItems(LOCK_LABELS, (d, w) -> {
                    Vault.setLockTimeoutSeconds(this, LOCK_SECONDS[w]);
                    showPage(4);
                })
                .show());
        btn(sec, "قفل التطبيق الآن", Ui.GOLD, v -> {
            Vault.lockNow();
            startActivity(new Intent(this, LockActivity.class));
        });

        LinearLayout bk = card("نسخة احتياطية للراوترات");
        info(bk, "نص مشفّر بكلمة مرور تختارها؛ احفظه بأي مكان واسترجعه على هاتف آخر.");
        btn(bk, "تصدير نسخة مشفّرة", Ui.BLUE, v -> exportBackup());
        btn(bk, "استرجاع نسخة", Ui.MUTED, v -> importBackup());

        LinearLayout pf = card("التفضيلات");
        toggle(pf, "تحديث عند فتح قائمة الشبكات", prefs.getBoolean("auto_refresh", true),
                on -> prefs.edit().putBoolean("auto_refresh", on).apply());
        toggle(pf, "حفظ سجل التحليل (على الجهاز فقط)", prefs.getBoolean("save_history", false),
                on -> prefs.edit().putBoolean("save_history", on).apply());
        btn(pf, "السجل المحلي", Ui.CYAN, v -> showHistory());
        btn(pf, "حذف السجل", Ui.RED, v -> new AlertDialog.Builder(this)
                .setTitle("حذف نتائج التحليل؟")
                .setMessage("سيتم مسح السجل المخزّن على هذا الجهاز.")
                .setPositiveButton("حذف", (d, w) -> prefs.edit().remove("history").apply())
                .setNegativeButton("إلغاء", null)
                .show());

        LinearLayout pm = card("أذونات أندرويد");
        boolean granted = NetworkEngine.permissionGranted(this);
        pair(pm, "إذن الموقع", granted ? "مسموح" : "غير مسموح");
        pair(pm, "خدمة الموقع", NetworkEngine.locationEnabled(this) ? "شغالة" : "مطفأة");
        if (!granted) btn(pm, "ضبط الأذونات", Ui.CYAN, v -> requestWifiPermissions());

        LinearLayout ab = card("معلومة");
        info(ab, "لا يرسل التطبيق معلوماتك لأي خادم. كلمات مرور الراوترات تُحفظ مشفّرة على هذا الهاتف فقط وتُرسل للراوتر نفسه عند الدخول. الفحص يستعمل واجهات أندرويد الرسمية ولا يحتاج روت.");
        info(ab, "WiFi Guard • 2.0.0");
    }

    private void showHistory() {
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray a = new JSONArray(prefs.getString("history", "[]"));
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.getJSONObject(i);
                sb.append(o.optString("date")).append('\n')
                        .append(o.optString("ssid")).append(" — ").append(o.optString("security"))
                        .append(" — ").append(o.optInt("score")).append("/100\n\n");
            }
        } catch (Exception e) {
            sb.append("تعذّر عرض السجل المحلي");
        }
        new AlertDialog.Builder(this)
                .setTitle("السجل المحلي")
                .setMessage(sb.length() == 0 ? "السجل فارغ حالياً" : sb.toString().trim())
                .setPositiveButton("تم", null)
                .show();
    }

    private void exportBackup() {
        LinearLayout f = form();
        final EditText pw = Ui.input(this, "كلمة مرور للنسخة (6 أحرف على الأقل)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        f.addView(pw);
        new AlertDialog.Builder(this)
                .setTitle("تصدير نسخة مشفّرة")
                .setView(f)
                .setPositiveButton("تصدير", (d, w) -> {
                    String password = pw.getText().toString();
                    if (password.length() < 6) {
                        Ui.toast(this, "كلمة المرور قصيرة");
                        return;
                    }
                    try {
                        share("حفظ النسخة الاحتياطية", Vault.exportBackup(this, password));
                    } catch (Exception e) {
                        Ui.toast(this, "تعذّر التصدير: " + e.getMessage());
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void importBackup() {
        LinearLayout f = form();
        final EditText text = Ui.input(this, "الصق نص النسخة هنا (يبدأ بـ WGB1:)", InputType.TYPE_CLASS_TEXT);
        final EditText pw = Ui.input(this, "كلمة مرور النسخة",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        f.addView(text);
        f.addView(pw);
        new AlertDialog.Builder(this)
                .setTitle("استرجاع نسخة")
                .setView(f)
                .setPositiveButton("استرجاع", (d, w) -> {
                    try {
                        int n = Vault.importBackup(this, text.getText().toString(), pw.getText().toString());
                        Ui.toast(this, "تمت إضافة " + n + " راوتر");
                    } catch (Exception e) {
                        Ui.toast(this, "كلمة المرور أو النص غير صحيح");
                    }
                    showPage(page);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }
}
