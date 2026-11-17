package com.sl.wifiguard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** قراءة حالة الاتصال والشبكات القريبة عبر واجهات أندرويد الرسمية فقط. */
final class NetworkEngine {

    static final class Snapshot {
        boolean connected;
        String ssid = "";
        String bssid = "";
        String security = "غير معروف";
        String ip = "";
        String gateway = "";
        String dns = "";
        String message = "";
        int rssi, frequency, linkMbps, prefix, score;
    }

    static final class AccessPoint {
        String ssid = "";
        String bssid = "";
        String security = "";
        int rssi, frequency;
        boolean weak;
    }

    private NetworkEngine() {
    }

    static boolean permissionGranted(Context c) {
        boolean fine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= 33) {
            return fine && c.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return fine;
    }

    static boolean locationEnabled(Context c) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    @SuppressWarnings("deprecation")
    static Snapshot snapshot(Context c) {
        Snapshot s = new Snapshot();
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (wm == null || cm == null) {
                s.message = "تعذّرت قراءة معلومات الاتصال";
                return s;
            }
            if (!wm.isWifiEnabled()) {
                s.message = "شغّل الواي فاي حتى تظهر معلومات الشبكة";
                return s;
            }
            Network wifi = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = n;
            }
            if (wifi == null) {
                s.message = "اتصل بشبكة Wi-Fi لإظهار النتائج.";
                return s;
            }
            s.connected = true;
            LinkProperties lp = cm.getLinkProperties(wifi);
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof Inet4Address) {
                        s.ip = la.getAddress().getHostAddress();
                        s.prefix = la.getPrefixLength();
                    }
                }
                for (RouteInfo r : lp.getRoutes()) {
                    if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                        s.gateway = r.getGateway().getHostAddress();
                    }
                }
                StringBuilder dns = new StringBuilder();
                for (InetAddress a : lp.getDnsServers()) {
                    if (dns.length() > 0) dns.append("، ");
                    dns.append(a.getHostAddress());
                }
                s.dns = dns.toString();
            }
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null) {
                String ssid = wi.getSSID();
                if (ssid == null || ssid.contains("unknown ssid")) {
                    s.ssid = "شبكة متصلة";
                    s.message = "قد يخفي أندرويد اسم الشبكة ونوع الحماية عند غياب الأذونات. جرّب منح إذن الموقع.";
                } else {
                    s.ssid = ssid.replace("\"", "");
                }
                s.bssid = wi.getBSSID() == null ? "" : wi.getBSSID();
                s.rssi = wi.getRssi();
                s.frequency = wi.getFrequency();
                s.linkMbps = wi.getLinkSpeed();
                String sec = null;
                if (Build.VERSION.SDK_INT >= 31) sec = securityType(wi.getCurrentSecurityType());
                if (sec == null && permissionGranted(c)) {
                    for (ScanResult r : wm.getScanResults()) {
                        if (r.BSSID != null && r.BSSID.equalsIgnoreCase(s.bssid)) {
                            sec = securityFromCapabilities(r.capabilities);
                        }
                    }
                }
                if (sec != null) s.security = sec;
            }
            s.score = score(s.security, s.rssi);
        } catch (Exception e) {
            s.message = "تعذّرت قراءة معلومات الاتصال";
        }
        return s;
    }

    @SuppressWarnings("deprecation")
    static List<AccessPoint> nearby(Context c) {
        List<AccessPoint> out = new ArrayList<>();
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return out;
            Set<String> seen = new HashSet<>();
            for (ScanResult r : wm.getScanResults()) {
                if (r.BSSID == null || !seen.add(r.BSSID)) continue;
                AccessPoint ap = new AccessPoint();
                ap.ssid = r.SSID == null || r.SSID.isEmpty() ? "(شبكة مخفية)" : r.SSID;
                ap.bssid = r.BSSID;
                ap.rssi = r.level;
                ap.frequency = r.frequency;
                ap.security = securityFromCapabilities(r.capabilities);
                ap.weak = ap.security.equals("مفتوحة") || ap.security.equals("WEP") || ap.security.equals("WPA");
                out.add(ap);
            }
            Collections.sort(out, (a, b) -> Integer.compare(b.rssi, a.rssi));
        } catch (Exception ignored) {
        }
        return out;
    }

    static String securityFromCapabilities(String caps) {
        String c = caps == null ? "" : caps.toUpperCase();
        boolean eap = c.contains("EAP");
        if (c.contains("SAE")) return c.contains("PSK") ? "WPA2/WPA3" : "WPA3";
        if (c.contains("EAP_SUITE_B_192")) return "WPA3 Enterprise";
        if (c.contains("OWE")) return "OWE";
        if (c.contains("WPA2") || c.contains("RSN")) return eap ? "WPA2 Enterprise" : "WPA2";
        if (c.contains("WPA")) return eap ? "WPA Enterprise" : "WPA";
        if (c.contains("WEP")) return "WEP";
        return "مفتوحة";
    }

    /** قيم WifiInfo.SECURITY_TYPE_* (أندرويد 12+). يرجع null إذا كان النوع غير معروف. */
    static String securityType(int type) {
        switch (type) {
            case 0: return "مفتوحة";
            case 1: return "WEP";
            case 2: return "WPA2";
            case 3: return "WPA2 Enterprise";
            case 4: return "WPA3";
            case 5:
            case 9: return "WPA3 Enterprise";
            case 6: return "OWE";
            default: return null;
        }
    }

    static int score(String security, int rssi) {
        int base;
        switch (security) {
            case "WPA3":
            case "WPA3 Enterprise": base = 95; break;
            case "WPA2/WPA3": base = 88; break;
            case "WPA2 Enterprise": base = 88; break;
            case "WPA2": base = 80; break;
            case "OWE": base = 60; break;
            case "WPA":
            case "WPA Enterprise": base = 45; break;
            case "WEP": base = 20; break;
            case "مفتوحة": base = 10; break;
            default: base = 50;
        }
        if (rssi != 0 && rssi < -80) base -= 5;
        return Math.max(0, Math.min(100, base));
    }

    static String band(int mhz) {
        if (mhz >= 5925) return "6 GHz";
        if (mhz >= 4900) return "5 GHz";
        if (mhz >= 2400) return "2.4 GHz";
        return "غير محدد";
    }

    static String strength(int rssi) {
        if (rssi >= -55) return "ممتازة";
        if (rssi >= -67) return "جيدة";
        if (rssi >= -78) return "متوسطة";
        return "ضعيفة";
    }

    static String explain(String security) {
        if (security.startsWith("WPA3") || security.equals("WPA2/WPA3")) {
            return "تشفير WPA3 حديث. راجع كلمة المرور وتحديثات الراوتر وتعطيل WPS من لوحة إدارة الراوتر.";
        }
        if (security.startsWith("WPA2")) {
            return "WPA2 جيد عند استخدام AES وكلمة مرور قوية، لكن التقييم لا يتحقق من كلمة المرور فعلياً.";
        }
        if (security.equals("غير معروف")) {
            return "لم تتوفر بيانات كافية. امنح إذن الموقع ثم أعد التحليل.";
        }
        return "هذه الشبكة تستخدم تشفيراً ضعيفاً أو بدون تشفير. فعّل WPA2-AES أو WPA3 في الراوتر واستعمل كلمة مرور قوية وفريدة.";
    }
}
