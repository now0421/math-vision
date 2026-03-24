package com.automanim.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted artifact for all code-fix prompts issued during a workflow run.
 */
public class CodeFixTraceReport {

    private int totalFixEvents;
    private List<CodeFixTraceEntry> entries = new ArrayList<>();

    public int getTotalFixEvents() { return totalFixEvents; }
    public void setTotalFixEvents(int totalFixEvents) { this.totalFixEvents = totalFixEvents; }

    public List<CodeFixTraceEntry> getEntries() { return entries; }
    public void setEntries(List<CodeFixTraceEntry> entries) {
        this.entries = entries != null ? new ArrayList<>(entries) : new ArrayList<>();
    }
}
