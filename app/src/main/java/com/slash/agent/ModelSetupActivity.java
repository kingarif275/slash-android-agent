package com.slash.agent;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public final class ModelSetupActivity extends Activity {
    private static final int PICK_SPEAKER_REFERENCE = 7001;
    private LocalModelManager models;
    private ChatterboxNanoModelManager voiceModels;
    private ChatCoordinator coordinator;
    private TextView selected;
    private TextView status;
    private ProgressBar progress;
    private Button download;
    private TextView voiceStatus;
    private Button voiceDownload;
    private Typeface inter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        coordinator = ((SlashApplication) getApplication()).coordinator();
        models = coordinator.modelManager();
        voiceModels = coordinator.voiceModelManager();
        inter = Typeface.createFromAsset(getAssets(), "fonts/Inter-Regular.otf");
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(dp(24) + bars.left, dp(28) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom);
            return insets;
        });

        TextView back = label("‹  Chats", 16, Color.rgb(13, 13, 13));
        back.setOnClickListener(view -> finish());
        root.addView(back);
        root.addView(label("Local model", 28, Color.rgb(13, 13, 13)));
        root.addView(label("Models stay in Slash's private storage. Choose a profile that fits this phone before downloading.", 16, Color.rgb(92, 92, 92)));

        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(RadioGroup.VERTICAL);
        for (ModelProfile profile : models.profiles()) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setTag(profile.id);
            option.setText(profile.displayName + "\n" + profile.architecture + " · " + profile.contextLength + " context · tools " + profile.toolCallingSupport);
            option.setTextSize(15);
            option.setTypeface(inter);
            option.setTextColor(Color.rgb(13, 13, 13));
            option.setPadding(0, dp(8), 0, dp(8));
            choices.addView(option, new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
            if (profile.id.equals(models.selectedProfile().id)) choices.check(option.getId());
        }
        choices.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton checked = group.findViewById(checkedId);
            if (checked == null) return;
            coordinator.selectModelProfile(String.valueOf(checked.getTag()));
            refreshSelection();
        });
        root.addView(choices);

        selected = label("", 16, Color.rgb(13, 13, 13));
        status = label("", 13, Color.rgb(92, 92, 92));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        download = new Button(this);
        download.setAllCaps(false);
        download.setOnClickListener(view -> downloadSelected());
        root.addView(selected);
        root.addView(status);
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.topMargin = dp(18);
        root.addView(download, buttonParams);

        TextView voiceHeading = label("Neural voice", 24, Color.rgb(13, 13, 13));
        voiceHeading.setPadding(0, dp(32), 0, dp(12));
        root.addView(voiceHeading);
        root.addView(label("Voice turns use the local Chatterbox Nano ONNX runtime at 24 kHz. Its model package and a short speaker-reference WAV are stored privately on this device.", 15, Color.rgb(92, 92, 92)));
        voiceStatus = label("", 14, Color.rgb(92, 92, 92));
        root.addView(voiceStatus);
        voiceDownload = new Button(this);
        voiceDownload.setAllCaps(false);
        voiceDownload.setText("Download neural voice model");
        voiceDownload.setOnClickListener(view -> downloadVoiceModel());
        root.addView(voiceDownload, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        Button importVoice = new Button(this);
        importVoice.setAllCaps(false);
        importVoice.setText("Choose speaker-reference WAV");
        importVoice.setOnClickListener(view -> chooseSpeakerReference());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        importParams.topMargin = dp(10);
        root.addView(importVoice, importParams);
        refreshSelection();
        refreshVoiceSelection();
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void refreshSelection() {
        ModelProfile profile = models.selectedProfile();
        selected.setText("Selected: " + profile.displayName);
        download.setText(models.exists() ? "Re-download selected model" : "Download selected model");
        status.setText(models.exists() ? "Downloaded and GGUF header validated.\n" + models.capabilitySummary()
                : "Not downloaded.\n" + models.capabilitySummary());
        progress.setProgress(0);
    }

    private void downloadSelected() {
        ModelProfile profile = models.selectedProfile();
        download.setEnabled(false);
        status.setText("Downloading " + profile.displayName + "…");
        models.downloadSelected(new LocalModelManager.ProgressCallback() {
            @Override public void onProgress(long received, long total) {
                runOnUiThread(() -> {
                    if (total > 0) progress.setProgress((int) Math.min(1000, received * 1000 / total));
                    String totalText = total > 0 ? String.format(Locale.US, " / %.1f MB", total / 1_000_000f) : "";
                    status.setText(String.format(Locale.US, "Downloaded %.1f MB%s", received / 1_000_000f, totalText));
                });
            }

            @Override public void onComplete(Throwable error) {
                runOnUiThread(() -> {
                    download.setEnabled(true);
                    if (error == null) refreshSelection();
                    else status.setText("Download failed. Check the connection and available storage.");
                });
            }
        });
    }

    private void downloadVoiceModel() {
        voiceDownload.setEnabled(false);
        voiceStatus.setText("Preparing Chatterbox Nano download…");
        voiceModels.download(new ChatterboxNanoModelManager.ProgressCallback() {
            @Override public void onProgress(long completedFiles, long totalFiles, String currentFile) {
                runOnUiThread(() -> voiceStatus.setText("Verified " + completedFiles + " of " + totalFiles + " files\n" + currentFile));
            }

            @Override public void onComplete(Throwable error) {
                runOnUiThread(() -> {
                    voiceDownload.setEnabled(true);
                    if (error == null) refreshVoiceSelection();
                    else voiceStatus.setText("Neural voice download failed: " + error.getMessage());
                });
            }
        });
    }

    private void chooseSpeakerReference() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/wav")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_SPEAKER_REFERENCE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SPEAKER_REFERENCE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            voiceModels.installSpeakerReference(data.getData());
            refreshVoiceSelection();
        } catch (Exception error) {
            voiceStatus.setText("Speaker profile import failed: " + error.getMessage());
        }
    }

    private void refreshVoiceSelection() {
        if (voiceStatus == null) return;
        String model = voiceModels.modelReady() ? "model verified" : "model not downloaded";
        String speaker = voiceModels.speakerReference().isFile() ? "speaker profile ready" : "speaker profile required";
        voiceStatus.setText("Chatterbox Nano: " + model + " · " + speaker
                + (voiceModels.ready() ? "\nVoice mode will use CHATTERBOX_NANO_ONNX." : "\nAndroid TTS remains the failure fallback until both are ready."));
        if (voiceDownload != null) voiceDownload.setText(voiceModels.modelReady() ? "Re-verify / repair neural voice model" : "Download neural voice model (~550 MB)");
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(inter);
        view.setGravity(Gravity.START);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, 0, 0, dp(18));
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
