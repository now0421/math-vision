package com.automanim.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrative composition result from the enrichment stage.
 * Contains the verbose prompt (2000+ word animation script)
 * and metadata used by the code generation stage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Narrative {

    @JsonProperty("target_concept")
    private String targetConcept;

    @JsonProperty("verbose_prompt")
    private String verbosePrompt;

    @JsonProperty("concept_order")
    private List<String> conceptOrder = new ArrayList<>();

    @JsonProperty("total_duration")
    private int totalDuration;

    @JsonProperty("scene_count")
    private int sceneCount;

    public Narrative() {}

    public Narrative(String targetConcept, String verbosePrompt,
                     List<String> conceptOrder, int totalDuration, int sceneCount) {
        this.targetConcept = targetConcept;
        this.verbosePrompt = verbosePrompt;
        this.conceptOrder = conceptOrder;
        this.totalDuration = totalDuration;
        this.sceneCount = sceneCount;
    }

    public int wordCount() {
        if (verbosePrompt == null || verbosePrompt.isBlank()) return 0;
        return verbosePrompt.split("\\s+").length;
    }

    // ---- Getters / Setters ----

    public String getTargetConcept() { return targetConcept; }
    public void setTargetConcept(String targetConcept) { this.targetConcept = targetConcept; }

    public String getVerbosePrompt() { return verbosePrompt; }
    public void setVerbosePrompt(String verbosePrompt) { this.verbosePrompt = verbosePrompt; }

    public List<String> getConceptOrder() { return conceptOrder; }
    public void setConceptOrder(List<String> conceptOrder) { this.conceptOrder = conceptOrder; }

    public int getTotalDuration() { return totalDuration; }
    public void setTotalDuration(int totalDuration) { this.totalDuration = totalDuration; }

    public int getSceneCount() { return sceneCount; }
    public void setSceneCount(int sceneCount) { this.sceneCount = sceneCount; }
}
