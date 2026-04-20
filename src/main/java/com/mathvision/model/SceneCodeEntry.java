package com.mathvision.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SceneCodeEntry {

    @JsonProperty("scene_index")
    private int sceneIndex;

    @JsonProperty("scene_id")
    private String sceneId;

    @JsonProperty("scene_method_name")
    private String sceneMethodName;

    @JsonProperty("scene_code")
    private String sceneCode;

    @JsonProperty("validated")
    private boolean validated;

    public SceneCodeEntry() {}

    public SceneCodeEntry(int sceneIndex, String sceneId, String sceneMethodName,
                          String sceneCode, boolean validated) {
        this.sceneIndex = sceneIndex;
        this.sceneId = sceneId;
        this.sceneMethodName = sceneMethodName;
        this.sceneCode = sceneCode;
        this.validated = validated;
    }

    public int getSceneIndex() { return sceneIndex; }
    public void setSceneIndex(int sceneIndex) { this.sceneIndex = sceneIndex; }

    public String getSceneId() { return sceneId; }
    public void setSceneId(String sceneId) { this.sceneId = sceneId; }

    public String getSceneMethodName() { return sceneMethodName; }
    public void setSceneMethodName(String sceneMethodName) { this.sceneMethodName = sceneMethodName; }

    public String getSceneCode() { return sceneCode; }
    public void setSceneCode(String sceneCode) { this.sceneCode = sceneCode; }

    public boolean isValidated() { return validated; }
    public void setValidated(boolean validated) { this.validated = validated; }
}
