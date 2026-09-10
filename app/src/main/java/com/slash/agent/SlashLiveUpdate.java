package com.slash.agent;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/** Android 16+ live agent status surface. Older devices intentionally no-op. */
public final class SlashLiveUpdate {
    // Reuse the foreground-service notification ID. A Live Update is a promoted form of the
    // ongoing notification, not a second notification.
    private static final int NOTIFICATION_ID = 4107;
    private final Context context;

    public SlashLiveUpdate(Context context) { this.context = context.getApplicationContext(); }

    public boolean isAvailable() { return Build.VERSION.SDK_INT >= 36; }

    public void publish(Notification notification) {
        if (!isAvailable() || notification == null) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification);
    }

    public void clear() {
        if (!isAvailable()) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }
}
