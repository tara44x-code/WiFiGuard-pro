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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    // ---------------------------------------------------------------- الأجهزة

    /** كلمات تُحظر بها المواقع الإباحية عبر فلتر المواقع بالراوتر (يطابق أي عنوان يحتوي الكلمة). */
    static final String ADULT_KEYWORDS = "porn,xxx,xnxx,xvideos,xhamster,pornhub,redtube,youporn,spankbang,"
            + "onlyfans,brazzers,chaturbate,stripchat,bongacams,livejasmin,sex";

    /**
     * قيمة «قائمة سوداء» في limit_type. الافتراضي بواجهة Tenda قائمة سوداء والمثال المنشور يرجع 1،
     * لكنها غير مؤكدة لكل إصدار؛ لذلك نطلب من المستخدم يتأكد مرة من لوحة الراوتر.
     */
    static final String LIMIT_BLACKLIST = "1";

    /** يدمج قائمة المتصلين (getOnlineList) مع جدول السرعة (GetNetControlList) حسب عنوان MAC. */
    List<NetDevice> devices() throws IOException, JSONException {
        Map<String, NetDevice> map = new LinkedHashMap<>();
        boolean onlineWorked = false;
        try {
            JSONArray online = firstArray(get("/goform/getOnlineList"));
            for (int i = 0; i < online.length(); i++) {
                JSONObject o = online.optJSONObject(i);
                if (o == null) continue;
                String mac = NetDevice.normalizeMac(o.optString("deviceId", o.optString("mac", "")));
                if (!mac.contains(":")) continue;
                NetDevice d = device(map, mac);
                d.ip = o.optString("ip", d.ip);
                d.routerName = o.optString("devName", o.optString("hostName", d.routerName));
                d.online = true;
                d.blacklisted = "1".equals(o.optString("black", "0"));
                d.up = num(o.optString("uploadSpeed", "0"));
                d.down = num(o.optString("downloadSpeed", "0"));
                onlineWorked = true;
            }
        } catch (Exception ignored) {
            // بعض الإصدارات ما بيها هذا المسار؛ نكمل بجدول السرعة.
        }
        JSONArray net;
        try {
            net = netControl();
        } catch (IOException | JSONException e) {
            if (onlineWorked) return new ArrayList<>(map.values());
            throw e;
        }
        for (int i = 0; i < net.length(); i++) {
            JSONObject o = net.optJSONObject(i);
            if (o == null || !o.has("mac")) continue;
            String mac = NetDevice.normalizeMac(o.optString("mac"));
            boolean known = map.containsKey(mac);
            NetDevice d = device(map, mac);
            if (d.ip.isEmpty()) d.ip = o.optString("ip", "");
            if (d.routerName.isEmpty()) d.routerName = o.optString("hostName", "");
            d.limitUp = num(o.optString("limitUp", "0"));
            d.limitDown = num(o.optString("limitDown", "0"));
            if (!onlineWorked || !known) {
                d.online = !onlineWorked && !"1".equals(o.optString("offline", "0"));
                if (d.down == 0) d.down = num(o.optString("downSpeed", "0"));
                if (d.up == 0) d.up = num(o.optString("upSpeed", "0"));
            }
        }
        return new ArrayList<>(map.values());
    }

    private static NetDevice device(Map<String, NetDevice> map, String mac) {
        NetDevice d = map.get(mac);
        if (d == null) {
            d = new NetDevice();
            d.mac = mac;
            map.put(mac, d);
        }
        return d;
    }

    private static long num(String s) {
        try {
            return Math.round(Double.parseDouble(s.trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    private static JSONArray firstArray(String text) throws JSONException {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("[")) return new JSONArray(t);
        if (t.startsWith("{")) {
            JSONObject o = new JSONObject(t);
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    Object v = o.opt(names.optString(i));
                    if (v instanceof JSONArray) return (JSONArray) v;
                }
            }
        }
        return new JSONArray();
    }

    /** يغيّر اسم الجهاز بالراوتر نفسه، ثم يقرأ القائمة ويتأكد أن الاسم انحفظ. */
    boolean rename(String mac, String name) throws IOException, JSONException {
        post("/goform/SetOnlineDevName", "mac=" + enc(mac) + "&devName=" + enc(name));
        for (NetDevice d : devices()) {
            if (d.mac.equals(NetDevice.normalizeMac(mac))) return name.equals(d.routerName);
        }
        return false;
    }

    /**
     * يحوّل سياسة الجهاز لقاعدة رقابة أبوية وحدة بالراوتر، ثم يقرأها ويتأكد أنها انحفظت.
     * <ul>
     * <li>قطع النت: أيام السماح كلها 0.</li>
     * <li>وقت السماح: حقل time (النت مسموح فقط بهذا الوقت).</li>
     * <li>المواقع الإباحية: فلتر المواقع بقائمة سوداء من الكلمات.</li>
     * </ul>
     */
    boolean applyPolicy(String mac, DeviceStore.Policy p) throws IOException, JSONException {
        JSONObject cfg = parentControl(mac);
        String day = p.blocked ? "0,0,0,0,0,0,0" : "1,1,1,1,1,1,1";
        if (p.any()) {
            cfg.put("enable", "1");
            cfg.put("day", day);
            cfg.put("time", p.allowed.isEmpty() ? "00:00-23:59" : p.allowed);
            cfg.put("url_enable", p.adultFilter ? "1" : "0");
            cfg.put("urls", p.adultFilter ? ADULT_KEYWORDS : "");
            cfg.put("limit_type", LIMIT_BLACKLIST);
        } else {
            cfg.put("enable", "0");
            cfg.put("url_enable", "0");
        }
        setParentControl(mac, cfg);
        JSONObject back = parentControl(mac);
        if (!p.any()) return "0".equals(back.optString("enable"));
        return "1".equals(back.optString("enable"))
                && day.equals(back.optString("day").replace(" ", ""))
                && (p.adultFilter ? "1" : "0").equals(back.optString("url_enable"));
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
