package com.slash.agent;

/** A normalized user input. Downstream reasoning is deliberately source-agnostic. */
public final class UserTurn {
    public enum Source { TEXT, VOICE }

    public final Source source;
    public final String text;
    public final String chatId;
    public final long timestamp;

    public UserTurn(Source source, String text, String chatId, long timestamp) {
        this.source = source;
        this.text = text == null ? "" : text.trim();
        this.chatId = chatId;
        this.timestamp = timestamp;
    }
}
