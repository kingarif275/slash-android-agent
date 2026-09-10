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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.text.InputType;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModelSetupActivity extends Activity {
    private static final String[] MODEL_CATALOG = {
            "gemini-live-2.5-flash-native-audio", "gemini-live-2.5-flash", "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash", "gemini-3.1-pro", "gemini-3-flash", "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite", "claude-sonnet-5", "claude-fable-5.1", "claude-opus-5", "claude-opus-4.6", "claude-sonnet-4.6", "claude-haiku-4.5", "minimax-m2", "kimi-k2", "kimi-k2-thinking", "deepseek-v3.2", "deepseek-v3.2-speciale", "deepseek-v3.1", "deepseek-r1", "glm-5", "glm-4.7", "mistral-large-3", "ministral-3", "mistral-small", "mistral-nemo", "llama-4-maverick", "llama-4-scout", "llama-3.3", "qwen3", "qwen3-next", "qwen3-next-80b-thinking", "qwen3-coder", "qwen3-vl", "gpt-oss-120b", "gpt-oss-20b", "nemotron-3-super-120b", "nemotron-3-nano", "gemma-3"
    };
    private static final int PICK_SPEAKER_REFERENCE = 7001;
    private LocalModelManager models;
    private ChatterboxNanoModelManager voiceModels;
    private ChatCoordinator coordinator;
    private TextView selected;
    private TextView status;
    private ProgressBar progress;
    private Button download;
    private TextView cloudStatus;
    private EditText cloudKey;
    private Button cloudUse;
    private Button conversationModelChoice;
    private Button agentModelChoice;
    private Switch runtimeSwitch;
    private boolean refreshingRuntimeChoice;
    private TextView voiceStatus;
    private Button voiceDownload;
    private Typeface inter;
    private String pendingProfileId;
    private final Map<String, RadioButton> profileOptions = new LinkedHashMap<>();

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

        TextView back = label("‹  CHATS", 13, Color.rgb(70, 70, 70));
        back.setOnClickListener(view -> finish());
        root.addView(back);
        TextView title = label("AI CONTROL", 28, Color.rgb(13, 13, 13));
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        root.addView(title);
        root.addView(label("Models stay in Slash's private storage. Choose a profile that fits this phone before downloading.", 16, Color.rgb(92, 92, 92)));

        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(RadioGroup.VERTICAL);
        pendingProfileId = models.selectedProfile().id;
        for (ModelProfile profile : models.profiles()) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setTag(profile.id);
            option.setText(profileOptionText(profile));
            option.setTextSize(15);
            option.setTypeface(inter);
            option.setTextColor(Color.rgb(13, 13, 13));
            option.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(13, 13, 13)));
            option.setPadding(0, dp(8), 0, dp(8));
            choices.addView(option, new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
            profileOptions.put(profile.id, option);
            if (profile.id.equals(models.selectedProfile().id)) choices.check(option.getId());
        }
        choices.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton checked = group.findViewById(checkedId);
            if (checked == null) return;
            pendingProfileId = String.valueOf(checked.getTag());
            refreshSelection();
        });
        root.addView(choices);

        selected = label("", 16, Color.rgb(13, 13, 13));
        status = label("", 13, Color.rgb(92, 92, 92));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        download = new Button(this);
        styleControl(download);
        download.setAllCaps(false);
        download.setOnClickListener(view -> handleModelAction());
        root.addView(selected);
        root.addView(status);
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.topMargin = dp(18);
        root.addView(download, buttonParams);

        TextView cloudHeading = label("Vertex AI Gemini", 24, Color.rgb(13, 13, 13));
        cloudHeading.setPadding(0, dp(32), 0, dp(12));
        root.addView(cloudHeading);
        root.addView(label("Choose separate Vertex AI models for normal conversation and grounded Agent Mode. Cloud messages leave this phone and are processed by Google Cloud.", 15, Color.rgb(92, 92, 92)));
        root.addView(label("Conversation catalog: Google, Anthropic, MiniMax, Kimi, DeepSeek, GLM, Mistral, Llama, Qwen, GPT-OSS, Nemotron and Gemma. Use the dropdown to choose.", 13, Color.rgb(92, 92, 92)));
        root.addView(label("Conversation model", 15, Color.rgb(13, 13, 13)));
        conversationModelChoice = modelSpinner(MODEL_CATALOG, coordinator.cloudAiSettings().conversationModel());
        root.addView(conversationModelChoice);
        root.addView(label("Agent catalog: frontier reasoning, coding and tool-use models from Google and Model Garden partners. Use the dropdown to choose.", 13, Color.rgb(92, 92, 92)));
        root.addView(label("Agent model", 15, Color.rgb(13, 13, 13)));
        agentModelChoice = modelSpinner(MODEL_CATALOG, coordinator.cloudAiSettings().agentModel());
        root.addView(agentModelChoice);
        cloudStatus = label("", 14, Color.rgb(92, 92, 92));
        root.addView(cloudStatus);
        runtimeSwitch = new Switch(this);
        runtimeSwitch.setText("USE VERTEX AI\nTurn off to use the installed local model");
        runtimeSwitch.setTextSize(15);
        runtimeSwitch.setTypeface(inter);
        runtimeSwitch.setTextColor(Color.rgb(13, 13, 13));
        runtimeSwitch.setPadding(dp(16), dp(12), dp(12), dp(12));
        runtimeSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (refreshingRuntimeChoice) return;
            if (checked && !coordinator.cloudAiSettings().hasApiKey()) {
                cloudStatus.setText("Vertex AI is not configured yet. Save an AQ. key below first.");
                refreshingRuntimeChoice = true;
                runtimeSwitch.setChecked(false);
                refreshingRuntimeChoice = false;
                return;
            }
            if (checked) coordinator.activateSavedCloudRuntime(this::refreshCloudSelection);
            else coordinator.activateLocalRuntime(this::refreshCloudSelection);
        });
        root.addView(runtimeSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));
        cloudKey = new EditText(this);
        cloudKey.setHint("Vertex AI Express Mode API key (AQ.…)");
        cloudKey.setSingleLine(true);
        cloudKey.setTextSize(15);
        cloudKey.setTypeface(inter);
        cloudKey.setTextColor(Color.rgb(13, 13, 13));
        cloudKey.setHintTextColor(Color.rgb(110, 110, 110));
        cloudKey.setBackgroundColor(Color.WHITE);
        cloudKey.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        cloudKey.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.addView(cloudKey, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        cloudUse = new Button(this);
        styleControl(cloudUse);
        cloudUse.setAllCaps(false);
        cloudUse.setText("Save key and use Vertex AI Gemini");
        cloudUse.setOnClickListener(view -> activateCloud());
        LinearLayout.LayoutParams cloudButtonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        cloudButtonParams.topMargin = dp(10);
        root.addView(cloudUse, cloudButtonParams);
        Button clearCloud = new Button(this);
        styleControl(clearCloud);
        clearCloud.setAllCaps(false);
        clearCloud.setText("Remove saved Vertex AI key");
        clearCloud.setOnClickListener(view -> coordinator.clearCloudApiKey(() -> {
            cloudKey.setText("");
            refreshCloudSelection();
        }));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        clearParams.topMargin = dp(10);
        root.addView(clearCloud, clearParams);

        TextView agentHeading = label("Agent verification", 24, Color.rgb(13, 13, 13));
        agentHeading.setPadding(0, dp(32), 0, dp(12));
        root.addView(agentHeading);
        root.addView(label("For playback goals, Slash verifies the actual Android MediaSession state and metadata instead of trusting the AI or the visible screen. Enable Slash media verification under Notification access.", 15, Color.rgb(92, 92, 92)));
        Button mediaAccess = new Button(this);
        styleControl(mediaAccess);
        mediaAccess.setAllCaps(false);
        mediaAccess.setText("Open Notification access settings");
        mediaAccess.setOnClickListener(view -> startActivity(
                new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        LinearLayout.LayoutParams mediaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        mediaParams.topMargin = dp(10);
        root.addView(mediaAccess, mediaParams);

        TextView voiceHeading = label("Neural voice", 24, Color.rgb(13, 13, 13));
        voiceHeading.setPadding(0, dp(32), 0, dp(12));
        root.addView(voiceHeading);
        root.addView(label("Voice turns use the local Chatterbox Nano ONNX runtime at 24 kHz. Its model package and a short speaker-reference WAV are stored privately on this device.", 15, Color.rgb(92, 92, 92)));
        voiceStatus = label("", 14, Color.rgb(92, 92, 92));
        root.addView(voiceStatus);
        voiceDownload = new Button(this);
        styleControl(voiceDownload);
        voiceDownload.setAllCaps(false);
        voiceDownload.setText("Download neural voice model");
        voiceDownload.setOnClickListener(view -> downloadVoiceModel());
        root.addView(voiceDownload, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        Button importVoice = new Button(this);
        styleControl(importVoice);
        importVoice.setAllCaps(false);
        importVoice.setText("Choose speaker-reference WAV");
        importVoice.setOnClickListener(view -> chooseSpeakerReference());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        importParams.topMargin = dp(10);
        root.addView(importVoice, importParams);
        refreshSelection();
        refreshCloudSelection();
        refreshVoiceSelection();
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private Button modelSpinner(String[] models, String selectedModel) {
        Button button = new Button(this);
        styleControl(button);
        button.setAllCaps(false);
        button.setText(selectedModel + "  ▾");
        button.setTextSize(15);
        button.setOnClickListener(view -> {
            int checked = 0;
            for (int i = 0; i < models.length; i++) if (models[i].equals(button.getText().toString().replace("  ▾", ""))) checked = i;
            new android.app.AlertDialog.Builder(this)
                    .setTitle(button == conversationModelChoice ? "Conversation model" : "Agent Mode model")
                    .setSingleChoiceItems(models, checked, (dialog, which) -> {
                        String value = models[which];
                        button.setText(value + "  ▾");
                        if (button == conversationModelChoice) coordinator.cloudAiSettings().setConversationModel(value);
                        else coordinator.cloudAiSettings().setAgentModel(value);
                        refreshCloudSelection();
                        dialog.dismiss();
                    }).show();
        });
        return button;
    }

    private void refreshSelection() {
        ModelProfile active = models.selectedProfile();
        ModelProfile profile = models.profile(pendingProfileId == null ? active.id : pendingProfileId);
        for (ModelProfile item : models.profiles()) {
            RadioButton option = profileOptions.get(item.id);
            if (option != null) option.setText(profileOptionText(item));
        }
        selected.setText("Active: " + active.displayName + "\nChosen: " + profile.displayName);
        if (models.isDownloading()) {
            download.setEnabled(true);
            download.setText("Cancel download");
        } else if (models.exists(profile)) {
            download.setEnabled(true);
            download.setText(profile.id.equals(active.id) ? "Re-download selected model" : "Use installed model");
        } else {
            download.setEnabled(true);
            download.setText(models.partialBytes(profile) > 0 ? "Resume download" : "Download selected model");
        }
        status.setText(models.exists(profile)
                ? "Installed, checksum verified, and llama.cpp compatible.\n" + models.capabilitySummary(profile)
                : (models.partialBytes(profile) > 0
                    ? "Partial download ready to resume: " + formatBytes(models.partialBytes(profile)) + ".\n"
                    : "Not downloaded.\n") + models.capabilitySummary(profile));
        progress.setProgress(0);
    }

    private void handleModelAction() {
        if (models.isDownloading()) {
            coordinator.cancelModelDownload();
            status.setText("Cancelling safely… The partial file will be kept for resume.");
            download.setEnabled(false);
            return;
        }
        ModelProfile profile = models.profile(pendingProfileId);
        if (models.exists(profile) && !profile.id.equals(models.selectedProfile().id)) {
            download.setEnabled(false);
            status.setText("Activating verified " + profile.displayName + "…");
            coordinator.activateModelProfile(profile.id, this::refreshSelection);
            return;
        }
        downloadProfile(profile);
    }

    private void downloadProfile(ModelProfile profile) {
        download.setEnabled(true);
        download.setText("Cancel download");
        status.setText("Downloading " + profile.displayName + "…");
        coordinator.downloadModelProfile(profile.id, new LocalModelManager.ProgressCallback() {
            @Override public void onProgress(long received, long total) {
                runOnUiThread(() -> {
                    if (total > 0) progress.setProgress((int) Math.min(1000, received * 1000 / total));
                    String totalText = total > 0 ? " / " + formatBytes(total) : "";
                    status.setText("Downloaded " + formatBytes(received) + totalText
                            + "\nSafe to cancel; progress will be resumable.");
                });
            }

            @Override public void onComplete(Throwable error) {
                runOnUiThread(() -> {
                    if (error == null) refreshSelection();
                    else {
                        refreshSelection();
                        status.setText("Model download was not activated: " + error.getMessage()
                                + "\nAny safe partial download remains available to resume.");
                    }
                });
            }
        });
    }

    private String profileOptionText(ModelProfile profile) {
        String tier = profile.tier == ModelProfile.Tier.LITE ? "Lite"
                : profile.tier == ModelProfile.Tier.BALANCED ? "Balanced · Recommended" : "High";
        String model = profile.tier == ModelProfile.Tier.LITE ? "Qwen3 0.6B Abliterated"
                : profile.tier == ModelProfile.Tier.BALANCED ? "Qwen3 1.7B Abliterated"
                : "Qwen3 4B Abliterated";
        String state = models.exists(profile) ? "Installed" : models.partialBytes(profile) > 0 ? "Partial" : "Not installed";
        if (profile.id.equals(models.selectedProfile().id)) state += " · Selected";
        return tier + "\n" + model + "\n~" + formatBytes(profile.expectedSizeBytes) + " · " + state;
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000d);
        return String.format(Locale.US, "%.0f MB", bytes / 1_000_000d);
    }

    private void activateCloud() {
        String key = cloudKey.getText().toString().trim();
        if (!key.startsWith("AQ.") || key.length() < 32) {
            cloudStatus.setText("Enter a valid Vertex AI Express Mode API key. It is never written to source or logs.");
            return;
        }
        cloudUse.setEnabled(false);
        cloudStatus.setText("Encrypting the key with Android Keystore…");
        coordinator.activateCloudRuntime(key, () -> {
            cloudKey.setText("");
            cloudUse.setEnabled(true);
            refreshCloudSelection();
        });
    }

    private void refreshCloudSelection() {
        if (cloudStatus == null) return;
        boolean configured = coordinator.cloudAiSettings().hasApiKey();
        boolean active = coordinator.cloudRuntimeSelected();
        cloudStatus.setText("Conversation: " + coordinator.cloudAiSettings().conversationModel()
                + "\nAgent Mode: " + coordinator.cloudAiSettings().agentModel()
                + "\nKey: " + (configured ? "saved with Android Keystore" : "not saved")
                + "\nActive runtime: " + (active ? "Vertex AI Gemini" : "local abliterated Qwen3"));
        if (runtimeSwitch != null) {
            refreshingRuntimeChoice = true;
            runtimeSwitch.setChecked(active);
            refreshingRuntimeChoice = false;
        }
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

    /** Keep native Material widgets from inheriting the dark theme's white labels. */
    private void styleControl(Button button) {
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(13, 13, 13));
        button.setTypeface(inter);
        button.setTextSize(15);
        button.setBackgroundColor(Color.WHITE);
        button.setPadding(dp(12), 0, dp(12), 0);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
