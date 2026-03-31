package com.automanim.model;

import com.automanim.config.WorkflowConfig;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result from the code generation stage (Stage 2).
 * Carries the generated backend code/artifact text and metadata.
 */
public class CodeResult {

    @JsonProperty("code")
    @JsonAlias("manimCode")
    private String code;
    private String sceneName;
    private String description;
    private String targetConcept;
    private String targetDescription;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private String artifactFormat = "python";
    private int toolCalls;
    private double executionTimeSeconds;

    public CodeResult() {}

    public CodeResult(String code, String sceneName, String description, String targetConcept) {
        this(code, sceneName, description, targetConcept, "");
    }

    public CodeResult(String code, String sceneName, String description,
                      String targetConcept, String targetDescription) {
        this.code = code;
        this.sceneName = sceneName;
        this.description = description;
        this.targetConcept = targetConcept;
        this.targetDescription = targetDescription;
    }

    public int codeLineCount() {
        if (code == null || code.isBlank()) return 0;
        return code.split("\n").length;
    }

    public boolean hasCode() {
        return code != null && !code.isBlank();
    }

    // ---- Getters / Setters ----

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    @Deprecated
    @JsonIgnore
    public String getManimCode() { return code; }

    @Deprecated
    @JsonIgnore
    public void setManimCode(String manimCode) { this.code = manimCode; }

    public String getSceneName() { return sceneName; }
    public void setSceneName(String sceneName) { this.sceneName = sceneName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTargetConcept() { return targetConcept; }
    public void setTargetConcept(String targetConcept) { this.targetConcept = targetConcept; }

    public String getTargetDescription() { return targetDescription; }
    public void setTargetDescription(String targetDescription) { this.targetDescription = targetDescription; }

    public String getOutputTarget() { return outputTarget; }
    public void setOutputTarget(String outputTarget) { this.outputTarget = outputTarget; }

    public String getArtifactFormat() { return artifactFormat; }
    public void setArtifactFormat(String artifactFormat) { this.artifactFormat = artifactFormat; }

    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }

    public double getExecutionTimeSeconds() { return executionTimeSeconds; }
    public void setExecutionTimeSeconds(double executionTimeSeconds) { this.executionTimeSeconds = executionTimeSeconds; }

    public boolean isGeoGebraTarget() {
        return WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equalsIgnoreCase(outputTarget);
    }

    public boolean isManimTarget() {
        return !isGeoGebraTarget();
    }
}
