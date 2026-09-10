package com.slash.agent;

public final class ChatSummary {
    public final String id;
    public final String title;
    public final long createdAt;
    public final long updatedAt;
    public final boolean pinned;
    public final boolean archived;

    public ChatSummary(String id, String title, long createdAt, long updatedAt,
            boolean pinned, boolean archived) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.pinned = pinned;
        this.archived = archived;
    }
}
