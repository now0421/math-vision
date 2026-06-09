package com.mathvision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ProblemDiagram {

    @JsonProperty("present")
    private boolean present;

    @JsonProperty("source_observed")
    private boolean sourceObserved;

    @JsonProperty("diagram_description")
    private JsonNode diagramDescription;

    @JsonProperty("coordinate_model")
    private JsonNode coordinateModel;

    @JsonProperty("unknowns")
    private List<JsonNode> unknowns = new ArrayList<>();

    @JsonProperty("ambiguities")
    private List<JsonNode> ambiguities = new ArrayList<>();

    @JsonProperty("normalization_notes")
    private List<String> normalizationNotes = new ArrayList<>();

    public ProblemDiagram() {}

    public boolean isPresent() { return present; }
    public void setPresent(boolean present) { this.present = present; }

    public boolean isSourceObserved() { return sourceObserved; }
    public void setSourceObserved(boolean sourceObserved) { this.sourceObserved = sourceObserved; }

    public JsonNode getDiagramDescription() { return diagramDescription; }
    public void setDiagramDescription(JsonNode diagramDescription) {
        this.diagramDescription = diagramDescription;
    }

    public JsonNode getCoordinateModel() { return coordinateModel; }
    public void setCoordinateModel(JsonNode coordinateModel) {
        this.coordinateModel = coordinateModel;
    }

    public List<JsonNode> getUnknowns() { return unknowns; }
    public void setUnknowns(List<JsonNode> unknowns) {
        this.unknowns = unknowns != null ? unknowns : new ArrayList<>();
    }

    public List<JsonNode> getAmbiguities() { return ambiguities; }
    public void setAmbiguities(List<JsonNode> ambiguities) {
        this.ambiguities = ambiguities != null ? ambiguities : new ArrayList<>();
    }

    public List<String> getNormalizationNotes() { return normalizationNotes; }
    public void setNormalizationNotes(List<String> normalizationNotes) {
        this.normalizationNotes = normalizationNotes != null ? normalizationNotes : new ArrayList<>();
    }

    public boolean hasDescriptionPayload() {
        return isMeaningful(diagramDescription)
                || isMeaningful(coordinateModel)
                || (unknowns != null && !unknowns.isEmpty())
                || (ambiguities != null && !ambiguities.isEmpty())
                || (normalizationNotes != null && !normalizationNotes.isEmpty());
    }

    private boolean isMeaningful(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isContainerNode()) {
            return node.size() > 0;
        }
        return !node.asText("").isBlank();
    }
}
