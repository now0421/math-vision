package com.mathvision.prompt;

import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardAction;
import com.mathvision.model.Narrative.StoryboardStyle;
import com.mathvision.util.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Builds compact storyboard JSON for code generation prompts.
 *
 * Canonical compact storyboard serializer for code-generation and review prompts.
 */
public final class StoryboardJsonBuilder {

    public static final String EMPTY_STORYBOARD_JSON = "{\"scenes\":[]}";

    private static final class BuildOptions {
        private final boolean includeNarrativeFields;
        private final boolean includeSceneFixFields;

        private BuildOptions(boolean includeNarrativeFields, boolean includeSceneFixFields) {
            this.includeNarrativeFields = includeNarrativeFields;
            this.includeSceneFixFields = includeSceneFixFields;
        }
    }

    private StoryboardJsonBuilder() {}

    /**
     * Builds a compact storyboard JSON string optimized for code generation.
     */
    public static String buildForCodegen(Storyboard storyboard) {
        return build(storyboard, new BuildOptions(false, true));
    }

    /**
     * Builds storyboard JSON for post-render layout repair.
     * This keeps compact structure but preserves additional scene intent fields
     * so the fixer can recover layout without breaking geometric constraints.
     */
    public static String buildForSceneEvaluationFix(Storyboard storyboard) {
        return build(storyboard, new BuildOptions(true, true));
    }

    private static String build(Storyboard storyboard, BuildOptions options) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        if (storyboard == null) {
            root.putArray("scenes");
            return JsonUtils.toPrettyJson(root);
        }

        if (options.includeNarrativeFields) {
            putNonBlank(root, "hook", storyboard.getHook());
            putNonBlank(root, "summary", storyboard.getSummary());
        }
        putNonBlank(root, "continuity_plan", storyboard.getContinuityPlan());
        putTrimmedStringArray(root, "global_visual_rules", storyboard.getGlobalVisualRules());

        ArrayNode scenesArray = root.putArray("scenes");
        if (storyboard.getScenes() != null) {
            for (StoryboardScene scene : storyboard.getScenes()) {
                if (scene == null) {
                    continue;
                }
                addSceneNode(scenesArray, scene, options);
            }
        }

        return JsonUtils.toPrettyJson(root);
    }

    private static void addSceneNode(ArrayNode scenesArray,
                                     StoryboardScene scene,
                                     BuildOptions options) {
        ObjectNode sceneNode = scenesArray.addObject();

        putNonBlank(sceneNode, "scene_id", scene.getSceneId());
        putNonBlank(sceneNode, "title", scene.getTitle());
        if (options.includeSceneFixFields) {
            putNonBlank(sceneNode, "goal", scene.getGoal());
            putNonBlank(sceneNode, "layout_goal", scene.getLayoutGoal());
        }
        putNonBlank(sceneNode, "narration", scene.getNarration());

        if (scene.getDurationSeconds() > 0) {
            sceneNode.put("duration_seconds", scene.getDurationSeconds());
        }

        putNonBlank(sceneNode, "scene_mode", scene.getSceneMode());
        putNonBlank(sceneNode, "camera_anchor", scene.getCameraAnchor());
        putNonBlank(sceneNode, "camera_plan", scene.getCameraPlan());
        putNonBlank(sceneNode, "safe_area_plan", scene.getSafeAreaPlan());
        putNonBlank(sceneNode, "screen_overlay_plan", scene.getScreenOverlayPlan());
        putTrimmedStringArray(sceneNode, "geometry_constraints", scene.getGeometryConstraints());
        putTrimmedStringArray(sceneNode, "step_refs", scene.getStepRefs());

        addEnteringObjects(sceneNode, scene.getEnteringObjects());
        putTrimmedStringArray(sceneNode, "persistent_objects", scene.getPersistentObjects());
        putTrimmedStringArray(sceneNode, "exiting_objects", scene.getExitingObjects());
        addActions(sceneNode, scene.getActions());
        putTrimmedStringArray(sceneNode, "notes_for_codegen", scene.getNotesForCodegen());
    }

    private static void addEnteringObjects(ObjectNode sceneNode, List<StoryboardObject> objects) {
        ArrayNode enteringObjects = sceneNode.putArray("entering_objects");
        if (objects == null) {
            return;
        }

        for (StoryboardObject object : objects) {
            if (object == null) {
                continue;
            }
            ObjectNode objectNode = enteringObjects.addObject();
            putNonBlank(objectNode, "id", object.getId());
            putNonBlank(objectNode, "kind", object.getKind());
            putNonBlank(objectNode, "content", object.getContent());
            putNonBlank(objectNode, "placement", object.getPlacement());
            addStyles(objectNode, object.getStyle());
            putNonBlank(objectNode, "source_node", object.getSourceNode());
            putNonBlank(objectNode, "behavior", object.getBehavior());
            putNonBlank(objectNode, "anchor_id", object.getAnchorId());
            putNonBlank(objectNode, "dependency_note", object.getDependencyNote());
            putNonBlank(objectNode, "constraint_note", object.getConstraintNote());
        }
    }

    private static void addStyles(ObjectNode objectNode, List<StoryboardStyle> styles) {
        if (styles == null || styles.isEmpty()) {
            return;
        }

        ArrayNode styleArray = objectNode.putArray("style");
        for (StoryboardStyle style : styles) {
            if (style == null) {
                continue;
            }
            ObjectNode styleNode = styleArray.addObject();
            putNonBlank(styleNode, "role", style.getRole());
            putNonBlank(styleNode, "type", style.getType());
            if (style.getProperties() != null && !style.getProperties().isEmpty()) {
                styleNode.set("properties", JsonUtils.mapper().valueToTree(style.getProperties()));
            }
            removeIfEmpty(styleArray, styleNode);
        }
        if (styleArray.isEmpty()) {
            objectNode.remove("style");
        }
    }

    private static void addActions(ObjectNode sceneNode, List<StoryboardAction> actions) {
        ArrayNode actionsArray = sceneNode.putArray("actions");
        if (actions == null) {
            return;
        }

        for (StoryboardAction action : actions) {
            if (action == null) {
                continue;
            }
            ObjectNode actionNode = actionsArray.addObject();
            if (action.getOrder() > 0) {
                actionNode.put("order", action.getOrder());
            }
            putNonBlank(actionNode, "type", action.getType());
            putTrimmedStringArray(actionNode, "targets", action.getTargets());
            putNonBlank(actionNode, "description", action.getDescription());
        }
    }

    private static void putNonBlank(ObjectNode node, String fieldName, String value) {
        String normalized = sanitize(value);
        if (!normalized.isEmpty()) {
            node.put(fieldName, normalized);
        }
    }

    private static void putTrimmedStringArray(ObjectNode node, String fieldName, List<String> values) {
        ArrayNode array = node.putArray(fieldName);
        if (values == null) {
            return;
        }
        for (String value : values) {
            String normalized = sanitize(value);
            if (!normalized.isEmpty()) {
                array.add(normalized);
            }
        }
    }

    private static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    private static void removeIfEmpty(ObjectNode parentNode, ObjectNode childNode, String fieldName) {
        if (childNode.isEmpty()) {
            parentNode.remove(fieldName);
        }
    }

    private static void removeIfEmpty(ArrayNode parentNode, ObjectNode childNode) {
        if (childNode.isEmpty()) {
            parentNode.remove(parentNode.size() - 1);
        }
    }
}
