package com.mathvision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Narrative composition result from the enrichment stage.
 * Contains storyboard metadata used by the code generation stage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Narrative {

    @JsonProperty("target_concept")
    private String targetConcept;

    @JsonProperty("target_description")
    private String targetDescription;

    @JsonProperty("storyboard")
    private Storyboard storyboard;

    public Narrative() {}

    public Narrative(String targetConcept, Storyboard storyboard) {
        this(targetConcept, "", storyboard);
    }

    public Narrative(String targetConcept, String targetDescription,
                     Storyboard storyboard) {
        this.targetConcept = targetConcept;
        this.targetDescription = targetDescription;
        this.storyboard = storyboard;
    }

    // ---- Getters / Setters ----

    public String getTargetConcept() { return targetConcept; }
    public void setTargetConcept(String targetConcept) { this.targetConcept = targetConcept; }

    public String getTargetDescription() { return targetDescription; }
    public void setTargetDescription(String targetDescription) { this.targetDescription = targetDescription; }

    public Storyboard getStoryboard() { return storyboard; }
    public void setStoryboard(Storyboard storyboard) { this.storyboard = storyboard; }

    public boolean hasStoryboard() {
        return storyboard != null
                && storyboard.getScenes() != null
                && !storyboard.getScenes().isEmpty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Storyboard {

        @JsonProperty("continuity_plan")
        private String continuityPlan;

        @JsonProperty("global_visual_rules")
        private List<String> globalVisualRules = new ArrayList<>();

        @JsonProperty("object_registry")
        private List<StoryboardObject> objectRegistry = new ArrayList<>();

        @JsonProperty("scenes")
        private List<StoryboardScene> scenes = new ArrayList<>();

        public Storyboard() {}

        public List<StoryboardObject> getObjectRegistry() { return objectRegistry; }
        public void setObjectRegistry(List<StoryboardObject> objectRegistry) {
            this.objectRegistry = objectRegistry != null ? objectRegistry : new ArrayList<>();
        }

        public String getContinuityPlan() { return continuityPlan; }
        public void setContinuityPlan(String continuityPlan) { this.continuityPlan = continuityPlan; }

        public List<String> getGlobalVisualRules() { return globalVisualRules; }
        public void setGlobalVisualRules(List<String> globalVisualRules) {
            this.globalVisualRules = globalVisualRules;
        }

        public List<StoryboardScene> getScenes() { return scenes; }
        public void setScenes(List<StoryboardScene> scenes) { this.scenes = scenes; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoryboardScene {

        @JsonProperty("scene_id")
        private String sceneId;

        @JsonProperty("title")
        private String title;

        @JsonProperty("goal")
        private String goal;

        @JsonProperty("narration")
        private String narration;

        @JsonProperty("duration_seconds")
        private int durationSeconds;

        @JsonProperty("scene_mode")
        private String sceneMode;

        @JsonProperty("camera_anchor")
        private String cameraAnchor;

        @JsonProperty("camera_plan")
        private String cameraPlan;

        @JsonProperty("layout_goal")
        private String layoutGoal;

        @JsonProperty("safe_area_plan")
        private String safeAreaPlan;

        @JsonProperty("screen_overlay_plan")
        private String screenOverlayPlan;

        @JsonProperty("geometry_constraints")
        private List<String> geometryConstraints = new ArrayList<>();

        @JsonProperty("step_refs")
        private List<String> stepRefs = new ArrayList<>();

        @JsonProperty("entering_objects")
        private List<StoryboardObject> enteringObjects = new ArrayList<>();

        @JsonProperty("persistent_objects")
        private List<StoryboardObject> persistentObjects = new ArrayList<>();

        @JsonProperty("exiting_objects")
        private List<StoryboardObject> exitingObjects = new ArrayList<>();

        @JsonProperty("actions")
        private List<StoryboardAction> actions = new ArrayList<>();

        @JsonProperty("notes_for_codegen")
        private List<String> notesForCodegen = new ArrayList<>();

        public StoryboardScene() {}

        public String getSceneId() { return sceneId; }
        public void setSceneId(String sceneId) { this.sceneId = sceneId; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getGoal() { return goal; }
        public void setGoal(String goal) { this.goal = goal; }

        public String getNarration() { return narration; }
        public void setNarration(String narration) { this.narration = narration; }

        public int getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }

        public String getSceneMode() { return sceneMode; }
        public void setSceneMode(String sceneMode) { this.sceneMode = sceneMode; }

        public String getCameraAnchor() { return cameraAnchor; }
        public void setCameraAnchor(String cameraAnchor) { this.cameraAnchor = cameraAnchor; }

        public String getCameraPlan() { return cameraPlan; }
        public void setCameraPlan(String cameraPlan) { this.cameraPlan = cameraPlan; }

        public String getLayoutGoal() { return layoutGoal; }
        public void setLayoutGoal(String layoutGoal) { this.layoutGoal = layoutGoal; }

        public String getSafeAreaPlan() { return safeAreaPlan; }
        public void setSafeAreaPlan(String safeAreaPlan) { this.safeAreaPlan = safeAreaPlan; }

        public String getScreenOverlayPlan() { return screenOverlayPlan; }
        public void setScreenOverlayPlan(String screenOverlayPlan) {
            this.screenOverlayPlan = screenOverlayPlan;
        }

        public List<String> getGeometryConstraints() { return geometryConstraints; }
        public void setGeometryConstraints(List<String> geometryConstraints) {
            this.geometryConstraints = geometryConstraints;
        }

        public List<String> getStepRefs() { return stepRefs; }
        public void setStepRefs(List<String> stepRefs) { this.stepRefs = stepRefs; }

        public List<StoryboardObject> getEnteringObjects() { return enteringObjects; }
        public void setEnteringObjects(List<StoryboardObject> enteringObjects) {
            this.enteringObjects = enteringObjects;
        }

        public List<StoryboardObject> getPersistentObjects() { return persistentObjects; }
        public void setPersistentObjects(List<StoryboardObject> persistentObjects) {
            this.persistentObjects = persistentObjects != null ? persistentObjects : new ArrayList<>();
        }

        public List<StoryboardObject> getExitingObjects() { return exitingObjects; }
        public void setExitingObjects(List<StoryboardObject> exitingObjects) {
            this.exitingObjects = exitingObjects != null ? exitingObjects : new ArrayList<>();
        }

        public List<StoryboardAction> getActions() { return actions; }
        public void setActions(List<StoryboardAction> actions) { this.actions = actions; }

        public List<String> getNotesForCodegen() { return notesForCodegen; }
        public void setNotesForCodegen(List<String> notesForCodegen) {
            this.notesForCodegen = notesForCodegen;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoryboardObject {

        public static final String BEHAVIOR_STATIC = "static";
        public static final String BEHAVIOR_FOLLOWS_ANCHOR = "follows_anchor";
        public static final String BEHAVIOR_DERIVED = "derived";
        public static final String BEHAVIOR_FIXED_OVERLAY = "fixed_overlay";

        @JsonProperty("id")
        private String id;

        @JsonProperty("kind")
        private String kind;

        @JsonProperty("content")
        private String content;

        @JsonProperty("placement")
        private StoryboardPlacement placement;

        private List<StoryboardStyle> style = new ArrayList<>();

        @JsonProperty("source_node")
        private String sourceNode;

        @JsonProperty("behavior")
        private String behavior;

        @JsonProperty("anchor_id")
        private String anchorId;

        @JsonProperty("dependency_note")
        private String dependencyNote;

        @JsonProperty("constraint_note")
        private String constraintNote;

        public StoryboardObject() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public StoryboardPlacement getPlacement() { return placement; }
        public void setPlacement(StoryboardPlacement placement) { this.placement = placement; }

        @JsonProperty("style")
        public List<StoryboardStyle> getStyle() { return style; }

        public void setStyle(List<StoryboardStyle> style) {
            this.style = style != null ? style : new ArrayList<>();
        }

        public String getSourceNode() { return sourceNode; }
        public void setSourceNode(String sourceNode) { this.sourceNode = sourceNode; }

        public String getBehavior() { return behavior; }
        public void setBehavior(String behavior) { this.behavior = behavior; }

        public String getAnchorId() { return anchorId; }
        public void setAnchorId(String anchorId) { this.anchorId = anchorId; }

        public String getDependencyNote() { return dependencyNote; }
        public void setDependencyNote(String dependencyNote) { this.dependencyNote = dependencyNote; }

        public String getConstraintNote() { return constraintNote; }
        public void setConstraintNote(String constraintNote) { this.constraintNote = constraintNote; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoryboardPlacement {

        public static final String COORDINATE_SPACE_WORLD = "world";
        public static final String COORDINATE_SPACE_SCREEN = "screen";
        public static final String COORDINATE_SPACE_ANCHOR = "anchor";

        @JsonProperty("coordinate_space")
        private String coordinateSpace;

        @JsonProperty("x")
        private StoryboardPlacementAxis x;

        @JsonProperty("y")
        private StoryboardPlacementAxis y;

        @JsonProperty("z")
        private StoryboardPlacementAxis z;

        public StoryboardPlacement() {}

        public String getCoordinateSpace() { return coordinateSpace; }
        public void setCoordinateSpace(String coordinateSpace) { this.coordinateSpace = coordinateSpace; }

        public StoryboardPlacementAxis getX() { return x; }
        public void setX(StoryboardPlacementAxis x) { this.x = x; }

        public StoryboardPlacementAxis getY() { return y; }
        public void setY(StoryboardPlacementAxis y) { this.y = y; }

        public StoryboardPlacementAxis getZ() { return z; }
        public void setZ(StoryboardPlacementAxis z) { this.z = z; }

        public boolean hasData() {
            return (coordinateSpace != null && !coordinateSpace.isBlank())
                    || (x != null && x.hasData())
                    || (y != null && y.hasData())
                    || (z != null && z.hasData());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoryboardPlacementAxis {

        @JsonProperty("value")
        private Double value;

        @JsonProperty("min")
        private Double min;

        @JsonProperty("max")
        private Double max;

        public StoryboardPlacementAxis() {}

        public Double getValue() { return value; }
        public void setValue(Double value) { this.value = value; }

        public Double getMin() { return min; }
        public void setMin(Double min) { this.min = min; }

        public Double getMax() { return max; }
        public void setMax(Double max) { this.max = max; }

        public boolean hasData() {
            return value != null || min != null || max != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoryboardStyle {

        @JsonProperty("role")
        private String role;

        @JsonProperty("type")
        private String type;

        @JsonProperty("properties")
        private Map<String, Object> properties = new LinkedHashMap<>();

        public StoryboardStyle() {}

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) {
            this.properties = properties != null ? properties : new LinkedHashMap<>();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoryboardAction {

        @JsonProperty("order")
        private int order;

        @JsonProperty("type")
        private String type;

        @JsonProperty("targets")
        private List<String> targets = new ArrayList<>();

        @JsonProperty("description")
        private String description;

        public StoryboardAction() {}

        public int getOrder() { return order; }
        public void setOrder(int order) { this.order = order; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public List<String> getTargets() { return targets; }
        public void setTargets(List<String> targets) { this.targets = targets; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
