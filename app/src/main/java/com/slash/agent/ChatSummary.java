package com.slash.agent;

public final class ChatSummary {
    public final String id;
    public final String title;
    public final long createdAt;
    public final long updatedAt;

    public ChatSummary(String id, String title, long createdAt, long updatedAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
