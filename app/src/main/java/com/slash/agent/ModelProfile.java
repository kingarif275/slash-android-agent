package com.slash.agent;

public final class ModelProfile {
    public enum Tier { LITE, BALANCED, CUSTOM }
    public enum ToolSupport { QWEN_NATIVE_EXPECTED, UNVERIFIED, UNSUPPORTED }

    public final String id;
    public final String displayName;
    public final String fileName;
    public final String downloadUrl;
    public final String architecture;
    public final int contextLength;
    public final String chatTemplate;
    public final ToolSupport toolCallingSupport;
    public final Tier tier;
    public final long minimumBytes;
    public final long recommendedRamBytes;

    public ModelProfile(String id, String displayName, String fileName, String downloadUrl,
                        String architecture, int contextLength, String chatTemplate,
                        ToolSupport toolCallingSupport, Tier tier, long minimumBytes,
                        long recommendedRamBytes) {
        this.id = id;
        this.displayName = displayName;
        this.fileName = fileName;
        this.downloadUrl = downloadUrl;
        this.architecture = architecture;
        this.contextLength = contextLength;
        this.chatTemplate = chatTemplate;
        this.toolCallingSupport = toolCallingSupport;
        this.tier = tier;
        this.minimumBytes = minimumBytes;
        this.recommendedRamBytes = recommendedRamBytes;
    }
}
