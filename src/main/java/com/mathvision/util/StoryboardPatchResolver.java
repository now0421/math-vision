package com.mathvision.util;

import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardAction;
import com.mathvision.model.Narrative.StoryboardConstraint;
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
        copy.setStyle(copyStyle(source.getStyle()));
        copy.setConstraints(copyConstraints(source.getConstraints()));
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
        copy.setConstraints(copyConstraints(source.getConstraints()));
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
        if (patch.getPlacement() != null && patch.getPlacement().hasData()) {
            target.setPlacement(copyPlacement(patch.getPlacement()));
        }
        if (patch.getStyle() != null && patch.getStyle().hasData()) {
            target.setStyle(copyStyle(patch.getStyle()));
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
            copy.setVoiceoverText(action.getVoiceoverText());
            copy.setExpectedSeconds(action.getExpectedSeconds());
            copies.add(copy);
        }
        return copies;
    }

    private static StoryboardStyle copyStyle(StoryboardStyle style) {
        if (style == null) {
            return null;
        }
        StoryboardStyle copy = new StoryboardStyle();
        copy.setColor(style.getColor());
        copy.setFillColor(style.getFillColor());
        copy.setStrokeColor(style.getStrokeColor());
        copy.setHighlightColor(style.getHighlightColor());
        copy.setFontFamily(style.getFontFamily());
        copy.setFontWeight(style.getFontWeight());
        copy.setFontStyle(style.getFontStyle());
        copy.setLineStyle(style.getLineStyle());
        copy.setOpacity(style.getOpacity());
        copy.setFillOpacity(style.getFillOpacity());
        copy.setStrokeOpacity(style.getStrokeOpacity());
        copy.setStrokeWidth(style.getStrokeWidth());
        copy.setFontSize(style.getFontSize());
        copy.setPadding(style.getPadding());
        copy.setCornerRadius(style.getCornerRadius());
        copy.setZIndex(style.getZIndex());
        copy.setPointSize(style.getPointSize());
        copy.setRadius(style.getRadius());
        copy.setMarkerSize(style.getMarkerSize());
        copy.setPointStyle(style.getPointStyle());
        copy.setDecoration(style.getDecoration());
        copy.setLabelVisible(style.getLabelVisible());
        return copy.hasData() ? copy : null;
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

    private static List<StoryboardConstraint> copyConstraints(List<StoryboardConstraint> constraints) {
        List<StoryboardConstraint> copies = new ArrayList<>();
        if (constraints == null) {
            return copies;
        }
        for (StoryboardConstraint constraint : constraints) {
            if (constraint == null || !constraint.hasData()) {
                continue;
            }
            StoryboardConstraint copy = new StoryboardConstraint();
            copy.setId(constraint.getId());
            copy.setDomain(constraint.getDomain());
            copy.setRelation(constraint.getRelation());
            copy.setRefs(copyObjectMap(constraint.getRefs()));
            copy.setParameters(copyObjectMap(constraint.getParameters()));
            copy.setStrength(constraint.getStrength());
            copy.setReason(constraint.getReason());
            copies.add(copy);
        }
        return copies;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyObjectMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            copy.put(entry.getKey(), copyObjectValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object copyObjectValue(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), copyObjectValue(entry.getValue()));
                }
            }
            return copy;
        }
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(copyObjectValue(item));
            }
            return copy;
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
