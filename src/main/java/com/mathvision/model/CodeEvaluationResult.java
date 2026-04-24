package com.mathvision.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result from the code evaluation stage.
 * Captures static validation, rule-based code review checks,
 * one-shot code revision state, and whether rendering is allowed.
 */
public class CodeEvaluationResult {

    private int totalEvaluations;
    private boolean approvedForRender;
    private boolean revisionTriggered;
    private boolean revisedCodeApplied;
    private int revisionAttempts;
    private int toolCalls;
    private String gateReason;
    private String sceneName;
    private StaticAnalysis initialStaticAnalysis;
    private StaticAnalysis finalStaticAnalysis;
    private ReviewSnapshot initialReview;
    private ReviewSnapshot finalReview;
    private double executionTimeSeconds;
    private List<EvaluationAttempt> attempts = new ArrayList<>();

    public int getTotalEvaluations() { return totalEvaluations; }
    public void setTotalEvaluations(int totalEvaluations) { this.totalEvaluations = totalEvaluations; }

    public boolean isApprovedForRender() { return approvedForRender; }
    public void setApprovedForRender(boolean approvedForRender) {
        this.approvedForRender = approvedForRender;
    }

    public boolean isRevisionTriggered() { return revisionTriggered; }
    public void setRevisionTriggered(boolean revisionTriggered) {
        this.revisionTriggered = revisionTriggered;
    }

    public boolean isRevisedCodeApplied() { return revisedCodeApplied; }
    public void setRevisedCodeApplied(boolean revisedCodeApplied) {
        this.revisedCodeApplied = revisedCodeApplied;
    }

    public int getRevisionAttempts() { return revisionAttempts; }
    public void setRevisionAttempts(int revisionAttempts) { this.revisionAttempts = revisionAttempts; }

    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }

    public String getGateReason() { return gateReason; }
    public void setGateReason(String gateReason) { this.gateReason = gateReason; }

    public String getSceneName() { return sceneName; }
    public void setSceneName(String sceneName) { this.sceneName = sceneName; }

    public StaticAnalysis getInitialStaticAnalysis() { return initialStaticAnalysis; }
    public void setInitialStaticAnalysis(StaticAnalysis initialStaticAnalysis) {
        this.initialStaticAnalysis = initialStaticAnalysis;
    }

    public StaticAnalysis getFinalStaticAnalysis() { return finalStaticAnalysis; }
    public void setFinalStaticAnalysis(StaticAnalysis finalStaticAnalysis) {
        this.finalStaticAnalysis = finalStaticAnalysis;
    }

    public ReviewSnapshot getInitialReview() { return initialReview; }
    public void setInitialReview(ReviewSnapshot initialReview) { this.initialReview = initialReview; }

    public ReviewSnapshot getFinalReview() { return finalReview; }
    public void setFinalReview(ReviewSnapshot finalReview) { this.finalReview = finalReview; }

    public double getExecutionTimeSeconds() { return executionTimeSeconds; }
    public void setExecutionTimeSeconds(double executionTimeSeconds) {
        this.executionTimeSeconds = executionTimeSeconds;
    }

    public List<EvaluationAttempt> getAttempts() { return attempts; }
    public void setAttempts(List<EvaluationAttempt> attempts) {
        this.attempts = attempts != null ? attempts : new ArrayList<>();
    }

    public static EvaluationAttempt fromResult(CodeEvaluationResult result, int sequence) {
        EvaluationAttempt attempt = new EvaluationAttempt();
        attempt.setSequence(sequence);
        attempt.setApprovedForRender(result.isApprovedForRender());
        attempt.setRevisionTriggered(result.isRevisionTriggered());
        attempt.setRevisedCodeApplied(result.isRevisedCodeApplied());
        attempt.setRevisionAttempts(result.getRevisionAttempts());
        attempt.setToolCalls(result.getToolCalls());
        attempt.setGateReason(result.getGateReason());
        attempt.setSceneName(result.getSceneName());
        attempt.setInitialStaticAnalysis(result.getInitialStaticAnalysis());
        attempt.setFinalStaticAnalysis(result.getFinalStaticAnalysis());
        attempt.setInitialReview(result.getInitialReview());
        attempt.setFinalReview(result.getFinalReview());
        attempt.setExecutionTimeSeconds(result.getExecutionTimeSeconds());
        return attempt;
    }

    public static class EvaluationAttempt {
        private int sequence;
        private boolean approvedForRender;
        private boolean revisionTriggered;
        private boolean revisedCodeApplied;
        private int revisionAttempts;
        private int toolCalls;
        private String gateReason;
        private String sceneName;
        private StaticAnalysis initialStaticAnalysis;
        private StaticAnalysis finalStaticAnalysis;
        private ReviewSnapshot initialReview;
        private ReviewSnapshot finalReview;
        private double executionTimeSeconds;

        public int getSequence() { return sequence; }
        public void setSequence(int sequence) { this.sequence = sequence; }

        public boolean isApprovedForRender() { return approvedForRender; }
        public void setApprovedForRender(boolean approvedForRender) {
            this.approvedForRender = approvedForRender;
        }

        public boolean isRevisionTriggered() { return revisionTriggered; }
        public void setRevisionTriggered(boolean revisionTriggered) {
            this.revisionTriggered = revisionTriggered;
        }

        public boolean isRevisedCodeApplied() { return revisedCodeApplied; }
        public void setRevisedCodeApplied(boolean revisedCodeApplied) {
            this.revisedCodeApplied = revisedCodeApplied;
        }

        public int getRevisionAttempts() { return revisionAttempts; }
        public void setRevisionAttempts(int revisionAttempts) { this.revisionAttempts = revisionAttempts; }

        public int getToolCalls() { return toolCalls; }
        public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }

        public String getGateReason() { return gateReason; }
        public void setGateReason(String gateReason) { this.gateReason = gateReason; }

        public String getSceneName() { return sceneName; }
        public void setSceneName(String sceneName) { this.sceneName = sceneName; }

        public StaticAnalysis getInitialStaticAnalysis() { return initialStaticAnalysis; }
        public void setInitialStaticAnalysis(StaticAnalysis initialStaticAnalysis) {
            this.initialStaticAnalysis = initialStaticAnalysis;
        }

        public StaticAnalysis getFinalStaticAnalysis() { return finalStaticAnalysis; }
        public void setFinalStaticAnalysis(StaticAnalysis finalStaticAnalysis) {
            this.finalStaticAnalysis = finalStaticAnalysis;
        }

        public ReviewSnapshot getInitialReview() { return initialReview; }
        public void setInitialReview(ReviewSnapshot initialReview) { this.initialReview = initialReview; }

        public ReviewSnapshot getFinalReview() { return finalReview; }
        public void setFinalReview(ReviewSnapshot finalReview) { this.finalReview = finalReview; }

        public double getExecutionTimeSeconds() { return executionTimeSeconds; }
        public void setExecutionTimeSeconds(double executionTimeSeconds) {
            this.executionTimeSeconds = executionTimeSeconds;
        }
    }

    public static class StaticAnalysis {
        private int codeLines;
        private int sceneCount;
        private int toEdgeCount;
        private int shiftCount;
        private int largeShiftCount;
        private int fadeInCount;
        private int fadeOutCount;
        private int transformCount;
        private int replacementTransformCount;
        private int fadeTransformCount;
        private int arrangeCount;
        private int nextToCount;
        private int mathTexCount;
        private int textCount;
        private boolean threeDScene;
        private int threeDStoryboardSceneCount;
        private int threeDObjectCount;
        private int cameraOrientationCount;
        private int cameraMotionCount;
        private int fixedInFrameCount;
        private int fixedOrientationCount;
        private int maxEnteringObjects;
        private int maxVisibleObjects;
        private int maxVisibleTextualObjects;
        private double maxNarrationWordsPerSecond;
        private double minNarrationWordsPerSecond;
        private List<StaticFinding> findings = new ArrayList<>();

        public boolean hasBlockingFindings() {
            return findings.stream().anyMatch(finding -> "fail".equalsIgnoreCase(finding.getSeverity()));
        }

        public int getCodeLines() { return codeLines; }
        public void setCodeLines(int codeLines) { this.codeLines = codeLines; }

        public int getSceneCount() { return sceneCount; }
        public void setSceneCount(int sceneCount) { this.sceneCount = sceneCount; }

        public int getToEdgeCount() { return toEdgeCount; }
        public void setToEdgeCount(int toEdgeCount) { this.toEdgeCount = toEdgeCount; }

        public int getShiftCount() { return shiftCount; }
        public void setShiftCount(int shiftCount) { this.shiftCount = shiftCount; }

        public int getLargeShiftCount() { return largeShiftCount; }
        public void setLargeShiftCount(int largeShiftCount) { this.largeShiftCount = largeShiftCount; }

        public int getFadeInCount() { return fadeInCount; }
        public void setFadeInCount(int fadeInCount) { this.fadeInCount = fadeInCount; }

        public int getFadeOutCount() { return fadeOutCount; }
        public void setFadeOutCount(int fadeOutCount) { this.fadeOutCount = fadeOutCount; }

        public int getTransformCount() { return transformCount; }
        public void setTransformCount(int transformCount) { this.transformCount = transformCount; }

        public int getReplacementTransformCount() { return replacementTransformCount; }
        public void setReplacementTransformCount(int replacementTransformCount) {
            this.replacementTransformCount = replacementTransformCount;
        }

        public int getFadeTransformCount() { return fadeTransformCount; }
        public void setFadeTransformCount(int fadeTransformCount) {
            this.fadeTransformCount = fadeTransformCount;
        }

        public int getArrangeCount() { return arrangeCount; }
        public void setArrangeCount(int arrangeCount) { this.arrangeCount = arrangeCount; }

        public int getNextToCount() { return nextToCount; }
        public void setNextToCount(int nextToCount) { this.nextToCount = nextToCount; }

        public int getMathTexCount() { return mathTexCount; }
        public void setMathTexCount(int mathTexCount) { this.mathTexCount = mathTexCount; }

        public int getTextCount() { return textCount; }
        public void setTextCount(int textCount) { this.textCount = textCount; }

        public boolean isThreeDScene() { return threeDScene; }
        public void setThreeDScene(boolean threeDScene) { this.threeDScene = threeDScene; }

        public int getThreeDStoryboardSceneCount() { return threeDStoryboardSceneCount; }
        public void setThreeDStoryboardSceneCount(int threeDStoryboardSceneCount) {
            this.threeDStoryboardSceneCount = threeDStoryboardSceneCount;
        }

        public int getThreeDObjectCount() { return threeDObjectCount; }
        public void setThreeDObjectCount(int threeDObjectCount) { this.threeDObjectCount = threeDObjectCount; }

        public int getCameraOrientationCount() { return cameraOrientationCount; }
        public void setCameraOrientationCount(int cameraOrientationCount) {
            this.cameraOrientationCount = cameraOrientationCount;
        }

        public int getCameraMotionCount() { return cameraMotionCount; }
        public void setCameraMotionCount(int cameraMotionCount) {
            this.cameraMotionCount = cameraMotionCount;
        }

        public int getFixedInFrameCount() { return fixedInFrameCount; }
        public void setFixedInFrameCount(int fixedInFrameCount) {
            this.fixedInFrameCount = fixedInFrameCount;
        }

        public int getFixedOrientationCount() { return fixedOrientationCount; }
        public void setFixedOrientationCount(int fixedOrientationCount) {
            this.fixedOrientationCount = fixedOrientationCount;
        }

        public int getMaxEnteringObjects() { return maxEnteringObjects; }
        public void setMaxEnteringObjects(int maxEnteringObjects) {
            this.maxEnteringObjects = maxEnteringObjects;
        }

        public int getMaxVisibleObjects() { return maxVisibleObjects; }
        public void setMaxVisibleObjects(int maxVisibleObjects) {
            this.maxVisibleObjects = maxVisibleObjects;
        }

        public int getMaxVisibleTextualObjects() { return maxVisibleTextualObjects; }
        public void setMaxVisibleTextualObjects(int maxVisibleTextualObjects) {
            this.maxVisibleTextualObjects = maxVisibleTextualObjects;
        }

        public double getMaxNarrationWordsPerSecond() { return maxNarrationWordsPerSecond; }
        public void setMaxNarrationWordsPerSecond(double maxNarrationWordsPerSecond) {
            this.maxNarrationWordsPerSecond = maxNarrationWordsPerSecond;
        }

        public double getMinNarrationWordsPerSecond() { return minNarrationWordsPerSecond; }
        public void setMinNarrationWordsPerSecond(double minNarrationWordsPerSecond) {
            this.minNarrationWordsPerSecond = minNarrationWordsPerSecond;
        }

        public List<StaticFinding> getFindings() { return findings; }
        public void setFindings(List<StaticFinding> findings) {
            this.findings = findings != null ? findings : new ArrayList<>();
        }
    }

    public static class StaticFinding {
        private String ruleId;
        private String severity;
        private String summary;
        private String evidence;

        public StaticFinding() {}

        public StaticFinding(String ruleId, String severity, String summary, String evidence) {
            this.ruleId = ruleId;
            this.severity = severity;
            this.summary = summary;
            this.evidence = evidence;
        }

        public String getRuleId() { return ruleId; }
        public void setRuleId(String ruleId) { this.ruleId = ruleId; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public String getEvidence() { return evidence; }
        public void setEvidence(String evidence) { this.evidence = evidence; }
    }

    public static class ReviewSnapshot {
        private boolean approvedForRender;
        private List<RuleCheck> ruleChecks = new ArrayList<>();
        private String summary;
        private List<String> strengths = new ArrayList<>();
        private List<String> blockingIssues = new ArrayList<>();
        private List<String> revisionDirectives = new ArrayList<>();

        public boolean isApprovedForRender() { return approvedForRender; }
        public void setApprovedForRender(boolean approvedForRender) {
            this.approvedForRender = approvedForRender;
        }

        public List<RuleCheck> getRuleChecks() { return ruleChecks; }
        public void setRuleChecks(List<RuleCheck> ruleChecks) {
            this.ruleChecks = ruleChecks != null ? ruleChecks : new ArrayList<>();
        }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public List<String> getStrengths() { return strengths; }
        public void setStrengths(List<String> strengths) {
            this.strengths = strengths != null ? strengths : new ArrayList<>();
        }

        public List<String> getBlockingIssues() { return blockingIssues; }
        public void setBlockingIssues(List<String> blockingIssues) {
            this.blockingIssues = blockingIssues != null ? blockingIssues : new ArrayList<>();
        }

        public List<String> getRevisionDirectives() { return revisionDirectives; }
        public void setRevisionDirectives(List<String> revisionDirectives) {
            this.revisionDirectives = revisionDirectives != null ? revisionDirectives : new ArrayList<>();
        }
    }

    public static class RuleCheck {
        private String ruleId;
        private String requirement;
        private String status;
        private String evidence;

        public RuleCheck() {}

        public RuleCheck(String ruleId, String requirement, String status, String evidence) {
            this.ruleId = ruleId;
            this.requirement = requirement;
            this.status = status;
            this.evidence = evidence;
        }

        public String getRuleId() { return ruleId; }
        public void setRuleId(String ruleId) { this.ruleId = ruleId; }

        public String getRequirement() { return requirement; }
        public void setRequirement(String requirement) { this.requirement = requirement; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getEvidence() { return evidence; }
        public void setEvidence(String evidence) { this.evidence = evidence; }
    }
}
