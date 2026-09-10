package com.slash.agent;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

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
        Uri data = request.getData();
        ChatCoordinator coordinator = ((SlashApplication) getApplication()).coordinator();
        if (request.getBooleanExtra("use_local", false)) {
            coordinator.activateLocalRuntime(() -> {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            });
            return;
        }
        if (request.getBooleanExtra("use_saved_cloud", false)) {
            coordinator.activateSavedCloudRuntime(() -> {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            });
            return;
        }
        String vertexKey = request.getStringExtra("vertex_key");
        if (vertexKey != null && !vertexKey.trim().isEmpty()) {
            String cloudText = request.getStringExtra("text");
            if (cloudText == null || cloudText.trim().isEmpty()) {
                cloudText = "How many r's are in the word strawberry?";
            }
            String finalCloudText = cloudText.trim();
            coordinator.activateCloudRuntime(vertexKey.trim(), () -> {
                submit(coordinator, finalCloudText, false);
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            });
            return;
        }
        String modelProfile = data == null ? null : data.getQueryParameter("download_model");
        if (modelProfile != null && !modelProfile.trim().isEmpty()) {
            coordinator.downloadModelProfile(modelProfile.trim(), new LocalModelManager.ProgressCallback() {
                @Override public void onProgress(long received, long total) {
                    Log.i("SlashModelMigration", "MODEL_DOWNLOAD_PROGRESS received=" + received
                            + " total=" + total);
                }

                @Override public void onComplete(Throwable error) {
                    if (error == null) Log.i("SlashModelMigration", "MODEL_DOWNLOAD_VERIFIED_AND_ACTIVATED");
                    else Log.e("SlashModelMigration", "MODEL_DOWNLOAD_FAILED", error);
                }
            });
            finish();
            return;
        }
        String text = request.getStringExtra("text");
        if (data != null && data.getQueryParameter("text") != null) {
            text = data.getQueryParameter("text");
        }
        if (text == null || text.trim().isEmpty()) text = "Reply with exactly READY";
        boolean voice = request.getBooleanExtra("voice", false)
                || (data != null && data.getBooleanQueryParameter("voice", false));
        submit(coordinator, text.trim(), voice);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void submit(ChatCoordinator coordinator, String text, boolean voice) {
        UserTurn.Source source = voice ? UserTurn.Source.VOICE : UserTurn.Source.TEXT;
        String chatId = coordinator.newChat();
        coordinator.submit(new UserTurn(source, text, chatId, System.currentTimeMillis()));
    }
}
