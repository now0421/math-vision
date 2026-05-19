package com.mathvision.util;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardConstraint;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.util.StoryboardConstraintCatalog.RelationSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, backend-specific static checks for hard storyboard constraints.
 */
public final class StoryboardConstraintComplianceAnalyzer {

    public static final String RULE_ID = "constraint_compliance";

    private final Map<String, StoryboardObject> registry = new LinkedHashMap<>();
    private final List<StoryboardConstraint> constraints = new ArrayList<>();
    private final Map<String, Boolean> dynamicCache = new LinkedHashMap<>();

    public List<Violation> analyze(Storyboard storyboard, String outputTarget, String generatedCode) {
        registry.clear();
        constraints.clear();
        dynamicCache.clear();

        if (storyboard == null || generatedCode == null || generatedCode.isBlank()) {
            return List.of();
        }
        registry.putAll(StoryboardConstraintUtils.registryById(storyboard));
        constraints.addAll(StoryboardConstraintUtils.allConstraints(storyboard));

        boolean geoGebra = WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equalsIgnoreCase(safe(outputTarget));
        List<Violation> violations = new ArrayList<>();
        for (StoryboardConstraint constraint : constraints) {
            if (constraint == null || constraint.getRelation() == null || constraint.getRelation().isBlank()) {
                continue;
            }
            Violation violation = geoGebra
                    ? analyzeGeoGebra(constraint, generatedCode)
                    : analyzeManim(constraint, generatedCode);
            if (violation != null) {
                violations.add(violation);
            }
        }
        return violations;
    }

    private Violation analyzeManim(StoryboardConstraint constraint, String code) {
        String relation = normalize(constraint.getRelation());
        switch (relation) {
            case "label_for":
            case "fixed_offset_from":
            case "anchored_to":
                return analyzeManimAttachment(constraint, code);
            case "connects_points":
            case "line_through_points":
            case "ray_from_to":
                return analyzeManimConnector(constraint, code);
            case "moves_on_object":
            case "lies_on":
            case "follows_path":
                return analyzeManimPathConstraint(constraint, code);
            case "intersection_of":
            case "reflection_across":
            case "midpoint_of":
            case "projection_onto":
            case "perpendicular_through":
            case "parallel_through":
            case "angle_between":
            case "arc_sweep":
            case "right_angle_at":
                return analyzeDependencyImplementation(constraint, code, false);
            default:
                return null;
        }
    }

    private Violation analyzeGeoGebra(StoryboardConstraint constraint, String code) {
        String relation = normalize(constraint.getRelation());
        if ("label_for".equals(relation)) {
            return null;
        }
        switch (relation) {
            case "moves_on_object":
            case "lies_on":
            case "follows_path":
                return analyzeGeoGebraPathConstraint(constraint, code);
            case "connects_points":
            case "line_through_points":
            case "ray_from_to":
                return analyzeGeoGebraConnector(constraint, code);
            case "intersection_of":
            case "reflection_across":
            case "midpoint_of":
            case "projection_onto":
            case "perpendicular_through":
            case "parallel_through":
            case "midpoint":
            case "angle_between":
            case "arc_sweep":
            case "right_angle_at":
                return analyzeDependencyImplementation(constraint, code, true);
            default:
                return null;
        }
    }

    private Violation analyzeManimAttachment(StoryboardConstraint constraint, String code) {
        String attachedId = firstRef(constraint, "label", "object", "attached");
        String anchorId = firstRef(constraint, "anchor");
        if (attachedId == null || anchorId == null) {
            return null;
        }
        if (!mentionsObject(code, attachedId)) {
            return violation(constraint, attachedId,
                    "attached object id '" + attachedId + "' does not appear in generated Manim code",
                    "Create the attached text/overlay object and implement the attachment from refs.");
        }
        if (!isDynamicObject(anchorId)) {
            return null;
        }
        if (hasDynamicUpdateFor(code, attachedId) || hasSynchronizedMovement(code, attachedId, anchorId)) {
            return null;
        }
        return violation(constraint, attachedId,
                "anchor '" + anchorId + "' is motion-sensitive, but attached object '" + attachedId
                        + "' is not maintained by always_redraw/add_updater/synchronized movement",
                "Wrap the attached object in always_redraw(...) or add an updater that recomputes its offset from the anchor.");
    }

    private Violation analyzeManimConnector(StoryboardConstraint constraint, String code) {
        String ownerId = firstOwnerId(constraint);
        String startId = firstRef(constraint, "start", "point_a", "from");
        String endId = firstRef(constraint, "end", "point_b", "to", "through");
        if (ownerId == null || startId == null || endId == null) {
            return null;
        }
        if (!mentionsObject(code, ownerId)) {
            return violation(constraint, ownerId,
                    "connector id '" + ownerId + "' does not appear in generated Manim code",
                    "Create the connector from its endpoint refs rather than omitting it.");
        }
        if (!objectDefinitionMentionsAny(code, ownerId, startId, endId)) {
            return violation(constraint, ownerId,
                    "connector '" + ownerId + "' is not visibly constructed from endpoint refs '"
                            + startId + "' and '" + endId + "'",
                    "Construct the connector from the referenced endpoint objects, not from unrelated coordinates.");
        }
        if ((isDynamicObject(startId) || isDynamicObject(endId)) && !hasDynamicUpdateFor(code, ownerId)) {
            return violation(constraint, ownerId,
                    "connector '" + ownerId + "' depends on a moving endpoint but has no updater/always_redraw recomputation",
                    "Use always_redraw(lambda: Line/Segment(...)) or an updater that refreshes both endpoints.");
        }
        return null;
    }

    private Violation analyzeManimPathConstraint(StoryboardConstraint constraint, String code) {
        String pointId = firstRef(constraint, "point", "object");
        String supportId = firstRef(constraint, "support", "path");
        if (pointId == null || supportId == null) {
            return null;
        }
        if (!mentionsObject(code, pointId)) {
            return violation(constraint, pointId,
                    "constrained point/object '" + pointId + "' does not appear in generated Manim code",
                    "Create the constrained object and keep it tied to its support ref.");
        }
        if (!mentionsObject(code, supportId)) {
            return violation(constraint, pointId,
                    "support ref '" + supportId + "' for constrained object '" + pointId + "' does not appear in generated Manim code",
                    "Construct and reference the support object when positioning or animating the constrained point.");
        }
        if (hasFreeShiftAnimation(code, pointId) && !hasPathMotion(code, pointId, supportId)) {
            return violation(constraint, pointId,
                    "constrained object '" + pointId + "' is animated freely instead of along support '" + supportId + "'",
                    "Use MoveAlongPath, a support-based ValueTracker/updater, or another path-preserving construction.");
        }
        return null;
    }

    private Violation analyzeGeoGebraPathConstraint(StoryboardConstraint constraint, String code) {
        String pointId = firstRef(constraint, "point", "object");
        String supportId = firstRef(constraint, "support", "path");
        if (pointId == null || supportId == null) {
            return null;
        }
        String definition = findGeoGebraDefinition(code, pointId);
        if (definition == null) {
            return violation(constraint, pointId,
                    "constrained point '" + pointId + "' is not defined in GeoGebra code",
                    "Define it with a path-dependent command such as Point(" + supportId + ").");
        }
        if (isGeoGebraFreeCoordinateDefinition(definition)) {
            return violation(constraint, pointId,
                    "constrained point '" + pointId + "' is defined as free coordinates: " + abbreviate(definition),
                    "Use Point(" + supportId + ") or an equivalent path-dependent expression.");
        }
        if (!definitionContains(definition, supportId) && !containsCommand(definition, "Point", "ClosestPoint", "Intersect")) {
            return violation(constraint, pointId,
                    "constrained point '" + pointId + "' definition does not depend on support '" + supportId + "': "
                            + abbreviate(definition),
                    "Reference the support object in the point definition.");
        }
        return null;
    }

    private Violation analyzeGeoGebraConnector(StoryboardConstraint constraint, String code) {
        String ownerId = firstOwnerId(constraint);
        String startId = firstRef(constraint, "start", "point_a", "from");
        String endId = firstRef(constraint, "end", "point_b", "to", "through");
        if (ownerId == null || startId == null || endId == null) {
            return null;
        }
        String definition = findGeoGebraDefinition(code, ownerId);
        if (definition == null) {
            return violation(constraint, ownerId,
                    "connector '" + ownerId + "' is not defined in GeoGebra code",
                    "Define it with a native dependency command using the endpoint refs.");
        }
        if (!definitionContains(definition, startId) || !definitionContains(definition, endId)) {
            return violation(constraint, ownerId,
                    "connector '" + ownerId + "' definition does not reference both endpoints: " + abbreviate(definition),
                    "Use Segment/Line/Ray(" + startId + ", " + endId + ") or an equivalent native command.");
        }
        return null;
    }

    private Violation analyzeDependencyImplementation(StoryboardConstraint constraint, String code, boolean geoGebra) {
        String ownerId = firstOwnerId(constraint);
        Set<String> dependencies = StoryboardConstraintUtils.dependencyIds(constraint);
        if (ownerId == null || dependencies.isEmpty()) {
            return null;
        }
        if (geoGebra) {
            String definition = findGeoGebraDefinition(code, ownerId);
            if (definition == null) {
                return violation(constraint, ownerId,
                        "derived object '" + ownerId + "' is not defined in GeoGebra code",
                        "Define it with the native dependency command matching relation '" + constraint.getRelation() + "'.");
            }
            if (isGeoGebraFreeCoordinateDefinition(definition)) {
                return violation(constraint, ownerId,
                        "derived object '" + ownerId + "' is defined as free coordinates: " + abbreviate(definition),
                        "Use a native dependency command such as Intersect, Reflect, Midpoint, Angle, or Segment.");
            }
            if (!mentionsAnyDefinitionDependency(definition, dependencies)) {
                return violation(constraint, ownerId,
                        "derived object '" + ownerId + "' definition does not reference source refs " + dependencies
                                + ": " + abbreviate(definition),
                        "Reference the source refs in the native construction command.");
            }
            return null;
        }

        if (!mentionsObject(code, ownerId)) {
            return violation(constraint, ownerId,
                    "derived object '" + ownerId + "' does not appear in generated Manim code",
                    "Create the derived object from its source refs.");
        }
        if (!mentionsAnyDefinitionDependency(findObjectLines(code, ownerId), dependencies)) {
            return violation(constraint, ownerId,
                    "derived object '" + ownerId + "' is not constructed from source refs " + dependencies,
                    "Compute or redraw it from the referenced objects instead of hardcoded placement coordinates.");
        }
        if (dependencies.stream().anyMatch(this::isDynamicObject) && !hasDynamicUpdateFor(code, ownerId)) {
            return violation(constraint, ownerId,
                    "derived object '" + ownerId + "' depends on moving refs but is not dynamically recomputed",
                    "Use always_redraw(...) or an updater so the derived construction follows its refs.");
        }
        return null;
    }

    private boolean isDynamicObject(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return false;
        }
        return isDynamicObject(objectId, new LinkedHashSet<>());
    }

    private boolean isDynamicObject(String objectId, Set<String> visiting) {
        Boolean cached = dynamicCache.get(objectId);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(objectId)) {
            return false;
        }
        for (StoryboardConstraint constraint : constraints) {
            Set<String> owners = StoryboardConstraintUtils.ownerIds(constraint);
            if (!owners.contains(objectId)) {
                continue;
            }
            if (StoryboardConstraintUtils.isMotionConstraint(constraint)) {
                dynamicCache.put(objectId, true);
                visiting.remove(objectId);
                return true;
            }
            if (StoryboardConstraintUtils.isMotionSensitiveConstraint(constraint)) {
                for (String dependency : StoryboardConstraintUtils.dependencyIds(constraint)) {
                    if (isDynamicObject(dependency, visiting)) {
                        dynamicCache.put(objectId, true);
                        visiting.remove(objectId);
                        return true;
                    }
                }
            }
        }
        visiting.remove(objectId);
        dynamicCache.put(objectId, false);
        return false;
    }

    private Violation violation(StoryboardConstraint constraint, String owner, String evidence, String repairHint) {
        return new Violation(
                normalize(constraint.getRelation()),
                constraint.getRefs() != null ? new LinkedHashMap<>(constraint.getRefs()) : Map.of(),
                owner,
                evidence,
                repairHint,
                StoryboardConstraintUtils.isHard(constraint) ? "fail" : "warn",
                safe(constraint.getStrength()).isBlank() ? "hard" : constraint.getStrength().trim());
    }

    private String firstOwnerId(StoryboardConstraint constraint) {
        Set<String> owners = StoryboardConstraintUtils.ownerIds(constraint);
        if (!owners.isEmpty()) {
            return owners.iterator().next();
        }
        RelationSpec spec = StoryboardConstraintCatalog.relation(constraint.getRelation());
        if (spec == null) {
            return null;
        }
        for (String role : spec.ownerRefRoles()) {
            String id = firstRef(constraint, role);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private String firstRef(StoryboardConstraint constraint, String... roles) {
        if (constraint == null || constraint.getRefs() == null || roles == null) {
            return null;
        }
        for (String role : roles) {
            Object value = constraint.getRefs().get(role);
            String id = firstRefId(value);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private String firstRefId(Object value) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                String id = firstRefId(item);
                if (id != null) {
                    return id;
                }
            }
        }
        if (value instanceof Map<?, ?>) {
            for (Object nested : ((Map<?, ?>) value).values()) {
                String id = firstRefId(nested);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    private boolean hasDynamicUpdateFor(String code, String objectId) {
        String objectLines = findObjectLines(code, objectId);
        return containsAnyIgnoreCase(objectLines,
                "always_redraw", "add_updater", ".add_updater", "updater", "f_always", "always(", ".become(")
                || nearbyDynamicConstruct(code, objectId);
    }

    private boolean nearbyDynamicConstruct(String code, String objectId) {
        if (code == null || objectId == null) {
            return false;
        }
        String[] lines = code.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!containsIdentifier(lines[i], objectId)) {
                continue;
            }
            int start = Math.max(0, i - 2);
            int end = Math.min(lines.length - 1, i + 2);
            StringBuilder block = new StringBuilder();
            for (int j = start; j <= end; j++) {
                block.append(lines[j]).append('\n');
            }
            if (containsAnyIgnoreCase(block.toString(), "always_redraw", "add_updater", ".become(", "put_start_and_end_on")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSynchronizedMovement(String code, String objectId, String anchorId) {
        String block = findObjectLines(code, objectId) + "\n" + findObjectLines(code, anchorId);
        return containsAnyIgnoreCase(block, "VGroup", "AnimationGroup", "UpdateFromFunc", "MaintainPositionRelativeTo");
    }

    private boolean objectDefinitionMentionsAny(String code, String objectId, String... dependencyIds) {
        String lines = findObjectLines(code, objectId);
        for (String dependencyId : dependencyIds) {
            if (containsIdentifier(lines, dependencyId)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFreeShiftAnimation(String code, String objectId) {
        String lines = findObjectLines(code, objectId);
        return containsAnyIgnoreCase(lines, ".animate.shift", ".shift(", ".move_to(", "ApplyMethod")
                && !containsAnyIgnoreCase(lines, "MoveAlongPath", "point_from_proportion", "get_projection", "always_redraw", "add_updater");
    }

    private boolean hasPathMotion(String code, String pointId, String supportId) {
        String lines = findObjectLines(code, pointId) + "\n" + findObjectLines(code, supportId);
        return containsAnyIgnoreCase(lines, "MoveAlongPath", "point_from_proportion", "point_from_proportion", "get_projection")
                || (containsIdentifier(lines, pointId) && containsIdentifier(lines, supportId)
                && containsAnyIgnoreCase(lines, "always_redraw", "add_updater", "ValueTracker"));
    }

    private String findObjectLines(String code, String objectId) {
        if (code == null || objectId == null || objectId.isBlank()) {
            return "";
        }
        String[] lines = code.split("\\R", -1);
        StringBuilder matches = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (!containsIdentifier(lines[i], objectId)) {
                continue;
            }
            int start = Math.max(0, i - 1);
            int end = Math.min(lines.length - 1, i + 1);
            for (int j = start; j <= end; j++) {
                matches.append(lines[j]).append('\n');
            }
        }
        return matches.toString();
    }

    private String findGeoGebraDefinition(String code, String objectId) {
        if (code == null || objectId == null || objectId.isBlank()) {
            return null;
        }
        for (String command : GeoGebraCodeUtils.extractCommands(code)) {
            String trimmed = command.trim();
            if (trimmed.matches("(?i)^" + Pattern.quote(objectId) + "\\s*[:=].*")) {
                return trimmed;
            }
        }
        return null;
    }

    private boolean isGeoGebraFreeCoordinateDefinition(String definition) {
        if (definition == null) {
            return false;
        }
        String rhs = definition.replaceFirst("^[A-Za-z][A-Za-z0-9_'{}]*\\s*[:=]\\s*", "").trim();
        return rhs.matches("^\\(?\\s*-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?(?:\\s*,\\s*-?\\d+(?:\\.\\d+)?)?\\s*\\)?$");
    }

    private boolean mentionsAnyDefinitionDependency(String text, Set<String> dependencies) {
        if (text == null || dependencies == null || dependencies.isEmpty()) {
            return false;
        }
        for (String dependency : dependencies) {
            if (containsIdentifier(text, dependency)) {
                return true;
            }
        }
        return false;
    }

    private boolean mentionsObject(String code, String objectId) {
        return containsIdentifier(code, objectId);
    }

    private boolean definitionContains(String text, String objectId) {
        return containsIdentifier(text, objectId);
    }

    private boolean containsCommand(String text, String... commandNames) {
        if (text == null || commandNames == null) {
            return false;
        }
        for (String commandName : commandNames) {
            if (text.matches("(?is).*\\b" + Pattern.quote(commandName) + "\\s*\\(.*")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIdentifier(String text, String id) {
        if (text == null || id == null || id.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(id) + "(?![A-Za-z0-9_])")
                .matcher(text)
                .find();
    }

    private boolean containsAnyIgnoreCase(String text, String... needles) {
        if (text == null || needles == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && normalized.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return safe(text).trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    public static final class Violation {
        private final String relation;
        private final Map<String, Object> refs;
        private final String owner;
        private final String evidence;
        private final String repairHint;
        private final String severity;
        private final String strength;

        public Violation(String relation,
                         Map<String, Object> refs,
                         String owner,
                         String evidence,
                         String repairHint,
                         String severity,
                         String strength) {
            this.relation = relation;
            this.refs = refs;
            this.owner = owner;
            this.evidence = evidence;
            this.repairHint = repairHint;
            this.severity = severity;
            this.strength = strength;
        }

        public String getRelation() { return relation; }
        public Map<String, Object> getRefs() { return refs; }
        public String getOwner() { return owner; }
        public String getEvidence() { return evidence; }
        public String getRepairHint() { return repairHint; }
        public String getSeverity() { return severity; }
        public String getStrength() { return strength; }

        public String summary() {
            return "Constraint compliance failed for relation '" + relation + "'"
                    + (owner != null && !owner.isBlank() ? " on '" + owner + "'" : "")
                    + ": " + evidence + ". Repair: " + repairHint;
        }

        public String evidenceText() {
            return "relation=" + relation
                    + ", owner=" + owner
                    + ", strength=" + strength
                    + ", refs=" + JsonUtils.toJson(refs)
                    + ", evidence=" + evidence
                    + ", repair_hint=" + repairHint;
        }
    }
}
