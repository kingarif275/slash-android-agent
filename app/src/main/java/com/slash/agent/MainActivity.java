package com.slash.agent;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Chat-first Slash experience. The Android system owns status, navigation, keyboard, and permission UI. */
public final class MainActivity extends Activity implements ChatCoordinator.Listener, RecognitionListener {
    public static final String EXTRA_START_VOICE = "com.slash.agent.extra.START_VOICE";
    private static final int AUDIO_PERMISSION = 41;
    private static final int WHITE = Color.WHITE;
    private static final int INK = Color.rgb(13, 13, 13);
    private static final int TERTIARY = Color.rgb(243, 243, 243);
    private static final int MUTED = Color.rgb(143, 143, 143);

    private ChatCoordinator coordinator;
    private Typeface inter;
    private Typeface interSemiBold;
    private FrameLayout root;
    private LinearLayout messageList;
    private ScrollView messageScroll;
    private EditText composer;
    private ImageButton actionButton;
    private TextView title;
    private LinearLayout drawer;
    private View drawerScrim;
    private LinearLayout historyList;
    private SpeechRecognizer recognizer;
    private boolean voicePending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        coordinator = ((SlashApplication) getApplication()).coordinator();
        inter = Typeface.createFromAsset(getAssets(), "fonts/Inter-Regular.otf");
        interSemiBold = Typeface.createFromAsset(getAssets(), "fonts/Inter-SemiBold.otf");
        configureWindow();
        setContentView(buildUi());
        coordinator.addListener(this);
        renderCurrentChat();
        if (getIntent().getBooleanExtra(EXTRA_START_VOICE, false)) root.post(this::startVoiceInput);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_START_VOICE, false)) root.post(this::startVoiceInput);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(WHITE);
        window.setNavigationBarColor(WHITE);
        window.setNavigationBarDividerColor(Color.rgb(232, 232, 232));
        window.setDecorFitsSystemWindows(false);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(WHITE);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets system = insets.getInsets(WindowInsets.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(system.left, system.top, system.right, Math.max(system.bottom, ime.bottom));
            scrollToLatest();
            return insets;
        });

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(buildHeader(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true);
        messageScroll.setClipToPadding(false);
        messageScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        messageList.setPadding(dp(18), dp(16), dp(18), dp(16));
        messageScroll.addView(messageList, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(messageScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(buildComposer(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(Color.argb(80, 0, 0, 0));
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(view -> closeDrawer());
        root.addView(drawerScrim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        drawer = buildDrawer();
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(dp(304), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        root.addView(drawer, drawerParams);
        drawer.setVisibility(View.GONE);
        root.requestApplyInsets();
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), 0);
        header.setBackgroundColor(WHITE);

        TextView menu = textButton("☰", "Open chat history");
        menu.setTextSize(23);
        menu.setOnClickListener(view -> openDrawer());
        header.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));

        title = label("Slash", 16, INK, interSemiBold);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView newChat = textButton("＋", "Start a new chat");
        newChat.setTextSize(25);
        newChat.setOnClickListener(view -> createNewChat());
        header.addView(newChat, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return header;
    }

    private View buildComposer() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(dp(12), dp(8), dp(12), dp(8));
        area.setBackgroundColor(WHITE);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);

        ImageButton add = imageButton(R.drawable.ic_chat_add, "Start a new chat");
        add.setBackground(circle(TERTIARY));
        add.setPadding(dp(10), dp(10), dp(10), dp(10));
        add.setOnClickListener(view -> createNewChat());
        row.addView(add, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout shell = new LinearLayout(this);
        shell.setGravity(Gravity.CENTER_VERTICAL);
        shell.setMinimumHeight(dp(44));
        shell.setPadding(dp(16), dp(2), dp(6), dp(2));
        shell.setBackground(pill(TERTIARY, 1000));
        LinearLayout.LayoutParams shellParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        shellParams.leftMargin = dp(8);
        row.addView(shell, shellParams);

        composer = new EditText(this);
        composer.setTypeface(inter);
        composer.setTextSize(16);
        composer.setTextColor(INK);
        composer.setHintTextColor(MUTED);
        composer.setHint("Ask anything");
        composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setPadding(0, dp(8), dp(4), dp(8));
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setMinLines(1);
        composer.setMaxLines(5);
        composer.setMaxHeight(dp(116));
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        composer.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { submitComposer(UserTurn.Source.TEXT); return true; }
            return false;
        });
        composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { updateComposerAction(); }
            @Override public void afterTextChanged(Editable editable) { }
        });
        shell.addView(composer, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton mic = imageButton(R.drawable.ic_chat_mic, "Dictate a message");
        mic.setBackgroundColor(Color.TRANSPARENT);
        mic.setPadding(dp(6), dp(6), dp(6), dp(6));
        mic.setOnClickListener(view -> startVoiceInput());
        shell.addView(mic, new LinearLayout.LayoutParams(dp(36), dp(36)));

        actionButton = imageButton(R.drawable.ic_chat_voice, "Start voice input");
        actionButton.setBackground(circle(INK));
        actionButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        actionButton.setOnClickListener(view -> {
            if (composer.getText().toString().trim().isEmpty()) startVoiceInput();
            else submitComposer(UserTurn.Source.TEXT);
        });
        shell.addView(actionButton, new LinearLayout.LayoutParams(dp(32), dp(32)));

        area.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return area;
    }

    private LinearLayout buildDrawer() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackgroundColor(Color.rgb(247, 247, 248));
        panel.setElevation(dp(12));

        TextView brand = label("Slash", 22, INK, interSemiBold);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(12), 0, dp(12), 0);
        panel.addView(brand, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        TextView newChat = drawerAction("＋   New chat");
        newChat.setOnClickListener(view -> createNewChat());
        panel.addView(newChat, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        TextView historyTitle = label("Chats", 12, MUTED, interSemiBold);
        historyTitle.setPadding(dp(12), dp(18), dp(12), dp(8));
        panel.addView(historyTitle);

        ScrollView histories = new ScrollView(this);
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        histories.addView(historyList, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(histories, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView modelSetup = drawerAction("⚙   Local model setup");
        modelSetup.setOnClickListener(view -> startActivity(new Intent(this, ModelSetupActivity.class)));
        panel.addView(modelSetup, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        return panel;
    }

    private void renderCurrentChat() {
        String chatId = coordinator.activeChatId();
        title.setText(coordinator.repository().title(chatId));
        List<ChatMessage> messages = coordinator.repository().messages(chatId);
        messageList.removeAllViews();
        if (messages.isEmpty()) {
            TextView empty = label("What can I help with?", 24, INK, interSemiBold);
            empty.setGravity(Gravity.CENTER);
            empty.setMinHeight(Math.max(dp(280), root.getHeight() - dp(220)));
            messageList.addView(empty, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            for (ChatMessage message : messages) messageList.addView(messageView(message));
        }
        refreshHistory();
        scrollToLatest();
    }

    private View messageView(ChatMessage message) {
        LinearLayout row = new LinearLayout(this);
        boolean user = "user".equals(message.role);
        row.setGravity(user ? Gravity.END : Gravity.START);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView body = label(message.content, ChatMessage.AGENT_PROGRESS.equals(message.type) ? 14 : 16,
                ChatMessage.AGENT_PROGRESS.equals(message.type) ? Color.rgb(104, 104, 104) : INK, inter);
        body.setTextIsSelectable(!ChatMessage.AGENT_PROGRESS.equals(message.type));
        body.setLineSpacing(dp(3), 1f);
        body.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * (user ? 0.82f : 0.92f)));
        if (user) {
            body.setBackground(pill(TERTIARY, 18));
            body.setPadding(dp(14), dp(10), dp(14), dp(10));
        } else {
            body.setPadding(dp(2), dp(8), dp(2), dp(8));
        }
        row.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void refreshHistory() {
        historyList.removeAllViews();
        String active = coordinator.activeChatId();
        for (ChatSummary chat : coordinator.repository().listChats()) {
            TextView item = drawerAction(chat.title);
            if (chat.id.equals(active)) item.setBackground(pill(Color.rgb(232, 232, 232), 10));
            item.setOnClickListener(view -> { coordinator.openChat(chat.id); closeDrawer(); });
            historyList.addView(item, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        }
    }

    private void createNewChat() {
        hideKeyboard();
        composer.setText("");
        coordinator.newChat();
        closeDrawer();
        composer.requestFocus();
    }

    private void submitComposer(UserTurn.Source source) {
        String value = composer.getText().toString().trim();
        if (value.isEmpty()) return;
        composer.setText("");
        coordinator.submit(new UserTurn(source, value, coordinator.activeChatId(), System.currentTimeMillis()));
    }

    private void updateComposerAction() {
        boolean hasText = composer != null && !composer.getText().toString().trim().isEmpty();
        actionButton.setImageResource(hasText ? R.drawable.ic_chat_arrow_up : R.drawable.ic_chat_voice);
        if (hasText) actionButton.setColorFilter(Color.WHITE); else actionButton.clearColorFilter();
        actionButton.setContentDescription(hasText ? "Send message" : "Start voice input");
    }

    private void openDrawer() {
        hideKeyboard();
        refreshHistory();
        drawerScrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-drawer.getWidth());
        drawer.animate().translationX(0).setDuration(180).start();
    }

    private void closeDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        drawer.animate().translationX(-drawer.getWidth()).setDuration(160).withEndAction(() -> {
            drawer.setVisibility(View.GONE);
            drawerScrim.setVisibility(View.GONE);
        }).start();
    }

    private void startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePending = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            composer.setHint("Speech recognition isn't available");
            return;
        }
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        composer.setHint("Listening…");
        recognizer.startListening(request);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION && voicePending) {
            voicePending = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startVoiceInput();
        }
    }

    @Override public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        composer.setHint("Ask anything");
        if (matches == null || matches.isEmpty()) return;
        composer.setText(matches.get(0));
        composer.setSelection(composer.length());
        submitComposer(UserTurn.Source.VOICE);
    }

    @Override public void onPartialResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;
        composer.setText(matches.get(0));
        composer.setSelection(composer.length());
    }

    @Override public void onError(int error) { composer.setHint("Ask anything"); }
    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override public void onChatChanged(String chatId) {
        if (chatId.equals(coordinator.activeChatId())) renderCurrentChat();
        else refreshHistory();
    }

    @Override public void onBusyChanged(boolean busy) { }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        coordinator.removeListener(this);
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        super.onDestroy();
    }

    private void scrollToLatest() {
        if (messageScroll != null) messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(), 0);
        composer.clearFocus();
    }

    private TextView label(String value, float size, int color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView textButton(String value, String description) {
        TextView view = label(value, 18, INK, inter);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setBackgroundColor(Color.TRANSPARENT);
        return view;
    }

    private TextView drawerAction(String value) {
        TextView view = label(value, 15, INK, inter);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setSingleLine(true);
        view.setPadding(dp(12), 0, dp(12), 0);
        return view;
    }

    private ImageButton imageButton(int drawable, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawable);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setContentDescription(description);
        button.setFocusable(true);
        return button;
    }

    private GradientDrawable circle(int color) { return pill(color, 1000); }

    private GradientDrawable pill(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
