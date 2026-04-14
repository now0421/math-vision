package com.mathvision.model;

/**
 * Result from the shared routed code-fix node.
 */
public class CodeFixResult {

    private CodeFixSource source;
    private String returnAction;
    private String originalGeneratedCode;
    private String fixedGeneratedCode;
    private String errorReason;
    private String systemPrompt;
    private String userPrompt;
    private String failureReason;
    private boolean applied;
    private int toolCalls;
    private double executionTimeSeconds;

    public CodeFixSource getSource() { return source; }
    public void setSource(CodeFixSource source) { this.source = source; }

    public String getReturnAction() { return returnAction; }
    public void setReturnAction(String returnAction) { this.returnAction = returnAction; }

    public String getOriginalGeneratedCode() { return originalGeneratedCode; }
    public void setOriginalGeneratedCode(String originalGeneratedCode) { this.originalGeneratedCode = originalGeneratedCode; }

    public String getFixedGeneratedCode() { return fixedGeneratedCode; }
    public void setFixedGeneratedCode(String fixedGeneratedCode) { this.fixedGeneratedCode = fixedGeneratedCode; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getUserPrompt() { return userPrompt; }
    public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }

    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }

    public double getExecutionTimeSeconds() { return executionTimeSeconds; }
    public void setExecutionTimeSeconds(double executionTimeSeconds) {
        this.executionTimeSeconds = executionTimeSeconds;
    }
}
