package com.mathvision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured output for Stage 1c storyboard validation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoryboardValidationReport {

    @JsonProperty("validated")
    private boolean validated;

    @JsonProperty("passed")
    private boolean passed;

    @JsonProperty("output_target")
    private String outputTarget;

    @JsonProperty("scene_count")
    private int sceneCount;

    @JsonProperty("initial_issue_count")
    private int initialIssueCount;

    @JsonProperty("initial_issues")
    private List<String> initialIssues = new ArrayList<>();

    @JsonProperty("fix_attempted")
    private boolean fixAttempted;

    @JsonProperty("fix_applied")
    private boolean fixApplied;

    @JsonProperty("resolved_issue_count")
    private int resolvedIssueCount;

    @JsonProperty("final_issue_count")
    private int finalIssueCount;

    @JsonProperty("final_issues")
    private List<String> finalIssues = new ArrayList<>();

    @JsonProperty("message")
    private String message;

    public boolean isValidated() { return validated; }
    public void setValidated(boolean validated) { this.validated = validated; }

    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }

    public String getOutputTarget() { return outputTarget; }
    public void setOutputTarget(String outputTarget) { this.outputTarget = outputTarget; }

    public int getSceneCount() { return sceneCount; }
    public void setSceneCount(int sceneCount) { this.sceneCount = sceneCount; }

    public int getInitialIssueCount() { return initialIssueCount; }
    public void setInitialIssueCount(int initialIssueCount) { this.initialIssueCount = initialIssueCount; }

    public List<String> getInitialIssues() { return initialIssues; }
    public void setInitialIssues(List<String> initialIssues) {
        this.initialIssues = initialIssues != null ? initialIssues : new ArrayList<>();
    }

    public boolean isFixAttempted() { return fixAttempted; }
    public void setFixAttempted(boolean fixAttempted) { this.fixAttempted = fixAttempted; }

    public boolean isFixApplied() { return fixApplied; }
    public void setFixApplied(boolean fixApplied) { this.fixApplied = fixApplied; }

    public int getResolvedIssueCount() { return resolvedIssueCount; }
    public void setResolvedIssueCount(int resolvedIssueCount) { this.resolvedIssueCount = resolvedIssueCount; }

    public int getFinalIssueCount() { return finalIssueCount; }
    public void setFinalIssueCount(int finalIssueCount) { this.finalIssueCount = finalIssueCount; }

    public List<String> getFinalIssues() { return finalIssues; }
    public void setFinalIssues(List<String> finalIssues) {
        this.finalIssues = finalIssues != null ? finalIssues : new ArrayList<>();
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
