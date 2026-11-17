package com.sl.wifiguard;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** التعرف على ماركة الراوتر من صفحة الدخول، ودليل مختصر لمكان كل ميزة. */
final class RouterDetect {
    static final String[] KEYS = {"auto", "tplink", "huawei", "zte", "tenda", "generic"};
    static final String[] NAMES = {"كشف تلقائي", "TP-Link", "Huawei", "ZTE", "Tenda", "راوتر آخر"};

    private RouterDetect() {
    }

    static String name(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return NAMES[i];
        return NAMES[NAMES.length - 1];
    }

    /** طلب شبكة: يُستدعى من خيط خلفي فقط. يرجع "generic" إذا لم تُعرف الماركة. */
    static String detect(String baseUrl) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(true);
            String server = String.valueOf(c.getHeaderField("Server"));
            StringBuilder page = new StringBuilder(server).append('\n');
            try (InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream()) {
                if (in != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while (out.size() < 96 * 1024 && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                    page.append(new String(out.toByteArray(), StandardCharsets.UTF_8));
                }
            } finally {
                c.disconnect();
            }
            String t = page.toString().toLowerCase(Locale.ROOT);
            if (t.contains("tenda")) return "tenda";
            if (t.contains("tp-link") || t.contains("tplink")) return "tplink";
            if (t.contains("huawei") || t.contains("hilink")) return "huawei";
            if (t.matches("(?s).*\\bzte\\b.*")) return "zte";
        } catch (Exception ignored) {
        }
        return "generic";
    }

    static String guide(String key) {
        String head = "أسماء القوائم تختلف حسب الموديل وإصدار النظام، وهذه أماكنها المعتادة:\n\n";
        switch (key) {
            case "tplink":
                return head
                        + "• تحديد سرعة جهاز: Advanced ← QoS (في الموديلات القديمة: Bandwidth Control).\n"
                        + "• حظر المواقع: Parental Controls أو Access Control.\n"
                        + "• حظر جهاز: Security ← Access Control ← Blacklist.\n"
                        + "• التحكم من بعيد: System Tools ← Administration ← Remote Management.";
            case "huawei":
                return head
                        + "• راوترات الألياف ONT (مثل HG8245 / EG8145): Security ← URL Filter لحظر المواقع، "
                        + "و MAC Filter أو Parental Control لحظر جهاز. أغلبها لا يدعم تحديد سرعة لكل جهاز.\n"
                        + "• راوترات Huawei WiFi (WS / AX): Manage Device ← Speed Limit لتحديد السرعة، "
                        + "و Parental Control لحظر المواقع.";
            case "zte":
                return head
                        + "• حظر المواقع: Internet ← Security ← URL Filter أو Parental Controls.\n"
                        + "• حظر جهاز: Local Network ← WLAN ← Access Control (MAC Filter).\n"
                        + "• تحديد السرعة: Internet ← QoS، وغالباً غير متوفر لكل جهاز في موديلات ONT.";
            case "tenda":
                return head
                        + "• تحديد سرعة جهاز: Bandwidth Control.\n"
                        + "• حظر المواقع وجدولة الإنترنت: Parental Control.\n"
                        + "• حظر جهاز: Blacklist أو MAC Filter.\n"
                        + "• التحكم من بعيد: System Settings ← Remote Web Management.\n\n"
                        + "يتوفر لهذه الماركة «تحكم مباشر» تجريبي من قائمة الأدوات.";
            default:
                return head
                        + "• تحديد السرعة: QoS أو Bandwidth Control.\n"
                        + "• حظر المواقع: Parental Control أو URL Filter.\n"
                        + "• حظر جهاز: MAC Filter أو Access Control.\n"
                        + "• التحكم من بعيد: Remote Management.\n\n"
                        + "إذا لم تجد الميزة فالراوتر نفسه لا يدعمها.";
        }
    }
}
