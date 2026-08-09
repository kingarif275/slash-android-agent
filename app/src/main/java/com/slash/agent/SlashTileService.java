package com.slash.agent;

import android.content.Intent;
import android.app.PendingIntent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

public final class SlashTileService extends TileService {
    private static final String TAG = "SlashTile";

    @Override public void onStartListening() {
        super.onStartListening();
        Log.i(TAG, "Quick Settings tile is listening");
    }

    @Override public void onClick() {
        boolean active = getQsTile().getState() == Tile.STATE_ACTIVE;
        Log.i(TAG, "Tile clicked; active=" + active);
        if (active) {
            Intent intent = new Intent(this, SlashListeningService.class)
                    .setAction(SlashListeningService.STOP);
            stopService(intent);
            getQsTile().setState(Tile.STATE_INACTIVE);
            getQsTile().updateTile();
            return;
        }
        Runnable start = () -> {
            try {
                Intent gate = new Intent(this, ListeningGateActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent gatePendingIntent = PendingIntent.getActivity(
                        this, 0, gate, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(gatePendingIntent);
                getQsTile().setState(Tile.STATE_ACTIVE);
                getQsTile().updateTile();
                Log.i(TAG, "Listening gate activity requested");
            } catch (Throwable error) {
                Log.e(TAG, "Could not start listening service", error);
                getQsTile().setState(Tile.STATE_INACTIVE);
                getQsTile().updateTile();
            }
        };
        if (isLocked()) unlockAndRun(start); else start.run();
    }
}
