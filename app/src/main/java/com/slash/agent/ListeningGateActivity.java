package com.slash.agent;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * A short-lived, transparent activity used to satisfy Android's
 * user-initiated microphone foreground-service eligibility rule.
 */
public final class ListeningGateActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Intent service = new Intent(this, SlashListeningService.class)
                    .setAction(SlashListeningService.START);
            startForegroundService(service);
        }
        getWindow().getDecorView().postDelayed(this::finish, 350);
    }
}
