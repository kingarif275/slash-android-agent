package com.slash.agent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Debug-only deterministic launcher for physical-device runtime verification. */
public final class RuntimeSmokeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        runSmoke(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        runSmoke(intent);
    }

    private void runSmoke(Intent request) {
        String text = request.getStringExtra("text");
        if (text == null || text.trim().isEmpty()) text = "Reply with exactly READY";
        UserTurn.Source source = request.getBooleanExtra("voice", false)
                ? UserTurn.Source.VOICE : UserTurn.Source.TEXT;

        ChatCoordinator coordinator = ((SlashApplication) getApplication()).coordinator();
        String chatId = coordinator.newChat();
        coordinator.submit(new UserTurn(source, text.trim(), chatId, System.currentTimeMillis()));
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
