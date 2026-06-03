package com.mathvision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ProblemDiagram {

    @JsonProperty("present")
    private boolean present;

    @JsonProperty("description")
    private String description;

    @JsonProperty("objects")
    private List<Narrative.StoryboardObject> objects = new ArrayList<>();

    @JsonProperty("constraints")
    private List<Narrative.StoryboardConstraint> constraints = new ArrayList<>();

    @JsonProperty("construction_notes")
    private List<String> constructionNotes = new ArrayList<>();

    public ProblemDiagram() {}

    public boolean isPresent() { return present; }
    public void setPresent(boolean present) { this.present = present; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<Narrative.StoryboardObject> getObjects() { return objects; }
    public void setObjects(List<Narrative.StoryboardObject> objects) {
        this.objects = objects != null ? objects : new ArrayList<>();
    }

    public List<Narrative.StoryboardConstraint> getConstraints() { return constraints; }
    public void setConstraints(List<Narrative.StoryboardConstraint> constraints) {
        this.constraints = constraints != null ? constraints : new ArrayList<>();
    }

    public List<String> getConstructionNotes() { return constructionNotes; }
    public void setConstructionNotes(List<String> constructionNotes) {
        this.constructionNotes = constructionNotes != null ? constructionNotes : new ArrayList<>();
    }
}
