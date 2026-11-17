package com.sl.wifiguard;

import java.util.Locale;

/** جهاز على شبكة الراوتر، مدموج من قائمة الأجهزة المتصلة وجدول التحكم بالسرعة. */
final class NetDevice {
    String mac = "";
    String ip = "";
    /** الاسم كما يعرفه الراوتر. */
    String routerName = "";
    /** الاسم اللي اختاره المستخدم (محفوظ على الهاتف). */
    String alias = "";
    boolean online;
    boolean blacklisted;
    /** السرعة الحالية والحدود بوحدة KB/s كما يرجعها الراوتر. */
    long down, up, limitDown, limitUp;
    long firstSeen, lastSeen;
    boolean isNew;

    String displayName() {
        if (!alias.isEmpty()) return alias;
        if (!routerName.isEmpty() && !routerName.equalsIgnoreCase("unknown")) return routerName;
        return "جهاز " + (mac.length() >= 5 ? mac.substring(mac.length() - 5).toUpperCase(Locale.ROOT) : mac);
    }

    /** أجهزة أندرويد وآيفون الحديثة تستعمل عنواناً عشوائياً لكل شبكة (بت «محلي الإدارة» مفعّل). */
    boolean randomizedMac() {
        if (mac.length() < 2) return false;
        try {
            return (Integer.parseInt(mac.substring(0, 2), 16) & 0x02) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    String icon() {
        return kind()[0];
    }

    String kindLabel() {
        return kind()[1];
    }

    /** تخمين نوع الجهاز من اسمه؛ الراوتر لا يرجع النوع بشكل موثوق. */
    private String[] kind() {
        String n = (routerName + " " + alias).toLowerCase(Locale.ROOT);
        if (has(n, "playstation", "ps4", "ps5", "xbox", "nintendo", "switch")) return new String[]{"🎮", "جهاز ألعاب"};
        if (has(n, "tv", "bravia", "chromecast", "roku", "firestick", "fire-tv", "shield", "webos", "tizen", "تلفزيون", "شاشة")) return new String[]{"📺", "تلفزيون"};
        if (has(n, "printer", "epson", "canon", "brother", "laserjet", "hp-", "طابعة")) return new String[]{"🖨", "طابعة"};
        if (has(n, "cam", "ipc", "dvr", "nvr", "hikvision", "dahua", "ezviz", "imou", "كاميرا")) return new String[]{"📷", "كاميرا"};
        if (has(n, "desktop", "laptop", "-pc", "pc-", "macbook", "imac", "thinkpad", "windows", "لابتوب", "حاسبة")) return new String[]{"💻", "حاسبة"};
        if (has(n, "ipad", "tab", "تابلت")) return new String[]{"📲", "تابلت"};
        if (has(n, "iphone", "android", "galaxy", "redmi", "xiaomi", "poco", "huawei", "honor", "oppo", "vivo",
                "realme", "infinix", "tecno", "samsung", "pixel", "oneplus", "nokia", "موبايل", "هاتف", "تلفون")) {
            return new String[]{"📱", "هاتف"};
        }
        if (has(n, "esp", "tuya", "smart", "plug", "bulb", "sonoff", "alexa", "echo")) return new String[]{"💡", "جهاز ذكي"};
        if (randomizedMac()) return new String[]{"📱", "هاتف غالباً"};
        return new String[]{"🔌", "جهاز"};
    }

    private static boolean has(String text, String... keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }

    static String normalizeMac(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', ':');
    }

    static String speed(long kbps) {
        if (kbps <= 0) return "0";
        if (kbps >= 1024) return String.format(Locale.ROOT, "%.1f MB/s", kbps / 1024.0);
        return kbps + " KB/s";
    }
}
