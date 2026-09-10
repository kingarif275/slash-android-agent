package com.slash.agent;

import android.content.Context;
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

/** Stores the optional Vertex AI Express Mode credential encrypted by Android Keystore. */
public final class CloudAiSettings {
    public static final String CONVERSATION_MODEL = "gemini-live-2.5-flash-native-audio";
    public static final String AGENT_MODEL = "gemini-live-2.5-flash-native-audio";
    public static final String MODEL = CONVERSATION_MODEL;
    private static final String PREFERENCES = "slash_cloud_ai";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_CONVERSATION_MODEL = "conversation_model";
    private static final String KEY_AGENT_MODEL = "agent_model";
    private static final String KEY_CIPHERTEXT = "api_key_ciphertext";
    private static final String KEY_IV = "api_key_iv";
    private static final String KEYSTORE_ALIAS = "slash_vertex_api_key_v1";
    private final SharedPreferences preferences;

    public CloudAiSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
    }

    public boolean cloudEnabled() { return preferences.getBoolean(KEY_ENABLED, false); }
    public String conversationModel() { return preferences.getString(KEY_CONVERSATION_MODEL, CONVERSATION_MODEL); }
    public String agentModel() { return preferences.getString(KEY_AGENT_MODEL, AGENT_MODEL); }
    public void setConversationModel(String model) { preferences.edit().putString(KEY_CONVERSATION_MODEL, model).apply(); }
    public void setAgentModel(String model) { preferences.edit().putString(KEY_AGENT_MODEL, model).apply(); }
    public boolean conversationSupportsLive() { return supportsLive(conversationModel()); }
    public boolean agentSupportsLive() { return supportsLive(agentModel()); }
    public boolean liveAvailable() { return conversationSupportsLive() || agentSupportsLive(); }
    public boolean agentSwitchingAvailable() { return conversationSupportsLive() && agentSupportsLive(); }
    public static boolean supportsLive(String model) {
        if (model == null) return false;
        return model.equals("gemini-live-2.5-flash-native-audio") || model.equals("gemini-live-2.5-flash");
    }
    public void setCloudEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled && hasApiKey()).apply();
    }
    public boolean hasApiKey() { return !apiKey().isEmpty(); }

    public void setApiKey(String value) {
        String key = value == null ? "" : value.trim();
        if (!key.startsWith("AQ.") || key.length() < 32) {
            throw new IllegalArgumentException("Enter a valid Vertex AI Express Mode API key.");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] encrypted = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
            preferences.edit()
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception error) {
            throw new IllegalStateException("Android could not protect the Vertex AI key.", error);
        }
    }

    public String apiKey() {
        String encrypted = preferences.getString(KEY_CIPHERTEXT, "");
        String iv = preferences.getString(KEY_IV, "");
        if (encrypted.isEmpty() || iv.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception error) {
            preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV)
                    .putBoolean(KEY_ENABLED, false).apply();
            return "";
        }
    }

    public void clearApiKey() {
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV)
                .putBoolean(KEY_ENABLED, false).apply();
    }

    private SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEYSTORE_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
