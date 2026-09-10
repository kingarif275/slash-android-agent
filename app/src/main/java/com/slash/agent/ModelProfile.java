package com.slash.agent;

public final class ModelProfile {
    public enum Tier { LITE, BALANCED, HIGH, CUSTOM }
    public enum ToolSupport { QWEN_NATIVE_EXPECTED, UNVERIFIED, UNSUPPORTED }
    public enum ModelVariant { ABLITERATED, CUSTOM }

    public final String id;
    public final String displayName;
    public final String repository;
    public final String revision;
    public final String fileName;
    public final String downloadUrl;
    public final String architecture;
    public final String quantization;
    public final int contextLength;
    public final String chatTemplate;
    public final ToolSupport toolCallingSupport;
    public final Tier tier;
    public final long expectedSizeBytes;
    public final String sha256;
    public final ModelVariant modelVariant;
    public final long recommendedRamBytes;

    public ModelProfile(String id, String displayName, String repository, String revision,
                        String fileName, String architecture, String quantization,
                        int contextLength, String chatTemplate, ToolSupport toolCallingSupport,
                        Tier tier, long expectedSizeBytes, String sha256,
                        ModelVariant modelVariant, long recommendedRamBytes) {
        this.id = id;
        this.displayName = displayName;
        this.repository = repository;
        this.revision = revision;
        this.fileName = fileName;
        this.downloadUrl = "https://huggingface.co/" + repository + "/resolve/" + revision
                + "/" + fileName + "?download=true";
        this.architecture = architecture;
        this.quantization = quantization;
        this.contextLength = contextLength;
        this.chatTemplate = chatTemplate;
        this.toolCallingSupport = toolCallingSupport;
        this.tier = tier;
        this.expectedSizeBytes = expectedSizeBytes;
        this.sha256 = sha256;
        this.modelVariant = modelVariant;
        this.recommendedRamBytes = recommendedRamBytes;
    }
}
