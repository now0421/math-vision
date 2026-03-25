package com.automanim.node.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for fix retry state tracking across nodes.
 *
 * Consolidates the common retry state pattern previously duplicated
 * across GenerationFixState, EvaluationFixState, RenderRetryState,
 * and SceneEvaluationRetryState.
 */
public class FixRetryState {

    protected int attempts;
    protected int fixToolCalls;
    protected int carryoverToolCalls;
    protected boolean requestFix;
    protected String originalCodeBeforeFix;
    protected int originalIssueCount;
    protected List<String> currentIssues = new ArrayList<>();

    public FixRetryState() {
        // Do not call reset() here - subclass fields may not be initialized yet
    }

    /**
     * Clears pending fix state while preserving attempt history.
     */
    public void clearPending() {
        requestFix = false;
        originalCodeBeforeFix = null;
        originalIssueCount = 0;
        currentIssues = new ArrayList<>();
    }

    /**
     * Fully resets all state for a new workflow run.
     */
    public void reset() {
        attempts = 0;
        fixToolCalls = 0;
        carryoverToolCalls = 0;
        clearPending();
    }

    /**
     * Records a fix request for routing.
     */
    public void recordFixRequest(String code, List<String> issues) {
        requestFix = true;
        attempts++;
        originalCodeBeforeFix = code;
        originalIssueCount = issues != null ? issues.size() : 0;
        currentIssues = issues != null ? new ArrayList<>(issues) : new ArrayList<>();
    }

    /**
     * Checks if the fix improved the issue count.
     */
    public boolean didFixImprove(int newIssueCount) {
        if (originalCodeBeforeFix == null) {
            return true;
        }
        return newIssueCount < originalIssueCount;
    }

    /**
     * Adds carryover tool calls from a completed fix.
     */
    public void addCarryoverToolCalls(int count) {
        carryoverToolCalls += count;
    }

    /**
     * Returns total tool calls including fixes and carryover.
     */
    public int totalToolCalls() {
        return fixToolCalls + carryoverToolCalls;
    }

    // Accessors

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getFixToolCalls() {
        return fixToolCalls;
    }

    public void setFixToolCalls(int fixToolCalls) {
        this.fixToolCalls = fixToolCalls;
    }

    public void addFixToolCalls(int count) {
        this.fixToolCalls += count;
    }

    public int getCarryoverToolCalls() {
        return carryoverToolCalls;
    }

    public void setCarryoverToolCalls(int carryoverToolCalls) {
        this.carryoverToolCalls = carryoverToolCalls;
    }

    public boolean isRequestFix() {
        return requestFix;
    }

    public void setRequestFix(boolean requestFix) {
        this.requestFix = requestFix;
    }

    public String getOriginalCodeBeforeFix() {
        return originalCodeBeforeFix;
    }

    public void setOriginalCodeBeforeFix(String originalCodeBeforeFix) {
        this.originalCodeBeforeFix = originalCodeBeforeFix;
    }

    public int getOriginalIssueCount() {
        return originalIssueCount;
    }

    public void setOriginalIssueCount(int originalIssueCount) {
        this.originalIssueCount = originalIssueCount;
    }

    public List<String> getCurrentIssues() {
        return currentIssues;
    }

    public void setCurrentIssues(List<String> currentIssues) {
        this.currentIssues = currentIssues != null ? currentIssues : new ArrayList<>();
    }
}
