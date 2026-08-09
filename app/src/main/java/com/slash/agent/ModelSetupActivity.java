package com.slash.agent;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

public final class ModelSetupActivity extends Activity {
    private LocalModelManager models;
    private TextView status;
    private ProgressBar progress;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        models = new LocalModelManager(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        root.setBackgroundColor(Color.rgb(16, 16, 16));
        TextView title = label("Slash local AI", 26, Color.WHITE);
        TextView details = label("Recommended model: Qwen3 0.6B Q4_K_M\nStored privately inside Slash", 16, Color.LTGRAY);
        status = label(statusText(), 14, Color.LTGRAY);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        Button download = new Button(this);
        download.setText("Download recommended model");
        download.setOnClickListener(v -> download(download));
        root.addView(title); root.addView(details); root.addView(status); root.addView(progress);
        root.addView(download);
        setContentView(root);
    }

    private String statusText() {
        return models.exists() ? "Model downloaded. Device: " + models.capabilitySummary() : "No local model downloaded. Device: " + models.capabilitySummary();
    }

    private void download(Button button) {
        button.setEnabled(false);
        status.setText("Downloading Qwen3 0.6B…");
        models.downloadRecommended(new LocalModelManager.ProgressCallback() {
            @Override public void onProgress(long received, long total) {
                runOnUiThread(() -> {
                    if (total > 0) progress.setProgress((int) Math.min(1000, received * 1000 / total));
                    status.setText(String.format(Locale.US, "Downloaded %.1f MB", received / 1_000_000f));
                });
            }
            @Override public void onComplete(Throwable error) {
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    status.setText(error == null ? "Model downloaded and validated." : "Download failed: " + error.getMessage());
                });
            }
        });
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.START);
        view.setPadding(0, 0, 0, 24);
        return view;
    }
}
