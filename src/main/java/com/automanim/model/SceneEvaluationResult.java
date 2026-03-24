package com.automanim.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result from the post-render scene evaluation stage.
 * Captures per-sample layout issues derived from geometry exports.
 */
public class SceneEvaluationResult {

    private boolean evaluated;
    private boolean approved;
    private boolean revisionTriggered;
    private int revisionAttempts;
    private boolean renderSuccess;
    private String sceneName;
    private String geometryPath;
    private String gateReason;
    private int sampleCount;
    private int issueSampleCount;
    private int totalIssueCount;
    private int overlapIssueCount;
    private int offscreenIssueCount;
    private int blockingIssueCount;
    private int toolCalls;
    private double executionTimeSeconds;
    private List<SampleEvaluation> samples = new ArrayList<>();

    public boolean isEvaluated() { return evaluated; }
    public void setEvaluated(boolean evaluated) { this.evaluated = evaluated; }

    public boolean isApproved() { return approved; }
    public void setApproved(boolean approved) { this.approved = approved; }

    public boolean isRevisionTriggered() { return revisionTriggered; }
    public void setRevisionTriggered(boolean revisionTriggered) { this.revisionTriggered = revisionTriggered; }

    public int getRevisionAttempts() { return revisionAttempts; }
    public void setRevisionAttempts(int revisionAttempts) { this.revisionAttempts = revisionAttempts; }

    public boolean isRenderSuccess() { return renderSuccess; }
    public void setRenderSuccess(boolean renderSuccess) { this.renderSuccess = renderSuccess; }

    public String getSceneName() { return sceneName; }
    public void setSceneName(String sceneName) { this.sceneName = sceneName; }

    public String getGeometryPath() { return geometryPath; }
    public void setGeometryPath(String geometryPath) { this.geometryPath = geometryPath; }

    public String getGateReason() { return gateReason; }
    public void setGateReason(String gateReason) { this.gateReason = gateReason; }

    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }

    public int getIssueSampleCount() { return issueSampleCount; }
    public void setIssueSampleCount(int issueSampleCount) { this.issueSampleCount = issueSampleCount; }

    public int getTotalIssueCount() { return totalIssueCount; }
    public void setTotalIssueCount(int totalIssueCount) { this.totalIssueCount = totalIssueCount; }

    public int getOverlapIssueCount() { return overlapIssueCount; }
    public void setOverlapIssueCount(int overlapIssueCount) { this.overlapIssueCount = overlapIssueCount; }

    public int getOffscreenIssueCount() { return offscreenIssueCount; }
    public void setOffscreenIssueCount(int offscreenIssueCount) { this.offscreenIssueCount = offscreenIssueCount; }

    public int getBlockingIssueCount() { return blockingIssueCount; }
    public void setBlockingIssueCount(int blockingIssueCount) { this.blockingIssueCount = blockingIssueCount; }

    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }

    public double getExecutionTimeSeconds() { return executionTimeSeconds; }
    public void setExecutionTimeSeconds(double executionTimeSeconds) { this.executionTimeSeconds = executionTimeSeconds; }

    public List<SampleEvaluation> getSamples() { return samples; }
    public void setSamples(List<SampleEvaluation> samples) {
        this.samples = samples != null ? new ArrayList<>(samples) : new ArrayList<>();
    }

    public static class SampleEvaluation {
        private String sampleId;
        private Integer playIndex;
        private String sampleRole;
        private String trigger;
        private String sceneMethod;
        private String sourceCode;
        private int elementCount;
        private boolean hasIssues;
        private int issueCount;
        private int overlapIssueCount;
        private int offscreenIssueCount;
        private int blockingIssueCount;
        private List<LayoutIssue> issues = new ArrayList<>();

        public String getSampleId() { return sampleId; }
        public void setSampleId(String sampleId) { this.sampleId = sampleId; }

        public Integer getPlayIndex() { return playIndex; }
        public void setPlayIndex(Integer playIndex) { this.playIndex = playIndex; }

        public String getSampleRole() { return sampleRole; }
        public void setSampleRole(String sampleRole) { this.sampleRole = sampleRole; }

        public String getTrigger() { return trigger; }
        public void setTrigger(String trigger) { this.trigger = trigger; }

        public String getSceneMethod() { return sceneMethod; }
        public void setSceneMethod(String sceneMethod) { this.sceneMethod = sceneMethod; }

        public String getSourceCode() { return sourceCode; }
        public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

        public int getElementCount() { return elementCount; }
        public void setElementCount(int elementCount) { this.elementCount = elementCount; }

        public boolean isHasIssues() { return hasIssues; }
        public void setHasIssues(boolean hasIssues) { this.hasIssues = hasIssues; }

        public int getIssueCount() { return issueCount; }
        public void setIssueCount(int issueCount) { this.issueCount = issueCount; }

        public int getOverlapIssueCount() { return overlapIssueCount; }
        public void setOverlapIssueCount(int overlapIssueCount) { this.overlapIssueCount = overlapIssueCount; }

        public int getOffscreenIssueCount() { return offscreenIssueCount; }
        public void setOffscreenIssueCount(int offscreenIssueCount) { this.offscreenIssueCount = offscreenIssueCount; }

        public int getBlockingIssueCount() { return blockingIssueCount; }
        public void setBlockingIssueCount(int blockingIssueCount) { this.blockingIssueCount = blockingIssueCount; }

        public List<LayoutIssue> getIssues() { return issues; }
        public void setIssues(List<LayoutIssue> issues) {
            this.issues = issues != null ? new ArrayList<>(issues) : new ArrayList<>();
        }
    }

    public static class LayoutIssue {
        private String type;
        private String message;
        private String severity;
        private String reasonCode;
        private Boolean likelyFalsePositive;
        private String recommendedAction;
        private ElementRef primaryElement;
        private ElementRef secondaryElement;
        private Overflow overflow;
        private Double intersectionArea;
        private Bounds intersectionBounds;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

        public Boolean getLikelyFalsePositive() { return likelyFalsePositive; }
        public void setLikelyFalsePositive(Boolean likelyFalsePositive) { this.likelyFalsePositive = likelyFalsePositive; }

        public String getRecommendedAction() { return recommendedAction; }
        public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }

        public ElementRef getPrimaryElement() { return primaryElement; }
        public void setPrimaryElement(ElementRef primaryElement) { this.primaryElement = primaryElement; }

        public ElementRef getSecondaryElement() { return secondaryElement; }
        public void setSecondaryElement(ElementRef secondaryElement) { this.secondaryElement = secondaryElement; }

        public Overflow getOverflow() { return overflow; }
        public void setOverflow(Overflow overflow) { this.overflow = overflow; }

        public Double getIntersectionArea() { return intersectionArea; }
        public void setIntersectionArea(Double intersectionArea) { this.intersectionArea = intersectionArea; }

        public Bounds getIntersectionBounds() { return intersectionBounds; }
        public void setIntersectionBounds(Bounds intersectionBounds) { this.intersectionBounds = intersectionBounds; }
    }

    public static class ElementRef {
        private String stableId;
        private String semanticName;
        private String className;
        private String sampleRole;
        private Bounds bounds;

        public String getStableId() { return stableId; }
        public void setStableId(String stableId) { this.stableId = stableId; }

        public String getSemanticName() { return semanticName; }
        public void setSemanticName(String semanticName) { this.semanticName = semanticName; }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getSampleRole() { return sampleRole; }
        public void setSampleRole(String sampleRole) { this.sampleRole = sampleRole; }

        public Bounds getBounds() { return bounds; }
        public void setBounds(Bounds bounds) { this.bounds = bounds; }
    }

    public static class Overflow {
        private double left;
        private double right;
        private double top;
        private double bottom;

        public double getLeft() { return left; }
        public void setLeft(double left) { this.left = left; }

        public double getRight() { return right; }
        public void setRight(double right) { this.right = right; }

        public double getTop() { return top; }
        public void setTop(double top) { this.top = top; }

        public double getBottom() { return bottom; }
        public void setBottom(double bottom) { this.bottom = bottom; }
    }

    public static class Bounds {
        private double[] min;
        private double[] max;

        public double[] getMin() { return min; }
        public void setMin(double[] min) { this.min = min; }

        public double[] getMax() { return max; }
        public void setMax(double[] max) { this.max = max; }
    }
}
