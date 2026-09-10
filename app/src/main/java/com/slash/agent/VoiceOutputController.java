package com.slash.agent;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Voice output boundary. Local neural ONNX is intentionally disabled; Live voice belongs to Vertex Live. */
public final class VoiceOutputController implements TextToSpeech.OnInitListener {
    private static final String TAG = "SlashVoiceOutput";
    private final ChatterboxNanoModelManager models;
    private final TextToSpeech fallback;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean fallbackReady;

    public VoiceOutputController(Context context) {
        models = new ChatterboxNanoModelManager(context);
        fallback = new TextToSpeech(context.getApplicationContext(), this);
    }

    /** Kept for the model setup screen; this manager is not used for speech inference. */
    public ChatterboxNanoModelManager modelManager() { return models; }

    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        long token = generation.incrementAndGet();
        Log.w(TAG, "VERTEX_LIVE_AUDIO_NOT_CONNECTED; using Android TTS compatibility output");
        speakFallback(text, token);
    }

    public void cancel() {
        generation.incrementAndGet();
        fallback.stop();
    }

    private void speakFallback(String text, long token) {
        if (!fallbackReady || token != generation.get()) return;
        Bundle parameters = new Bundle();
        fallback.speak(text, TextToSpeech.QUEUE_FLUSH, parameters, "slash-fallback-" + token);
    }

    @Override public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        int language = fallback.setLanguage(Locale.getDefault());
        fallbackReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
    }
}
