package com.mathvision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ProblemBundle {

    @JsonProperty("id")
    private String id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("input_mode")
    private String inputMode;

    @JsonProperty("output_target")
    private String outputTarget;

    @JsonProperty("scene_mode")
    private String sceneMode;

    @JsonProperty("source")
    private ProblemSource source;

    @JsonProperty("statement")
    private String statement;

    @JsonProperty("diagram")
    private ProblemDiagram diagram;

    public ProblemBundle() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getInputMode() { return inputMode; }
    public void setInputMode(String inputMode) { this.inputMode = inputMode; }

    public String getOutputTarget() { return outputTarget; }
    public void setOutputTarget(String outputTarget) { this.outputTarget = outputTarget; }

    public String getSceneMode() { return sceneMode; }
    public void setSceneMode(String sceneMode) { this.sceneMode = sceneMode; }

    public ProblemSource getSource() { return source; }
    public void setSource(ProblemSource source) { this.source = source; }

    public String getStatement() { return statement; }
    public void setStatement(String statement) { this.statement = statement; }

    public ProblemDiagram getDiagram() { return diagram; }
    public void setDiagram(ProblemDiagram diagram) { this.diagram = diagram; }

    public boolean hasDiagram() {
        return diagram != null && diagram.isPresent() && diagram.hasDescriptionPayload();
    }
}
