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

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, 0);
        root.addView(Ui.text(this, "تحكم مباشر • " + profile.name, 20, Ui.WHITE, true));
        status = Ui.text(this, "", 12, Ui.MUTED, false);
        root.addView(status, Ui.top(this, 4));
        LinearLayout bar = Ui.row(this);
        bar.addView(Ui.button(this, "تحديث ↻", Ui.CYAN, v -> load()), Ui.weight(this, 3));
        bar.addView(Ui.button(this, "إعادة تشغيل الراوتر", Ui.RED, v -> confirmReboot()), Ui.weight(this, 3));
        root.addView(bar, Ui.top(this, 10));
        TextView warn = Ui.text(this, "ميزة تجريبية لراوترات Tenda (سلسلة AC). تأكد من النتيجة من لوحة الراوتر بعد أول تغيير.",
                12, Ui.GOLD, false);
        root.addView(warn, Ui.top(this, 8));

        ScrollView scroll = new ScrollView(this);
        list = Ui.column(this);
        list.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 16));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        setContentView(root);
        load();
    }

    private void load() {
        status.setText("جاري الاتصال بالراوتر…");
        io.execute(() -> {
            try {
                if (!driver.login(profile.username, profile.password)) {
                    throw new Exception("فشل تسجيل الدخول. تأكد من كلمة المرور أو استعمل لوحة الراوتر.");
                }
                final JSONArray t = driver.netControl();
                ui.post(() -> render(t));
            } catch (final Exception e) {
                ui.post(() -> {
                    if (!isFinishing()) status.setText("تعذّر التحكم المباشر: " + e.getMessage());
                });
            }
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
