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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the abliterated-only Qwen3 lineup and safe private-storage downloads. */
public final class LocalModelManager {
    private static final String PREFERENCES = "slash_models";
    private static final String SELECTED_PROFILE = "selected_profile";
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long DOWNLOAD_HEADROOM = 256L * 1024L * 1024L;

    public static final ModelProfile LITE = new ModelProfile(
            "qwen3-0.6b-abliterated-q4-k-m",
            "Lite — Qwen3 0.6B Abliterated Q4_K_M",
            "jaahas/Qwen3-0.6B-abliterated-Q4_K_M-GGUF",
            "21b05cf423f545464cac4fadc091caf8677119f1",
            "qwen3-0.6b-abliterated-q4_k_m.gguf", "qwen3", "Q4_K_M", 32768,
            "qwen3", ModelProfile.ToolSupport.QWEN_NATIVE_EXPECTED,
            ModelProfile.Tier.LITE, 396_704_416L,
            "ef60a6b7493e8e61f7e645abfc2d705d4d3acad4c0448bbcc96ae6d7204f2a43",
            ModelProfile.ModelVariant.ABLITERATED, 4L * GIB);

    public static final ModelProfile BALANCED = new ModelProfile(
            "qwen3-1.7b-abliterated-q4-k-m",
            "Balanced — Qwen3 1.7B Abliterated Q4_K_M",
            "swittk/Qwen3-1.7B-abliterated-Q4_K_M-GGUF",
            "8dd848bc93f5b6a14fb7d82ec8aebde8710498df",
            "qwen3-1.7b-abliterated-q4_k_m.gguf", "qwen3", "Q4_K_M", 32768,
            "qwen3", ModelProfile.ToolSupport.QWEN_NATIVE_EXPECTED,
            ModelProfile.Tier.BALANCED, 1_107_408_544L,
            "63018a10260944b410822e45f2595bf402f7132b29a8514d112ebac3064f89b0",
            ModelProfile.ModelVariant.ABLITERATED, 6L * GIB);

    public static final ModelProfile HIGH = new ModelProfile(
            "qwen3-4b-abliterated-q4-k-m",
            "High — Qwen3 4B Abliterated Q4_K_M",
            "Mungert/Qwen3-4B-abliterated-GGUF",
            "56175aed285a884480f49bb18d2a1b0e05a7749f",
            "Qwen3-4B-abliterated-q4_k_m.gguf", "qwen3", "Q4_K_M", 32768,
            "qwen3", ModelProfile.ToolSupport.QWEN_NATIVE_EXPECTED,
            ModelProfile.Tier.HIGH, 2_497_280_736L,
            "2638dc26f9b18e5cd1cda97a2e649af7b2543e755ed3f14ab3825bd57ad57082",
            ModelProfile.ModelVariant.ABLITERATED, 8L * GIB);

    private static final List<ModelProfile> PROFILES = Collections.unmodifiableList(
            Arrays.asList(LITE, BALANCED, HIGH));
    private static final List<String> OBSOLETE_STANDARD_FILES = Arrays.asList(
            "Qwen_Qwen3-0.6B-Q4_K_M.gguf", "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            "Qwen_Qwen3-4B-Q4_K_M.gguf");

    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private volatile HttpURLConnection activeConnection;
    private volatile boolean downloading;
    private volatile ModelProbe modelProbe;

    public LocalModelManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public List<ModelProfile> profiles() { return PROFILES; }

    public ModelProfile profile(String id) {
        for (ModelProfile profile : PROFILES) if (profile.id.equals(id)) return profile;
        throw new IllegalArgumentException("Unknown abliterated model profile");
    }

    public ModelProfile selectedProfile() {
        String selected = preferences.getString(SELECTED_PROFILE, BALANCED.id);
        for (ModelProfile profile : PROFILES) if (profile.id.equals(selected)) return profile;
        return BALANCED;
    }

    public void selectProfile(String id) {
        ModelProfile profile = profile(id);
        if (!exists(profile)) throw new IllegalStateException("Model must be verified before selection");
        preferences.edit().putString(SELECTED_PROFILE, profile.id).apply();
    }

    public File modelsDirectory() { return new File(context.getFilesDir(), "models"); }
    public File modelFile() { return modelFile(selectedProfile()); }
    public File modelFile(ModelProfile profile) { return new File(modelsDirectory(), profile.fileName); }
    public File partialFile(ModelProfile profile) { return new File(modelsDirectory(), profile.fileName + ".part"); }

    public boolean exists() { return exists(selectedProfile()); }

    public boolean exists(ModelProfile profile) {
        File file = modelFile(profile);
        return file.isFile() && file.length() == profile.expectedSizeBytes && isGguf(file);
    }

    public long partialBytes(ModelProfile profile) {
        File part = partialFile(profile);
        return part.isFile() ? part.length() : 0L;
    }

    public boolean isDownloading() { return downloading; }

    public void setModelProbe(ModelProbe probe) { modelProbe = probe; }

    public String capabilitySummary() { return capabilitySummary(selectedProfile()); }

    public String capabilitySummary(ModelProfile profile) {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        long free = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
        long neededStorage = Math.max(800_000_000L, profile.expectedSizeBytes + 500_000_000L);
        String tier = memory.totalMem >= profile.recommendedRamBytes && free >= neededStorage ? "RECOMMENDED" :
                memory.totalMem >= 4L * GIB && free >= profile.expectedSizeBytes + 200_000_000L
                        ? "BASIC_LOCAL_AI" : "UNSUPPORTED";
        return tier + "; model=" + profile.id + "; variant=" + profile.modelVariant
                + "; tools=" + profile.toolCallingSupport + "; android=" + Build.VERSION.SDK_INT
                + "; abi=" + abi + "; ram=" + memory.totalMem + "; free_storage=" + free;
    }

    public void downloadSelected(ProgressCallback callback) {
        downloadProfile(selectedProfile().id, callback);
    }

    public void downloadProfile(String profileId, ProgressCallback callback) {
        ModelProfile profile = profile(profileId);
        if (downloading) {
            if (callback != null) callback.onComplete(new IllegalStateException("A model download is already active"));
            return;
        }
        downloading = true;
        cancelRequested.set(false);
        worker.execute(() -> {
            Throwable failure = null;
            try {
                downloadAndPromote(profile, callback);
            } catch (Throwable error) {
                failure = error;
                if (error instanceof VerificationException) partialFile(profile).delete();
            } finally {
                activeConnection = null;
                downloading = false;
                if (callback != null) callback.onComplete(failure);
            }
        });
    }

    public void cancelDownload() {
        cancelRequested.set(true);
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    private void downloadAndPromote(ModelProfile profile, ProgressCallback callback) throws Exception {
        File directory = modelsDirectory();
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create model directory");
        File part = partialFile(profile);
        if (part.length() > profile.expectedSizeBytes) {
            if (!part.delete()) throw new IllegalStateException("Cannot clear oversized partial download");
        }
        long existing = part.isFile() ? part.length() : 0L;
        long needed = profile.expectedSizeBytes - existing + DOWNLOAD_HEADROOM;
        long free = new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        if (free < needed) throw new IllegalStateException("Not enough free storage; need " + needed + " bytes");

        if (existing < profile.expectedSizeBytes) {
            HttpURLConnection connection = (HttpURLConnection) new URL(profile.downloadUrl).openConnection();
            activeConnection = connection;
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Slash-Android/0.3");
            if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
            int status = connection.getResponseCode();
            boolean resumed = existing > 0 && status == HttpURLConnection.HTTP_PARTIAL;
            if (status < 200 || status >= 300) throw new IllegalStateException("Model download returned HTTP " + status);
            if (!resumed) existing = 0;
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(part, resumed)) {
                byte[] buffer = new byte[1024 * 1024];
                long received = existing;
                long lastReported = received;
                if (callback != null) callback.onProgress(received, profile.expectedSizeBytes);
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelRequested.get()) throw new DownloadCancelledException();
                    output.write(buffer, 0, count);
                    received += count;
                    if (received > profile.expectedSizeBytes) {
                        throw new VerificationException("Download exceeded expected size");
                    }
                    if (callback != null && (received - lastReported >= 1024 * 1024
                            || received == profile.expectedSizeBytes)) {
                        callback.onProgress(received, profile.expectedSizeBytes);
                        lastReported = received;
                    }
                }
                output.getFD().sync();
            } finally {
                connection.disconnect();
                activeConnection = null;
            }
        }
        if (cancelRequested.get()) throw new DownloadCancelledException();
        verifyCandidate(part, profile);
        atomicPromote(part, modelFile(profile));
    }

    public void verifyCandidate(File file, ModelProfile profile) throws Exception {
        if (!file.isFile() || file.length() != profile.expectedSizeBytes) {
            throw new VerificationException("Expected " + profile.expectedSizeBytes
                    + " bytes but found " + (file.isFile() ? file.length() : 0));
        }
        GgufMetadataReader.Metadata metadata;
        try { metadata = GgufMetadataReader.read(file); }
        catch (Exception error) { throw new VerificationException("GGUF metadata is invalid", error); }
        if (!profile.architecture.equalsIgnoreCase(metadata.architecture)) {
            throw new VerificationException("Expected architecture " + profile.architecture
                    + " but found " + metadata.architecture);
        }
        if ("Q4_K_M".equals(profile.quantization)
                && metadata.fileType != GgufMetadataReader.Q4_K_M_FILE_TYPE) {
            throw new VerificationException("Expected Q4_K_M GGUF file type but found " + metadata.fileType);
        }
        String actualHash = sha256(file);
        if (!profile.sha256.equalsIgnoreCase(actualHash)) {
            throw new VerificationException("SHA-256 mismatch");
        }
        ModelProbe probe = modelProbe;
        if (probe == null || !probe.opensWithLlama(file.getAbsolutePath())) {
            throw new VerificationException("llama.cpp could not open the candidate model");
        }
    }

    private void atomicPromote(File part, File target) throws Exception {
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        if (backup.exists() && !backup.delete()) throw new IllegalStateException("Cannot clear stale model backup");
        boolean backedUp = false;
        if (target.exists()) {
            move(target, backup);
            backedUp = true;
        }
        try {
            move(part, target);
            if (backedUp && !backup.delete()) backup.deleteOnExit();
        } catch (Exception error) {
            if (backedUp && backup.exists() && !target.exists()) move(backup, target);
            throw error;
        }
    }

    private void move(File source, File target) throws Exception {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public int deleteObsoleteStandardModels() {
        ModelProfile selected = selectedProfile();
        if (selected.modelVariant != ModelProfile.ModelVariant.ABLITERATED || !exists(selected)) {
            throw new IllegalStateException("A verified abliterated model must be active before cleanup");
        }
        int deleted = 0;
        for (String name : OBSOLETE_STANDARD_FILES) {
            File file = new File(modelsDirectory(), name);
            if (file.isFile() && file.delete()) deleted++;
        }
        return deleted;
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) value.append(String.format(Locale.US, "%02x", item & 0xff));
        return value.toString();
    }

    private boolean isGguf(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            return input.read(magic) == 4 && magic[0] == 'G' && magic[1] == 'G'
                    && magic[2] == 'U' && magic[3] == 'F';
        } catch (Exception ignored) { return false; }
    }

    public void shutdown() { cancelDownload(); worker.shutdownNow(); }

    public interface ModelProbe { boolean opensWithLlama(String path); }
    public interface ProgressCallback {
        void onProgress(long received, long total);
        void onComplete(Throwable error);
    }

    public static final class DownloadCancelledException extends Exception {
        DownloadCancelledException() { super("Download cancelled; partial data was kept for resume"); }
    }

    private static final class VerificationException extends Exception {
        VerificationException(String message) { super(message); }
        VerificationException(String message, Throwable cause) { super(message, cause); }
    }
}
