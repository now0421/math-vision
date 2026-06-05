package com.mathvision.model;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Storyboard {

        @JsonProperty("coordinate_bounds")
        private StoryboardCoordinateBounds coordinateBounds;

        @JsonProperty("continuity_plan")
        private String continuityPlan;

        @JsonProperty("global_visual_rules")
        private List<String> globalVisualRules = new ArrayList<>();

        @JsonProperty("object_registry")
        private List<StoryboardObject> objectRegistry = new ArrayList<>();

        @JsonProperty("scenes")
        private List<StoryboardScene> scenes = new ArrayList<>();

        public Storyboard() {}

        public StoryboardCoordinateBounds getCoordinateBounds() { return coordinateBounds; }
        public void setCoordinateBounds(StoryboardCoordinateBounds coordinateBounds) {
            this.coordinateBounds = coordinateBounds;
        }

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
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StoryboardCoordinateBounds {

        public static final double DEFAULT_PADDING = 1.0;

        @JsonProperty("x")
        private StoryboardCoordinateBoundsAxis x;

        @JsonProperty("y")
        private StoryboardCoordinateBoundsAxis y;

        @JsonProperty("z")
        private StoryboardCoordinateBoundsAxis z;

        @JsonProperty("padding")
        private Double padding = DEFAULT_PADDING;

        public StoryboardCoordinateBounds() {}

        public StoryboardCoordinateBoundsAxis getX() { return x; }
        public void setX(StoryboardCoordinateBoundsAxis x) { this.x = x; }

        public StoryboardCoordinateBoundsAxis getY() { return y; }
        public void setY(StoryboardCoordinateBoundsAxis y) { this.y = y; }

        public StoryboardCoordinateBoundsAxis getZ() { return z; }
        public void setZ(StoryboardCoordinateBoundsAxis z) { this.z = z; }

        public Double getPadding() { return padding; }
        public void setPadding(Double padding) { this.padding = padding; }

        public boolean hasData() {
            return (x != null && x.hasData())
                    || (y != null && y.hasData())
                    || (z != null && z.hasData());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StoryboardCoordinateBoundsAxis {

        @JsonProperty("min")
        private Double min;

        @JsonProperty("max")
        private Double max;

        public StoryboardCoordinateBoundsAxis() {}

        public StoryboardCoordinateBoundsAxis(Double min, Double max) {
            this.min = min;
            this.max = max;
        }

        public Double getMin() { return min; }
        public void setMin(Double min) { this.min = min; }

        public Double getMax() { return max; }
        public void setMax(Double max) { this.max = max; }

        public boolean hasData() {
            return min != null || max != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
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

        @JsonProperty("constraints")
        private List<StoryboardConstraint> constraints = new ArrayList<>();

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

        public List<StoryboardConstraint> getConstraints() { return constraints; }
        public void setConstraints(List<StoryboardConstraint> constraints) {
            this.constraints = constraints != null ? constraints : new ArrayList<>();
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
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StoryboardObject {

        @JsonProperty("id")
        private String id;

        @JsonProperty("kind")
        private String kind;

        @JsonProperty("content")
        private String content;

        @JsonProperty("placement")
        private StoryboardPlacement placement;

        @JsonProperty("style")
        private StoryboardStyle style;

        @JsonProperty("constraints")
        private List<StoryboardConstraint> constraints = new ArrayList<>();

        public StoryboardObject() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public StoryboardPlacement getPlacement() { return placement; }
        public void setPlacement(StoryboardPlacement placement) { this.placement = placement; }

        public StoryboardStyle getStyle() { return style; }
        public void setStyle(StoryboardStyle style) { this.style = style; }

        public List<StoryboardConstraint> getConstraints() { return constraints; }
        public void setConstraints(List<StoryboardConstraint> constraints) {
            this.constraints = constraints != null ? constraints : new ArrayList<>();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StoryboardConstraint {

        @JsonProperty("id")
        private String id;

        @JsonProperty("domain")
        private String domain;

        @JsonProperty("relation")
        private String relation;

        @JsonProperty("refs")
        private Map<String, Object> refs = new LinkedHashMap<>();

        @JsonProperty("parameters")
        private Map<String, Object> parameters = new LinkedHashMap<>();

        @JsonProperty("strength")
        private String strength;

        @JsonProperty("reason")
        private String reason;

        public StoryboardConstraint() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }

        public String getRelation() { return relation; }
        public void setRelation(String relation) { this.relation = relation; }

        public Map<String, Object> getRefs() { return refs; }
        public void setRefs(Map<String, Object> refs) {
            this.refs = refs != null ? refs : new LinkedHashMap<>();
        }

        public Map<String, Object> getParameters() { return parameters; }
        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters != null ? parameters : new LinkedHashMap<>();
        }

        public String getStrength() { return strength; }
        public void setStrength(String strength) { this.strength = strength; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public boolean hasData() {
            return (id != null && !id.isBlank())
                    || (domain != null && !domain.isBlank())
                    || (relation != null && !relation.isBlank())
                    || (refs != null && !refs.isEmpty())
                    || (parameters != null && !parameters.isEmpty())
                    || (strength != null && !strength.isBlank())
                    || (reason != null && !reason.isBlank());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoryboardPlacement {

        public static final String POSITIONING_ABSOLUTE = "absolute";
        public static final String POSITIONING_RELATIVE = "relative";

        @JsonProperty("positioning")
        private String positioning;

        @JsonProperty("x")
        private StoryboardPlacementAxis x;

        @JsonProperty("y")
        private StoryboardPlacementAxis y;

        @JsonProperty("z")
        private StoryboardPlacementAxis z;

        public StoryboardPlacement() {}

        public String getPositioning() { return positioning; }
        public void setPositioning(String positioning) { this.positioning = positioning; }

        public StoryboardPlacementAxis getX() { return x; }
        public void setX(StoryboardPlacementAxis x) { this.x = x; }

        public StoryboardPlacementAxis getY() { return y; }
        public void setY(StoryboardPlacementAxis y) { this.y = y; }

        public StoryboardPlacementAxis getZ() { return z; }
        public void setZ(StoryboardPlacementAxis z) { this.z = z; }

        public boolean hasData() {
            return (positioning != null && !positioning.isBlank())
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StoryboardStyle {

        // ── Color ────────────────────────────────────────

        /** Primary foreground / accent color (#RRGGBB).
         *  For text/equation: text color.
         *  For point: dot color.
         *  For line/segment/ray: stroke color when stroke_color is absent. */
        @JsonProperty("color")
        private String color;

        /** Shape fill color (#RRGGBB).
         *  For circle/polygon/region: interior fill.
         *  For text_card: background box fill color. */
        @JsonProperty("fill_color")
        private String fillColor;

        /** Stroke / border / outline color (#RRGGBB).
         *  For line/segment/ray: line color.
         *  For circle/polygon: border color.
         *  For text_card: background box border color.
         *  For angle_marker: arc color. */
        @JsonProperty("stroke_color")
        private String strokeColor;

        /** Emphasis / highlight color (#RRGGBB), used for highlight actions. */
        @JsonProperty("highlight_color")
        private String highlightColor;

        // ── Opacity ────────────────────────────────────────

        /** Overall opacity (0..1). Overrides fill_opacity and stroke_opacity when set. */
        @JsonProperty("opacity")
        private Double opacity;

        /** Fill opacity (0..1).
         *  For shapes: interior transparency.
         *  For text_card: background box fill transparency. */
        @JsonProperty("fill_opacity")
        private Double fillOpacity;

        /** Stroke opacity (0..1).
         *  For line/segment: line transparency.
         *  For text_card: background box border transparency. */
        @JsonProperty("stroke_opacity")
        private Double strokeOpacity;

        // ── Stroke / line width ────────────────────────────────────

        /** Stroke / line width (in Mobject units for Manim, or thickness index for GeoGebra). */
        @JsonProperty("stroke_width")
        private Double strokeWidth;

        // ── Line style ────────────────────────────────────────

        /** Line dash style: solid | dashed | dotted | dash_dot.
         *  Manim: DashedLine / DashedVMobject.
         *  GeoGebra: SetLineStyle (0=solid, 1=long dash, 2=short dash, 3=dotted, 4=dash-dot). */
        @JsonProperty("line_style")
        private String lineStyle;

        // ── Font / text ────────────────────────────────────

        /** Font size in points (Manim: font_size; GeoGebra: no direct command, default size). */
        @JsonProperty("font_size")
        private Double fontSize;

        /** Font family name (Manim: font parameter; GeoGebra: not supported, ignored). */
        @JsonProperty("font_family")
        private String fontFamily;

        /** Font weight: normal | bold (Manim: t2w; GeoGebra: not supported, ignored). */
        @JsonProperty("font_weight")
        private String fontWeight;

        /** Font style: normal | italic (Manim: t2s; GeoGebra: not supported, ignored). */
        @JsonProperty("font_style")
        private String fontStyle;

        // ── Geometry size ────────────────────────────────────────

        /** Point dot radius (Manim: Dot radius; GeoGebra: SetPointSize). */
        @JsonProperty("radius")
        private Double radius;

        /** Point size (GeoGebra: SetPointSize; Manim: use radius instead). */
        @JsonProperty("point_size")
        private Double pointSize;

        /** Angle marker / arc radius. */
        @JsonProperty("marker_size")
        private Double markerSize;

        // ── Text card specific ────────────────────────────────────

        /** Inner padding between text and background box edge (text_card only).
         *  Manim: BackgroundRectangle buff.
         *  GeoGebra: manual offset between text and polygon boundary. */
        @JsonProperty("padding")
        private Double padding;

        /** Corner radius of the background box (text_card only).
         *  Manim: SurroundingRectangle corner_radius.
         *  GeoGebra: not natively supported, polygon has sharp corners. */
        @JsonProperty("corner_radius")
        private Double cornerRadius;

        // ── Layer / layout ────────────────────────────────────

        /** Z-index / layer for draw order.
         *  Manim: set_z_index.
         *  GeoGebra: SetLayer (0-9). */
        @JsonProperty("z_index")
        private Double zIndex;

        // ── GeoGebra specific ────────────────────────────────────

        /** Point style code (GeoGebra only: 0=dot, 1=cross, 2=empty dot, 3=plus, ...).
         *  Manim: not applicable, use radius. */
        @JsonProperty("point_style")
        private Double pointStyle;

        /** Decoration code (GeoGebra only: segment/tick decorations). */
        @JsonProperty("decoration")
        private Double decoration;

        // ── Label visibility ────────────────────────────────────

        /** Whether the native object label should be visible
         *  (GeoGebra: ShowLabel; Manim: use explicit text objects). */
        @JsonProperty("label_visible")
        private Boolean labelVisible;

        public StoryboardStyle() {}

        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }

        public String getFillColor() { return fillColor; }
        public void setFillColor(String fillColor) { this.fillColor = fillColor; }

        public String getStrokeColor() { return strokeColor; }
        public void setStrokeColor(String strokeColor) { this.strokeColor = strokeColor; }

        public String getHighlightColor() { return highlightColor; }
        public void setHighlightColor(String highlightColor) { this.highlightColor = highlightColor; }

        public Double getOpacity() { return opacity; }
        public void setOpacity(Double opacity) { this.opacity = opacity; }

        public Double getFillOpacity() { return fillOpacity; }
        public void setFillOpacity(Double fillOpacity) { this.fillOpacity = fillOpacity; }

        public Double getStrokeOpacity() { return strokeOpacity; }
        public void setStrokeOpacity(Double strokeOpacity) { this.strokeOpacity = strokeOpacity; }

        public Double getStrokeWidth() { return strokeWidth; }
        public void setStrokeWidth(Double strokeWidth) { this.strokeWidth = strokeWidth; }

        public String getLineStyle() { return lineStyle; }
        public void setLineStyle(String lineStyle) { this.lineStyle = lineStyle; }

        public Double getFontSize() { return fontSize; }
        public void setFontSize(Double fontSize) { this.fontSize = fontSize; }

        public String getFontFamily() { return fontFamily; }
        public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }

        public String getFontWeight() { return fontWeight; }
        public void setFontWeight(String fontWeight) { this.fontWeight = fontWeight; }

        public String getFontStyle() { return fontStyle; }
        public void setFontStyle(String fontStyle) { this.fontStyle = fontStyle; }

        public Double getRadius() { return radius; }
        public void setRadius(Double radius) { this.radius = radius; }

        public Double getPointSize() { return pointSize; }
        public void setPointSize(Double pointSize) { this.pointSize = pointSize; }

        public Double getMarkerSize() { return markerSize; }
        public void setMarkerSize(Double markerSize) { this.markerSize = markerSize; }

        public Double getPadding() { return padding; }
        public void setPadding(Double padding) { this.padding = padding; }

        public Double getCornerRadius() { return cornerRadius; }
        public void setCornerRadius(Double cornerRadius) { this.cornerRadius = cornerRadius; }

        public Double getZIndex() { return zIndex; }
        public void setZIndex(Double zIndex) { this.zIndex = zIndex; }

        public Double getPointStyle() { return pointStyle; }
        public void setPointStyle(Double pointStyle) { this.pointStyle = pointStyle; }

        public Double getDecoration() { return decoration; }
        public void setDecoration(Double decoration) { this.decoration = decoration; }

        public Boolean getLabelVisible() { return labelVisible; }
        public void setLabelVisible(Boolean labelVisible) { this.labelVisible = labelVisible; }

        public boolean hasData() {
            return color != null
                    || fillColor != null
                    || strokeColor != null
                    || highlightColor != null
                    || opacity != null
                    || fillOpacity != null
                    || strokeOpacity != null
                    || strokeWidth != null
                    || lineStyle != null
                    || fontSize != null
                    || fontFamily != null
                    || fontWeight != null
                    || fontStyle != null
                    || radius != null
                    || pointSize != null
                    || markerSize != null
                    || padding != null
                    || cornerRadius != null
                    || zIndex != null
                    || pointStyle != null
                    || decoration != null
                    || labelVisible != null;
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

        @JsonProperty("voiceover_text")
        private String voiceoverText;

        @JsonProperty("expected_seconds")
        private Double expectedSeconds;

        public StoryboardAction() {}

        public int getOrder() { return order; }
        public void setOrder(int order) { this.order = order; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public List<String> getTargets() { return targets; }
        public void setTargets(List<String> targets) { this.targets = targets; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getVoiceoverText() { return voiceoverText; }
        public void setVoiceoverText(String voiceoverText) { this.voiceoverText = voiceoverText; }

        public Double getExpectedSeconds() { return expectedSeconds; }
        public void setExpectedSeconds(Double expectedSeconds) { this.expectedSeconds = expectedSeconds; }
    }
}
