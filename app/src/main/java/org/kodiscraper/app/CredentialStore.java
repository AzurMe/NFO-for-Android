package org.kodiscraper.app;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialStore {
    private CredentialStore() {}

    static String read(SharedPreferences preferences, String service) {
        String text = preferences.getString(service + "_ciphertext", "");
        String iv = preferences.getString(service + "_iv", "");
        if (text.isEmpty() || iv.isEmpty()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(service, false),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(text, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    static boolean save(SharedPreferences preferences, String service, String credential) {
        String valueKey = service + "_ciphertext";
        String ivKey = service + "_iv";
        if (credential.isEmpty()) {
            preferences.edit().remove(valueKey).remove(ivKey).apply();
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(service, true));
            byte[] encrypted = cipher.doFinal(credential.getBytes(StandardCharsets.UTF_8));
            preferences.edit()
                    .putString(valueKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(ivKey, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static SecretKey key(String service, boolean create) throws Exception {
        String alias = service + "-credential";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey saved = (SecretKey) store.getKey(alias, null);
        if (saved != null || !create) {
            return saved;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
