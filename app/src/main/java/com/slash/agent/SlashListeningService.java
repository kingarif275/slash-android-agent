package com.slash.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

public final class SlashListeningService extends Service implements RecognitionListener {
    public static final String START = "com.slash.agent.START";
    public static final String STOP = "com.slash.agent.STOP";
    private static final String CHANNEL = "slash-listening";
    private static final int NOTIFICATION_ID = 7;
    private static final String TAG = "SlashListening";
    public static final String HIDE_GLOW = "com.slash.agent.HIDE_GLOW";

    private SpeechRecognizer recognizer;
    private TextToSpeech speaker;
    private boolean speaking;
    private boolean handled;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (STOP.equals(intent == null ? null : intent.getAction())) {
            stopInteraction();
            hideGlow();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission is not granted");
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForegroundNow();
            startInteraction();
        } catch (Throwable error) {
            Log.e(TAG, "Unable to start voice interaction", error);
            stopInteraction();
            hideGlow();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundNow() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Slash listening", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Slash is listening")
                .setContentText("Say a command")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startInteraction() {
        handled = false;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("I cannot find a speech recognition service on this phone.", false);
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        speaker = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                speaker.setLanguage(Locale.US);
                speak("Hi, I'm Slash. What can I do for you?", true);
            } else {
                beginListening();
            }
        });
    }

    private void speak(String message, boolean listenAfter) {
        if (speaker == null) {
            if (listenAfter) beginListening();
            return;
        }
        speaking = true;
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "slash");
        speaker.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) {
                mainHandler.post(() -> {
                    speaking = false;
                    if (listenAfter) beginListening();
                    else stopSelfAfterReply();
                });
            }
            @Override public void onError(String id) {
                mainHandler.post(() -> {
                    speaking = false;
                    if (listenAfter) beginListening(); else stopSelfAfterReply();
                });
            }
        });
        speaker.speak(message, TextToSpeech.QUEUE_FLUSH, params, "slash");
    }

    private void beginListening() {
        if (recognizer == null || handled) return;
        Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag());
        request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        recognizer.startListening(request);
        Log.i(TAG, "Speech recognition started");
    }

    @Override public void onResults(Bundle results) {
        if (handled) return;
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String command = matches == null || matches.isEmpty() ? "" : matches.get(0);
        String reply = execute(command);
        handled = false;
        speak(reply, true);
    }

    private String execute(String command) {
        String normalized = command == null ? "" : command.toLowerCase(Locale.US).trim();
        if (normalized.isEmpty()) return "I didn't catch that. Try again when you press Slash.";
        if (normalized.contains("hello") || normalized.equals("hi") || normalized.contains("hey slash")) {
            return "Hey there. I'm ready.";
        }
        if (normalized.contains("open settings") || normalized.equals("settings")) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "Opening Settings.";
        }
        boolean launchRequested = normalized.contains("open ")
                || normalized.contains("launch ")
                || normalized.contains("start ")
                || normalized.contains("play ");
        if (launchRequested || normalized.equals("spotify") || normalized.equals("youtube")) {
            ApplicationInfo app = findInstalledApp(normalized);
            if (app != null) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(app.packageName);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                    String label = getPackageManager().getApplicationLabel(app).toString();
                    return "Opening " + label + ". Anything else?";
                }
            }
            return "I couldn't find that app on this phone. Try saying the app name again.";
        }
        if (normalized.contains("go home") || normalized.equals("home")) {
            if (SlashAccessibilityService.performGlobalActionSafe(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) {
                return "Going home.";
            }
            return "I need Accessibility access enabled to go Home for you.";
        }
        if (normalized.equals("back") || normalized.contains("go back")) {
            if (SlashAccessibilityService.performGlobalActionSafe(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
                return "Going back.";
            }
            return "I need Accessibility access enabled to go back for you.";
        }
        return "I heard you say: " + command + ". I’m still listening—try an app name or say Settings, Home, or Back.";
    }

    private ApplicationInfo findInstalledApp(String spokenCommand) {
        String command = compact(spokenCommand);
        ApplicationInfo best = null;
        int bestScore = 0;
        for (ApplicationInfo app : getPackageManager().getInstalledApplications(PackageManager.MATCH_ALL)) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(app.packageName);
            if (launch == null) continue;
            String label = compact(getPackageManager().getApplicationLabel(app).toString());
            String packageName = compact(app.packageName);
            int score = 0;
            if (!label.isEmpty() && command.contains(label)) score = 100 + label.length();
            else if (!label.isEmpty() && label.contains(command)) score = 80;
            else if (command.contains(packageName)) score = 50;
            if (score > bestScore) {
                best = app;
                bestScore = score;
            }
        }
        return best;
    }

    private String compact(String value) {
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private void stopSelfAfterReply() {
        stopInteraction();
        hideGlow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopInteraction() {
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
        if (speaker != null) {
            speaker.stop();
            speaker.shutdown();
            speaker = null;
        }
    }

    @Override public void onDestroy() { stopInteraction(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }
    @Override public void onError(int error) {
        Log.e(TAG, "Speech recognition error: " + error);
        if (!handled) {
            handled = false;
            speak("I didn't catch that. I'm still listening—please try again.", true);
        }
    }

    private void hideGlow() {
        sendBroadcast(new Intent(HIDE_GLOW).setPackage(getPackageName()));
    }
}
