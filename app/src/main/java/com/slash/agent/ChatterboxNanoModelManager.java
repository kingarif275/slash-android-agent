package com.slash.agent;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the independently downloadable Chatterbox Nano ONNX package and speaker reference. */
public final class ChatterboxNanoModelManager {
    private static final String BASE = "https://huggingface.co/owensong/chatterbox-nano-ONNX/resolve/main/";
    private static final List<Artifact> ARTIFACTS = Collections.unmodifiableList(Arrays.asList(
            new Artifact("tokenizer.json", "3f04e34bea22f9144d1a19151154095bc9ce0430bf421304f5797e716288a906"),
            new Artifact("onnx/embed_tokens_fp16.onnx", "019d257243774091d78c2ad91c2c0f61e4e442740cb7b3b00b5a89109417b18d"),
            new Artifact("onnx/embed_tokens_fp16.onnx_data", "bcd7b35ae4f206932e2491cb60b42ebb80f6d8facfdb53ba7d7449ad00a3237b"),
            new Artifact("onnx/speech_encoder_q4f16.onnx", "29e249f59eaf95015527588b955e5286c7ee4524e7bb54a2f8d589b838e8aed2"),
            new Artifact("onnx/speech_encoder_q4f16.onnx_data", "55d89bd87fd36be48b2e831c99c2e309d432169119049066f9f22fcfe517798d"),
            new Artifact("onnx/language_model_q4f16.onnx", "8fe9620856d86b8a0235041d7fd2a8a47292da50828044bab5740b246e66e65b"),
            new Artifact("onnx/language_model_q4f16.onnx_data", "8f2fdc616373c9ccaebbf2db3210dc80c860922a672898bca111eb75f6d3abd2"),
            new Artifact("onnx/conditional_decoder_q4.onnx", "745faa9c2e2494a81e47f2a72601ac248639fd417ae53d999c5068bc441d1f97"),
            new Artifact("onnx/conditional_decoder_q4.onnx_data", "b5c5317e0b79a1a19dd3d5e2b2091ea06b15716716ab801a54eaeb906c6971ec")
    ));

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public ChatterboxNanoModelManager(Context context) { this.context = context.getApplicationContext(); }
    public File modelDir() { return new File(context.getFilesDir(), "models/chatterbox-nano-onnx"); }
    public File defaultSpeakerReference() { return new File(modelDir(), "default_voice.wav"); }
    public File feminineSpeakerReference() { return new File(modelDir(), "slash_original_feminine.wav"); }
    public File speakerReference() {
        return feminineSpeakerReference().isFile() && feminineSpeakerReference().length() > 10_000
                ? feminineSpeakerReference() : defaultSpeakerReference();
    }
    public File artifact(String path) { return new File(modelDir(), path); }

    public boolean modelReady() {
        for (Artifact artifact : ARTIFACTS) if (!artifact(artifact.path).isFile()) return false;
        return true;
    }

    public boolean ready() { return modelReady() && speakerReference().isFile(); }

    public void installSpeakerReference(Uri uri) throws Exception {
        File directory = modelDir();
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create voice directory");
        File temporary = new File(directory, "slash_original_feminine.wav.download");
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) throw new IllegalStateException("Cannot read selected audio");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        WavAudio.read24kMono(temporary);
        replace(temporary, feminineSpeakerReference());
    }

    public void download(ProgressCallback callback) {
        worker.execute(() -> {
            long completed = 0;
            try {
                for (Artifact artifact : ARTIFACTS) {
                    File destination = artifact(artifact.path);
                    if (destination.isFile() && artifact.sha256.equals(sha256(destination))) {
                        completed++;
                        if (callback != null) callback.onProgress(completed, ARTIFACTS.size(), artifact.path);
                        continue;
                    }
                    File parent = destination.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Cannot create voice model directory");
                    File temporary = new File(parent, destination.getName() + ".download");
                    downloadFile(BASE + artifact.path, temporary);
                    if (!artifact.sha256.equals(sha256(temporary))) throw new IllegalStateException("Checksum mismatch: " + artifact.path);
                    replace(temporary, destination);
                    completed++;
                    if (callback != null) callback.onProgress(completed, ARTIFACTS.size(), artifact.path);
                }
                if (callback != null) callback.onComplete(null);
            } catch (Throwable error) {
                if (callback != null) callback.onComplete(error);
            }
        });
    }

    private void downloadFile(String url, File destination) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Slash-Android/0.2");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("Voice download returned HTTP " + status);
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        } finally { connection.disconnect(); }
    }

    private void replace(File source, File destination) throws Exception {
        if (destination.exists() && !destination.delete()) throw new IllegalStateException("Cannot replace " + destination.getName());
        if (!source.renameTo(destination)) throw new IllegalStateException("Cannot finalize " + destination.getName());
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) value.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
        return value.toString();
    }

    public interface ProgressCallback {
        void onProgress(long completedFiles, long totalFiles, String currentFile);
        void onComplete(Throwable error);
    }

    private static final class Artifact {
        final String path;
        final String sha256;
        Artifact(String path, String sha256) { this.path = path; this.sha256 = sha256; }
    }
}
