package com.slash.agent;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class SlashTileService extends TileService {
    @Override public void onStartListening() { super.onStartListening(); getQsTile().setState(Tile.STATE_INACTIVE); getQsTile().updateTile(); }
    @Override public void onClick() {
        boolean active = getQsTile().getState() == Tile.STATE_ACTIVE;
        getQsTile().setState(active ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        getQsTile().updateTile();
        Intent intent = new Intent(this, SlashListeningService.class);
        intent.setAction(active ? SlashListeningService.STOP : SlashListeningService.START);
        if (active) stopService(intent); else startForegroundService(intent);
    }
}
