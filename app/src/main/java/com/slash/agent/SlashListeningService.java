package com.slash.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.content.Intent;
import android.os.IBinder;
import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;

public final class SlashListeningService extends Service {
    public static final String START = "com.slash.agent.START";
    public static final String STOP = "com.slash.agent.STOP";
    private static final String CHANNEL = "slash-listening";
    private static final int NOTIFICATION_ID = 7;
    private static final String TAG = "SlashListening";
    private AudioRecord recorder;
    private Thread captureThread;
    private volatile boolean capturing;

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (STOP.equals(intent == null ? null : intent.getAction())) {
            stopCapture();
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
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Slash listening", NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Slash is listening")
                    .setContentText("Microphone is active")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            startCapture();
        } catch (Throwable error) {
            Log.e(TAG, "Unable to start microphone capture", error);
            stopCapture();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_STICKY;
    }

    private void startCapture() {
        if (capturing) return;
        int sampleRate = 16000;
        int minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            Log.e(TAG, "AudioRecord returned an invalid buffer size: " + minimum);
            stopSelf();
            return;
        }
        int bufferSize = Math.max(minimum, sampleRate / 2);
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
        } catch (Throwable error) {
            Log.e(TAG, "AudioRecord construction failed", error);
            stopSelf();
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize");
            recorder.release();
            recorder = null;
            stopSelf();
            return;
        }
        capturing = true;
        try {
            recorder.startRecording();
        } catch (Throwable error) {
            Log.e(TAG, "AudioRecord start failed", error);
            stopCapture();
            stopSelf();
            return;
        }
        AudioRecord activeRecorder = recorder;
        captureThread = new Thread(() -> {
            short[] buffer = new short[bufferSize / 2];
            while (capturing) {
                try {
                    int read = activeRecorder.read(buffer, 0, buffer.length);
                    if (read < 0) Log.w(TAG, "AudioRecord read failed: " + read);
                } catch (Throwable error) {
                    if (capturing) Log.e(TAG, "AudioRecord read crashed", error);
                    break;
                }
            }
            Log.i(TAG, "Microphone capture loop ended");
        }, "slash-mic-capture");
        captureThread.start();
        Log.i(TAG, "Microphone capture started");
    }

    private void stopCapture() {
        capturing = false;
        AudioRecord activeRecorder = recorder;
        recorder = null;
        if (activeRecorder != null) {
            try { activeRecorder.stop(); } catch (IllegalStateException ignored) { }
            activeRecorder.release();
        }
        captureThread = null;
        Log.i(TAG, "Microphone capture stopped");
    }

    @Override public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
