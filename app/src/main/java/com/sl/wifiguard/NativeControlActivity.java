package com.sl.wifiguard;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** لوحة راوتر Tenda المبسطة: حالة الراوتر، أزرار سريعة، والأجهزة مرتبة حسب الاستهلاك. */
public class NativeControlActivity extends BaseActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private RouterProfile profile;
    private TendaDriver driver;
    private boolean remote, loading;
    private LinearLayout list;
    private TextView status;
    private RouterIcon icon;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        profile = Vault.find(this, String.valueOf(getIntent().getStringExtra(RouterActivity.EXTRA_ID)));
        remote = getIntent().getBooleanExtra(RouterActivity.EXTRA_REMOTE, false);
        if (profile == null || profile.url(remote).isEmpty()) {
            finish();
            return;
        }
        driver = new TendaDriver(profile.url(remote));

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

        String[] labels = {"تحديث ↻", "إعادة تشغيل", "مشاركة الواي فاي", "باسورد الواي فاي", "حماية كل البيت", "لوحة الراوتر"};
        int[] colors = {Ui.CYAN, Ui.RED, Ui.GREEN, Ui.BLUE, Ui.GOLD, Ui.MUTED};
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
        head.addView(Ui.text(this, "الأجهزة", 17, Ui.WHITE, true), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView diag = Ui.text(this, "تقرير تشخيص", 12, Ui.MUTED, false);
        diag.setOnClickListener(v -> diagnostics());
        head.addView(diag);
        page.addView(head, Ui.top(this, 18));
        page.addView(Ui.text(this, "اضغط على أي جهاز حتى تسميه أو تقطع عنه النت أو تحظر المواقع الإباحية.",
                12, Ui.MUTED, false), Ui.top(this, 2));

        list = Ui.column(this);
        list.setPadding(0, Ui.dp(this, 10), 0, 0);
        page.addView(list);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Vault.isLocked()) load();
    }

    private void load() {
        if (loading) return;
        loading = true;
        status.setTextColor(Ui.MUTED);
        status.setText("جاري الاتصال بالراوتر…");
        io.execute(() -> {
            try {
                if (!driver.login(profile.username, profile.password)) {
                    throw new Exception("فشل تسجيل الدخول. تأكد من كلمة المرور.");
                }
                final List<NetDevice> devices = driver.devices();
                DeviceStore.merge(this, profile.id, devices);
                rememberHomeNetwork();
                final List<NetDevice> offline = DeviceStore.offline(this, profile.id, devices);
                ui.post(() -> {
                    loading = false;
                    if (isFinishing()) return;
                    icon.setOnline(true);
                    render(devices, offline);
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    loading = false;
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

    private void render(List<NetDevice> devices, List<NetDevice> offline) {
        list.removeAllViews();
        List<NetDevice> on = new ArrayList<>(), off = new ArrayList<>(offline);
        for (NetDevice d : devices) (d.online ? on : off).add(d);
        Collections.sort(on, (a, b) -> Long.compare(b.down + b.up, a.down + a.up));
        Collections.sort(off, (a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        int fresh = 0;
        for (NetDevice d : on) if (d.isNew) fresh++;
        RouterWidget.save(this, profile.name, true, on.size());

        status.setTextColor(fresh > 0 ? Ui.GOLD : Ui.GREEN);
        status.setText("● شغال  •  " + on.size() + " جهاز متصل" + (fresh > 0 ? "  •  " + fresh + " جديد" : ""));
        for (NetDevice d : on) list.addView(DeviceRow.build(this, profile, remote, d));
        if (!off.isEmpty()) {
            list.addView(Ui.text(this, "غير متصلة حالياً", 13, Ui.MUTED, true), Ui.top(this, 10));
            for (NetDevice d : off) list.addView(DeviceRow.build(this, profile, remote, d));
        }
    }

    /** أول دخول ناجح محلياً: نحفظ اسم شبكة البيت حتى يعرف التطبيق (والتنبيهات) أنك بالبيت. */
    private void rememberHomeNetwork() {
        if (remote || !profile.homeSsid.isEmpty()) return;
        String ssid = NetworkEngine.snapshot(this).ssid;
        if (ssid.isEmpty() || ssid.equals("شبكة متصلة")) return;
        profile.homeSsid = ssid;
        try {
            Vault.put(this, profile);
        } catch (Exception ignored) {
        }
    }

    private void action(int idx) {
        switch (idx) {
            case 0: load(); break;
            case 1: confirmReboot(); break;
            case 2: WifiQr.show(this, profile); break;
            case 3: web("⁦Wireless / WiFi Settings⁩ ← غيّر ⁦WiFi Password⁩ واحفظ. انتبه: كل الأجهزة راح تنفصل وترجع تتصل بالباسورد الجديد."); break;
            case 4: web("⁦Internet Settings⁩ ← ⁦DNS⁩: اختار ⁦Manual⁩ واكتب 1.1.1.3 بالأول و 1.0.0.3 بالثاني ثم ⁦Save⁩. هذا يحظر المواقع الإباحية والضارة على كل أجهزة البيت."); break;
            default: web(null);
        }
    }

    private void web(String hint) {
        startActivity(new Intent(this, RouterActivity.class)
                .putExtra(RouterActivity.EXTRA_ID, profile.id)
                .putExtra(RouterActivity.EXTRA_REMOTE, remote)
                .putExtra(RouterActivity.EXTRA_HINT, hint));
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("إعادة تشغيل الراوتر؟")
                .setMessage("سينقطع الإنترنت عن كل الأجهزة لمدة دقيقة تقريباً.")
                .setPositiveButton("إعادة تشغيل", (d, w) -> io.execute(() -> {
                    String m;
                    try {
                        if (!driver.login(profile.username, profile.password)) throw new Exception("فشل تسجيل الدخول");
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
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(i, "إرسال تقرير التشخيص"));
            });
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
