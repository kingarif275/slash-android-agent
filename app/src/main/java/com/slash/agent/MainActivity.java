package com.slash.agent;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
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
import android.util.Log;
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
    private static final int NOTIFICATION_PERMISSION = 42;
    private static final int PICK_DOCUMENT = 42;
    private static final int PICK_IMAGE = 43;
    private static final int MAX_ATTACHMENTS = 4;
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
    private LinearLayout attachmentTray;
    private ImageButton actionButton;
    private TextView title;
    private LinearLayout drawer;
    private View drawerScrim;
    private LinearLayout historyList;
    private TextView archiveToggle;
    private SpeechRecognizer recognizer;
    private boolean voicePending;
    private boolean conversationSessionActive;
    private ConversationState conversationState = ConversationState.IDLE;
    private boolean oneShotVoice;
    private boolean showingArchived;
    private final List<ChatAttachment> pendingAttachments = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        coordinator = ((SlashApplication) getApplication()).coordinator();
        inter = Typeface.createFromAsset(getAssets(), "fonts/Inter-Regular.otf");
        interSemiBold = Typeface.createFromAsset(getAssets(), "fonts/Inter-SemiBold.otf");
        configureWindow();
        setContentView(buildUi());
        coordinator.addListener(this);
        renderCurrentChat();
        requestNotificationPermissionIfNeeded();
        if (getIntent().getBooleanExtra(EXTRA_START_VOICE, false)) root.post(this::startVoiceInput);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
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
        messageScroll.setFocusable(false);
        messageScroll.setFocusableInTouchMode(false);
        messageScroll.setFillViewport(true);
        messageScroll.setClipToPadding(false);
        messageScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        messageList.setFocusable(false);
        messageList.setFocusableInTouchMode(false);
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
        title.setContentDescription("Rename current chat");
        title.setOnClickListener(view -> showRenameDialog(coordinator.activeChatId()));
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

        attachmentTray = new LinearLayout(this);
        attachmentTray.setOrientation(LinearLayout.VERTICAL);
        attachmentTray.setVisibility(View.GONE);
        area.addView(attachmentTray, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);

        ImageButton add = imageButton(R.drawable.ic_chat_add, "Attach files or images");
        add.setBackground(circle(TERTIARY));
        add.setPadding(dp(10), dp(10), dp(10), dp(10));
        add.setOnClickListener(view -> showAttachmentMenu());
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
        composer.setFocusable(true);
        composer.setFocusableInTouchMode(true);
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
        mic.setOnClickListener(view -> startVoiceInput(false));
        shell.addView(mic, new LinearLayout.LayoutParams(dp(36), dp(36)));

        actionButton = imageButton(R.drawable.ic_chat_voice, "Start voice input");
        actionButton.setBackground(circle(INK));
        actionButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        actionButton.setOnClickListener(view -> {
            if (conversationSessionActive) { stopConversation(); return; }
            if (composer.getText().toString().trim().isEmpty() && pendingAttachments.isEmpty()) {
                if (!coordinator.cloudAiSettings().liveAvailable()) {
                    composer.setHint("Selected models do not support Live mode");
                    return;
                }
                startVoiceInput(true);
            }
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
        histories.setFocusable(false);
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        historyList.setFocusable(false);
        histories.addView(historyList, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(histories, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        archiveToggle = drawerAction("▣   Archived chats");
        archiveToggle.setOnClickListener(view -> {
            showingArchived = !showingArchived;
            refreshHistory();
        });
        panel.addView(archiveToggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        TextView modelSetup = drawerAction("⚙   AI model setup");
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

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(user ? Gravity.END : Gravity.START);

        boolean progress = ChatMessage.AGENT_PROGRESS.equals(message.type);
        String summary = message.content;
        String details = "";
        int detailsMarker = summary.indexOf("\n\n[DETAILS]\n");
        if (progress && detailsMarker >= 0) {
            details = summary.substring(detailsMarker + "\n\n[DETAILS]\n".length());
            summary = summary.substring(0, detailsMarker);
        }
        final String collapsedSummary = summary;
        TextView body = label(summary, progress ? 14 : 16,
                progress ? Color.rgb(104, 104, 104) : INK, inter);
        body.setTextIsSelectable(!progress);
        // setTextIsSelectable makes TextView keyboard-focusable. During streaming re-renders that
        // allowed a message to become the IME target instead of the persistent composer.
        body.setFocusable(false);
        body.setFocusableInTouchMode(false);
        body.setLineSpacing(dp(3), 1f);
        body.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * (user ? 0.82f : 0.92f)));
        if (user) {
            body.setBackground(pill(TERTIARY, 18));
            body.setPadding(dp(14), dp(10), dp(14), dp(10));
        } else {
            body.setPadding(dp(2), dp(8), dp(2), dp(8));
        }
        content.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (progress && !details.isEmpty()) {
            TextView detailView = label("▾  " + details, 12, Color.rgb(92, 92, 92), inter);
            detailView.setVisibility(View.GONE);
            detailView.setPadding(dp(10), dp(8), dp(10), dp(8));
            detailView.setBackground(pill(Color.rgb(247, 247, 247), 10));
            body.setOnClickListener(view -> {
                boolean expanded = detailView.getVisibility() == View.VISIBLE;
                detailView.setVisibility(expanded ? View.GONE : View.VISIBLE);
                body.setText(collapsedSummary + (expanded ? "  ›" : "  ‹"));
            });
            content.addView(detailView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        for (ChatAttachment attachment : coordinator.repository().attachments(message.id)) {
            TextView card = label("↳  " + attachment.displayName + "\n    " + attachment.mimeType,
                    12, Color.rgb(88, 88, 88), inter);
            card.setPadding(dp(10), dp(8), dp(10), dp(8));
            card.setBackground(pill(Color.rgb(236, 236, 236), 12));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.topMargin = dp(5);
            content.addView(card, cardParams);
        }
        row.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void refreshHistory() {
        historyList.removeAllViews();
        archiveToggle.setText(showingArchived ? "‹   Back to chats" : "▣   Archived chats");
        String active = coordinator.activeChatId();
        List<ChatSummary> chats = coordinator.repository().listChats(showingArchived);
        if (chats.isEmpty()) {
            TextView empty = label(showingArchived ? "No archived chats" : "No chats yet",
                    14, MUTED, inter);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            historyList.addView(empty);
            return;
        }
        for (ChatSummary chat : chats) {
            TextView item = drawerAction((chat.pinned ? "●  " : "") + chat.title);
            if (chat.id.equals(active)) item.setBackground(pill(Color.rgb(232, 232, 232), 10));
            item.setOnClickListener(view -> { coordinator.openChat(chat.id); closeDrawer(); });
            item.setOnLongClickListener(view -> {
                showChatActions(chat);
                return true;
            });
            historyList.addView(item, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        }
    }

    private void createNewChat() {
        hideKeyboard();
        composer.setText("");
        pendingAttachments.clear();
        renderPendingAttachments();
        coordinator.newChat();
        closeDrawer();
    }

    private void submitComposer(UserTurn.Source source) {
        String value = composer.getText().toString().trim();
        if (value.isEmpty() && pendingAttachments.isEmpty()) return;
        List<ChatAttachment> attachments = new ArrayList<>(pendingAttachments);
        composer.setText("");
        pendingAttachments.clear();
        renderPendingAttachments();
        coordinator.submit(new UserTurn(source, value, coordinator.activeChatId(),
                System.currentTimeMillis(), attachments));
    }

    private void updateComposerAction() {
        boolean hasContent = (composer != null && !composer.getText().toString().trim().isEmpty())
                || !pendingAttachments.isEmpty();
        actionButton.setImageResource(hasContent ? R.drawable.ic_chat_arrow_up : R.drawable.ic_chat_voice);
        if (hasContent) actionButton.setColorFilter(Color.WHITE); else actionButton.clearColorFilter();
        actionButton.setContentDescription(hasContent ? "Send message" : (coordinator.cloudAiSettings().liveAvailable()
                ? "Start Live Conversation" : "Live unavailable for selected models"));
    }

    private void showAttachmentMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Attach content")
                .setItems(new String[]{"Files and documents", "Images"}, (dialog, which) -> {
                    if (which == 0) launchDocumentPicker(); else launchImagePicker();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchDocumentPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, PICK_DOCUMENT);
    }

    private void launchImagePicker() {
        Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        if (picker.resolveActivity(getPackageManager()) == null) {
            picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        startActivityForResult(picker, PICK_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != PICK_DOCUMENT && requestCode != PICK_IMAGE)
                || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (pendingAttachments.size() >= MAX_ATTACHMENTS) {
            new AlertDialog.Builder(this).setMessage("You can attach up to four items per message.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) { }
        pendingAttachments.add(attachmentMetadata(uri));
        renderPendingAttachments();
    }

    private ChatAttachment attachmentMetadata(Uri uri) {
        String name = uri.getLastPathSegment();
        long size = 0;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
            }
        } catch (RuntimeException ignored) { }
        String mime = getContentResolver().getType(uri);
        return ChatAttachment.pending(uri.toString(), name == null ? "Attachment" : name,
                mime == null ? "application/octet-stream" : mime, size);
    }

    private void renderPendingAttachments() {
        if (attachmentTray == null) return;
        attachmentTray.removeAllViews();
        attachmentTray.setVisibility(pendingAttachments.isEmpty() ? View.GONE : View.VISIBLE);
        for (ChatAttachment attachment : new ArrayList<>(pendingAttachments)) {
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(12), dp(7), dp(8), dp(7));
            chip.setBackground(pill(Color.rgb(236, 236, 236), 12));

            TextView text = label(attachment.displayName + "  ·  " + attachment.mimeType,
                    12, INK, inter);
            text.setSingleLine(true);
            chip.addView(text, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView remove = textButton("×", "Remove " + attachment.displayName);
            remove.setTextSize(20);
            remove.setOnClickListener(view -> {
                pendingAttachments.remove(attachment);
                renderPendingAttachments();
            });
            chip.addView(remove, new LinearLayout.LayoutParams(dp(32), dp(32)));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(52), 0, dp(8), dp(6));
            attachmentTray.addView(chip, params);
        }
        if (actionButton != null) updateComposerAction();
    }

    private void showChatActions(ChatSummary chat) {
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(12), dp(18), dp(22));
        sheet.setBackground(pill(WHITE, 24));

        TextView heading = label(chat.title, 16, INK, interSemiBold);
        heading.setPadding(dp(14), dp(12), dp(14), dp(12));
        sheet.addView(heading);
        sheet.addView(sheetAction(chat.pinned ? "Unpin" : "Pin", INK, () -> {
            dialog.dismiss();
            coordinator.setPinned(chat.id, !chat.pinned);
        }));
        sheet.addView(sheetAction(chat.archived ? "Unarchive" : "Archive", INK, () -> {
            dialog.dismiss();
            coordinator.setArchived(chat.id, !chat.archived);
            refreshHistory();
        }));
        sheet.addView(sheetAction("Rename", INK, () -> {
            dialog.dismiss();
            showRenameDialog(chat.id);
        }));
        sheet.addView(sheetAction("Delete", Color.rgb(190, 42, 42), () -> {
            dialog.dismiss();
            confirmDelete(chat);
        }));

        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(0.35f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnShowListener(ignored -> {
            Window shown = dialog.getWindow();
            if (shown != null) shown.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        });
        dialog.show();
    }

    private View sheetAction(String label, int color, Runnable action) {
        TextView view = this.label(label, 16, color, inter);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setMinHeight(dp(52));
        view.setBackgroundColor(Color.TRANSPARENT);
        view.setOnClickListener(ignored -> action.run());
        return view;
    }

    private void showRenameDialog(String chatId) {
        ChatSummary summary = coordinator.repository().summary(chatId);
        if (summary == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(summary.title);
        input.setSelection(input.length());
        int horizontal = dp(22);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(horizontal, 0, horizontal, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Rename chat")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String title = input.getText().toString().replaceAll("\\s+", " ").trim();
                    if (title.isEmpty()) {
                        input.setError("Enter a title");
                        return;
                    }
                    coordinator.renameChat(chatId, title);
                    dialog.dismiss();
                }));
        dialog.getWindow();
        dialog.show();
        input.requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void confirmDelete(ChatSummary chat) {
        new AlertDialog.Builder(this)
                .setTitle("Delete chat?")
                .setMessage("This permanently deletes “" + chat.title + "” and its messages.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    coordinator.deleteChat(chat.id);
                    closeDrawer();
                })
                .show();
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

    private void startVoiceInput() { startVoiceInput(true); }

    private void startVoiceInput(boolean persistent) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePending = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            composer.setHint("Speech recognition isn't available");
            return;
        }
        oneShotVoice = !persistent;
        conversationSessionActive = persistent;
        transitionConversation(ConversationState.STARTING);
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        composer.setHint("Listening…");
        transitionConversation(ConversationState.LISTENING);
        if (persistent) {
            actionButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            actionButton.setContentDescription("Stop Conversation");
        }
        recognizer.startListening(request);
    }

    private void stopConversation() {
        Log.i("SlashConversation", "STOP_REQUESTED");
        conversationSessionActive = false;
        transitionConversation(ConversationState.STOPPING);
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        composer.setHint("Ask anything");
        actionButton.setImageResource(R.drawable.ic_chat_voice);
        actionButton.setContentDescription("Start Conversation");
        transitionConversation(ConversationState.IDLE);
        Log.i("SlashConversation", "SESSION_ENDED");
    }

    private void transitionConversation(ConversationState next) {
        conversationState = next;
        Log.i("SlashConversation", "STATE " + next.name());
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
        if (!conversationSessionActive && !oneShotVoice) return;
        transitionConversation(ConversationState.THINKING);
        if (matches == null || matches.isEmpty()) { if (conversationSessionActive) restartConversationListening(); return; }
        composer.setText(matches.get(0));
        composer.setSelection(composer.length());
        submitComposer(UserTurn.Source.VOICE);
        if (conversationSessionActive) restartConversationListening();
        else {
            oneShotVoice = false;
            transitionConversation(ConversationState.IDLE);
            composer.setHint("Ask anything");
        }
    }

    @Override public void onPartialResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;
        composer.setText(matches.get(0));
        composer.setSelection(composer.length());
    }

    private void restartConversationListening() {
        if (!conversationSessionActive) return;
        root.postDelayed(this::startVoiceInput, 350);
    }

    @Override public void onError(int error) {
        if (conversationSessionActive) { Log.w("SlashConversation", "RECOGNITION_ERROR " + error); restartConversationListening(); }
        else if (oneShotVoice) { oneShotVoice = false; composer.setHint("Ask anything"); }
        else composer.setHint("Ask anything");
    }
    @Override public void onReadyForSpeech(Bundle params) { if (conversationSessionActive) transitionConversation(ConversationState.LISTENING); }
    @Override public void onBeginningOfSpeech() { if (conversationSessionActive) Log.i("SlashConversation", "USER_SPEECH_STARTED"); }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { if (conversationSessionActive) Log.i("SlashConversation", "USER_SPEECH_ENDED"); }
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

    private enum ConversationState { IDLE, STARTING, LISTENING, THINKING, SPEAKING, AGENT_WORKING, INTERRUPTED, STOPPING }

    private void scrollToLatest() {
        if (messageScroll != null) messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(), 0);
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
