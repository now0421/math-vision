package com.automanim.model;

import com.automanim.config.WorkflowConfig;

/**
 * Result from the render stage (Stage 3).
 * Captures render success/failure, video output path, retry attempts,
 * and the final (possibly AI-fixed) code.
 */
public class RenderResult {

    private boolean success;
    private String finalCode;
    private String sceneName;
    private String videoPath;
    private String artifactPath;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private String artifactType;
    private String geometryPath;
    private int attempts;
    private String lastError;
    private int toolCalls;
    private double executionTimeSeconds;

    public RenderResult() {}

    public static RenderResult failed(String sceneName, String error) {
        RenderResult r = new RenderResult();
        r.success = false;
        r.sceneName = sceneName;
        r.lastError = error;
        return r;
    }

    public static RenderResult skipped(String sceneName, String reason) {
        RenderResult r = new RenderResult();
        r.success = false;
        r.sceneName = sceneName;
        r.lastError = reason;
        r.attempts = 0;
        return r;
    }

    // ---- Getters / Setters ----

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getFinalCode() { return finalCode; }
    public void setFinalCode(String finalCode) { this.finalCode = finalCode; }

    public String getSceneName() { return sceneName; }
    public void setSceneName(String sceneName) { this.sceneName = sceneName; }

    public String getVideoPath() { return videoPath; }
    public void setVideoPath(String videoPath) { this.videoPath = videoPath; }

    public String getArtifactPath() { return artifactPath; }
    public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }

    public String getOutputTarget() { return outputTarget; }
    public void setOutputTarget(String outputTarget) { this.outputTarget = outputTarget; }

    public String getArtifactType() { return artifactType; }
    public void setArtifactType(String artifactType) { this.artifactType = artifactType; }

    public String getGeometryPath() { return geometryPath; }
    public void setGeometryPath(String geometryPath) { this.geometryPath = geometryPath; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }

    public double getExecutionTimeSeconds() { return executionTimeSeconds; }
    public void setExecutionTimeSeconds(double executionTimeSeconds) { this.executionTimeSeconds = executionTimeSeconds; }

    public boolean isGeoGebraTarget() {
        return WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equalsIgnoreCase(outputTarget);
    }
}
