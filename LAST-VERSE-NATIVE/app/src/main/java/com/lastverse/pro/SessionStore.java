package com.lastverse.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keeps the API session cookie encrypted with a key protected by Android Keystore. */
public final class SessionStore {
    private static final String PREFS = "session";
    private static final String ALIAS = "last_verse_session_key";
    private static final String COOKIE = "encrypted_cookie";
    private final SharedPreferences prefs;

    public SessionStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void saveCookie(String cookie) throws Exception {
        if (cookie == null || cookie.trim().isEmpty()) {
            clearCookie();
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(cookie.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer buffer = ByteBuffer.allocate(4 + iv.length + encrypted.length);
        buffer.putInt(iv.length).put(iv).put(encrypted);
        prefs.edit().putString(COOKIE, Base64.encodeToString(buffer.array(), Base64.NO_WRAP)).apply();
    }

    public synchronized String readCookie() {
        String encoded = prefs.getString(COOKIE, null);
        if (encoded == null) return "";
        try {
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            int ivLength = buffer.getInt();
            if (ivLength < 12 || ivLength > 16 || buffer.remaining() <= ivLength) throw new IllegalStateException("Invalid session data");
            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) {
            clearCookie();
            return "";
        }
    }

    public void clearCookie() { prefs.edit().remove(COOKIE).apply(); }
    public String getBaseUrl() { return prefs.getString("api_base_url", ""); }
    public void setBaseUrl(String value) { prefs.edit().putString("api_base_url", value == null ? "" : value.trim()).apply(); }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) return (SecretKey) keyStore.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
