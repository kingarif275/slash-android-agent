package com.slash.agent;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalModelManager {
    private static final String PREFERENCES = "slash_models";
    private static final String SELECTED_PROFILE = "selected_profile";
    private static final long GIB = 1024L * 1024L * 1024L;

    public static final ModelProfile LITE = new ModelProfile(
            "qwen3-0.6b-q4_k_m", "Lite · Qwen3 0.6B Q4_K_M", "Qwen_Qwen3-0.6B-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen_Qwen3-0.6B-Q4_K_M.gguf?download=true",
            "qwen3", 32768, "qwen3", ModelProfile.ToolSupport.QWEN_NATIVE_EXPECTED,
            ModelProfile.Tier.LITE, 450_000_000L, 4L * GIB);

    public static final ModelProfile BALANCED = new ModelProfile(
            "qwen3-1.7b-q4_k_m", "Balanced · Qwen3 1.7B Q4_K_M", "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf?download=true",
            "qwen3", 32768, "qwen3", ModelProfile.ToolSupport.QWEN_NATIVE_EXPECTED,
            ModelProfile.Tier.BALANCED, 1_200_000_000L, 6L * GIB);

    public static final ModelProfile HIGH = new ModelProfile(
            "qwen3-4b-q4_k_m", "High · Qwen3 4B Q4_K_M", "Qwen_Qwen3-4B-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/main/Qwen_Qwen3-4B-Q4_K_M.gguf?download=true",
            "qwen3", 32768, "qwen3", ModelProfile.ToolSupport.QWEN_NATIVE_EXPECTED,
            ModelProfile.Tier.HIGH, 2_400_000_000L, 8L * GIB);

    private static final List<ModelProfile> PROFILES = Collections.unmodifiableList(Arrays.asList(LITE, BALANCED, HIGH));
    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public LocalModelManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public List<ModelProfile> profiles() { return PROFILES; }

    public ModelProfile selectedProfile() {
        String selected = preferences.getString(SELECTED_PROFILE, BALANCED.id);
        for (ModelProfile profile : PROFILES) if (profile.id.equals(selected)) return profile;
        return BALANCED;
    }

    public void selectProfile(String id) {
        for (ModelProfile profile : PROFILES) {
            if (profile.id.equals(id)) {
                preferences.edit().putString(SELECTED_PROFILE, id).apply();
                return;
            }
        }
        throw new IllegalArgumentException("Unknown model profile");
    }

    public File modelFile() {
        return new File(new File(context.getFilesDir(), "models"), selectedProfile().fileName);
    }

    public boolean exists() {
        ModelProfile profile = selectedProfile();
        return modelFile().isFile() && modelFile().length() >= profile.minimumBytes && isGguf(modelFile());
    }

    public String capabilitySummary() {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        StatFs storage = new StatFs(context.getFilesDir().getAbsolutePath());
        long free = storage.getAvailableBytes();
        ModelProfile profile = selectedProfile();
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
        long neededStorage = Math.max(800_000_000L, profile.minimumBytes + 500_000_000L);
        String tier = memory.totalMem >= profile.recommendedRamBytes && free >= neededStorage ? "RECOMMENDED" :
                memory.totalMem >= 4L * GIB && free >= profile.minimumBytes + 200_000_000L ? "BASIC_LOCAL_AI" : "UNSUPPORTED";
        return tier + "; model=" + profile.id + "; tools=" + profile.toolCallingSupport
                + "; android=" + Build.VERSION.SDK_INT + "; abi=" + abi
                + "; ram=" + memory.totalMem + "; free_storage=" + free;
    }

    public void downloadSelected(ProgressCallback callback) {
        worker.execute(() -> {
            ModelProfile profile = selectedProfile();
            try {
                File dir = modelFile().getParentFile();
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create model directory");
                File temporary = new File(dir, profile.fileName + ".download");
                HttpURLConnection connection = (HttpURLConnection) new URL(profile.downloadUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "Slash-Android/0.2");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new IllegalStateException("Model download returned HTTP " + status);
                long total = connection.getContentLengthLong();
                long received = 0;
                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        received += count;
                        if (callback != null) callback.onProgress(received, total);
                    }
                } finally { connection.disconnect(); }
                if (temporary.length() < profile.minimumBytes || !isGguf(temporary)) {
                    throw new IllegalStateException("Downloaded file is not a compatible GGUF model");
                }
                if (modelFile().exists() && !modelFile().delete()) throw new IllegalStateException("Cannot replace old model");
                if (!temporary.renameTo(modelFile())) throw new IllegalStateException("Cannot finalize model file");
                if (callback != null) callback.onComplete(null);
            } catch (Throwable error) {
                if (callback != null) callback.onComplete(error);
            }
        });
    }

    private boolean isGguf(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            return input.read(magic) == 4 && new String(magic, StandardCharsets.US_ASCII).equals("GGUF");
        } catch (Exception ignored) { return false; }
    }

    public void shutdown() { worker.shutdownNow(); }
    public interface ProgressCallback { void onProgress(long received, long total); void onComplete(Throwable error); }
}
