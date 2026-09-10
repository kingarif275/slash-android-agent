package com.slash.agent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Local source of truth for chats, messages, and cross-chat memory. */
public final class ChatRepository extends SQLiteOpenHelper {
    private static final String DATABASE = "slash.db";
    private static final int VERSION = 3;

    public ChatRepository(Context context) {
        super(context.getApplicationContext(), DATABASE, null, VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chats (id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, timestamp INTEGER NOT NULL, type TEXT NOT NULL, FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX messages_by_chat ON messages(chat_id, id)");
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL)");
        createAttachments(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE chats ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE chats ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) createAttachments(db);
    }

    private void createAttachments(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS attachments (id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER NOT NULL, chat_id TEXT NOT NULL, uri TEXT NOT NULL, display_name TEXT NOT NULL, mime_type TEXT NOT NULL, size INTEGER NOT NULL DEFAULT 0, extracted_text TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE, FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS attachments_by_message ON attachments(message_id, id)");
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    public synchronized String createChat() {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("title", "New chat");
        values.put("created_at", now);
        values.put("updated_at", now);
        getWritableDatabase().insertOrThrow("chats", null, values);
        return id;
    }

    public synchronized String latestOrCreateChat() {
        try (Cursor cursor = getReadableDatabase().query("chats", new String[]{"id"},
                "archived = 0", null, null, null, "pinned DESC, updated_at DESC", "1")) {
            if (cursor.moveToFirst()) return cursor.getString(0);
        }
        return createChat();
    }

    public synchronized boolean chatExists(String id) {
        if (id == null) return false;
        try (Cursor cursor = getReadableDatabase().query("chats", new String[]{"id"}, "id = ?", new String[]{id}, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    public synchronized List<ChatSummary> listChats() {
        return listChats(false);
    }

    public synchronized List<ChatSummary> listChats(boolean archived) {
        List<ChatSummary> chats = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("chats",
                new String[]{"id", "title", "created_at", "updated_at", "pinned", "archived"},
                "archived = ?", new String[]{archived ? "1" : "0"}, null, null,
                "pinned DESC, updated_at DESC")) {
            while (cursor.moveToNext()) chats.add(new ChatSummary(cursor.getString(0),
                    cursor.getString(1), cursor.getLong(2), cursor.getLong(3),
                    cursor.getInt(4) != 0, cursor.getInt(5) != 0));
        }
        return chats;
    }

    public synchronized ChatSummary summary(String chatId) {
        try (Cursor cursor = getReadableDatabase().query("chats",
                new String[]{"id", "title", "created_at", "updated_at", "pinned", "archived"},
                "id = ?", new String[]{chatId}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            return new ChatSummary(cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                    cursor.getLong(3), cursor.getInt(4) != 0, cursor.getInt(5) != 0);
        }
    }

    public synchronized String title(String chatId) {
        try (Cursor cursor = getReadableDatabase().query("chats", new String[]{"title"}, "id = ?", new String[]{chatId}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getString(0) : "Slash";
        }
    }

    public synchronized long appendMessage(String chatId, String role, String content, String type, long timestamp) {
        return appendMessage(chatId, role, content, type, timestamp, java.util.Collections.emptyList());
    }

    public synchronized long appendMessage(String chatId, String role, String content, String type,
            long timestamp, List<ChatAttachment> attachments) {
        if (!chatExists(chatId)) throw new IllegalArgumentException("Unknown chat: " + chatId);
        ContentValues message = new ContentValues();
        message.put("chat_id", chatId);
        message.put("role", role);
        message.put("content", content);
        message.put("timestamp", timestamp);
        message.put("type", type);
        long id = getWritableDatabase().insertOrThrow("messages", null, message);

        ContentValues chat = new ContentValues();
        chat.put("updated_at", timestamp);
        getWritableDatabase().update("chats", chat, "id = ?", new String[]{chatId});
        if ("user".equals(role)) setInitialTitle(chatId, content);
        if (attachments != null) {
            for (ChatAttachment attachment : attachments) {
                ContentValues values = new ContentValues();
                values.put("message_id", id);
                values.put("chat_id", chatId);
                values.put("uri", attachment.uri);
                values.put("display_name", attachment.displayName);
                values.put("mime_type", attachment.mimeType);
                values.put("size", attachment.size);
                values.put("extracted_text", attachment.extractedText);
                values.put("created_at", timestamp);
                getWritableDatabase().insertOrThrow("attachments", null, values);
            }
        }
        return id;
    }

    public synchronized List<ChatAttachment> attachments(long messageId) {
        List<ChatAttachment> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("attachments",
                new String[]{"id", "message_id", "uri", "display_name", "mime_type", "size", "extracted_text"},
                "message_id = ?", new String[]{Long.toString(messageId)}, null, null, "id ASC")) {
            while (cursor.moveToNext()) result.add(new ChatAttachment(cursor.getLong(0),
                    cursor.getLong(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                    cursor.getLong(5), cursor.getString(6)));
        }
        return result;
    }

    public synchronized void updateAttachmentText(long id, String extractedText) {
        ContentValues values = new ContentValues();
        values.put("extracted_text", extractedText == null ? "" : extractedText);
        getWritableDatabase().update("attachments", values, "id = ?", new String[]{Long.toString(id)});
    }

    public synchronized void renameChat(String chatId, String requestedTitle) {
        String title = requestedTitle == null ? "" : requestedTitle.replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) throw new IllegalArgumentException("Title cannot be empty");
        if (title.length() > 80) title = title.substring(0, 80).trim();
        ContentValues values = new ContentValues();
        values.put("title", title);
        getWritableDatabase().update("chats", values, "id = ?", new String[]{chatId});
    }

    public synchronized void setPinned(String chatId, boolean pinned) {
        ContentValues values = new ContentValues();
        values.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update("chats", values, "id = ?", new String[]{chatId});
    }

    public synchronized void setArchived(String chatId, boolean archived) {
        ContentValues values = new ContentValues();
        values.put("archived", archived ? 1 : 0);
        if (archived) values.put("pinned", 0);
        getWritableDatabase().update("chats", values, "id = ?", new String[]{chatId});
    }

    public synchronized void deleteChat(String chatId) {
        getWritableDatabase().delete("chats", "id = ?", new String[]{chatId});
    }

    private void setInitialTitle(String chatId, String content) {
        if (!"New chat".equals(title(chatId))) return;
        String clean = content.replaceAll("\\s+", " ").trim();
        if (clean.length() > 44) clean = clean.substring(0, 43).trim() + "…";
        if (clean.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("title", clean);
        getWritableDatabase().update("chats", values, "id = ?", new String[]{chatId});
    }

    public synchronized List<ChatMessage> messages(String chatId) {
        List<ChatMessage> messages = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"id", "chat_id", "role", "content", "timestamp", "type"}, "chat_id = ?", new String[]{chatId}, null, null, "id ASC")) {
            while (cursor.moveToNext()) messages.add(new ChatMessage(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getString(5)));
        }
        return messages;
    }

    public synchronized void updateMessageContent(long id, String content) {
        ContentValues values = new ContentValues();
        values.put("content", content);
        getWritableDatabase().update("messages", values, "id = ?", new String[]{Long.toString(id)});
    }

    public synchronized List<ChatMessage> recentModelMessages(String chatId, int limit) {
        List<ChatMessage> reversed = new ArrayList<>();
        String selection = "chat_id = ? AND type IN (?, ?)";
        String[] arguments = {chatId, ChatMessage.USER_TEXT, ChatMessage.ASSISTANT_TEXT};
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"id", "chat_id", "role", "content", "timestamp", "type"}, selection, arguments, null, null, "id DESC", Integer.toString(limit))) {
            while (cursor.moveToNext()) reversed.add(new ChatMessage(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getString(5)));
        }
        List<ChatMessage> ordered = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) ordered.add(reversed.get(i));
        return ordered;
    }

    public synchronized void remember(String content) {
        String clean = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("content", clean);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("memories", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized List<String> relevantMemories(String query, int limit) {
        Set<String> terms = tokens(query);
        List<ScoredMemory> scored = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("memories", new String[]{"content"}, null, null, null, null, "created_at DESC", "100")) {
            while (cursor.moveToNext()) {
                String content = cursor.getString(0);
                Set<String> memoryTerms = tokens(content);
                int score = 0;
                for (String term : terms) if (memoryTerms.contains(term)) score++;
                if (score > 0 || terms.isEmpty()) scored.add(new ScoredMemory(content, score));
            }
        }
        scored.sort((left, right) -> Integer.compare(right.score, left.score));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) result.add(scored.get(i).content);
        return result;
    }

    private Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null) return result;
        for (String word : text.toLowerCase(Locale.US).split("[^a-z0-9]+")) if (word.length() > 2) result.add(word);
        return result;
    }

    private static final class ScoredMemory {
        final String content;
        final int score;
        ScoredMemory(String content, int score) { this.content = content; this.score = score; }
    }
}
