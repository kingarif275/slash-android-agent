package com.slash.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Keeps user-requested local inference and accessibility automation alive while the app is
 * backgrounded. The coordinator remains application-scoped so activities can reconnect without
 * losing the active task or reloading the model.
 */
public final class SlashRuntimeService extends Service {
    private static final String TAG = "SlashRuntimeService";
    private static final String CHANNEL_ID = "slash_runtime";
    private static final int NOTIFICATION_ID = 4107;
    private static final String ACTION_START = "com.slash.agent.runtime.START";
    private static final String ACTION_STOP = "com.slash.agent.runtime.STOP";
    private static final long IDLE_KEEPALIVE_MS = 5 * 60_000L;
    private static volatile SlashRuntimeService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopAfterIdle = this::stopSelf;

    public static void ensureRunning(Context context, String status) {
        Intent intent = new Intent(context, SlashRuntimeService.class)
                .setAction(ACTION_START)
                .putExtra("status", status);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException error) {
            // Android 12+ can reject a start after the initiating Activity has backgrounded.
            // Inference still continues in-process; the visible UI can start the service next turn.
            Log.w(TAG, "FOREGROUND_RUNTIME_START_REJECTED", error);
        }
    }

    public static void updateStatus(String status) {
        SlashRuntimeService service = instance;
        if (service == null) return;
        service.handler.removeCallbacks(service.stopAfterIdle);
        service.showNotification(status == null || status.trim().isEmpty() ? "Working locally" : status.trim());
    }

    public static void markIdle() {
        SlashRuntimeService service = instance;
        if (service == null) return;
        service.showNotification("Local model ready");
        service.handler.removeCallbacks(service.stopAfterIdle);
        service.handler.postDelayed(service.stopAfterIdle, IDLE_KEEPALIVE_MS);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Slash local runtime", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SlashApplication application = (SlashApplication) getApplication();
            application.coordinator().cancelActive();
            stopSelf();
            return START_NOT_STICKY;
        }
        String status = intent == null ? "Working locally" : intent.getStringExtra("status");
        if (status == null || status.trim().isEmpty()) status = "Working locally";
        Notification notification = buildNotification(status);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        handler.removeCallbacks(stopAfterIdle);
        return START_NOT_STICKY;
    }

    private void showNotification(String status) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(status));
    }

    private Notification buildNotification(String status) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SlashRuntimeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_slash_tile)
                .setContentTitle("Slash")
                .setContentText(status)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(stopAfterIdle);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
