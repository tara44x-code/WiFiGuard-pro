package com.sl.wifiguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * الخزنة: الرمز السري، قفل الجلسة، وتخزين الراوترات مشفّرة.
 * <ul>
 * <li>الرمز السري لا يُحفظ؛ يُحفظ فقط ناتج PBKDF2 مع ملح عشوائي.</li>
 * <li>بيانات الراوترات تُشفّر بـ AES-256-GCM بمفتاح داخل Android Keystore لا يغادر الجهاز.</li>
 * </ul>
 */
final class Vault {
    private static final String PREFS = "wifi_guard_vault";
    private static final String KEY_ALIAS = "wifiguard_vault_key";
    private static final int PIN_ITERATIONS = 120000;
    private static final int BACKUP_ITERATIONS = 150000;
    private static final String BACKUP_PREFIX = "WGB1:";

    private static boolean unlocked;
    private static int started;
    private static long backgroundAt;

    private Vault() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- الجلسة

    static boolean isLocked() {
        return !unlocked;
    }

    static void unlockSession() {
        unlocked = true;
        backgroundAt = 0;
    }

    static void lockNow() {
        unlocked = false;
    }

    static void appStarted(Context c) {
        if (started == 0 && backgroundAt > 0
                && SystemClock.elapsedRealtime() - backgroundAt >= lockTimeoutSeconds(c) * 1000L) {
            unlocked = false;
        }
        started++;
    }

    static void appStopped() {
        started = Math.max(0, started - 1);
        if (started == 0) backgroundAt = SystemClock.elapsedRealtime();
    }

    static int lockTimeoutSeconds(Context c) {
        return prefs(c).getInt("lock_timeout", 30);
    }

    static void setLockTimeoutSeconds(Context c, int seconds) {
        prefs(c).edit().putInt("lock_timeout", seconds).apply();
    }

    static boolean biometricEnabled(Context c) {
        return prefs(c).getBoolean("bio", false);
    }

    static void setBiometricEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("bio", on).apply();
    }

    // ---------------------------------------------------------------- الرمز السري

    static boolean hasPin(Context c) {
        return prefs(c).contains("pin_hash");
    }

    static void setPin(Context c, String pin) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(pin, salt, PIN_ITERATIONS);
        prefs(c).edit()
                .putString("pin_salt", b64(salt))
                .putString("pin_hash", b64(hash))
                .putInt("pin_fail", 0)
                .putLong("pin_until", 0)
                .apply();
    }

    /** الثواني المتبقية قبل السماح بمحاولة جديدة (0 = مسموح). */
    static long lockoutSeconds(Context c) {
        long left = prefs(c).getLong("pin_until", 0) - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    static boolean checkPin(Context c, String pin) {
        SharedPreferences p = prefs(c);
        if (lockoutSeconds(c) > 0) return false;
        boolean ok = false;
        try {
            byte[] salt = unb64(p.getString("pin_salt", ""));
            byte[] expected = unb64(p.getString("pin_hash", ""));
            ok = MessageDigest.isEqual(expected, pbkdf2(pin, salt, PIN_ITERATIONS));
        } catch (Exception ignored) {
        }
        if (ok) {
            p.edit().putInt("pin_fail", 0).putLong("pin_until", 0).apply();
            return true;
        }
        int fails = p.getInt("pin_fail", 0) + 1;
        long until = 0;
        if (fails >= 5) {
            // 30 ثانية بعد خامس خطأ، وتتضاعف حتى 15 دقيقة.
            long wait = Math.min(15 * 60, 30L << Math.min(fails - 5, 5));
            until = System.currentTimeMillis() + wait * 1000;
        }
        p.edit().putInt("pin_fail", fails).putLong("pin_until", until).apply();
        return false;
    }

    private static byte[] pbkdf2(String secret, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    // ---------------------------------------------------------------- التشفير

    private static SecretKey keystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Key existing = ks.getKey(KEY_ALIAS, null);
        if (existing != null) return (SecretKey) existing;
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keystoreKey());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return b64(concat(iv, ct));
    }

    private static String decrypt(String stored) throws Exception {
        byte[] all = unb64(stored);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, all, 0, 12));
        return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- الراوترات

    static List<RouterProfile> routers(Context c) {
        List<RouterProfile> out = new ArrayList<>();
        String stored = prefs(c).getString("routers", "");
        if (stored.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(decrypt(stored));
            for (int i = 0; i < a.length(); i++) out.add(RouterProfile.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
            // مفتاح Keystore تغيّر (مثلاً بعد استرجاع الجهاز): البيانات القديمة غير قابلة للقراءة.
        }
        return out;
    }

    static void saveRouters(Context c, List<RouterProfile> list) throws Exception {
        JSONArray a = new JSONArray();
        for (RouterProfile p : list) a.put(p.toJson());
        prefs(c).edit().putString("routers", encrypt(a.toString())).apply();
    }

    static RouterProfile find(Context c, String id) {
        for (RouterProfile p : routers(c)) if (p.id.equals(id)) return p;
        return null;
    }

    /** يضيف الراوتر أو يحدّثه إذا كان موجوداً. */
    static void put(Context c, RouterProfile profile) throws Exception {
        List<RouterProfile> list = routers(c);
        if (profile.id.isEmpty()) profile.id = UUID.randomUUID().toString();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(profile.id)) {
                list.set(i, profile);
                replaced = true;
            }
        }
        if (!replaced) list.add(profile);
        saveRouters(c, list);
    }

    static void delete(Context c, String id) throws Exception {
        List<RouterProfile> list = routers(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        saveRouters(c, list);
    }

    // ---------------------------------------------------------------- النسخ الاحتياطي

    /** نص مشفّر بكلمة مرور يختارها المستخدم؛ يمكن نقله لهاتف آخر. */
    static String exportBackup(Context c, String password) throws Exception {
        JSONArray a = new JSONArray();
        for (RouterProfile p : routers(c)) a.put(p.toJson());
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        SecretKeySpec key = new SecretKeySpec(pbkdf2(password, salt, BACKUP_ITERATIONS), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ct = cipher.doFinal(a.toString().getBytes(StandardCharsets.UTF_8));
        return BACKUP_PREFIX + b64(concat(concat(salt, cipher.getIV()), ct));
    }

    /** يرجع عدد الراوترات المضافة. يرمي استثناء إذا كانت كلمة المرور أو النص خطأ. */
    static int importBackup(Context c, String text, String password) throws Exception {
        String t = text.trim();
        if (!t.startsWith(BACKUP_PREFIX)) throw new IllegalArgumentException("صيغة غير معروفة");
        byte[] all = unb64(t.substring(BACKUP_PREFIX.length()));
        byte[] salt = new byte[16];
        System.arraycopy(all, 0, salt, 0, 16);
        SecretKeySpec key = new SecretKeySpec(pbkdf2(password, salt, BACKUP_ITERATIONS), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 16, 12));
        String json = new String(cipher.doFinal(all, 28, all.length - 28), StandardCharsets.UTF_8);
        JSONArray a = new JSONArray(json);
        List<RouterProfile> list = routers(c);
        int added = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            RouterProfile p = RouterProfile.fromJson(o);
            boolean exists = false;
            for (RouterProfile e : list) if (e.id.equals(p.id)) exists = true;
            if (!exists) {
                if (p.id.isEmpty()) p.id = UUID.randomUUID().toString();
                list.add(p);
                added++;
            }
        }
        saveRouters(c, list);
        return added;
    }

    // ---------------------------------------------------------------- مساعدات

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static byte[] unb64(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
