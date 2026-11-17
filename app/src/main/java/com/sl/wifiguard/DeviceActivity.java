package com.sl.wifiguard;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** شاشة جهاز واحد: الاسم، قطع النت، وقت السماح، حظر المواقع الإباحية، والسرعة. */
public class DeviceActivity extends BaseActivity {
    static final String EXTRA_MAC = "mac";

    private interface Job {
        String run() throws Exception;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private RouterProfile profile;
    private TendaDriver driver;
    private boolean remote, busy;
    private String mac;
    private NetDevice device;
    private LinearLayout page;
    private TextView result;
    private String resultText = "";
    private int resultColor = Ui.MUTED;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        profile = Vault.find(this, String.valueOf(getIntent().getStringExtra(RouterActivity.EXTRA_ID)));
        remote = getIntent().getBooleanExtra(RouterActivity.EXTRA_REMOTE, false);
        mac = NetDevice.normalizeMac(getIntent().getStringExtra(EXTRA_MAC));
        if (profile == null || mac.isEmpty() || profile.url(remote).isEmpty()) {
            finish();
            return;
        }
        driver = new TendaDriver(profile.url(remote));

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll = new ScrollView(this);
        page = Ui.column(this);
        int p = Ui.dp(this, 16);
        page.setPadding(p, p, p, p);
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        setContentView(root);

        page.addView(Ui.text(this, "جاري قراءة الجهاز من الراوتر…", 15, Ui.MUTED, false));
        load();
    }

    private void load() {
        io.execute(() -> {
            NetDevice found = null;
            String error = null;
            try {
                if (!driver.login(profile.username, profile.password)) throw new Exception("فشل تسجيل الدخول للراوتر");
                List<NetDevice> list = driver.devices();
                DeviceStore.merge(this, profile.id, list);
                for (NetDevice d : list) if (d.mac.equals(mac)) found = d;
                if (found == null) {
                    for (NetDevice d : DeviceStore.offline(this, profile.id, list)) if (d.mac.equals(mac)) found = d;
                }
            } catch (Exception e) {
                error = e.getMessage();
                for (NetDevice d : DeviceStore.offline(this, profile.id, java.util.Collections.<NetDevice>emptyList())) {
                    if (d.mac.equals(mac)) found = d;
                }
            }
            final NetDevice f = found;
            final String err = error;
            ui.post(() -> {
                if (isFinishing()) return;
                if (f == null) {
                    page.removeAllViews();
                    page.addView(Ui.text(this, "ما لكيت هذا الجهاز" + (err != null ? ": " + err : ""), 15, Ui.RED, false));
                    return;
                }
                device = f;
                if (err != null) {
                    resultText = "الراوتر ما رد: " + err;
                    resultColor = Ui.RED;
                }
                render();
            });
        });
    }

    private void render() {
        page.removeAllViews();
        final DeviceStore.Policy policy = DeviceStore.policy(this, profile.id, mac);

        // ---------------------------------------------------------------- رأس الصفحة
        LinearLayout head = Ui.card(this);
        LinearLayout top = Ui.row(this);
        TextView icon = Ui.text(this, device.icon(), 40, Ui.WHITE, false);
        top.addView(icon);
        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        titles.addView(Ui.text(this, device.displayName(), 20, Ui.WHITE, true));
        titles.addView(Ui.text(this, device.kindLabel() + "  •  " + profile.name, 12, Ui.MUTED, false));
        top.addView(titles, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView close = Ui.text(this, "✕", 20, Ui.MUTED, true);
        close.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        close.setOnClickListener(v -> finish());
        top.addView(close);
        head.addView(top);

        TextView st = Ui.text(this, device.online ? "● متصل الآن" : "○ غير متصل • آخر ظهور " + DeviceStore.ago(device.lastSeen),
                14, device.online ? Ui.GREEN : Ui.MUTED, true);
        head.addView(st, Ui.top(this, 12));
        pair(head, "عنوان IP", device.ip);
        pair(head, "عنوان MAC", device.mac.toUpperCase(java.util.Locale.ROOT));
        if (device.online) {
            pair(head, "الاستهلاك الآن", "↓ " + NetDevice.speed(device.down) + "   ↑ " + NetDevice.speed(device.up));
        }
        if (device.firstSeen > 0) {
            pair(head, "أول مرة شفناه", DateFormat.getDateInstance().format(new Date(device.firstSeen)));
        }
        if (device.randomizedMac()) {
            head.addView(Ui.text(this, "هذا الجهاز يستعمل عنوان خصوصية عشوائي، فإذا غيّر إعداده ممكن يطلع كجهاز جديد.",
                    12, Ui.MUTED, false), Ui.top(this, 8));
        }
        head.addView(Ui.button(this, "تغيير الاسم ✎", Ui.CYAN, v -> renameDialog()), Ui.top(this, 12));
        page.addView(head);

        // ---------------------------------------------------------------- التحكم
        LinearLayout ctl = Ui.card(this);
        ctl.addView(Ui.text(this, "التحكم بالنت", 13, Ui.MUTED, true));

        final Switch block = sw(ctl, "قطع النت عن هذا الجهاز", policy.blocked);
        block.setOnClickListener(v -> {
            DeviceStore.Policy np = copy(policy);
            np.blocked = block.isChecked();
            apply(np, np.blocked ? "تم قطع النت" : "رجع النت");
        });

        LinearLayout tr = Ui.row(this);
        LinearLayout tcol = Ui.column(this);
        tcol.addView(Ui.text(this, "وقت السماح بالنت", 15, Ui.WHITE, false));
        tcol.addView(Ui.text(this, policy.allowed.isEmpty() ? "طول اليوم" : "من " + policy.allowed.replace("-", " إلى "),
                13, policy.allowed.isEmpty() ? Ui.MUTED : Ui.GOLD, true));
        tr.addView(tcol, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        tr.addView(Ui.button(this, "تغيير", Ui.GOLD, v -> timeDialog(policy)));
        ctl.addView(tr, Ui.top(this, 14));

        final Switch adult = sw(ctl, "حظر المواقع الإباحية", policy.adultFilter);
        adult.setOnClickListener(v -> {
            DeviceStore.Policy np = copy(policy);
            np.adultFilter = adult.isChecked();
            apply(np, np.adultFilter ? "تم تفعيل حظر المواقع الإباحية" : "تم إلغاء حظر المواقع");
        });
        ctl.addView(Ui.text(this, "الحظر بالكلمات (porn و xxx وأشهر المواقع). ممكن يتجاوزه جهاز يستعمل VPN أو DNS خاص، "
                + "ونادراً يحظر موقع عادي اسمه يحتوي هالكلمات.", 12, Ui.MUTED, false), Ui.top(this, 4));

        result = Ui.text(this, resultText, 13, resultColor, true);
        ctl.addView(result, Ui.top(this, 10));
        page.addView(ctl);

        // ---------------------------------------------------------------- حماية أوسع
        LinearLayout safe = Ui.card(this);
        safe.addView(Ui.text(this, "حماية أقوى", 13, Ui.MUTED, true));
        safe.addView(Ui.text(this, "لحماية كل البيت من المواقع الإباحية، خلي DNS الراوتر على خدمة Cloudflare العائلية "
                + "(1.1.1.3 و 1.0.0.3). تشمل كل الأجهزة وتفرض البحث الآمن.", 13, Ui.WHITE, false), Ui.top(this, 6));
        safe.addView(Ui.button(this, "حماية كل البيت (DNS العائلي)", Ui.GREEN, v -> web(
                "Internet Settings ← DNS: اختار Manual، واكتب 1.1.1.3 بالأول و 1.0.0.3 بالثاني، ثم Save.")), Ui.top(this, 10));
        safe.addView(Ui.button(this, "تأكد من الإعداد بلوحة الراوتر", Ui.MUTED, v -> web(
                "Parental Control ← هذا الجهاز: تأكد إن Website Limit مفعّل ونوعه Blacklist. إذا طالع Whitelist بدّله وكلي.")),
                Ui.top(this, 8));
        page.addView(safe);

        // ---------------------------------------------------------------- السرعة
        LinearLayout sp = Ui.card(this);
        sp.addView(Ui.text(this, "السرعة", 13, Ui.MUTED, true));
        pair(sp, "الحد الحالي", device.limitDown == 0 && device.limitUp == 0 ? "بدون حد"
                : "↓ " + NetDevice.speed(device.limitDown) + "   ↑ " + NetDevice.speed(device.limitUp));
        sp.addView(Ui.button(this, "تحديد السرعة", Ui.BLUE, v -> speedDialog()), Ui.top(this, 10));
        page.addView(sp);
    }

    // ---------------------------------------------------------------- عناصر

    private Switch sw(LinearLayout parent, String label, boolean checked) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.WHITE);
        s.setTextSize(15);
        s.setChecked(checked);
        parent.addView(s, Ui.top(this, 12));
        return s;
    }

    private void pair(LinearLayout parent, String k, String v) {
        LinearLayout r = Ui.row(this);
        r.addView(Ui.text(this, k, 13, Ui.MUTED, false), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView t = Ui.text(this, v == null || v.isEmpty() ? "—" : v, 14, Ui.WHITE, true);
        t.setTextIsSelectable(true);
        r.addView(t);
        parent.addView(r, Ui.top(this, 8));
    }

    private static DeviceStore.Policy copy(DeviceStore.Policy p) {
        DeviceStore.Policy n = new DeviceStore.Policy();
        n.blocked = p.blocked;
        n.allowed = p.allowed;
        n.adultFilter = p.adultFilter;
        return n;
    }

    private LinearLayout form() {
        LinearLayout f = Ui.column(this);
        int p = Ui.dp(this, 20);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        f.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return f;
    }

    private void web(String hint) {
        startActivity(new Intent(this, RouterActivity.class)
                .putExtra(RouterActivity.EXTRA_ID, profile.id)
                .putExtra(RouterActivity.EXTRA_REMOTE, remote)
                .putExtra(RouterActivity.EXTRA_HINT, hint));
    }

    private void showResult(String text, int color) {
        resultText = text;
        resultColor = color;
        if (result != null) {
            result.setText(text);
            result.setTextColor(color);
        }
    }

    // ---------------------------------------------------------------- الإجراءات

    private void run(final String progress, final Job job, final Runnable after) {
        if (busy) return;
        busy = true;
        showResult(progress, Ui.MUTED);
        io.execute(() -> {
            String msg;
            int color;
            try {
                if (!driver.login(profile.username, profile.password)) throw new Exception("فشل تسجيل الدخول للراوتر");
                msg = job.run();
                color = msg.startsWith("✓") ? Ui.GREEN : Ui.GOLD;
            } catch (Exception e) {
                msg = "✕ فشل: " + e.getMessage();
                color = Ui.RED;
            }
            final String m = msg;
            final int c = color;
            ui.post(() -> {
                busy = false;
                if (isFinishing()) return;
                resultText = m;
                resultColor = c;
                if (after != null) after.run();
                render();
            });
        });
    }

    private void apply(final DeviceStore.Policy np, final String done) {
        run("جاري الإرسال للراوتر…", () -> {
            boolean verified = driver.applyPolicy(mac, np);
            DeviceStore.setPolicy(this, profile.id, mac, np);
            return verified ? "✓ " + done + " — الراوتر حفظ الإعداد"
                    : "⚠ انرسل الإعداد بس الراوتر رجّع قيم مختلفة. تأكد من لوحة الراوتر.";
        }, null);
    }

    private void timeDialog(final DeviceStore.Policy policy) {
        LinearLayout f = form();
        f.addView(Ui.text(this, "النت يشتغل على هذا الجهاز بس بهذا الوقت، وينقطع بباقي اليوم (مثلاً وقت النوم).",
                13, Ui.MUTED, false));
        String[] cur = policy.allowed.isEmpty() ? new String[]{"07:00", "23:00"} : policy.allowed.split("-");
        final EditText from = Ui.input(this, "من (مثلاً 07:00)", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        final EditText to = Ui.input(this, "إلى (مثلاً 23:00)", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        from.setText(cur[0]);
        to.setText(cur.length > 1 ? cur[1] : "23:00");
        from.setGravity(Gravity.CENTER);
        to.setGravity(Gravity.CENTER);
        f.addView(from);
        f.addView(to);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("وقت السماح بالنت")
                .setView(f)
                .setPositiveButton("تطبيق", null)
                .setNeutralButton("طول اليوم", (d, w) -> {
                    DeviceStore.Policy np = copy(policy);
                    np.allowed = "";
                    apply(np, "النت مسموح طول اليوم");
                })
                .setNegativeButton("إلغاء", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = clock(from.getText().toString()), z = clock(to.getText().toString());
            if (a == null || z == null || a.equals(z)) {
                Ui.toast(this, "اكتب الوقت بصيغة 07:00");
                return;
            }
            dlg.dismiss();
            DeviceStore.Policy np = copy(policy);
            np.allowed = a + "-" + z;
            apply(np, "النت مسموح من " + a + " إلى " + z);
        });
    }

    /** يقبل 7 أو 7:5 أو 07:05 ويرجع 07:05، أو null إذا غلط. */
    private static String clock(String raw) {
        String t = raw == null ? "" : raw.trim().replace('.', ':').replace('٫', ':');
        String[] p = t.split(":");
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return String.format(java.util.Locale.ROOT, "%02d:%02d", h, m);
        } catch (Exception e) {
            return null;
        }
    }

    private void renameDialog() {
        LinearLayout f = form();
        final EditText name = Ui.input(this, "اسم الجهاز (مثلاً: موبايل أحمد)", InputType.TYPE_CLASS_TEXT);
        name.setText(device.displayName());
        f.addView(name);
        final CheckBox onRouter = new CheckBox(this);
        onRouter.setText("احفظه بالراوتر هم (يطلع بكل مكان)");
        onRouter.setTextColor(Ui.WHITE);
        onRouter.setChecked(true);
        f.addView(onRouter, Ui.top(this, 8));
        new AlertDialog.Builder(this)
                .setTitle("تغيير اسم الجهاز")
                .setView(f)
                .setPositiveButton("حفظ", (d, w) -> {
                    final String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    DeviceStore.setAlias(this, profile.id, mac, n);
                    device.alias = n;
                    if (!onRouter.isChecked()) {
                        showResult("✓ انحفظ الاسم على هاتفك", Ui.GREEN);
                        render();
                        return;
                    }
                    run("جاري حفظ الاسم بالراوتر…", () -> driver.rename(mac, n)
                            ? "✓ انحفظ الاسم بالراوتر وعلى هاتفك"
                            : "⚠ انحفظ الاسم على هاتفك، بس الراوتر ما قبله", () -> device.routerName = n);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void speedDialog() {
        final SharedPreferences prefs = getSharedPreferences("wifi_guard_settings", MODE_PRIVATE);
        final String origKey = "orig_" + profile.id + "_" + mac;
        LinearLayout f = form();
        f.addView(Ui.text(this, "الوحدة KB/s — كل 1 ميگا (Mbps) تقريباً 128 KB/s", 12, Ui.MUTED, false));
        final EditText down = Ui.input(this, "حد التنزيل", InputType.TYPE_CLASS_NUMBER);
        final EditText up = Ui.input(this, "حد الرفع", InputType.TYPE_CLASS_NUMBER);
        if (device.limitDown > 0) down.setText(String.valueOf(device.limitDown));
        if (device.limitUp > 0) up.setText(String.valueOf(device.limitUp));
        f.addView(down);
        f.addView(up);
        LinearLayout presets = Ui.row(this);
        String[][] ps = {{"1 ميگا", "128"}, {"2 ميگا", "256"}, {"5 ميگا", "640"}};
        for (final String[] pr : ps) {
            presets.addView(Ui.button(this, pr[0], Ui.CYAN, v -> {
                down.setText(pr[1]);
                up.setText(pr[1]);
            }), Ui.weight(this, 3));
        }
        f.addView(presets, Ui.top(this, 8));
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("تحديد السرعة")
                .setView(f)
                .setPositiveButton("تطبيق", (d, w) -> {
                    final String dn = down.getText().toString().trim(), u = up.getText().toString().trim();
                    if (dn.isEmpty() || u.isEmpty()) {
                        Ui.toast(this, "اكتب القيمتين");
                        return;
                    }
                    setLimit(prefs, origKey, u, dn, false);
                })
                .setNegativeButton("إلغاء", null);
        if (prefs.contains(origKey)) b.setNeutralButton("بدون حد", (d, w) -> setLimit(prefs, origKey, null, null, true));
        b.show();
    }

    private void setLimit(final SharedPreferences prefs, final String origKey, final String up, final String down,
                          final boolean restore) {
        run("جاري تطبيق السرعة…", () -> {
            JSONArray table = driver.netControl();
            JSONObject row = null;
            for (int i = 0; i < table.length(); i++) {
                JSONObject o = table.optJSONObject(i);
                if (o != null && mac.equals(NetDevice.normalizeMac(o.optString("mac")))) row = o;
            }
            if (row == null) return "⚠ الجهاز مو موجود بجدول السرعة بالراوتر (لازم يكون متصل)";
            if (restore) {
                String[] orig = prefs.getString(origKey, "0,0").split(",");
                row.put("limitUp", orig[0]);
                row.put("limitDown", orig.length > 1 ? orig[1] : "0");
                prefs.edit().remove(origKey).apply();
            } else {
                // نحفظ القيم الأصلية أول مرة حتى «بدون حد» يرجّع نفس حالة الراوتر.
                if (!prefs.contains(origKey)) {
                    prefs.edit().putString(origKey, row.optString("limitUp", "0") + "," + row.optString("limitDown", "0")).apply();
                }
                row.put("limitUp", up);
                row.put("limitDown", down);
            }
            driver.setNetControl(table);
            for (NetDevice d : driver.devices()) {
                if (d.mac.equals(mac)) {
                    device.limitUp = d.limitUp;
                    device.limitDown = d.limitDown;
                }
            }
            return "✓ انطبقت السرعة";
        }, null);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
