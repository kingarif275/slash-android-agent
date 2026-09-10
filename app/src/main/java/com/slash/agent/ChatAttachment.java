package com.slash.agent;

/** Persisted metadata for one attachment associated with a user message. */
public final class ChatAttachment {
    public final long id;
    public final long messageId;
    public final String uri;
    public final String displayName;
    public final String mimeType;
    public final long size;
    public final String extractedText;

    public ChatAttachment(long id, long messageId, String uri, String displayName,
            String mimeType, long size, String extractedText) {
        this.id = id;
        this.messageId = messageId;
        this.uri = uri == null ? "" : uri;
        this.displayName = displayName == null ? "Attachment" : displayName;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.size = Math.max(0, size);
        this.extractedText = extractedText == null ? "" : extractedText;
    }

    public static ChatAttachment pending(String uri, String displayName, String mimeType, long size) {
        return new ChatAttachment(-1, -1, uri, displayName, mimeType, size, "");
    }
}
