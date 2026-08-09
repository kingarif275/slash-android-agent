package com.slash.agent;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalModelManager {
    public static final String MODEL_ID = "qwen3-0.6b-q4_k_m";
    private static final String MODEL_FILE = "Qwen3-0.6B-Q4_K_M.gguf";
    private static final String MODEL_URL = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf?download=true";
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public LocalModelManager(Context context) { this.context = context.getApplicationContext(); }

    public File modelFile() { return new File(new File(context.getFilesDir(), "models"), MODEL_FILE); }
    public boolean exists() { return modelFile().isFile() && modelFile().length() > 100_000_000L && isGguf(modelFile()); }

    public String capabilitySummary() {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        StatFs storage = new StatFs(context.getFilesDir().getAbsolutePath());
        long free = storage.getAvailableBytes();
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
        String tier = memory.totalMem >= 8L * 1024 * 1024 * 1024 && free >= 1_500_000_000L ? "RECOMMENDED" :
                memory.totalMem >= 4L * 1024 * 1024 * 1024 && free >= 800_000_000L ? "BASIC_LOCAL_AI" : "UNSUPPORTED";
        return tier + "; android=" + Build.VERSION.SDK_INT + "; abi=" + abi + "; ram=" + memory.totalMem + "; free_storage=" + free;
    }

    public void downloadRecommended(ProgressCallback callback) {
        worker.execute(() -> {
            try {
                File dir = modelFile().getParentFile();
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create model directory");
                File temporary = new File(dir, MODEL_FILE + ".download");
                HttpURLConnection connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
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
                if (!isGguf(temporary)) throw new IllegalStateException("Downloaded file is not a valid GGUF model");
                if (modelFile().exists() && !modelFile().delete()) throw new IllegalStateException("Cannot replace old model");
                if (!temporary.renameTo(modelFile())) throw new IllegalStateException("Cannot finalize model file");
                if (callback != null) callback.onComplete(null);
            } catch (Throwable error) { if (callback != null) callback.onComplete(error); }
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
