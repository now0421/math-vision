package com.mathvision.prompt;

import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardAction;
import com.mathvision.model.Narrative.StoryboardConstraint;
import com.mathvision.model.Narrative.StoryboardPlacement;
import com.mathvision.model.Narrative.StoryboardPlacementAxis;
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
        private final boolean includeSceneFixFields;
        private final boolean includePlacement;

        private BuildOptions(boolean includeSceneFixFields, boolean includePlacement) {
            this.includeSceneFixFields = includeSceneFixFields;
            this.includePlacement = includePlacement;
        }
    }

    private StoryboardJsonBuilder() {}

    /**
     * Builds a compact storyboard JSON string optimized for code generation.
     */
    public static String buildForCodegen(Storyboard storyboard) {
        return build(storyboard, new BuildOptions(true, true));
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

        if (storyboard.getObjectRegistry() != null && !storyboard.getObjectRegistry().isEmpty()) {
            addRegistryObjectArray(root, "object_registry", storyboard.getObjectRegistry());
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
        addConstraintArray(sceneNode, "constraints", scene.getConstraints());
        putTrimmedStringArray(sceneNode, "step_refs", scene.getStepRefs());

        addPatchObjectArray(sceneNode, "entering_objects", scene.getEnteringObjects(), options.includePlacement, false);
        addPatchObjectArray(sceneNode, "persistent_objects", scene.getPersistentObjects(), options.includePlacement, false);
        addPatchObjectArray(sceneNode, "exiting_objects", scene.getExitingObjects(), false, true);
        addActions(sceneNode, scene.getActions());
        putTrimmedStringArray(sceneNode, "notes_for_codegen", scene.getNotesForCodegen());
    }

    private static void addRegistryObjectArray(ObjectNode parent,
                                               String fieldName,
                                               List<StoryboardObject> objects) {
        ArrayNode arrayNode = parent.putArray(fieldName);
        if (objects == null) {
            return;
        }

        for (StoryboardObject object : objects) {
            if (object == null) {
                continue;
            }
            ObjectNode objectNode = arrayNode.addObject();
            putNonBlank(objectNode, "id", object.getId());
            putNonBlank(objectNode, "kind", object.getKind());
            putNonBlank(objectNode, "content", object.getContent());
            addStyle(objectNode, object.getStyle());
            addConstraintArray(objectNode, "constraints", object.getConstraints());
            removeIfEmpty(arrayNode, objectNode);
        }
    }

    private static void addPatchObjectArray(ObjectNode parent,
                                            String fieldName,
                                            List<StoryboardObject> objects,
                                            boolean includePlacement,
                                            boolean idOnly) {
        ArrayNode arrayNode = parent.putArray(fieldName);
        if (objects == null) {
            return;
        }

        for (StoryboardObject object : objects) {
            if (object == null) {
                continue;
            }
            ObjectNode objectNode = arrayNode.addObject();
            putNonBlank(objectNode, "id", object.getId());
            if (!idOnly) {
                if (includePlacement) {
                    addPlacement(objectNode, object.getPlacement());
                }
                addStyle(objectNode, object.getStyle());
            }
            removeIfEmpty(arrayNode, objectNode);
        }
    }

    private static void addConstraintArray(ObjectNode parent,
                                           String fieldName,
                                           List<StoryboardConstraint> constraints) {
        if (constraints == null || constraints.isEmpty()) {
            return;
        }
        ArrayNode arrayNode = parent.putArray(fieldName);
        for (StoryboardConstraint constraint : constraints) {
            if (constraint == null || !constraint.hasData()) {
                continue;
            }
            ObjectNode constraintNode = arrayNode.addObject();
            putNonBlank(constraintNode, "id", constraint.getId());
            putNonBlank(constraintNode, "domain", constraint.getDomain());
            putNonBlank(constraintNode, "relation", constraint.getRelation());
            if (constraint.getRefs() != null && !constraint.getRefs().isEmpty()) {
                constraintNode.set("refs", JsonUtils.mapper().valueToTree(constraint.getRefs()));
            }
            if (constraint.getParameters() != null && !constraint.getParameters().isEmpty()) {
                constraintNode.set("parameters", JsonUtils.mapper().valueToTree(constraint.getParameters()));
            }
            putNonBlank(constraintNode, "strength", constraint.getStrength());
            putNonBlank(constraintNode, "reason", constraint.getReason());
            removeIfEmpty(arrayNode, constraintNode);
        }
        if (arrayNode.isEmpty()) {
            parent.remove(fieldName);
        }
    }

    private static void addPlacement(ObjectNode objectNode, StoryboardPlacement placement) {
        if (placement == null || !placement.hasData()) {
            return;
        }

        ObjectNode placementNode = objectNode.putObject("placement");
        putNonBlank(placementNode, "coordinate_space", placement.getCoordinateSpace());
        addPlacementAxis(placementNode, "x", placement.getX());
        addPlacementAxis(placementNode, "y", placement.getY());
        addPlacementAxis(placementNode, "z", placement.getZ());
        removeIfEmpty(objectNode, placementNode, "placement");
    }

    private static void addPlacementAxis(ObjectNode parentNode,
                                         String fieldName,
                                         StoryboardPlacementAxis axis) {
        if (axis == null || !axis.hasData()) {
            return;
        }

        ObjectNode axisNode = parentNode.putObject(fieldName);
        if (axis.getValue() != null) {
            axisNode.put("value", axis.getValue());
        }
        if (axis.getMin() != null) {
            axisNode.put("min", axis.getMin());
        }
        if (axis.getMax() != null) {
            axisNode.put("max", axis.getMax());
        }
        removeIfEmpty(parentNode, axisNode, fieldName);
    }

    private static void addStyle(ObjectNode objectNode, StoryboardStyle style) {
        if (style == null || !style.hasData()) {
            return;
        }

        objectNode.set("style", JsonUtils.mapper().valueToTree(style));
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
            node.remove(fieldName);
            return;
        }
        for (String value : values) {
            String normalized = sanitize(value);
            if (!normalized.isEmpty()) {
                array.add(normalized);
            }
        }
        if (array.isEmpty()) {
            node.remove(fieldName);
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
