package com.automanim.config;

/**
 * Central configuration for the pipeline.
 * Immutable after construction — use the builder for clarity.
 */
public class PipelineConfig {

    // Exploration
    private final int maxDepth;
    private final int minDepth;

    // Enrichment
    private final boolean parallelMathEnrichment;
    private final boolean parallelVisualDesign;
    private final int maxConcurrent;

    // Code generation
    private final int codeGenMaxRetries;

    // Render
    private final boolean renderEnabled;
    private final String renderQuality;   // "low", "medium", "high"
    private final int renderMaxRetries;

    // AI
    private final String aiProvider;       // "kimi" or "gemini"

    private PipelineConfig(Builder b) {
        this.maxDepth = b.maxDepth;
        this.minDepth = b.minDepth;
        this.parallelMathEnrichment = b.parallelMathEnrichment;
        this.parallelVisualDesign = b.parallelVisualDesign;
        this.maxConcurrent = b.maxConcurrent;
        this.codeGenMaxRetries = b.codeGenMaxRetries;
        this.renderEnabled = b.renderEnabled;
        this.renderQuality = b.renderQuality;
        this.renderMaxRetries = b.renderMaxRetries;
        this.aiProvider = b.aiProvider;
    }

    public static Builder builder() { return new Builder(); }

    // ---- Getters ----

    public int getMaxDepth() { return maxDepth; }
    public int getMinDepth() { return minDepth; }
    public boolean isParallelMathEnrichment() { return parallelMathEnrichment; }
    public boolean isParallelVisualDesign() { return parallelVisualDesign; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public int getCodeGenMaxRetries() { return codeGenMaxRetries; }
    public boolean isRenderEnabled() { return renderEnabled; }
    public String getRenderQuality() { return renderQuality; }
    public int getRenderMaxRetries() { return renderMaxRetries; }
    public String getAiProvider() { return aiProvider; }

    public static class Builder {
        private int maxDepth = 4;
        private int minDepth = 2;
        private boolean parallelMathEnrichment = true;
        private boolean parallelVisualDesign = true;
        private int maxConcurrent = 4;
        private int codeGenMaxRetries = 2;
        private boolean renderEnabled = true;
        private String renderQuality = "low";
        private int renderMaxRetries = 4;
        private String aiProvider = "kimi";

        public Builder maxDepth(int v) { this.maxDepth = v; return this; }
        public Builder minDepth(int v) { this.minDepth = v; return this; }
        public Builder parallelMathEnrichment(boolean v) { this.parallelMathEnrichment = v; return this; }
        public Builder parallelVisualDesign(boolean v) { this.parallelVisualDesign = v; return this; }
        public Builder maxConcurrent(int v) { this.maxConcurrent = v; return this; }
        public Builder codeGenMaxRetries(int v) { this.codeGenMaxRetries = v; return this; }
        public Builder renderEnabled(boolean v) { this.renderEnabled = v; return this; }
        public Builder renderQuality(String v) { this.renderQuality = v; return this; }
        public Builder renderMaxRetries(int v) { this.renderMaxRetries = v; return this; }
        public Builder aiProvider(String v) { this.aiProvider = v; return this; }

        public PipelineConfig build() { return new PipelineConfig(this); }
    }
}
