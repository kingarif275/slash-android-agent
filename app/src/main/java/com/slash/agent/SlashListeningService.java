package com.slash.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class SlashListeningService extends Service {
    public static final String START = "com.slash.agent.START";
    public static final String STOP = "com.slash.agent.STOP";
    private static final String CHANNEL = "slash-listening";

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (STOP.equals(intent == null ? null : intent.getAction())) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Slash listening", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL).setContentTitle("Slash is listening").setContentText("Quick Settings tile is active").setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build();
        startForeground(7, n);
        // TODO: Connect Moonshine streaming ASR and Qwen local planner here.
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
