package com.mathvision.model;

import com.mathvision.config.WorkflowConfig;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Result from the code generation stage (Stage 2).
 * Carries the generated backend code/artifact text and metadata.
 */
public class CodeResult {

    @JsonProperty("generatedCode")
    private String generatedCode;
    private String headerCode;
    private List<SceneCodeEntry> sceneEntries = new ArrayList<>();
    private String sceneName;
    private String description;
    private String targetConcept;
    private String targetDescription;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private String artifactFormat = "python";
    private int toolCalls;
    private double executionTimeSeconds;

    public CodeResult() {}

    public CodeResult(String generatedCode, String sceneName, String description, String targetConcept) {
        this(generatedCode, sceneName, description, targetConcept, "");
    }

    public CodeResult(String generatedCode, String sceneName, String description,
                      String targetConcept, String targetDescription) {
        this.generatedCode = generatedCode;
        this.sceneName = sceneName;
        this.description = description;
        this.targetConcept = targetConcept;
        this.targetDescription = targetDescription;
    }

    public int codeLineCount() {
        if (generatedCode == null || generatedCode.isBlank()) return 0;
        return generatedCode.split("\n").length;
    }

    public boolean hasCode() {
        return generatedCode != null && !generatedCode.isBlank();
    }

    // ---- Getters / Setters ----

    public String getGeneratedCode() { return generatedCode; }
    public void setGeneratedCode(String generatedCode) { this.generatedCode = generatedCode; }

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

    public String getHeaderCode() { return headerCode; }
    public void setHeaderCode(String headerCode) { this.headerCode = headerCode; }

    public List<SceneCodeEntry> getSceneEntries() { return sceneEntries; }
    public void setSceneEntries(List<SceneCodeEntry> sceneEntries) {
        this.sceneEntries = sceneEntries != null ? sceneEntries : new ArrayList<>();
    }

    public void appendSceneEntry(SceneCodeEntry entry) {
        this.sceneEntries.add(entry);
        rebuildGeneratedCode();
    }

    public void replaceSceneCode(int index, String newCode) {
        if (index >= 0 && index < sceneEntries.size()) {
            sceneEntries.get(index).setSceneCode(newCode);
            rebuildGeneratedCode();
        }
    }

    public void rebuildGeneratedCode() {
        if (headerCode == null || headerCode.isBlank()) {
            return;
        }
        StringBuilder sb = new StringBuilder(headerCode);
        for (SceneCodeEntry entry : sceneEntries) {
            if (entry.getSceneCode() != null && !entry.getSceneCode().isBlank()) {
                sb.append("\n\n").append(entry.getSceneCode());
            }
        }
        this.generatedCode = sb.toString();
    }

    public boolean isGeoGebraTarget() {
        return WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equalsIgnoreCase(outputTarget);
    }

    public boolean isManimTarget() {
        return !isGeoGebraTarget();
    }
}
