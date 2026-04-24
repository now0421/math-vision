package com.mathvision.model;

/**
 * Result from the shared routed code-fix node.
 */
public class CodeFixResult {

    public enum FixOutcome {
        FIXED,               // Code changed, no remaining static issues
        APPLIED_WITH_ISSUES, // Code changed, but post-fix audit found issues
        UNCHANGED,           // Code identical to source
        INPUT_CORRUPTED,     // Text health check flagged issues in the error input
        FAILED               // Request failed entirely
    }

    private FixOutcome outcome;
    private CodeFixSource source;
    private String returnAction;
    private String originalGeneratedCode;
    private String fixedGeneratedCode;
    private String errorReason;
    private String rulesPrompt;
    private String fixedContextPrompt;
    private String currentRequestPrompt;
    private String failureReason;
    private int postFixStaticAuditIssueCount;
    private String postFixStaticAuditSummary;
    private boolean applied;
    private int toolCalls;
    private double executionTimeSeconds;

    public FixOutcome getOutcome() { return outcome; }
    public void setOutcome(FixOutcome outcome) { this.outcome = outcome; }

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

    public String getRulesPrompt() { return rulesPrompt; }
    public void setRulesPrompt(String rulesPrompt) { this.rulesPrompt = rulesPrompt; }

    public String getFixedContextPrompt() { return fixedContextPrompt; }
    public void setFixedContextPrompt(String fixedContextPrompt) { this.fixedContextPrompt = fixedContextPrompt; }

    public String getCurrentRequestPrompt() { return currentRequestPrompt; }
    public void setCurrentRequestPrompt(String currentRequestPrompt) { this.currentRequestPrompt = currentRequestPrompt; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public int getPostFixStaticAuditIssueCount() { return postFixStaticAuditIssueCount; }
    public void setPostFixStaticAuditIssueCount(int postFixStaticAuditIssueCount) {
        this.postFixStaticAuditIssueCount = postFixStaticAuditIssueCount;
    }

    public String getPostFixStaticAuditSummary() { return postFixStaticAuditSummary; }
    public void setPostFixStaticAuditSummary(String postFixStaticAuditSummary) {
        this.postFixStaticAuditSummary = postFixStaticAuditSummary;
    }

    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }

    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }

    public double getExecutionTimeSeconds() { return executionTimeSeconds; }
    public void setExecutionTimeSeconds(double executionTimeSeconds) {
        this.executionTimeSeconds = executionTimeSeconds;
    }
}
