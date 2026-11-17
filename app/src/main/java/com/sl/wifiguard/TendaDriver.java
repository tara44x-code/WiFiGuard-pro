package com.sl.wifiguard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * تحكم مباشر تجريبي براوترات Tenda التي تستعمل واجهة goform (سلسلة AC مثل AC6 / AC10 / AC15).
 *
 * المسارات مأخوذة من مشاريع مفتوحة المصدر لنفس الواجهة، ولم تُختبر هنا على جهاز حقيقي.
 * موديلات أخرى (AX / Mesh / 4G) قد تستعمل واجهة مختلفة؛ عندها يفشل تسجيل الدخول
 * ويبقى الوضع العام (صفحة الراوتر داخل التطبيق) هو البديل.
 */
final class TendaDriver {
    private final String base;
    private String cookie;
    /** وصف آخر محاولة دخول (رمز الرد والتحويل وأسماء الكوكيز) لتقرير التشخيص. */
    String lastLogin = "";

    TendaDriver(String baseUrl) {
        base = baseUrl;
    }

    boolean login(String username, String password) throws Exception {
        cookie = null;
        String body = "username=" + enc(username.isEmpty() ? "admin" : username)
                + "&password=" + md5(password);
        Response r = http("POST", "/login/Auth", body);
        lastLogin = "HTTP " + r.code + " | Location: " + r.location + " | Cookies: " + cookieNames(r.cookie)
                + " | Body: " + clip(r.text, 200);
        if (r.cookie == null) return false;
        if (r.location != null && r.location.contains("login")) return false;
        cookie = r.cookie;
        return true;
    }

    /** العنصر الأول إعدادات عامة، والبقية أجهزة فيها: hostName, ip, mac, limitUp, limitDown, upSpeed, downSpeed. */
    JSONArray netControl() throws IOException, JSONException {
        return new JSONArray(get("/goform/GetNetControlList"));
    }

    /** يرسل الجدول كاملاً؛ الحدود بوحدة KB/s. */
    String setNetControl(JSONArray table) throws IOException {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < table.length(); i++) {
            JSONObject o = table.optJSONObject(i);
            if (o == null || !o.has("mac")) continue;
            list.append(o.optString("hostName")).append('\r')
                    .append(o.optString("mac")).append('\r')
                    .append(o.optString("limitUp", "0")).append('\r')
                    .append(o.optString("limitDown", "0")).append('\n');
        }
        return post("/goform/SetNetControlList", "list=" + enc(list.toString()));
    }

    /** الحقول: enable, url_enable, urls, time (HH:MM-HH:MM), day (سبع قيم), limit_type. */
    JSONObject parentControl(String mac) throws IOException, JSONException {
        return new JSONObject(get("/goform/GetParentControlInfo?mac=" + enc(mac)));
    }

    String setParentControl(String mac, JSONObject cfg) throws IOException {
        String body = "deviceId=" + enc(mac)
                + "&enable=" + enc(cfg.optString("enable", "0"))
                + "&time=" + enc(cfg.optString("time", "00:00-23:59"))
                + "&url_enable=" + enc(cfg.optString("url_enable", "0"))
                + "&urls=" + enc(cfg.optString("urls", ""))
                + "&day=" + enc(cfg.optString("day", "1,1,1,1,1,1,1"))
                + "&limit_type=" + enc(cfg.optString("limit_type", "0"));
        return post("/goform/saveParentControlInfo", body);
    }

    /**
     * قراءة فقط: حالة الإنترنت وعنوان WAN وإعداد الإدارة عن بعد.
     * أسماء الحقول تختلف بين الإصدارات، لذلك نبحث عنها بالاسم بدل الاعتماد على مسار ثابت.
     */
    JSONObject status() throws IOException, JSONException {
        return new JSONObject(get("/goform/getStatus?modules=internetStatus,wanBasicCfg,remoteWeb,systemInfo"));
    }

    /** أول عنوان IPv4 في حقل اسمه يحتوي wan و ip. */
    static String findWanIp(Object node) {
        return findValue(node, "(?i).*wan.*ip.*", "\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** "1" أو "0" إذا وُجد حقل تفعيل الإدارة عن بعد، وإلا null. */
    static String findRemoteEnabled(Object node) {
        return findValue(node, "(?i).*remote.*(en|enable)$", "[01]|true|false");
    }

    private static String findValue(Object node, String keyRegex, String valueRegex) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONArray names = o.names();
            if (names == null) return null;
            for (int i = 0; i < names.length(); i++) {
                String k = names.optString(i);
                Object v = o.opt(k);
                if (!(v instanceof JSONObject) && !(v instanceof JSONArray)
                        && k.matches(keyRegex) && String.valueOf(v).trim().matches(valueRegex)) {
                    return String.valueOf(v).trim();
                }
            }
            for (int i = 0; i < names.length(); i++) {
                String found = findValue(o.opt(names.optString(i)), keyRegex, valueRegex);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String found = findValue(a.opt(i), keyRegex, valueRegex);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** قراءة خام لتقرير التشخيص: لا تغيّر أي إعداد. */
    String probe(String path) {
        try {
            Response r = http("GET", path, null);
            return "HTTP " + r.code + (r.location != null ? " → " + r.location : "") + "\n" + clip(r.text, 700);
        } catch (Exception e) {
            return "خطأ: " + e.getMessage();
        }
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static String cookieNames(String jar) {
        if (jar == null) return "none";
        StringBuilder sb = new StringBuilder();
        for (String part : jar.split(";")) {
            int eq = part.indexOf('=');
            if (sb.length() > 0) sb.append(',');
            sb.append(eq > 0 ? part.substring(0, eq).trim() : part.trim());
        }
        return sb.toString();
    }

    void reboot() throws IOException {
        http("POST", "/goform/SysToolReboot", "action=0");
    }

    // ---------------------------------------------------------------- HTTP

    private static final class Response {
        int code;
        String text = "";
        String location;
        String cookie;
    }

    private String get(String path) throws IOException {
        return authed(http("GET", path, null));
    }

    private String post(String path, String body) throws IOException {
        return authed(http("POST", path, body));
    }

    private String authed(Response r) throws IOException {
        if (r.location != null && r.location.contains("login")) {
            throw new IOException("انتهت جلسة الراوتر، أعد التحديث");
        }
        return r.text;
    }

    private Response http(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(9000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            Response r = new Response();
            int code = c.getResponseCode();
            r.code = code;
            r.location = c.getHeaderField("Location");
            for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                if (h.getKey() == null || !h.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                StringBuilder jar = new StringBuilder();
                for (String v : h.getValue()) {
                    if (jar.length() > 0) jar.append("; ");
                    int semi = v.indexOf(';');
                    jar.append(semi > 0 ? v.substring(0, semi) : v);
                }
                if (jar.length() > 0) r.cookie = jar.toString();
            }
            try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
                if (in != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while (out.size() < 512 * 1024 && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                    r.text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            if (code >= 400) throw new IOException("HTTP " + code);
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
