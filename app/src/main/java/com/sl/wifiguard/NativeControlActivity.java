package com.sl.wifiguard;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * تحكم مباشر (تجريبي) عبر {@link TendaDriver}: قائمة الأجهزة من الراوتر نفسه،
 * تحديد سرعة جهاز، حظر مواقع، جدولة الإنترنت، وإعادة التشغيل.
 */
public class NativeControlActivity extends BaseActivity {
    private interface Job {
        String run() throws Exception;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private RouterProfile profile;
    private TendaDriver driver;
    private JSONArray table = new JSONArray();
    private LinearLayout list;
    private TextView status;
    private RouterIcon icon;
    private boolean remote;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("wifi_guard_settings", MODE_PRIVATE);
        profile = Vault.find(this, String.valueOf(getIntent().getStringExtra(RouterActivity.EXTRA_ID)));
        boolean remote = getIntent().getBooleanExtra(RouterActivity.EXTRA_REMOTE, false);
        if (profile == null || profile.url(remote).isEmpty()) {
            finish();
            return;
        }
        driver = new TendaDriver(profile.url(remote));

        this.remote = remote;
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = Ui.column(this);
        int p = Ui.dp(this, 16);
        page.setPadding(p, p, p, p);
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        // بطاقة الراوتر
        LinearLayout hero = Ui.card(this);
        LinearLayout top = Ui.row(this);
        icon = new RouterIcon(this);
        top.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 60)));
        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        titles.addView(Ui.text(this, profile.name, 20, Ui.WHITE, true));
        titles.addView(Ui.text(this, "Tenda  •  " + (remote ? "عن بعد" : "محلي"), 12, Ui.MUTED, false));
        top.addView(titles, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView close = Ui.text(this, "✕", 20, Ui.MUTED, true);
        close.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        close.setOnClickListener(v -> finish());
        top.addView(close);
        hero.addView(top);
        status = Ui.text(this, "", 13, Ui.MUTED, false);
        hero.addView(status, Ui.top(this, 10));
        page.addView(hero);

        // أزرار سريعة
        String[] labels = {"تحديث ↻", "إعادة تشغيل", "باسورد الواي فاي", "حظر جهاز", "التحكم من بعيد", "لوحة الراوتر"};
        int[] colors = {Ui.CYAN, Ui.RED, Ui.BLUE, Ui.GOLD, Ui.GREEN, Ui.MUTED};
        for (int i = 0; i < labels.length; i += 2) {
            LinearLayout r = Ui.row(this);
            for (int k = i; k < i + 2; k++) {
                final int idx = k;
                TextView tile = Ui.button(this, labels[k], colors[k], v -> action(idx));
                tile.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
                r.addView(tile, Ui.weight(this, 4));
            }
            page.addView(r, Ui.top(this, i == 0 ? 0 : 8));
        }

        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "الأجهزة المتصلة", 17, Ui.WHITE, true), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView diag = Ui.text(this, "تقرير تشخيص", 12, Ui.MUTED, false);
        diag.setOnClickListener(v -> diagnostics());
        head.addView(diag);
        page.addView(head, Ui.top(this, 18));

        list = Ui.column(this);
        list.setPadding(0, Ui.dp(this, 10), 0, 0);
        page.addView(list);
        page.addView(Ui.text(this, "التحكم المباشر تجريبي. إذا زر ما اشتغل، دز «تقرير تشخيص» للمطور.", 12, Ui.MUTED, false),
                Ui.top(this, 8));
        setContentView(root);
        load();
    }

    private void load() {
        status.setTextColor(Ui.MUTED);
        status.setText("جاري الاتصال بالراوتر…");
        io.execute(() -> {
            try {
                if (!driver.login(profile.username, profile.password)) {
                    throw new Exception("فشل تسجيل الدخول. تأكد من كلمة المرور أو استعمل لوحة الراوتر.");
                }
                final JSONArray t = driver.netControl();
                ui.post(() -> {
                    icon.setOnline(true);
                    render(t);
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    if (isFinishing()) return;
                    icon.setOnline(false);
                    status.setTextColor(Ui.RED);
                    status.setText("تعذّر الاتصال: " + e.getMessage());
                    list.removeAllViews();
                    LinearLayout c = Ui.card(this);
                    c.addView(Ui.text(this, "ما كدرت أقرا الأجهزة من الراوتر.", 14, Ui.WHITE, true));
                    c.addView(Ui.text(this, "جرّب «لوحة الراوتر»، ودز «تقرير تشخيص» للمطور حتى يضبط راوترك.",
                            13, Ui.MUTED, false), Ui.top(this, 4));
                    list.addView(c);
                });
            }
        });
    }

    private void action(int idx) {
        switch (idx) {
            case 0: load(); break;
            case 1: confirmReboot(); break;
            case 2: web("Wireless / WiFi Settings ← غيّر WiFi Password واحفظ. انتبه: كل الأجهزة راح تنفصل وترجع تتصل بالباسورد الجديد."); break;
            case 3: web("Advanced ← MAC Filter أو Blacklist: ضيف الجهاز للقائمة السوداء. تكدر تستعمل «الرقابة» من بطاقة الجهاز هم."); break;
            case 4: web("Advanced / Administration ← Remote Web Management: فعّلها وخلي المنفذ 8080. فحص التحكم من بعيد موجود بقائمة ⋮ بشاشة الراوترات."); break;
            default: web(null);
        }
    }

    private void web(String hint) {
        startActivity(new android.content.Intent(this, RouterActivity.class)
                .putExtra(RouterActivity.EXTRA_ID, profile.id)
                .putExtra(RouterActivity.EXTRA_REMOTE, remote)
                .putExtra(RouterActivity.EXTRA_HINT, hint));
    }

    /** قراءة فقط: يجمع ردود الراوتر الخام بدون كلمة المرور حتى نعرف شكل واجهة هذا الموديل. */
    private void diagnostics() {
        status.setText("جاري تجهيز التقرير…");
        io.execute(() -> {
            StringBuilder sb = new StringBuilder("WiFi Guard • تقرير تشخيص Tenda\n");
            sb.append("Android ").append(android.os.Build.VERSION.SDK_INT).append(" • ")
                    .append(remote ? "remote" : "local").append("\n\n");
            try {
                sb.append("[login] ").append(driver.login(profile.username, profile.password) ? "OK" : "FAIL")
                        .append("\n").append(driver.lastLogin).append("\n\n");
            } catch (Exception e) {
                sb.append("[login] خطأ: ").append(e.getMessage()).append("\n\n");
            }
            String[] paths = {"/goform/GetNetControlList", "/goform/getOnlineList",
                    "/goform/getStatus?modules=internetStatus,wanBasicCfg,remoteWeb,systemInfo,softWare",
                    "/goform/GetParentControlInfo?mac=00:00:00:00:00:00"};
            for (String path : paths) sb.append('[').append(path).append("]\n").append(driver.probe(path)).append("\n\n");
            final String report = sb.toString();
            ui.post(() -> {
                if (isFinishing()) return;
                status.setText("");
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(android.content.Intent.EXTRA_TEXT, report);
                startActivity(android.content.Intent.createChooser(i, "إرسال تقرير التشخيص"));
            });
        });
    }

    private void render(JSONArray t) {
        if (isFinishing()) return;
        table = t;
        list.removeAllViews();
        String knownKey = "known_" + profile.id;
        boolean firstRun = !prefs.contains(knownKey);
        Set<String> known = new HashSet<>(Arrays.asList(prefs.getString(knownKey, "").split(",")));
        int count = 0, fresh = 0;
        for (int i = 0; i < t.length(); i++) {
            final JSONObject o = t.optJSONObject(i);
            if (o == null || !o.has("mac")) continue;
            count++;
            final String mac = o.optString("mac");
            boolean isNew = !firstRun && !known.contains(mac);
            if (isNew) fresh++;
            known.add(mac);

            LinearLayout card = Ui.card(this);
            String alias = prefs.getString("name_" + mac, "");
            String shown = alias.isEmpty() ? o.optString("hostName", "جهاز") : alias;
            card.addView(Ui.text(this, shown + (isNew ? "   ● جديد" : ""), 16, isNew ? Ui.GOLD : Ui.WHITE, true));
            card.addView(Ui.text(this, o.optString("ip") + "  •  " + mac, 12, Ui.MUTED, false));
            card.addView(Ui.text(this, "الاستهلاك الآن: ↓ " + o.optString("downSpeed", "0") + "  ↑ "
                    + o.optString("upSpeed", "0") + " KB/s", 12, Ui.MUTED, false));
            card.addView(Ui.text(this, "الحد: ↓ " + o.optString("limitDown", "0") + "  ↑ "
                    + o.optString("limitUp", "0") + " KB/s", 13, Ui.CYAN, false));
            LinearLayout r = Ui.row(this);
            r.addView(Ui.button(this, "السرعة", Ui.CYAN, v -> speedDialog(o)), Ui.weight(this, 3));
            r.addView(Ui.button(this, "الرقابة", Ui.GREEN, v -> parentDialog(mac, shown)), Ui.weight(this, 3));
            r.addView(Ui.button(this, "تسمية", Ui.MUTED, v -> renameDialog(mac, shown)), Ui.weight(this, 3));
            card.addView(r, Ui.top(this, 10));
            list.addView(card);
        }
        StringBuilder joined = new StringBuilder();
        for (String m : known) if (!m.isEmpty()) joined.append(m).append(',');
        prefs.edit().putString(knownKey, joined.toString()).apply();
        status.setTextColor(fresh > 0 ? Ui.GOLD : Ui.MUTED);
        status.setText(count + " جهاز" + (fresh > 0 ? "  •  " + fresh + " جديد منذ آخر فحص" : ""));
    }

    private void run(final Job job) {
        status.setText("جاري التنفيذ…");
        io.execute(() -> {
            String message;
            try {
                message = "رد الراوتر: " + job.run();
            } catch (Exception e) {
                message = "فشل: " + e.getMessage();
            }
            final String shown = message;
            ui.post(() -> {
                if (isFinishing()) return;
                Ui.toast(this, shown);
                load();
            });
        });
    }

    private void speedDialog(final JSONObject device) {
        final String mac = device.optString("mac");
        final String origKey = "orig_" + profile.id + "_" + mac;
        LinearLayout f = form();
        f.addView(Ui.text(this, "الوحدة KB/s — كل 1 Mbps تقريباً 128 KB/s", 12, Ui.MUTED, false));
        final EditText down = Ui.input(this, "حد التنزيل", InputType.TYPE_CLASS_NUMBER);
        final EditText up = Ui.input(this, "حد الرفع", InputType.TYPE_CLASS_NUMBER);
        down.setText(device.optString("limitDown", ""));
        up.setText(device.optString("limitUp", ""));
        f.addView(down);
        f.addView(up);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("تحديد السرعة")
                .setView(f)
                .setPositiveButton("تطبيق", (d, w) -> {
                    final String dn = down.getText().toString().trim();
                    final String u = up.getText().toString().trim();
                    if (dn.isEmpty() || u.isEmpty()) {
                        Ui.toast(this, "اكتب القيمتين");
                        return;
                    }
                    // نحفظ القيم الأصلية أول مرة حتى يرجع «إلغاء التحديد» لنفس حالة الراوتر.
                    if (!prefs.contains(origKey)) {
                        prefs.edit().putString(origKey, device.optString("limitUp", "0") + ","
                                + device.optString("limitDown", "0")).apply();
                    }
                    applyLimit(device, u, dn);
                })
                .setNegativeButton("إلغاء", null);
        if (prefs.contains(origKey)) {
            b.setNeutralButton("إلغاء التحديد", (d, w) -> {
                String[] orig = prefs.getString(origKey, "0,0").split(",");
                prefs.edit().remove(origKey).apply();
                applyLimit(device, orig[0], orig.length > 1 ? orig[1] : "0");
            });
        }
        b.show();
    }

    private void applyLimit(final JSONObject device, final String up, final String down) {
        try {
            device.put("limitUp", up);
            device.put("limitDown", down);
        } catch (Exception e) {
            Ui.toast(this, "قيمة غير صالحة");
            return;
        }
        final JSONArray snapshot = table;
        run(() -> driver.setNetControl(snapshot));
    }

    private void parentDialog(final String mac, final String shown) {
        status.setText("جاري قراءة إعدادات الرقابة…");
        io.execute(() -> {
            try {
                final JSONObject cfg = driver.parentControl(mac);
                ui.post(() -> showParentDialog(mac, shown, cfg));
            } catch (final Exception e) {
                ui.post(() -> {
                    if (!isFinishing()) status.setText("تعذّرت قراءة الرقابة: " + e.getMessage());
                });
            }
        });
    }

    private void showParentDialog(final String mac, String shown, final JSONObject cfg) {
        if (isFinishing()) return;
        status.setText("");
        LinearLayout f = form();
        final Switch enable = new Switch(this);
        enable.setText("تفعيل الرقابة على هذا الجهاز");
        enable.setTextColor(Ui.WHITE);
        enable.setChecked("1".equals(cfg.optString("enable")));
        f.addView(enable);
        f.addView(Ui.text(this, "وقت السماح بالإنترنت (خارج هذا الوقت ينقطع النت عن الجهاز):", 12, Ui.MUTED, false),
                Ui.top(this, 10));
        final EditText time = Ui.input(this, "00:00-23:59", InputType.TYPE_CLASS_TEXT);
        time.setText(cfg.optString("time", "00:00-23:59"));
        f.addView(time);
        final Switch urlEnable = new Switch(this);
        urlEnable.setText("تفعيل قائمة المواقع");
        urlEnable.setTextColor(Ui.WHITE);
        urlEnable.setChecked("1".equals(cfg.optString("url_enable")));
        f.addView(urlEnable, Ui.top(this, 10));
        final EditText urls = Ui.input(this, "مواقع بينها فاصلة: tiktok,youtube", InputType.TYPE_CLASS_TEXT);
        urls.setText(cfg.optString("urls", ""));
        f.addView(urls);
        f.addView(Ui.text(this, "نوع القائمة (حظر أو سماح) والأيام تبقى كما هي في الراوتر؛ غيّرها من لوحة الراوتر.",
                12, Ui.MUTED, false), Ui.top(this, 8));
        new AlertDialog.Builder(this)
                .setTitle("الرقابة • " + shown)
                .setView(f)
                .setPositiveButton("تطبيق", (d, w) -> {
                    String t = time.getText().toString().trim();
                    if (!t.matches("\\d{2}:\\d{2}-\\d{2}:\\d{2}")) {
                        Ui.toast(this, "صيغة الوقت: 08:00-22:00");
                        return;
                    }
                    try {
                        cfg.put("enable", enable.isChecked() ? "1" : "0");
                        cfg.put("time", t);
                        cfg.put("url_enable", urlEnable.isChecked() ? "1" : "0");
                        cfg.put("urls", urls.getText().toString().trim());
                    } catch (Exception e) {
                        return;
                    }
                    run(() -> driver.setParentControl(mac, cfg));
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void renameDialog(final String mac, String shown) {
        LinearLayout f = form();
        final EditText name = Ui.input(this, "اسم الجهاز", InputType.TYPE_CLASS_TEXT);
        name.setText(shown);
        f.addView(name);
        new AlertDialog.Builder(this)
                .setTitle("تسمية الجهاز (تُحفظ على هاتفك فقط)")
                .setView(f)
                .setPositiveButton("حفظ", (d, w) -> {
                    prefs.edit().putString("name_" + mac, name.getText().toString().trim()).apply();
                    render(table);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("إعادة تشغيل الراوتر؟")
                .setMessage("سينقطع الإنترنت عن كل الأجهزة لمدة دقيقة تقريباً.")
                .setPositiveButton("إعادة تشغيل", (d, w) -> io.execute(() -> {
                    String m;
                    try {
                        driver.reboot();
                        m = "تم إرسال أمر إعادة التشغيل";
                    } catch (Exception e) {
                        m = "فشل: " + e.getMessage();
                    }
                    final String shown = m;
                    ui.post(() -> {
                        if (!isFinishing()) status.setText(shown);
                    });
                }))
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private LinearLayout form() {
        LinearLayout f = Ui.column(this);
        int p = Ui.dp(this, 20);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        f.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return f;
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
