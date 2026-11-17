package com.sl.wifiguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

/** مشاركة الواي فاي برمز QR: الضيف يصوّره بالكاميرا ويتصل بدون ما يكتب الباسورد. */
final class WifiQr {
    private WifiQr() {
    }

    static void show(final Activity a, final RouterProfile p) {
        LinearLayout f = Ui.column(a);
        int pad = Ui.dp(a, 20);
        f.setPadding(pad, Ui.dp(a, 8), pad, 0);
        f.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        f.addView(Ui.text(a, "أندرويد ما يسمح للتطبيقات تقرا باسورد الواي فاي، فاكتبه مرة وحدة ويتحفظ مشفّر.",
                12, Ui.MUTED, false));
        final EditText ssid = Ui.input(a, "اسم الشبكة", InputType.TYPE_CLASS_TEXT);
        final EditText pass = Ui.input(a, "باسورد الواي فاي",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        String current = NetworkEngine.snapshot(a).ssid;
        ssid.setText(!p.wifiSsid.isEmpty() ? p.wifiSsid : current.equals("شبكة متصلة") ? "" : current);
        pass.setText(p.wifiPassword);
        f.addView(ssid);
        f.addView(pass);
        new AlertDialog.Builder(a)
                .setTitle("مشاركة الواي فاي")
                .setView(f)
                .setPositiveButton("إظهار الرمز", (d, w) -> {
                    String s = ssid.getText().toString();
                    String pw = pass.getText().toString();
                    if (s.trim().isEmpty()) {
                        Ui.toast(a, "اكتب اسم الشبكة");
                        return;
                    }
                    p.wifiSsid = s;
                    p.wifiPassword = pw;
                    try {
                        Vault.put(a, p);
                    } catch (Exception ignored) {
                    }
                    showCode(a, s, pw);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private static void showCode(Activity a, String ssid, String password) {
        String text = "WIFI:T:" + (password.isEmpty() ? "nopass" : "WPA") + ";S:" + esc(ssid) + ";"
                + (password.isEmpty() ? "" : "P:" + esc(password) + ";") + ";";
        Bitmap bmp;
        try {
            bmp = render(text, 720);
        } catch (Exception e) {
            Ui.toast(a, "تعذّر إنشاء الرمز");
            return;
        }
        ImageView img = new ImageView(a);
        img.setImageBitmap(bmp);
        img.setAdjustViewBounds(true);
        int pad = Ui.dp(a, 16);
        img.setPadding(pad, pad, pad, pad);
        img.setBackgroundColor(0xFFFFFFFF);
        new AlertDialog.Builder(a)
                .setTitle(ssid)
                .setMessage("خلي الضيف يفتح كاميرا هاتفه ويصوّر الرمز.")
                .setView(img)
                .setPositiveButton("تم", null)
                .show();
    }

    /** الأحرف \ ; , : " لازم تسبقها \ حسب صيغة رمز الواي فاي. */
    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if ("\\;,:\"".indexOf(ch) >= 0) sb.append('\\');
            sb.append(ch);
        }
        return sb.toString();
    }

    private static Bitmap render(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int w = m.getWidth(), h = m.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }
}
