package com.slash.agent;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Optional shortcut; normal chat, voice, and agent work never depend on this tile. */
public final class SlashTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        getQsTile().setState(Tile.STATE_INACTIVE);
        getQsTile().setSubtitle("Open voice chat");
        getQsTile().updateTile();
    }

    @Override public void onClick() {
        Intent chat = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_START_VOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, chat,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pending);
        else startActivityAndCollapse(chat);
    }
}
