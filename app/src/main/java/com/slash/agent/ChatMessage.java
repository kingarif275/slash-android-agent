package com.slash.agent;

public final class ChatMessage {
    public static final String USER_TEXT = "USER_TEXT";
    public static final String ASSISTANT_TEXT = "ASSISTANT_TEXT";
    public static final String AGENT_PROGRESS = "AGENT_PROGRESS";
    public static final String ERROR = "ERROR";

    public final long id;
    public final String chatId;
    public final String role;
    public final String content;
    public final long timestamp;
    public final String type;

    public ChatMessage(long id, String chatId, String role, String content, long timestamp, String type) {
        this.id = id;
        this.chatId = chatId;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
        this.type = type;
    }
}
