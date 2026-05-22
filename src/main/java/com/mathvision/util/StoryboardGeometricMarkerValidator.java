package com.mathvision.util;

import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardConstraint;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardScene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Static semantic checks for angle, arc-sweep, and right-angle marker definitions.
 */
public final class StoryboardGeometricMarkerValidator {

    private StoryboardGeometricMarkerValidator() {}

    public static List<String> validateStoryboard(Storyboard storyboard) {
        List<String> issues = new ArrayList<>();
        if (storyboard == null || storyboard.getObjectRegistry() == null) {
            return issues;
        }
        Map<String, StoryboardObject> registry = registryById(storyboard.getObjectRegistry());
        validateMarkerDefinitions("object_registry", storyboard.getObjectRegistry(), registry, registry.keySet(), issues);
        validateAngularBoundaryVertexConsistency("object_registry", storyboard.getObjectRegistry(), registry, registry.keySet(), issues);
        return issues;
    }

    public static List<String> validateSceneDesign(StoryboardScene scene,
                                                   List<StoryboardObject> newObjects,
                                                   List<StoryboardObject> visibleObjects,
                                                   List<StoryboardObject> globalObjects) {
        List<String> issues = new ArrayList<>();
        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        putObjects(registry, globalObjects);
        putObjects(registry, visibleObjects);
        putObjects(registry, newObjects);
        if (scene != null) {
            mergeScenePatches(registry, scene.getEnteringObjects());
            mergeScenePatches(registry, scene.getPersistentObjects());
        }

        Set<String> knownIds = new LinkedHashSet<>();
        addObjectIds(knownIds, visibleObjects);
        addObjectIds(knownIds, newObjects);
        if (scene != null) {
            addObjectIds(knownIds, scene.getEnteringObjects());
            addObjectIds(knownIds, scene.getPersistentObjects());
        }

        List<StoryboardObject> relevantObjects = new ArrayList<>();
        if (newObjects != null) {
            relevantObjects.addAll(newObjects);
        }
        addSceneObjects(relevantObjects, scene != null ? scene.getEnteringObjects() : null, registry);
        addSceneObjects(relevantObjects, scene != null ? scene.getPersistentObjects() : null, registry);

        validateMarkerDefinitions("scene design", relevantObjects, registry, knownIds, issues);
        validateAngularBoundaryVertexConsistency("scene design", relevantObjects, registry, knownIds, issues);
        return issues;
    }

    private static void validateMarkerDefinitions(String scope,
                                                  List<StoryboardObject> objects,
                                                  Map<String, StoryboardObject> registry,
                                                  Set<String> knownIds,
                                                  List<String> issues) {
        if (objects == null) {
            return;
        }
        Set<String> checked = new LinkedHashSet<>();
        for (StoryboardObject object : objects) {
            String objectId = StoryboardPatchResolver.objectId(object);
            if (objectId == null || !checked.add(objectId) || !isAngleOrArcMarker(object)) {
                continue;
            }
            StoryboardObject definition = registry.getOrDefault(objectId, object);
            String kind = normalizeForSemanticCheck(definition != null ? definition.getKind() : object.getKind());
            boolean isRightAngle = isRightAngleMarkerKind(kind);
            boolean isArc = isArcMarkerKind(kind);
            boolean isPlainAngle = isAngleMarkerKind(kind) && !isRightAngle && !isArc;
            boolean hasAngleBetween = hasStructuredConstraint(definition, "angle_between");
            boolean hasArcSweep = hasStructuredConstraint(definition, "arc_sweep");
            boolean hasRightAngle = hasStructuredConstraint(definition, "right_angle_at");
            boolean hasAnyAngular = hasAngleBetween || hasArcSweep || hasRightAngle;

            if (isArc && !hasArcSweep) {
                issues.add(scope + ": arc marker '" + objectId
                        + "' must define the ordered arc sweep with an arc_sweep measurement constraint");
            }
            if (isRightAngle && !hasRightAngle) {
                issues.add(scope + ": right-angle marker '" + objectId
                        + "' must define the displayed right-angle side with a right_angle_at measurement constraint");
            }
            if (isPlainAngle && !hasAngleBetween) {
                issues.add(scope + ": angle marker '" + objectId
                        + "' must define the intended displayed sector with an angle_between measurement constraint");
            }
            if (!hasAnyAngular) {
                issues.add(scope + ": angle/arc marker '" + objectId
                        + "' must define the intended displayed sector with a structured measurement constraint");
            }
            validateObjectConstraints(scope + " object '" + objectId + "'", definition, knownIds, issues);
        }
    }

    private static void validateObjectConstraints(String scope,
                                                  StoryboardObject object,
                                                  Set<String> knownIds,
                                                  List<String> issues) {
        if (object == null || object.getConstraints() == null) {
            return;
        }
        String ownerId = StoryboardPatchResolver.objectId(object);
        for (int i = 0; i < object.getConstraints().size(); i++) {
            StoryboardConstraint constraint = object.getConstraints().get(i);
            if (constraint == null || constraint.getRelation() == null || !isAngularMeasurementRelation(constraint.getRelation())) {
                continue;
            }
            String label = scope + " constraints[" + i + "]";
            Map<String, Object> refs = constraint.getRefs() != null ? constraint.getRefs() : Map.of();
            Map<String, Object> parameters = constraint.getParameters() != null ? constraint.getParameters() : Map.of();
            Set<String> referencedIds = StoryboardConstraintUtils.referencedObjectIds(constraint);
            if (ownerId != null && !referencedIds.contains(ownerId)) {
                issues.add(label + ": object-level angular constraint should include its owner id '" + ownerId + "' in refs");
            }
            for (String refId : referencedIds) {
                if (!knownIds.contains(refId)) {
                    issues.add(label + ": refs references unknown id '" + refId + "'");
                }
            }
            validateAngularConstraintShape(label, constraint, refs, parameters, issues);
        }
    }

    private static void validateAngularConstraintShape(String label,
                                                       StoryboardConstraint constraint,
                                                       Map<String, Object> refs,
                                                       Map<String, Object> parameters,
                                                       List<String> issues) {
        String relation = normalizeConstraintKey(constraint.getRelation());
        switch (relation) {
            case "angle_between":
                requireRef(label, refs, "marker", issues);
                requireRef(label, refs, "vertex", issues);
                requireAnyRef(label, refs, issues, "line_a", "start_boundary", "ray_a");
                requireAnyRef(label, refs, issues, "line_b", "end_boundary", "ray_b");
                requireParameter(label, parameters, "sector", issues);
                break;
            case "arc_sweep":
                requireAnyRef(label, refs, issues, "marker", "arc");
                requireAnyRef(label, refs, issues, "center", "anchor", "vertex");
                String startBoundary = requireRef(label, refs, "start_boundary", issues);
                String endBoundary = requireRef(label, refs, "end_boundary", issues);
                if (startBoundary != null && startBoundary.equals(endBoundary)) {
                    issues.add(label + ": relation 'arc_sweep' requires distinct start_boundary and end_boundary refs");
                }
                requireParameter(label, parameters, "direction", issues);
                requireParameter(label, parameters, "sector", issues);
                break;
            case "right_angle_at":
                requireRef(label, refs, "marker", issues);
                requireRef(label, refs, "vertex", issues);
                requireRef(label, refs, "start_boundary", issues);
                requireRef(label, refs, "end_boundary", issues);
                requireParameter(label, parameters, "side_of_reference", issues);
                break;
            default:
                break;
        }
    }

    private static void validateAngularBoundaryVertexConsistency(String scope,
                                                                 List<StoryboardObject> objects,
                                                                 Map<String, StoryboardObject> registry,
                                                                 Set<String> knownIds,
                                                                 List<String> issues) {
        if (objects == null) {
            return;
        }
        Set<String> checked = new LinkedHashSet<>();
        for (StoryboardObject object : objects) {
            String objectId = StoryboardPatchResolver.objectId(object);
            if (objectId == null || !checked.add(objectId)) {
                continue;
            }
            StoryboardObject definition = registry.getOrDefault(objectId, object);
            if (definition == null || definition.getConstraints() == null) {
                continue;
            }
            for (StoryboardConstraint constraint : definition.getConstraints()) {
                if (constraint == null || constraint.getRefs() == null
                        || !isAngularMeasurementRelation(constraint.getRelation())) {
                    continue;
                }
                Map<String, Object> refs = constraint.getRefs();
                String vertexId = firstRefId(refs, "vertex", "center", "anchor");
                if (vertexId == null) {
                    continue;
                }
                for (String refKey : List.of("line_a", "line_b", "start_boundary", "end_boundary", "ray_a", "ray_b")) {
                    String boundaryId = firstRefId(refs, refKey);
                    if (boundaryId == null) {
                        continue;
                    }
                    StoryboardObject boundaryObject = knownIds.contains(boundaryId) ? registry.get(boundaryId) : null;
                    if (boundaryObject == null || constraintReferences(boundaryObject, vertexId)) {
                        continue;
                    }
                    String boundaryKind = normalizeForSemanticCheck(boundaryObject.getKind());
                    boolean lineLikeBoundary = containsAny(boundaryKind, " segment ", " line ", " ray ");
                    boolean perpendicularHelper = hasStructuredConstraint(boundaryObject, "perpendicular_through", "perpendicular_bisector")
                            || containsAny(normalizeForSemanticCheck(boundaryObject.getContent()), " perpendicular ", " normal ");
                    if (lineLikeBoundary && !perpendicularHelper) {
                        issues.add(scope + ": angular marker '" + objectId + "' constraint references '"
                                + boundaryId + "' as " + refKey + ", but '" + boundaryId
                                + "' has no constraint referencing vertex '" + vertexId
                                + "'; the boundary line may not pass through the declared vertex/anchor");
                    } else if (perpendicularHelper) {
                        issues.add(scope + ": angular marker '" + objectId + "' constraint references '"
                                + boundaryId + "' as " + refKey + ", but '" + boundaryId
                                + "' is a perpendicular/normal that does not reference vertex '"
                                + vertexId + "'; use a normal at the declared vertex/anchor instead");
                    }
                }
            }
        }
    }

    private static String requireRef(String label, Map<String, Object> refs, String role, List<String> issues) {
        String id = firstRefId(refs, role);
        if (id == null) {
            issues.add(label + ": relation requires refs role '" + role + "'");
        }
        return id;
    }

    private static String requireAnyRef(String label, Map<String, Object> refs, List<String> issues, String... roles) {
        String id = firstRefId(refs, roles);
        if (id == null) {
            issues.add(label + ": relation requires refs role one of [" + String.join(", ", roles) + "]");
        }
        return id;
    }

    private static void requireParameter(String label, Map<String, Object> parameters, String key, List<String> issues) {
        if (!parameters.containsKey(key) || parameters.get(key) == null
                || (parameters.get(key) instanceof String && ((String) parameters.get(key)).isBlank())) {
            issues.add(label + ": relation requires parameter '" + key + "'");
        }
    }

    private static Map<String, StoryboardObject> registryById(List<StoryboardObject> objects) {
        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        putObjects(registry, objects);
        return registry;
    }

    private static void putObjects(Map<String, StoryboardObject> registry, List<StoryboardObject> objects) {
        if (objects == null) {
            return;
        }
        for (StoryboardObject object : objects) {
            String id = StoryboardPatchResolver.objectId(object);
            if (id != null) {
                registry.put(id, object);
            }
        }
    }

    private static void mergeScenePatches(Map<String, StoryboardObject> registry, List<StoryboardObject> patches) {
        if (patches == null) {
            return;
        }
        for (StoryboardObject patch : patches) {
            String id = StoryboardPatchResolver.objectId(patch);
            if (id != null && !registry.containsKey(id)) {
                registry.put(id, patch);
            }
        }
    }

    private static void addObjectIds(Set<String> target, List<StoryboardObject> objects) {
        if (target == null || objects == null) {
            return;
        }
        for (StoryboardObject object : objects) {
            String id = StoryboardPatchResolver.objectId(object);
            if (id != null) {
                target.add(id);
            }
        }
    }

    private static void addSceneObjects(List<StoryboardObject> relevantObjects,
                                        List<StoryboardObject> sceneObjects,
                                        Map<String, StoryboardObject> registry) {
        if (sceneObjects == null) {
            return;
        }
        for (StoryboardObject sceneObject : sceneObjects) {
            String id = StoryboardPatchResolver.objectId(sceneObject);
            if (id == null) {
                continue;
            }
            relevantObjects.add(registry.getOrDefault(id, sceneObject));
        }
    }

    private static String firstRefId(Map<String, Object> refs, String... roles) {
        if (refs == null || roles == null) {
            return null;
        }
        for (String role : roles) {
            String id = refId(refs.get(role));
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static String refId(Object value) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private static boolean constraintReferences(StoryboardObject object, String objectId) {
        if (object == null || objectId == null || objectId.isBlank() || object.getConstraints() == null) {
            return false;
        }
        for (StoryboardConstraint constraint : object.getConstraints()) {
            if (StoryboardConstraintUtils.referencedObjectIds(constraint).contains(objectId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAngleOrArcMarker(StoryboardObject object) {
        if (object == null) {
            return false;
        }
        String kind = normalizeForSemanticCheck(object.getKind());
        if (isTextRenderKind(kind)) {
            return false;
        }
        return isAngleOrArcMarkerKind(kind) || hasStructuredConstraint(object,
                "angle_between", "arc_sweep", "right_angle_at");
    }

    private static boolean isAngleOrArcMarkerKind(String kind) {
        return isAngleMarkerKind(kind) || isArcMarkerKind(kind) || isRightAngleMarkerKind(kind);
    }

    private static boolean isAngleMarkerKind(String kind) {
        return containsAny(kind, " angle_marker ", " anglemarker ");
    }

    private static boolean isArcMarkerKind(String kind) {
        return containsAny(kind, " arc ", " arc_marker ");
    }

    private static boolean isRightAngleMarkerKind(String kind) {
        return containsAny(kind, " right_angle ", " rightangle ");
    }

    private static boolean hasStructuredConstraint(StoryboardObject object, String... relations) {
        if (object == null || object.getConstraints() == null || object.getConstraints().isEmpty()) {
            return false;
        }
        for (StoryboardConstraint constraint : object.getConstraints()) {
            if (constraint == null || constraint.getRelation() == null) {
                continue;
            }
            String relation = normalizeForSemanticCheck(constraint.getRelation());
            if (containsAny(relation, relations)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAngularMeasurementRelation(String relation) {
        String normalized = normalizeForSemanticCheck(relation);
        return containsAny(normalized, " angle_between ", " arc_sweep ", " right_angle_at ");
    }

    private static boolean isTextRenderKind(String kind) {
        return containsAny(kind,
                " text ", " label ", " text_card ", " equation ", " formula ", " formula_card ",
                " title ", " caption ");
    }

    private static String normalizeForSemanticCheck(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_*']+", " ").trim() + " ";
    }

    private static String normalizeConstraintKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
