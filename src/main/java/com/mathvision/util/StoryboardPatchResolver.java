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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves raw storyboard scene patches into per-scene full object snapshots for
 * downstream prompts and analysis.
 */
public final class StoryboardPatchResolver {

    private StoryboardPatchResolver() {}

    public static Storyboard buildMergedStoryboard(Storyboard storyboard) {
        if (storyboard == null) {
            return null;
        }

        Storyboard merged = new Storyboard();
        merged.setHook(storyboard.getHook());
        merged.setSummary(storyboard.getSummary());
        merged.setContinuityPlan(storyboard.getContinuityPlan());
        merged.setGlobalVisualRules(copyStringList(storyboard.getGlobalVisualRules()));

        Map<String, StoryboardObject> registryDefinitions = new LinkedHashMap<>();
        List<StoryboardObject> registryCopies = new ArrayList<>();
        if (storyboard.getObjectRegistry() != null) {
            for (StoryboardObject object : storyboard.getObjectRegistry()) {
                StoryboardObject copy = copyObject(object);
                if (copy == null || isBlank(copy.getId())) {
                    continue;
                }
                copy.setPlacement(null);
                registryDefinitions.put(copy.getId(), copy);
                registryCopies.add(copy);
            }
        }
        merged.setObjectRegistry(registryCopies);

        Map<String, StoryboardObject> visibleState = new LinkedHashMap<>();
        List<StoryboardScene> mergedScenes = new ArrayList<>();
        if (storyboard.getScenes() != null) {
            for (StoryboardScene scene : storyboard.getScenes()) {
                if (scene == null) {
                    continue;
                }
                StoryboardScene mergedScene = copySceneMetadata(scene);

                List<StoryboardObject> enteringObjects =
                        mergeObjects(scene.getEnteringObjects(), registryDefinitions, visibleState);
                List<StoryboardObject> persistentObjects =
                        mergeObjects(scene.getPersistentObjects(), registryDefinitions, visibleState);
                List<StoryboardObject> exitingObjects = copyIdOnlyObjects(scene.getExitingObjects());

                mergedScene.setEnteringObjects(enteringObjects);
                mergedScene.setPersistentObjects(persistentObjects);
                mergedScene.setExitingObjects(exitingObjects);
                mergedScenes.add(mergedScene);

                Map<String, StoryboardObject> nextVisibleState = new LinkedHashMap<>();
                addAllById(nextVisibleState, persistentObjects);
                addAllById(nextVisibleState, enteringObjects);
                for (StoryboardObject exiting : exitingObjects) {
                    String id = objectId(exiting);
                    if (id != null) {
                        nextVisibleState.remove(id);
                    }
                }
                visibleState = nextVisibleState;
            }
        }
        merged.setScenes(mergedScenes);
        return merged;
    }

    public static List<String> idsOf(List<StoryboardObject> objects) {
        List<String> ids = new ArrayList<>();
        if (objects == null) {
            return ids;
        }
        for (StoryboardObject object : objects) {
            String id = objectId(object);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    public static Set<String> idSetOf(List<StoryboardObject> objects) {
        return new LinkedHashSet<>(idsOf(objects));
    }

    public static String objectId(StoryboardObject object) {
        if (object == null || isBlank(object.getId())) {
            return null;
        }
        return object.getId().trim();
    }

    public static StoryboardObject copyObject(StoryboardObject source) {
        if (source == null) {
            return null;
        }
        StoryboardObject copy = new StoryboardObject();
        copy.setId(source.getId());
        copy.setKind(source.getKind());
        copy.setContent(source.getContent());
        copy.setPlacement(copyPlacement(source.getPlacement()));
        copy.setStyle(copyStyles(source.getStyle()));
        copy.setSourceNode(source.getSourceNode());
        copy.setBehavior(source.getBehavior());
        copy.setAnchorId(source.getAnchorId());
        copy.setDependencyNote(source.getDependencyNote());
        copy.setConstraintNote(source.getConstraintNote());
        return copy;
    }

    private static StoryboardScene copySceneMetadata(StoryboardScene source) {
        StoryboardScene copy = new StoryboardScene();
        copy.setSceneId(source.getSceneId());
        copy.setTitle(source.getTitle());
        copy.setGoal(source.getGoal());
        copy.setNarration(source.getNarration());
        copy.setDurationSeconds(source.getDurationSeconds());
        copy.setSceneMode(source.getSceneMode());
        copy.setCameraAnchor(source.getCameraAnchor());
        copy.setCameraPlan(source.getCameraPlan());
        copy.setLayoutGoal(source.getLayoutGoal());
        copy.setSafeAreaPlan(source.getSafeAreaPlan());
        copy.setScreenOverlayPlan(source.getScreenOverlayPlan());
        copy.setGeometryConstraints(copyStringList(source.getGeometryConstraints()));
        copy.setStepRefs(copyStringList(source.getStepRefs()));
        copy.setActions(copyActions(source.getActions()));
        copy.setNotesForCodegen(copyStringList(source.getNotesForCodegen()));
        return copy;
    }

    private static List<StoryboardObject> mergeObjects(List<StoryboardObject> patches,
                                                       Map<String, StoryboardObject> registryDefinitions,
                                                       Map<String, StoryboardObject> visibleState) {
        List<StoryboardObject> mergedObjects = new ArrayList<>();
        if (patches == null) {
            return mergedObjects;
        }
        for (StoryboardObject patch : patches) {
            String id = objectId(patch);
            if (id == null) {
                continue;
            }

            StoryboardObject merged = copyObject(visibleState.get(id));
            if (merged == null) {
                merged = copyObject(registryDefinitions.get(id));
            }
            if (merged == null) {
                merged = new StoryboardObject();
                merged.setId(id);
            }
            applyPatch(merged, patch);
            mergedObjects.add(merged);
        }
        return mergedObjects;
    }

    private static void applyPatch(StoryboardObject target, StoryboardObject patch) {
        if (target == null || patch == null) {
            return;
        }
        if (!isBlank(patch.getId())) {
            target.setId(patch.getId().trim());
        }
        if (!isBlank(patch.getKind())) {
            target.setKind(patch.getKind());
        }
        if (!isBlank(patch.getContent())) {
            target.setContent(patch.getContent());
        }
        if (patch.getPlacement() != null && patch.getPlacement().hasData()) {
            target.setPlacement(copyPlacement(patch.getPlacement()));
        }
        if (patch.getStyle() != null && !patch.getStyle().isEmpty()) {
            target.setStyle(copyStyles(patch.getStyle()));
        } else if (target.getStyle() == null) {
            target.setStyle(new ArrayList<>());
        }
        if (!isBlank(patch.getSourceNode())) {
            target.setSourceNode(patch.getSourceNode());
        }
        if (!isBlank(patch.getBehavior())) {
            target.setBehavior(patch.getBehavior());
        }
        if (!isBlank(patch.getAnchorId())) {
            target.setAnchorId(patch.getAnchorId());
        }
        if (!isBlank(patch.getDependencyNote())) {
            target.setDependencyNote(patch.getDependencyNote());
        }
        if (!isBlank(patch.getConstraintNote())) {
            target.setConstraintNote(patch.getConstraintNote());
        }
    }

    private static List<StoryboardObject> copyIdOnlyObjects(List<StoryboardObject> objects) {
        List<StoryboardObject> copies = new ArrayList<>();
        if (objects == null) {
            return copies;
        }
        for (StoryboardObject object : objects) {
            String id = objectId(object);
            if (id == null) {
                continue;
            }
            StoryboardObject copy = new StoryboardObject();
            copy.setId(id);
            copies.add(copy);
        }
        return copies;
    }

    private static void addAllById(Map<String, StoryboardObject> target, List<StoryboardObject> objects) {
        if (objects == null) {
            return;
        }
        for (StoryboardObject object : objects) {
            String id = objectId(object);
            if (id != null) {
                target.put(id, copyObject(object));
            }
        }
    }

    private static List<StoryboardAction> copyActions(List<StoryboardAction> actions) {
        List<StoryboardAction> copies = new ArrayList<>();
        if (actions == null) {
            return copies;
        }
        for (StoryboardAction action : actions) {
            if (action == null) {
                continue;
            }
            StoryboardAction copy = new StoryboardAction();
            copy.setOrder(action.getOrder());
            copy.setType(action.getType());
            copy.setTargets(copyStringList(action.getTargets()));
            copy.setDescription(action.getDescription());
            copies.add(copy);
        }
        return copies;
    }

    private static List<StoryboardStyle> copyStyles(List<StoryboardStyle> styles) {
        List<StoryboardStyle> copies = new ArrayList<>();
        if (styles == null) {
            return copies;
        }
        for (StoryboardStyle style : styles) {
            if (style == null) {
                continue;
            }
            StoryboardStyle copy = new StoryboardStyle();
            copy.setRole(style.getRole());
            copy.setType(style.getType());
            copy.setProperties(style.getProperties() != null
                    ? new LinkedHashMap<>(style.getProperties())
                    : new LinkedHashMap<>());
            copies.add(copy);
        }
        return copies;
    }

    private static StoryboardPlacement copyPlacement(StoryboardPlacement source) {
        if (source == null) {
            return null;
        }
        StoryboardPlacement copy = new StoryboardPlacement();
        copy.setCoordinateSpace(source.getCoordinateSpace());
        copy.setX(copyPlacementAxis(source.getX()));
        copy.setY(copyPlacementAxis(source.getY()));
        copy.setZ(copyPlacementAxis(source.getZ()));
        return copy.hasData() ? copy : null;
    }

    private static StoryboardPlacementAxis copyPlacementAxis(StoryboardPlacementAxis source) {
        if (source == null || !source.hasData()) {
            return null;
        }
        StoryboardPlacementAxis copy = new StoryboardPlacementAxis();
        copy.setValue(source.getValue());
        copy.setMin(source.getMin());
        copy.setMax(source.getMax());
        return copy;
    }

    private static List<String> copyStringList(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
