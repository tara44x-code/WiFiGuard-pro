package com.sl.wifiguard;

import org.json.JSONException;
import org.json.JSONObject;

/** راوتر محفوظ. كلمة المرور لا تُكتب على القرص إلا مشفّرة عبر {@link Vault}. */
final class RouterProfile {
    String id = "";
    String name = "";
    String localUrl = "";
    String remoteUrl = "";
    String username = "admin";
    String password = "";
    String vendor = "auto";

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("localUrl", localUrl);
        o.put("remoteUrl", remoteUrl);
        o.put("username", username);
        o.put("password", password);
        o.put("vendor", vendor);
        return o;
    }

    static RouterProfile fromJson(JSONObject o) {
        RouterProfile p = new RouterProfile();
        p.id = o.optString("id", "");
        p.name = o.optString("name", "");
        p.localUrl = o.optString("localUrl", "");
        p.remoteUrl = o.optString("remoteUrl", "");
        p.username = o.optString("username", "admin");
        p.password = o.optString("password", "");
        p.vendor = o.optString("vendor", "auto");
        return p;
    }

    String url(boolean remote) {
        return remote ? remoteUrl : localUrl;
    }

    /** يضيف http:// إذا لم يُكتب البروتوكول ويحذف الشرطة الأخيرة. */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
