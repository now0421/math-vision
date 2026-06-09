package com.mathvision.util;

import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardConstraint;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.util.StoryboardConstraintCatalog.RelationSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constraint-first helpers for storyboard semantic relationships.
 */
public final class StoryboardConstraintUtils {

    private StoryboardConstraintUtils() {}

    public static List<StoryboardConstraint> constraintsOf(StoryboardObject object) {
        return object != null && object.getConstraints() != null ? object.getConstraints() : List.of();
    }

    public static List<StoryboardConstraint> hardConstraintsOf(StoryboardObject object) {
        List<StoryboardConstraint> hard = new ArrayList<>();
        for (StoryboardConstraint constraint : constraintsOf(object)) {
            if (isHard(constraint)) {
                hard.add(constraint);
            }
        }
        return hard;
    }

    public static boolean isHard(StoryboardConstraint constraint) {
        if (constraint == null) {
            return false;
        }
        String strength = constraint.getStrength();
        return strength == null || strength.isBlank()
                || "hard".equalsIgnoreCase(strength.trim())
                || "repair_hard".equalsIgnoreCase(strength.trim());
    }

    public static Set<String> referencedObjectIds(StoryboardConstraint constraint) {
        Set<String> ids = new LinkedHashSet<>();
        if (constraint == null || constraint.getRefs() == null) {
            return ids;
        }
        for (Object value : constraint.getRefs().values()) {
            collectRefIds(value, ids);
        }
        return ids;
    }

    public static Set<String> ownerIds(StoryboardConstraint constraint) {
        Set<String> ids = new LinkedHashSet<>();
        if (constraint == null || constraint.getRefs() == null) {
            return ids;
        }
        RelationSpec spec = StoryboardConstraintCatalog.relation(constraint.getDomain(), constraint.getRelation());
        Set<String> roles = spec != null ? spec.ownerRefRoles() : Set.of();
        for (String role : roles) {
            collectRefIds(constraint.getRefs().get(role), ids);
        }
        return ids;
    }

    public static Set<String> dependencyIds(StoryboardConstraint constraint) {
        Set<String> ids = new LinkedHashSet<>();
        if (constraint == null || constraint.getRefs() == null) {
            return ids;
        }
        RelationSpec spec = StoryboardConstraintCatalog.relation(constraint.getDomain(), constraint.getRelation());
        Set<String> roles = spec != null ? spec.dependencyRefRoles() : Set.of();
        for (String role : roles) {
            collectRefIds(constraint.getRefs().get(role), ids);
        }
        ids.removeAll(ownerIds(constraint));
        return ids;
    }

    public static List<Set<String>> requiredDependencyIdGroups(StoryboardConstraint constraint) {
        List<Set<String>> groups = new ArrayList<>();
        if (constraint == null || constraint.getRefs() == null) {
            return groups;
        }
        RelationSpec spec = StoryboardConstraintCatalog.relation(constraint.getDomain(), constraint.getRelation());
        if (spec == null) {
            return groups;
        }
        Set<String> ownerRoles = spec.ownerRefRoles();
        Set<String> owners = ownerIds(constraint);
        for (Set<String> requiredRoles : spec.requiredRefGroups()) {
            if (requiredRoles.stream().anyMatch(ownerRoles::contains)) {
                continue;
            }
            Set<String> ids = new LinkedHashSet<>();
            for (String role : requiredRoles) {
                collectRefIds(constraint.getRefs().get(role), ids);
            }
            ids.removeAll(owners);
            if (!ids.isEmpty()) {
                groups.add(ids);
            }
        }
        return groups;
    }

    public static boolean isAttachmentConstraint(StoryboardConstraint constraint) {
        return constraint != null
                && StoryboardConstraintCatalog.isAttachmentRelation(constraint.getDomain(), constraint.getRelation());
    }

    public static boolean isMotionConstraint(StoryboardConstraint constraint) {
        if (constraint == null) {
            return false;
        }
        RelationSpec spec = StoryboardConstraintCatalog.relation(constraint.getDomain(), constraint.getRelation());
        return spec != null && "motion".equals(spec.domain());
    }

    public static boolean isCoordinateDerivedConstraint(StoryboardConstraint constraint) {
        return constraint != null
                && StoryboardConstraintCatalog.isCoordinateDerivedRelation(constraint.getDomain(), constraint.getRelation());
    }

    public static boolean isGeoGebraDefaultPlaceableConstraint(StoryboardConstraint constraint) {
        return constraint != null
                && StoryboardConstraintCatalog.isGeoGebraDefaultPlaceableRelation(
                        constraint.getDomain(), constraint.getRelation());
    }

    public static boolean isMotionSensitiveConstraint(StoryboardConstraint constraint) {
        return constraint != null
                && StoryboardConstraintCatalog.isMotionSensitiveRelation(constraint.getDomain(), constraint.getRelation());
    }

    public static boolean isObjectMotionConstrained(String objectId, Storyboard storyboard) {
        if (objectId == null || storyboard == null) {
            return false;
        }
        for (StoryboardConstraint constraint : allConstraints(storyboard)) {
            if (isMotionConstraint(constraint) && ownerIds(constraint).contains(objectId)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isObjectCoordinateDerived(String objectId, Storyboard storyboard) {
        if (objectId == null || storyboard == null) {
            return false;
        }
        for (StoryboardConstraint constraint : allConstraints(storyboard)) {
            if (isCoordinateDerivedConstraint(constraint) && ownerIds(constraint).contains(objectId)) {
                return true;
            }
        }
        return false;
    }

    public static List<StoryboardConstraint> findConstraintsReferencing(String objectId, Storyboard storyboard) {
        if (objectId == null || storyboard == null) {
            return List.of();
        }
        List<StoryboardConstraint> matches = new ArrayList<>();
        for (StoryboardConstraint constraint : allConstraints(storyboard)) {
            if (referencedObjectIds(constraint).contains(objectId)) {
                matches.add(constraint);
            }
        }
        return matches;
    }

    public static List<StoryboardConstraint> findAttachmentConstraints(String objectId, Storyboard storyboard) {
        if (objectId == null || storyboard == null) {
            return List.of();
        }
        List<StoryboardConstraint> matches = new ArrayList<>();
        for (StoryboardConstraint constraint : allConstraints(storyboard)) {
            if (isAttachmentConstraint(constraint) && ownerIds(constraint).contains(objectId)) {
                matches.add(constraint);
            }
        }
        return matches;
    }

    public static List<StoryboardConstraint> allConstraints(Storyboard storyboard) {
        List<StoryboardConstraint> constraints = new ArrayList<>();
        if (storyboard == null) {
            return constraints;
        }
        if (storyboard.getObjectRegistry() != null) {
            for (StoryboardObject object : storyboard.getObjectRegistry()) {
                constraints.addAll(constraintsOf(object));
            }
        }
        if (storyboard.getScenes() != null) {
            for (StoryboardScene scene : storyboard.getScenes()) {
                if (scene != null && scene.getConstraints() != null) {
                    constraints.addAll(scene.getConstraints());
                }
            }
        }
        return constraints;
    }

    public static Map<String, StoryboardObject> registryById(Storyboard storyboard) {
        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        if (storyboard == null || storyboard.getObjectRegistry() == null) {
            return registry;
        }
        for (StoryboardObject object : storyboard.getObjectRegistry()) {
            String id = StoryboardPatchResolver.objectId(object);
            if (id != null) {
                registry.put(id, object);
            }
        }
        return registry;
    }

    private static void collectRefIds(Object value, Set<String> ids) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (!text.isEmpty()) {
                ids.add(text);
            }
            return;
        }
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectRefIds(item, ids);
            }
            return;
        }
        if (value instanceof Map<?, ?>) {
            for (Object nested : ((Map<?, ?>) value).values()) {
                collectRefIds(nested, ids);
            }
        }
    }
}
