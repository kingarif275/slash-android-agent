package com.slash.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A normalized user input. Downstream reasoning is deliberately source-agnostic. */
public final class UserTurn {
    public enum Source { TEXT, VOICE }

    public final Source source;
    public final String text;
    public final String chatId;
    public final long timestamp;
    public final List<ChatAttachment> attachments;

    public UserTurn(Source source, String text, String chatId, long timestamp) {
        this(source, text, chatId, timestamp, Collections.emptyList());
    }

    public UserTurn(Source source, String text, String chatId, long timestamp,
            List<ChatAttachment> attachments) {
        this.source = source;
        this.text = text == null ? "" : text.trim();
        this.chatId = chatId;
        this.timestamp = timestamp;
        this.attachments = Collections.unmodifiableList(new ArrayList<>(
                attachments == null ? Collections.emptyList() : attachments));
    }
}
