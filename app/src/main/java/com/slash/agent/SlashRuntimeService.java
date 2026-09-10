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
    private SlashLiveUpdate liveUpdate;
    private static volatile boolean agentMode;
    private static volatile long agentStartedAt;

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
        service.showNotification(status == null || status.trim().isEmpty() ? "Working" : status.trim());
    }

    public static void markIdle() {
        SlashRuntimeService service = instance;
        if (service == null) return;
        agentMode = false;
        agentStartedAt = 0L;
        SlashAccessibilityService.hideAgentPointer();
        service.handler.post(() -> { });
        service.showNotification("Slash is ready");
        service.handler.removeCallbacks(service.stopAfterIdle);
        service.handler.postDelayed(service.stopAfterIdle, IDLE_KEEPALIVE_MS);
    }

    public static void beginAgentMode(Context context) {
        if (!agentMode) agentStartedAt = System.currentTimeMillis();
        agentMode = true;
        SlashRuntimeService service = instance;
        if (service != null) service.handler.post(() -> { });
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        liveUpdate = new SlashLiveUpdate(this);
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
        Notification notification = buildNotification(status, agentMode);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        liveUpdate.publish(notification);
        handler.removeCallbacks(stopAfterIdle);
        return START_NOT_STICKY;
    }

    private void showNotification(String status) {
        Notification notification = buildNotification(status, agentMode);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
        liveUpdate.publish(notification);
    }

    private Notification buildNotification(String status, boolean promoteAsLiveTask) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SlashRuntimeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_slash_tile)
                .setContentTitle("Slash")
                .setContentText(status)
                .setSubText(runtimeLabel(status))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                ;
        // These APIs were introduced with Android 16. Reflection keeps the APK buildable with
        // the installed Android 35 SDK while enabling promotion on API 36+ devices.
        if (Build.VERSION.SDK_INT >= 36 && promoteAsLiveTask) {
            try {
                builder.getClass().getMethod("setRequestPromotedOngoing", boolean.class)
                        .invoke(builder, true);
                // Android 16's promoted ongoing surface uses the app small icon
                // together with this short critical text. Keep it stable and
                // runtime-focused so the pill reads as Slash/Vertex or Slash/Local,
                // rather than exposing a clipped sentence from the task log.
                String chipText = runtimeLabel(status);
                builder.getClass().getMethod("setShortCriticalText", CharSequence.class)
                        .invoke(builder, chipText);
                // Feed Android 16 a real elapsed-time base. The promoted pill can
                // then render the running duration beside Slash's icon instead of
                // only showing a static logo. Keep the timestamp stable across
                // progress updates so it never jumps back to zero.
                long started = agentStartedAt > 0L ? agentStartedAt : System.currentTimeMillis();
                builder.setWhen(started)
                        .setShowWhen(true)
                        .setUsesChronometer(true);
            } catch (Exception error) {
                Log.w(TAG, "LIVE_UPDATE_PROMOTION_UNAVAILABLE", error);
            }
        }
        return builder.build();
    }

    private String runtimeLabel(String status) {
        String lower = status == null ? "" : status.toLowerCase(java.util.Locale.US);
        if (lower.contains("vertex") || lower.contains("gemini") || lower.contains("cloud")) {
            return "Vertex AI";
        }
        if (lower.contains("local") || lower.contains("qwen") || lower.contains("llama")) {
            return "Local AI";
        }
        return "Slash";
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(stopAfterIdle);
        if (instance == this) instance = null;
        if (liveUpdate != null) liveUpdate.clear();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
