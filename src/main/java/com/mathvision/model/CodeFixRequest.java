package com.mathvision.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared input for the routed code-fix node.
 */
public class CodeFixRequest {

    private CodeFixSource source;
    private String returnAction;
    private String generatedCode;
    private String errorReason;
    private String targetConcept;
    private String targetDescription;
    private String sceneName;
    private String expectedSceneName;
    private String storyboardJson;
    private String staticAnalysisJson;
    private String reviewJson;
    private String sceneEvaluationJson;
    private String errorContextMode;
    private String inputTextHealth;
    private int staticAuditIssueCount;
    private String staticAuditSummary;
    private List<String> fixHistory = new ArrayList<>();

    public CodeFixSource getSource() { return source; }
    public void setSource(CodeFixSource source) { this.source = source; }

    public String getReturnAction() { return returnAction; }
    public void setReturnAction(String returnAction) { this.returnAction = returnAction; }

    public String getGeneratedCode() { return generatedCode; }
    public void setGeneratedCode(String generatedCode) { this.generatedCode = generatedCode; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public String getTargetConcept() { return targetConcept; }
    public void setTargetConcept(String targetConcept) { this.targetConcept = targetConcept; }

    public String getTargetDescription() { return targetDescription; }
    public void setTargetDescription(String targetDescription) { this.targetDescription = targetDescription; }

    public String getSceneName() { return sceneName; }
    public void setSceneName(String sceneName) { this.sceneName = sceneName; }

    public String getExpectedSceneName() { return expectedSceneName; }
    public void setExpectedSceneName(String expectedSceneName) { this.expectedSceneName = expectedSceneName; }

    public String getStoryboardJson() { return storyboardJson; }
    public void setStoryboardJson(String storyboardJson) { this.storyboardJson = storyboardJson; }

    public String getStaticAnalysisJson() { return staticAnalysisJson; }
    public void setStaticAnalysisJson(String staticAnalysisJson) { this.staticAnalysisJson = staticAnalysisJson; }

    public String getReviewJson() { return reviewJson; }
    public void setReviewJson(String reviewJson) { this.reviewJson = reviewJson; }

    public String getSceneEvaluationJson() { return sceneEvaluationJson; }
    public void setSceneEvaluationJson(String sceneEvaluationJson) {
        this.sceneEvaluationJson = sceneEvaluationJson;
    }

    public String getErrorContextMode() { return errorContextMode; }
    public void setErrorContextMode(String errorContextMode) { this.errorContextMode = errorContextMode; }

    public String getInputTextHealth() { return inputTextHealth; }
    public void setInputTextHealth(String inputTextHealth) { this.inputTextHealth = inputTextHealth; }

    public int getStaticAuditIssueCount() { return staticAuditIssueCount; }
    public void setStaticAuditIssueCount(int staticAuditIssueCount) {
        this.staticAuditIssueCount = staticAuditIssueCount;
    }

    public String getStaticAuditSummary() { return staticAuditSummary; }
    public void setStaticAuditSummary(String staticAuditSummary) { this.staticAuditSummary = staticAuditSummary; }

    public List<String> getFixHistory() { return fixHistory; }
    public void setFixHistory(List<String> fixHistory) {
        this.fixHistory = fixHistory != null ? new ArrayList<>(fixHistory) : new ArrayList<>();
    }
}
