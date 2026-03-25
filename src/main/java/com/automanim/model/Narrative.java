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

    @JsonProperty("target_description")
    private String targetDescription;

    @JsonProperty("verbose_prompt")
    private String verbosePrompt;

    @JsonProperty("storyboard")
    private Storyboard storyboard;

    @JsonProperty("step_order")
    private List<String> stepOrder = new ArrayList<>();

    @JsonProperty("total_duration")
    private int totalDuration;

    @JsonProperty("scene_count")
    private int sceneCount;

    public Narrative() {}

    public Narrative(String targetConcept, String verbosePrompt,
                     List<String> stepOrder, int totalDuration, int sceneCount) {
        this(targetConcept, "", verbosePrompt, null, stepOrder, totalDuration, sceneCount);
    }

    public Narrative(String targetConcept, String verbosePrompt, Storyboard storyboard,
                     List<String> stepOrder, int totalDuration, int sceneCount) {
        this(targetConcept, "", verbosePrompt, storyboard, stepOrder, totalDuration, sceneCount);
    }

    public Narrative(String targetConcept, String targetDescription, String verbosePrompt,
                     Storyboard storyboard, List<String> stepOrder,
                     int totalDuration, int sceneCount) {
        this.targetConcept = targetConcept;
        this.targetDescription = targetDescription;
        this.verbosePrompt = verbosePrompt;
        this.storyboard = storyboard;
        this.stepOrder = stepOrder;
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

    public String getTargetDescription() { return targetDescription; }
    public void setTargetDescription(String targetDescription) { this.targetDescription = targetDescription; }

    public String getVerbosePrompt() { return verbosePrompt; }
    public void setVerbosePrompt(String verbosePrompt) { this.verbosePrompt = verbosePrompt; }

    public Storyboard getStoryboard() { return storyboard; }
    public void setStoryboard(Storyboard storyboard) { this.storyboard = storyboard; }

    public List<String> getStepOrder() { return stepOrder; }
    public void setStepOrder(List<String> stepOrder) { this.stepOrder = stepOrder; }

    public int getTotalDuration() { return totalDuration; }
    public void setTotalDuration(int totalDuration) { this.totalDuration = totalDuration; }

    public int getSceneCount() { return sceneCount; }
    public void setSceneCount(int sceneCount) { this.sceneCount = sceneCount; }

    public boolean hasStoryboard() {
        return storyboard != null
                && storyboard.getScenes() != null
                && !storyboard.getScenes().isEmpty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Storyboard {

        @JsonProperty("hook")
        private String hook;

        @JsonProperty("summary")
        private String summary;

        @JsonProperty("continuity_plan")
        private String continuityPlan;

        @JsonProperty("global_visual_rules")
        private List<String> globalVisualRules = new ArrayList<>();

        @JsonProperty("scenes")
        private List<StoryboardScene> scenes = new ArrayList<>();

        public Storyboard() {}

        public String getHook() { return hook; }
        public void setHook(String hook) { this.hook = hook; }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

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

        @JsonProperty("step_refs")
        private List<String> stepRefs = new ArrayList<>();

        @JsonProperty("entering_objects")
        private List<StoryboardObject> enteringObjects = new ArrayList<>();

        @JsonProperty("persistent_objects")
        private List<String> persistentObjects = new ArrayList<>();

        @JsonProperty("exiting_objects")
        private List<String> exitingObjects = new ArrayList<>();

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

        public List<String> getStepRefs() { return stepRefs; }
        public void setStepRefs(List<String> stepRefs) { this.stepRefs = stepRefs; }

        public List<StoryboardObject> getEnteringObjects() { return enteringObjects; }
        public void setEnteringObjects(List<StoryboardObject> enteringObjects) {
            this.enteringObjects = enteringObjects;
        }

        public List<String> getPersistentObjects() { return persistentObjects; }
        public void setPersistentObjects(List<String> persistentObjects) {
            this.persistentObjects = persistentObjects;
        }

        public List<String> getExitingObjects() { return exitingObjects; }
        public void setExitingObjects(List<String> exitingObjects) { this.exitingObjects = exitingObjects; }

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
        private String placement;

        @JsonProperty("style")
        private String style;

        @JsonProperty("source_node")
        private String sourceNode;

        @JsonProperty("behavior")
        private String behavior;

        @JsonProperty("anchor_id")
        private String anchorId;

        @JsonProperty("dependency_note")
        private String dependencyNote;

        public StoryboardObject() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getPlacement() { return placement; }
        public void setPlacement(String placement) { this.placement = placement; }

        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }

        public String getSourceNode() { return sourceNode; }
        public void setSourceNode(String sourceNode) { this.sourceNode = sourceNode; }

        public String getBehavior() { return behavior; }
        public void setBehavior(String behavior) { this.behavior = behavior; }

        public String getAnchorId() { return anchorId; }
        public void setAnchorId(String anchorId) { this.anchorId = anchorId; }

        public String getDependencyNote() { return dependencyNote; }
        public void setDependencyNote(String dependencyNote) { this.dependencyNote = dependencyNote; }
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
