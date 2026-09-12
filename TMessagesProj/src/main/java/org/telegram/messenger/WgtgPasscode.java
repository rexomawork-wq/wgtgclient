package org.telegram.messenger;

import android.content.SharedPreferences;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** App-wide privacy state. Credential acceptance is used only by the lock screen. */
public final class WgtgPasscode {
    private static final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("wgtg_passcode", 0);
    private static volatile boolean restricted = prefs.getBoolean("restricted", false);
    private static volatile boolean lockActive;
    private static long lockSession;
    private static final ArrayList<Runnable> listeners = new ArrayList<>();

    public static boolean isRestricted() { return restricted; }
    public static boolean canAccessArchive() {
        return !restricted && !lockActive && !SharedConfig.appLocked && !SharedConfig.isWaitingForPasscodeEnter;
    }

    public static boolean hasAlternate() {
        return !SharedConfig.passcodeHash.isEmpty()
                && SharedConfig.passcodeHash.equals(prefs.getString("normalHash", ""))
                && SharedConfig.passcodeType == prefs.getInt("type", -1)
                && !prefs.getString("hash", "").isEmpty();
    }

    public static void addListener(Runnable listener) { listeners.add(listener); }
    public static void refreshAccess() {
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    // Multiple PasscodeViews share one lock session; only its first successful callback wins.
    public static long beginLock() {
        if (!lockActive) { lockActive = true; lockSession++; refreshAccess(); }
        return lockSession;
    }

    public static boolean isCurrentSession(long session) { return lockActive && session == lockSession; }

    public static boolean acceptBiometric(long session) {
        if (!isCurrentSession(session)) return false;
        lockActive = false;
        return true;
    }

    public static boolean unlock(String password, long session) {
        if (!isCurrentSession(session) || SharedConfig.passcodeRetryInMs > 0 || password.isEmpty()) return false;
        boolean normal = SharedConfig.checkPasscode(password);
        if (!normal && !matchesAlternate(password)) return false;
        if (!setRestricted(!normal)) return false;
        lockActive = false;
        return true;
    }

    // Call only after the normal credential has been verified, never after biometric acceptance.
    public static boolean normalCodeAccepted() { return setRestricted(false); }

    private static boolean setRestricted(boolean value) {
        if (value) restricted = true; // Fail closed even if the persistent write fails.
        if (!prefs.edit().putBoolean("restricted", value).commit()) return false;
        restricted = value;
        refreshAccess();
        return true;
    }

    public static boolean configure(String normal, String alternate, String confirmation) {
        if (!canAccessArchive() || SharedConfig.passcodeHash.isEmpty() || retryBlocked()) return false;
        if (!SharedConfig.checkPasscode(normal)) { SharedConfig.increaseBadPasscodeTries(); return false; }
        SharedConfig.badPasscodeTries = 0;
        SharedConfig.saveConfig();
        if (!alternate.equals(confirmation) || alternate.length() > 128 || alternate.isEmpty()
                || SharedConfig.passcodeType == SharedConfig.PASSCODE_TYPE_PIN && !alternate.matches("[0-9]{4}")
                || SharedConfig.checkPasscode(alternate)) return false;
        try {
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            return prefs.edit().putString("salt", hex(salt)).putString("hash", hex(derive(alternate, salt)))
                    .putString("normalHash", SharedConfig.passcodeHash).putInt("type", SharedConfig.passcodeType).commit();
        } catch (Exception e) { FileLog.e(e); return false; }
    }

    public static boolean remove(String normal) {
        if (!canAccessArchive() || SharedConfig.passcodeHash.isEmpty() || retryBlocked()) return false;
        if (!SharedConfig.checkPasscode(normal)) { SharedConfig.increaseBadPasscodeTries(); return false; }
        SharedConfig.badPasscodeTries = 0;
        SharedConfig.saveConfig();
        return clearCredential();
    }

    private static boolean retryBlocked() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now > SharedConfig.lastUptimeMillis) SharedConfig.passcodeRetryInMs = Math.max(0, SharedConfig.passcodeRetryInMs - (now - SharedConfig.lastUptimeMillis));
        SharedConfig.lastUptimeMillis = now;
        SharedConfig.saveConfig();
        return SharedConfig.passcodeRetryInMs > 0;
    }

    public static boolean clearCredential() {
        return prefs.edit().remove("salt").remove("hash").remove("normalHash").remove("type").commit();
    }

    private static boolean matchesAlternate(String password) {
        if (!hasAlternate() || password.length() > 128) return false;
        try {
            byte[] salt = unhex(prefs.getString("salt", ""));
            byte[] expected = unhex(prefs.getString("hash", ""));
            return salt.length == 32 && expected.length == 32 && MessageDigest.isEqual(expected, derive(password, salt));
        } catch (Exception e) { FileLog.e(e); return false; }
    }

    private static byte[] derive(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        String digits = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) { chars[i * 2] = digits.charAt((bytes[i] & 255) >>> 4); chars[i * 2 + 1] = digits.charAt(bytes[i] & 15); }
        return new String(chars);
    }

    private static byte[] unhex(String value) {
        if (value.length() % 2 != 0) throw new IllegalArgumentException("Invalid credential");
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
