package com.mathvision.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Trace artifact entry for a single CodeFixNode invocation.
 */
public class CodeFixTraceEntry {

    private int sequence;
    private CodeFixSource source;
    private String returnAction;
    private String sceneName;
    private String expectedSceneName;
    private String targetConcept;
    private String errorReason;
    private List<String> fixHistory = new ArrayList<>();
    private String systemPrompt;
    private String userPrompt;
    private boolean applied;
    private String failureReason;
    private int toolCalls;
    private double executionTimeSeconds;

    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }

    public CodeFixSource getSource() { return source; }
    public void setSource(CodeFixSource source) { this.source = source; }

    public String getReturnAction() { return returnAction; }
    public void setReturnAction(String returnAction) { this.returnAction = returnAction; }

    public String getSceneName() { return sceneName; }
    public void setSceneName(String sceneName) { this.sceneName = sceneName; }

    public String getExpectedSceneName() { return expectedSceneName; }
    public void setExpectedSceneName(String expectedSceneName) { this.expectedSceneName = expectedSceneName; }

    public String getTargetConcept() { return targetConcept; }
    public void setTargetConcept(String targetConcept) { this.targetConcept = targetConcept; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public List<String> getFixHistory() { return fixHistory; }
    public void setFixHistory(List<String> fixHistory) {
        this.fixHistory = fixHistory != null ? new ArrayList<>(fixHistory) : new ArrayList<>();
    }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getUserPrompt() { return userPrompt; }
    public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }

    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }

    public double getExecutionTimeSeconds() { return executionTimeSeconds; }
    public void setExecutionTimeSeconds(double executionTimeSeconds) {
        this.executionTimeSeconds = executionTimeSeconds;
    }
}
