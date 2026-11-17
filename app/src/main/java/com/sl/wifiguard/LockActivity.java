package com.sl.wifiguard;

import android.app.Activity;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * شاشة الرمز السري.
 * - أول تشغيل: إنشاء رمز (4 إلى 8 أرقام) مع تأكيده.
 * - بعدها: إدخال الرمز لفتح التطبيق، أو البصمة إذا فُعّلت من الإعدادات.
 * - EXTRA_CHANGE: تغيير الرمز من داخل التطبيق، ويطلب الرمز الحالي أولاً.
 */
public class LockActivity extends Activity {
    static final String EXTRA_CHANGE = "change";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder entry = new StringBuilder();
    private TextView title, dots, hint;
    private boolean setup, change, verified;
    private String first;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.secureWindow(this);
        change = getIntent().getBooleanExtra(EXTRA_CHANGE, false) && !Vault.isLocked() && Vault.hasPin(this);
        setup = change || !Vault.hasPin(this);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setGravity(Gravity.CENTER);
        root.setFitsSystemWindows(true);
        int p = Ui.dp(this, 28);
        root.setPadding(p, p, p, p);

        TextView logo = Ui.text(this, "WiFi GUARD", 26, Ui.CYAN, true);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);
        title = Ui.text(this, "", 16, Ui.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, Ui.top(this, 18));
        dots = Ui.text(this, "", 30, Ui.CYAN, true);
        dots.setGravity(Gravity.CENTER);
        root.addView(dots, Ui.top(this, 14));
        hint = Ui.text(this, "", 13, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, Ui.top(this, 6));

        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"⌫", "0", "✓"}};
        for (String[] line : keys) {
            LinearLayout row = Ui.row(this);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            for (final String k : line) {
                int color = k.equals("✓") ? Ui.GREEN : k.equals("⌫") ? Ui.GOLD : Ui.WHITE;
                TextView key = Ui.button(this, k, color, v -> press(k));
                key.setTextSize(22);
                row.addView(key, Ui.weight(this, 6));
            }
            root.addView(row, Ui.top(this, 12));
        }

        if (!setup && Vault.biometricEnabled(this) && Build.VERSION.SDK_INT >= 28) {
            root.addView(Ui.button(this, "فتح بالبصمة", Ui.BLUE, v -> biometric()), Ui.top(this, 18));
        }
        setContentView(root);
        refresh("");
        if (!setup && Vault.biometricEnabled(this) && Build.VERSION.SDK_INT >= 28
                && Vault.lockoutSeconds(this) == 0) {
            biometric();
        }
    }

    private void press(String k) {
        if (k.equals("⌫")) {
            if (entry.length() > 0) entry.setLength(entry.length() - 1);
            refresh("");
        } else if (k.equals("✓")) {
            submit();
        } else if (entry.length() < 8) {
            entry.append(k);
            refresh("");
        }
    }

    private void submit() {
        String pin = entry.toString();
        entry.setLength(0);
        if (pin.length() < 4) {
            refresh("الرمز من 4 إلى 8 أرقام");
            return;
        }
        if (change && !verified) {
            // تغيير الرمز يتطلب الرمز الحالي أولاً، حتى لو كانت الجلسة مفتوحة.
            if (Vault.checkPin(this, pin)) {
                verified = true;
                refresh("");
            } else {
                refresh(Vault.lockoutSeconds(this) > 0 ? "" : "رمز خاطئ");
            }
            return;
        }
        if (setup) {
            if (first == null) {
                first = pin;
                refresh("");
            } else if (first.equals(pin)) {
                try {
                    Vault.setPin(this, pin);
                    Vault.unlockSession();
                    Ui.toast(this, "تم حفظ الرمز السري");
                    finish();
                } catch (Exception e) {
                    first = null;
                    refresh("تعذّر حفظ الرمز: " + e.getMessage());
                }
            } else {
                first = null;
                refresh("الرمزان غير متطابقين، أعد المحاولة");
            }
            return;
        }
        if (Vault.checkPin(this, pin)) {
            Vault.unlockSession();
            finish();
        } else {
            refresh(Vault.lockoutSeconds(this) > 0 ? "" : "رمز خاطئ");
        }
    }

    private void refresh(String message) {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < entry.length(); i++) d.append("● ");
        dots.setText(d.length() == 0 ? "— — — —" : d.toString().trim());
        boolean askCurrent = change && !verified;
        if (askCurrent) {
            title.setText("أدخل الرمز السري الحالي");
        } else if (setup) {
            title.setText(first == null
                    ? (change ? "اكتب الرمز السري الجديد" : "أنشئ رمزاً سرياً لحماية التطبيق")
                    : "أعد كتابة الرمز للتأكيد");
        } else {
            title.setText("أدخل الرمز السري");
        }
        long wait = setup && !askCurrent ? 0 : Vault.lockoutSeconds(this);
        ui.removeCallbacksAndMessages(null);
        if (wait > 0) {
            hint.setTextColor(Ui.RED);
            hint.setText("محاولات خاطئة كثيرة. انتظر " + wait + " ثانية");
            ui.postDelayed(() -> refresh(""), 1000);
        } else {
            hint.setTextColor(message.isEmpty() ? Ui.MUTED : Ui.RED);
            hint.setText(message.isEmpty() ? "ثم اضغط ✓" : message);
        }
    }

    private void biometric() {
        if (Build.VERSION.SDK_INT < 28 || Vault.lockoutSeconds(this) > 0) return;
        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle("WiFi Guard")
                    .setSubtitle("أكّد هويتك لفتح التطبيق")
                    .setNegativeButton("استخدام الرمز", getMainExecutor(), (dialog, which) -> {
                    })
                    .build();
            prompt.authenticate(new CancellationSignal(), getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            Vault.unlockSession();
                            finish();
                        }
                    });
        } catch (Exception e) {
            refresh("البصمة غير متاحة على هذا الجهاز");
        }
    }

    @Override
    public void onBackPressed() {
        if (change) {
            finish();
        } else {
            // لا يمكن تجاوز القفل بزر الرجوع: يرجع المستخدم للشاشة الرئيسية للهاتف.
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
