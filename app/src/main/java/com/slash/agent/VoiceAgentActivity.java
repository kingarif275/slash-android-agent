package com.slash.agent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Voice entry point; the chat-first surface remains MainActivity. */
public final class VoiceAgentActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_START_VOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
