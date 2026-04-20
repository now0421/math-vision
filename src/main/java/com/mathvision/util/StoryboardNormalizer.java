package com.mathvision.util;

import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardAction;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardPlacement;
import com.mathvision.model.Narrative.StoryboardPlacementAxis;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.Narrative.StoryboardStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Normalizes a {@link Storyboard} by ensuring all fields have safe defaults.
 * Extracted from NarrativeNode to be reusable across VisualDesignNode and
 * StoryboardValidationNode.
 */
public final class StoryboardNormalizer {

    private StoryboardNormalizer() {}

    public static Storyboard normalize(Storyboard storyboard) {
        if (storyboard == null) {
            return null;
        }

        if (storyboard.getGlobalVisualRules() == null) {
            storyboard.setGlobalVisualRules(new ArrayList<>());
        }
        if (storyboard.getScenes() == null) {
            storyboard.setScenes(new ArrayList<>());
        }
        if (storyboard.getContinuityPlan() == null || storyboard.getContinuityPlan().isBlank()) {
            storyboard.setContinuityPlan(
                    "Maintain one stable layout and update existing objects instead of redrawing the whole scene.");
        }
        if (storyboard.getSummary() == null || storyboard.getSummary().isBlank()) {
            storyboard.setSummary("Continuity-aware storyboard for the target lesson.");
        }

        List<String> globalRules = new ArrayList<>(storyboard.getGlobalVisualRules());
        if (globalRules.isEmpty()) {
            globalRules.add("Keep major objects inside the safe frame.");
            globalRules.add("Reuse stable anchors for persistent objects.");
        }
        storyboard.setGlobalVisualRules(globalRules);

        if (storyboard.getObjectRegistry() == null) {
            storyboard.setObjectRegistry(new ArrayList<>());
        } else {
            storyboard.setObjectRegistry(normalizeRegistryObjects(storyboard.getObjectRegistry()));
        }

        List<StoryboardScene> normalizedScenes = new ArrayList<>();
        for (int i = 0; i < storyboard.getScenes().size(); i++) {
            StoryboardScene scene = storyboard.getScenes().get(i);
            if (scene == null) {
                continue;
            }
            normalizeScene(scene, i);
            normalizedScenes.add(scene);
        }
        storyboard.setScenes(normalizedScenes);
        return storyboard;
    }

    public static void normalizeScene(StoryboardScene scene, int index) {
        String sceneId = scene.getSceneId() == null || scene.getSceneId().isBlank()
                ? "scene_" + (index + 1)
                : scene.getSceneId().trim();
        scene.setSceneId(sceneId);

        if (scene.getTitle() == null || scene.getTitle().isBlank()) {
            scene.setTitle("Scene " + (index + 1));
        }
        if (scene.getGoal() == null || scene.getGoal().isBlank()) {
            scene.setGoal(scene.getTitle());
        }
        if (scene.getNarration() == null || scene.getNarration().isBlank()) {
            scene.setNarration(scene.getGoal());
        }
        if (scene.getDurationSeconds() <= 0) {
            scene.setDurationSeconds(8);
        }
        scene.setSceneMode(normalizeSceneMode(scene.getSceneMode()));
        if (scene.getCameraAnchor() == null || scene.getCameraAnchor().isBlank()) {
            scene.setCameraAnchor("center");
        }
        if (scene.getCameraPlan() == null || scene.getCameraPlan().isBlank()) {
            scene.setCameraPlan(isThreeDSceneMode(scene)
                    ? "Set a readable 3D view before the main reveal."
                    : "Static 2D view.");
        }
        if (scene.getLayoutGoal() == null || scene.getLayoutGoal().isBlank()) {
            scene.setLayoutGoal("Keep the layout stable and uncluttered.");
        }
        if (scene.getSafeAreaPlan() == null || scene.getSafeAreaPlan().isBlank()) {
            scene.setSafeAreaPlan(
                    "Keep important screen-space content inside x in [-7, 7] and y in [-4, 4] with edge margin.");
        }
        if (scene.getScreenOverlayPlan() == null || scene.getScreenOverlayPlan().isBlank()) {
            scene.setScreenOverlayPlan(isThreeDSceneMode(scene)
                    ? "Keep titles and formulas visually separate if they must stay readable during viewpoint changes."
                    : "No separate overlay needed.");
        }
        if (scene.getGeometryConstraints() == null) {
            scene.setGeometryConstraints(new ArrayList<>());
        }
        if (scene.getStepRefs() == null) {
            scene.setStepRefs(new ArrayList<>());
        }
        if (scene.getPersistentObjects() == null) {
            scene.setPersistentObjects(new ArrayList<>());
        }
        if (scene.getExitingObjects() == null) {
            scene.setExitingObjects(new ArrayList<>());
        }
        if (scene.getNotesForCodegen() == null) {
            scene.setNotesForCodegen(new ArrayList<>());
        }

        scene.setEnteringObjects(normalizeScenePatchObjects(scene.getEnteringObjects(), sceneId, PatchMode.ENTERING));
        scene.setPersistentObjects(normalizeScenePatchObjects(scene.getPersistentObjects(), sceneId, PatchMode.PERSISTENT));
        scene.setExitingObjects(normalizeScenePatchObjects(scene.getExitingObjects(), sceneId, PatchMode.EXITING));
        normalizeActions(scene);
    }

    private static List<StoryboardObject> normalizeRegistryObjects(List<StoryboardObject> objects) {
        List<StoryboardObject> normalizedObjects = new ArrayList<>();
        if (objects == null) {
            return normalizedObjects;
        }
        for (StoryboardObject object : objects) {
            if (object == null) {
                continue;
            }
            object.setPlacement(null);
            object.setStyle(normalizeStyles(object.getStyle()));
            normalizedObjects.add(object);
        }
        return normalizedObjects;
    }

    private static List<StoryboardObject> normalizeScenePatchObjects(List<StoryboardObject> objects,
                                                                     String sceneId,
                                                                     PatchMode mode) {
        List<StoryboardObject> normalizedObjects = new ArrayList<>();
        List<StoryboardObject> patchObjects = objects == null ? new ArrayList<>() : objects;
        for (int j = 0; j < patchObjects.size(); j++) {
            StoryboardObject object = patchObjects.get(j);
            if (object == null) {
                continue;
            }

            if (object.getId() != null && object.getId().isBlank()) {
                object.setId(null);
            }
            object.setPlacement(normalizePlacement(object.getPlacement()));
            object.setStyle(normalizeStyles(object.getStyle()));

            if (mode == PatchMode.EXITING) {
                object.setPlacement(null);
                object.setStyle(new ArrayList<>());
            }

            stripPatchOnlyFields(object, mode);
            normalizedObjects.add(object);
        }
        return normalizedObjects;
    }

    private static void normalizeActions(StoryboardScene scene) {
        List<StoryboardAction> normalizedActions = new ArrayList<>();
        List<StoryboardAction> actions = scene.getActions() == null
                ? new ArrayList<>()
                : scene.getActions();
        for (int j = 0; j < actions.size(); j++) {
            StoryboardAction action = actions.get(j);
            if (action == null) {
                continue;
            }
            if (action.getOrder() <= 0) {
                action.setOrder(j + 1);
            }
            if (action.getType() == null || action.getType().isBlank()) {
                action.setType("transform");
            }
            if (action.getTargets() == null) {
                action.setTargets(new ArrayList<>());
            }
            if (action.getDescription() == null || action.getDescription().isBlank()) {
                action.setDescription("Advance the explanation with a precise visual update.");
            }
            normalizedActions.add(action);
        }
        scene.setActions(normalizedActions);
    }

    private static void stripPatchOnlyFields(StoryboardObject object, PatchMode mode) {
        object.setKind(null);
        object.setContent(null);
        object.setSourceNode(null);
        object.setBehavior(null);
        object.setAnchorId(null);
        object.setDependencyNote(null);
        object.setConstraintNote(null);
        if (mode == PatchMode.EXITING) {
            object.setStyle(new ArrayList<>());
        }
    }

    private static List<StoryboardStyle> normalizeStyles(List<StoryboardStyle> styles) {
        List<StoryboardStyle> normalizedStyles = new ArrayList<>();
        if (styles == null) {
            return normalizedStyles;
        }
        for (StoryboardStyle style : styles) {
            if (style == null) {
                continue;
            }
            if (style.getProperties() == null) {
                style.setProperties(new LinkedHashMap<>());
            }
            normalizedStyles.add(style);
        }
        return normalizedStyles;
    }

    private static StoryboardPlacement normalizePlacement(StoryboardPlacement placement) {
        if (placement == null) {
            return null;
        }

        if (placement.getCoordinateSpace() != null && placement.getCoordinateSpace().isBlank()) {
            placement.setCoordinateSpace(null);
        }
        placement.setX(normalizePlacementAxis(placement.getX()));
        placement.setY(normalizePlacementAxis(placement.getY()));
        placement.setZ(normalizePlacementAxis(placement.getZ()));
        return placement.hasData() ? placement : null;
    }

    private static StoryboardPlacementAxis normalizePlacementAxis(StoryboardPlacementAxis axis) {
        if (axis == null || !axis.hasData()) {
            return null;
        }
        return axis;
    }

    public static String normalizeSceneMode(String sceneMode) {
        if (sceneMode == null || sceneMode.isBlank()) {
            return "2d";
        }
        return sceneMode.trim().equalsIgnoreCase("3d") ? "3d" : "2d";
    }

    public static boolean isThreeDSceneMode(StoryboardScene scene) {
        return scene != null && "3d".equalsIgnoreCase(scene.getSceneMode());
    }

    public static int calculateStoryboardDuration(Storyboard storyboard, int fallbackDuration) {
        if (storyboard == null || storyboard.getScenes() == null || storyboard.getScenes().isEmpty()) {
            return fallbackDuration;
        }
        int total = storyboard.getScenes().stream()
                .mapToInt(StoryboardScene::getDurationSeconds)
                .sum();
        return total > 0 ? total : fallbackDuration;
    }

    private enum PatchMode {
        ENTERING,
        PERSISTENT,
        EXITING
    }
}
