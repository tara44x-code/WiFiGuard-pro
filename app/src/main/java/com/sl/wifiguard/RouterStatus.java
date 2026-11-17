package com.sl.wifiguard;

import java.net.HttpURLConnection;
import java.net.URL;

/** فحص هل لوحة الراوتر ترد على العنوان. يُستدعى من خيط خلفي فقط. */
final class RouterStatus {
    static final int UNKNOWN = 0, ONLINE = 1, OFFLINE = -1;

    private RouterStatus() {
    }

    /** أي رد HTTP (حتى 401 أو 404) يعني أن الراوتر شغال ويمكن الوصول له. */
    static int check(String baseUrl, int timeoutMs) {
        if (baseUrl == null || baseUrl.isEmpty()) return UNKNOWN;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/").openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET");
            return c.getResponseCode() > 0 ? ONLINE : OFFLINE;
        } catch (Exception e) {
            return OFFLINE;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** عناوين IPv4 الخاصة أو المشتركة (CGNAT): لا يمكن الوصول لها من الإنترنت. */
    static boolean isPrivateIpv4(String ip) {
        String[] p = ip == null ? new String[0] : ip.trim().split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || a == 127 || a == 0
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 169 && b == 254)
                    || (a == 100 && b >= 64 && b <= 127);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
