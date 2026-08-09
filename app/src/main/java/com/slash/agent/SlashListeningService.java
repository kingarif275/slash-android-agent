package com.slash.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public final class SlashListeningService extends Service implements RecognitionListener {
    public static final String START = "com.slash.agent.START";
    public static final String STOP = "com.slash.agent.STOP";
    public static final String HIDE_GLOW = "com.slash.agent.HIDE_GLOW";
    private static final String CHANNEL = "slash-listening";
    private static final int NOTIFICATION_ID = 7;
    private static final String TAG = "SlashListening";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONArray history = new JSONArray();
    private SpeechRecognizer recognizer;
    private TextToSpeech speaker;
    private LlmRuntime model;
    private EmbeddedLlamaRuntime embeddedRuntime;
    private SlashToolExecutor executor;
    private boolean handled;

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (STOP.equals(intent == null ? null : intent.getAction())) {
            stopInteraction(); hideGlow(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SYSTEM_ERROR: RECORD_AUDIO permission is not granted"); stopSelf(); return START_NOT_STICKY;
        }
        try {
            startForegroundNow();
            embeddedRuntime = new EmbeddedLlamaRuntime(this);
            model = embeddedRuntime;
            executor = new SlashToolExecutor(this);
            history.put(new JSONObject().put("role", "system").put("content", systemPrompt()));
            model.loadModel((error, ignoredTool) -> {
                if (error != null) {
                    Log.e(TAG, "MODEL_ERROR: " + error);
                    speak(error, false);
                } else {
                    startInteraction();
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "SYSTEM_ERROR: unable to start agent", error);
            stopInteraction(); hideGlow(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
        return START_NOT_STICKY;
    }

    private String systemPrompt() {
        return "You are Slash, a warm, intelligent, conversational mobile assistant. "
                + "Be friendly, natural, curious, and helpful; answer ordinary questions conversationally. "
                + "You are agentic only when a tool is needed. Never claim an action succeeded without the executor result. "
                + "Use the current screen context and recent conversation. Choose exactly one generic tool when appropriate, "
                + "otherwise answer normally. Keep replies spoken-friendly and concise but not robotic. "
                + "Available tools operate on generic Android UI; do not assume app-specific layouts.";
    }

    private void startForegroundNow() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Slash listening", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL).setContentTitle("Slash is listening")
                .setContentText("Your local agent is active").setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private void startInteraction() {
        handled = false;
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        speaker = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) speaker.setLanguage(Locale.US);
            askModel("The user has just activated Slash. Greet them naturally and invite them to speak.");
        });
    }

    private void beginListening() {
        if (recognizer == null || handled) return;
        Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag());
        request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        recognizer.startListening(request);
        Log.i(TAG, "EVENT: speech_recognition_started");
    }

    private void askModel(String userMessage) {
        try {
            JSONObject message = new JSONObject().put("role", "user").put("content", userMessage
                    + "\nCURRENT_SCREEN_CONTEXT:\n" + SlashAccessibilityService.readScreenSafe());
            history.put(message);
            trimHistory();
            model.generate(history, this::handleModelResult);
        } catch (Exception error) {
            speak("The agent could not prepare that request.", true);
        }
    }

    private void handleModelResult(String text, JSONObject toolCall) {
        if (toolCall != null) {
            String tool = toolCall.optString("tool");
            JSONObject args = toolCall.optJSONObject("arguments");
            SlashToolExecutor.Result result = executor.execute(tool, args == null ? new JSONObject() : args);
            Log.i(TAG, "TOOL: " + tool + " RESULT: " + result.result);
            try {
                history.put(new JSONObject().put("role", "system").put("content", "REAL_EXECUTOR_RESULT: " + result.result));
                trimHistory();
                model.generate(history, this::handleModelResult);
            } catch (Exception error) { speak("The action returned an unreadable result.", true); }
            return;
        }
        String reply = text == null || text.trim().isEmpty() ? "I’m here. Tell me what you need." : text.trim();
        Log.i(TAG, "VOICE_OUT: " + reply);
        try { history.put(new JSONObject().put("role", "assistant").put("content", reply)); trimHistory(); } catch (Exception ignored) { }
        handled = false;
        speak(reply, true);
    }

    private void speak(String message, boolean listenAfter) {
        if (speaker == null) { if (listenAfter) main.post(this::beginListening); return; }
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "slash-" + System.nanoTime());
        speaker.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { main.post(() -> { if (listenAfter) beginListening(); else finishInteraction(); }); }
            @Override public void onError(String id) { main.post(() -> { if (listenAfter) beginListening(); else finishInteraction(); }); }
        });
        speaker.speak(message, TextToSpeech.QUEUE_FLUSH, params, "slash");
    }

    private void trimHistory() { while (history.length() > 14) history.remove(1); }
    private void finishInteraction() { stopInteraction(); hideGlow(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    private void stopInteraction() {
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        if (speaker != null) { speaker.stop(); speaker.shutdown(); speaker = null; }
        if (model != null) { model.unload(); model = null; }
        if (embeddedRuntime != null) { embeddedRuntime.modelManager().shutdown(); embeddedRuntime = null; }
    }
    private void hideGlow() { sendBroadcast(new Intent(HIDE_GLOW).setPackage(getPackageName())); }

    @Override public void onResults(Bundle results) {
        if (handled) return;
        handled = true;
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String command = matches == null || matches.isEmpty() ? "" : matches.get(0);
        Log.i(TAG, "VOICE_IN: " + command);
        askModel(command);
    }
    @Override public void onError(int error) { Log.e(TAG, "SYSTEM_ERROR: speech_recognition=" + error); handled = false; speak("I didn’t catch that. I’m still here—try again.", true); }
    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }
    @Override public void onDestroy() { stopInteraction(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
