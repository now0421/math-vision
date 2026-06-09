package com.mathvision.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Central registry for storyboard structured constraint semantics.
 *
 * The JSON model intentionally keeps refs/parameters as maps so LLM output
 * stays flexible, but this catalog defines the relation-specific contract that
 * validation, prompt text, schema text, and codegen helpers should share.
 */
public final class StoryboardConstraintCatalog {

    public enum Scope {
        OBJECT,
        SCENE
    }

    private static final List<String> DOMAINS = List.of(
            "placement",
            "construction",
            "constraint",
            "metric",
            "marker",
            "motion",
            "attachment",
            "layout",
            "visibility",
            "style",
            "lifecycle"
    );

    private static final List<String> STRENGTHS = List.of("hard", "repair_hard", "soft");

    private static final Map<String, RelationSpec> RELATIONS = buildRelations();
    private static final Map<String, Map<String, RelationSpec>> RELATIONS_BY_DOMAIN = indexRelationsByDomain(RELATIONS.values());

    private StoryboardConstraintCatalog() {}

    public static boolean isValidDomain(String domain) {
        return domain != null && DOMAINS.contains(normalize(domain));
    }

    public static boolean isValidStrength(String strength) {
        return strength != null && STRENGTHS.contains(normalize(strength));
    }

    public static RelationSpec relation(String relation) {
        return RELATIONS.get(normalize(relation));
    }

    public static RelationSpec relation(String domain, String relation) {
        String normalizedDomain = normalize(domain);
        String normalizedRelation = normalize(relation);
        Map<String, RelationSpec> domainRelations = RELATIONS_BY_DOMAIN.get(normalizedDomain);
        if (domainRelations != null) {
            RelationSpec spec = domainRelations.get(normalizedRelation);
            if (spec != null) {
                return spec;
            }
        }
        return relation(normalizedRelation);
    }

    public static Collection<RelationSpec> relations() {
        return RELATIONS.values();
    }

    public static String domainEnumJson() {
        return jsonStringArray(DOMAINS);
    }

    public static String strengthEnumJson() {
        return jsonStringArray(STRENGTHS);
    }

    public static String relationEnumJson() {
        return jsonStringArray(relationNames());
    }

    public static String relationList() {
        return String.join(", ", relationNames());
    }

    public static String domainList() {
        return String.join("|", DOMAINS);
    }

    public static String toolSchemaSummary() {
        List<String> entries = new ArrayList<>();
        for (RelationSpec spec : RELATIONS.values()) {
            StringBuilder entry = new StringBuilder();
            entry.append(spec.relation()).append(": refs ");
            entry.append(spec.requiredRefDescription());
            if (!spec.requiredParameters().isEmpty()) {
                entry.append("; required parameters ").append(String.join("/", spec.requiredParameters()));
            }
            entries.add(entry.toString());
        }
        return String.join(" | ", entries);
    }

    public static String promptSummary() {
        return "Known constraint relations: " + relationList()
                + ". For each relation, refs must use the cataloged semantic roles and parameters must use cataloged non-object keys.";
    }

    public static boolean isCoordinateDerivedRelation(String relation) {
        RelationSpec spec = relation(relation);
        return spec != null && spec.coordinateDerived();
    }

    public static boolean isCoordinateDerivedRelation(String domain, String relation) {
        RelationSpec spec = relation(domain, relation);
        return spec != null && spec.coordinateDerived();
    }

    public static boolean isGeoGebraDefaultPlaceableRelation(String relation) {
        RelationSpec spec = relation(relation);
        return spec != null && spec.geoGebraDefaultPlaceable();
    }

    public static boolean isGeoGebraDefaultPlaceableRelation(String domain, String relation) {
        RelationSpec spec = relation(domain, relation);
        return spec != null && spec.geoGebraDefaultPlaceable();
    }

    public static boolean isMotionSensitiveRelation(String relation) {
        RelationSpec spec = relation(relation);
        return spec != null && spec.motionSensitive();
    }

    public static boolean isMotionSensitiveRelation(String domain, String relation) {
        RelationSpec spec = relation(domain, relation);
        return spec != null && spec.motionSensitive();
    }

    public static boolean isAttachmentRelation(String relation) {
        RelationSpec spec = relation(relation);
        return spec != null && "attachment".equals(spec.domain());
    }

    public static boolean isAttachmentRelation(String domain, String relation) {
        RelationSpec spec = relation(domain, relation);
        return spec != null && "attachment".equals(spec.domain());
    }

    public static Set<String> ownerRefRoles(String relation) {
        RelationSpec spec = relation(relation);
        return spec != null ? spec.ownerRefRoles() : Set.of();
    }

    public static Set<String> ownerRefRoles(String domain, String relation) {
        RelationSpec spec = relation(domain, relation);
        return spec != null ? spec.ownerRefRoles() : Set.of();
    }

    public static Set<String> dependencyRefRoles(String relation) {
        RelationSpec spec = relation(relation);
        return spec != null ? spec.dependencyRefRoles() : Set.of();
    }

    public static Set<String> dependencyRefRoles(String domain, String relation) {
        RelationSpec spec = relation(domain, relation);
        return spec != null ? spec.dependencyRefRoles() : Set.of();
    }

    private static Map<String, RelationSpec> buildRelations() {
        Map<String, RelationSpec> relations = new LinkedHashMap<>();

        add(relations, spec("placement", "point_at")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("point")
                .requireRef("point")
                .requireParam("coordinate")
                .optionalParams("positioning", "tolerance")
                .enumParam("positioning", "absolute", "relative"));
        add(relations, spec("constraint", "lies_on")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .geoGebraDefaultPlaceable()
                .ownerRefs("point")
                .dependencyRefs("support")
                .requireRef("point")
                .requireRef("support")
                .optionalParams("range", "tolerance"));
        add(relations, spec("constraint", "on_side_of")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "point", "objects")
                .dependencyRefs("reference", "line", "boundary")
                .requireAnyRef("object", "point", "objects")
                .requireAnyRef("reference", "line", "boundary")
                .requireParam("side")
                .optionalParams("positioning", "tolerance")
                .enumParam("positioning", "absolute", "relative")
                .enumParam("side", "above", "below", "left", "right", "positive", "negative"));
        add(relations, spec("construction", "connects_points")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("object", "connector", "segment", "line", "ray")
                .requireAnyRef("object", "connector", "segment", "line", "ray")
                .requireAnyRef("start", "point_a", "from")
                .requireAnyRef("end", "point_b", "to")
                .optionalParams("tolerance"));
        add(relations, spec("construction", "line_through_points")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("line", "object")
                .requireAnyRef("line", "object")
                .requireAnyRef("point_a", "start", "from")
                .requireAnyRef("point_b", "end", "to")
                .optionalParams("tolerance"));
        add(relations, spec("construction", "ray_from_to")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("ray", "object")
                .requireAnyRef("ray", "object")
                .requireAnyRef("start", "from")
                .requireAnyRef("through", "end", "to"));
        add(relations, spec("construction", "vector_from_to")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("vector", "object", "arrow", "directed_segment")
                .requireAnyRef("vector", "object", "arrow", "directed_segment")
                .requireAnyRef("start", "from")
                .requireAnyRef("end", "to")
                .optionalParams("tolerance"));
        add(relations, spec("construction", "intersection_of")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("point", "intersection")
                .requireAnyRef("point", "intersection")
                .requireAnyRef("object_a", "support_a", "first")
                .requireAnyRef("object_b", "support_b", "second")
                .optionalParams("which", "tolerance"));
        add(relations, spec("construction", "reflection_across")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("image")
                .requireRef("image")
                .requireRef("source")
                .requireAnyRef("mirror", "axis", "line")
                .optionalParams("tolerance"));
        add(relations, spec("construction", "rotate_about")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("image")
                .requireRef("image")
                .requireRef("source")
                .requireAnyRef("center", "anchor", "point")
                .requireParam("angle")
                .optionalParams("direction", "branch", "tolerance")
                .enumParam("direction", "clockwise", "counterclockwise"));
        add(relations, spec("construction", "midpoint_of")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("point", "midpoint")
                .requireAnyRef("point", "midpoint")
                .requireAnyRef("endpoint_a", "start", "point_a")
                .requireAnyRef("endpoint_b", "end", "point_b"));
        add(relations, spec("construction", "projection_onto")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("projection", "point", "image", "foot")
                .requireAnyRef("projection", "point", "image", "foot")
                .requireRef("source")
                .requireAnyRef("support", "line", "target")
                .optionalParams("tolerance")
                .enumParam("projection_type", "perpendicular", "oblique"));
        add(relations, spec("constraint", "parallel_to")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "line")
                .requireAnyRef("object", "line")
                .requireAnyRef("reference", "parallel_to")
                .optionalParams("tolerance"));
        add(relations, spec("constraint", "perpendicular_to")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "line")
                .requireAnyRef("object", "line")
                .requireAnyRef("reference", "perpendicular_to")
                .optionalParams("tolerance"));
        add(relations, spec("construction", "parallel_through")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("line", "object")
                .requireAnyRef("line", "object")
                .requireAnyRef("through_point", "point")
                .requireAnyRef("reference", "parallel_to")
                .optionalParams("tolerance"));
        add(relations, spec("construction", "perpendicular_through")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("line", "object")
                .requireAnyRef("line", "object")
                .requireAnyRef("through_point", "point")
                .requireAnyRef("reference", "perpendicular_to")
                .optionalParams("tolerance"));
        add(relations, spec("construction", "perpendicular_bisector")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("bisector", "line", "object")
                .requireAnyRef("bisector", "line", "object")
                .requireAnyRef("endpoint_a", "point_a", "start")
                .requireAnyRef("endpoint_b", "point_b", "end")
                .optionalParams("tolerance"));
        add(relations, spec("construction", "circle_through")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("circle", "object")
                .requireAnyRef("circle", "object")
                .requireRef("points")
                .optionalRefs("center")
                .optionalParams("tolerance"));
        add(relations, spec("constraint", "same_side_of")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("objects")
                .dependencyRefs("reference", "line", "boundary")
                .requireRef("objects")
                .requireAnyRef("reference", "line", "boundary")
                .optionalParams("side", "tolerance"));
        add(relations, spec("constraint", "opposite_side_of")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("objects")
                .dependencyRefs("reference", "line", "boundary")
                .requireRef("objects")
                .requireAnyRef("reference", "line", "boundary")
                .optionalParams("side", "tolerance"));
        add(relations, spec("constraint", "collinear")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("points")
                .requireRef("points")
                .optionalParams("tolerance"));

        add(relations, spec("marker", "angle_between")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("marker")
                .requireRef("marker")
                .requireRef("vertex")
                .requireAnyRef("line_a", "start_boundary", "ray_a")
                .requireAnyRef("line_b", "end_boundary", "ray_b")
                .requireParam("sector")
                .optionalParams("direction", "side_of_reference", "tolerance")
                .optionalRefs("reference_line", "normal")
                .enumParam("sector", "smaller", "interior", "exterior", "reflex", "directed", "right")
                .enumParam("direction", "clockwise", "counterclockwise", "undirected"));
        add(relations, spec("marker", "arc_sweep")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("marker", "arc")
                .requireAnyRef("marker", "arc")
                .requireAnyRef("center", "anchor", "vertex")
                .requireRef("start_boundary")
                .requireRef("end_boundary")
                .requireParam("direction")
                .requireParam("sector")
                .optionalParams("radius", "side_of_reference", "tolerance")
                .enumParam("direction", "clockwise", "counterclockwise")
                .enumParam("sector", "minor", "major", "directed", "interior", "exterior"));
        add(relations, spec("marker", "right_angle_at")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("marker")
                .requireRef("marker")
                .requireRef("vertex")
                .requireRef("start_boundary")
                .requireRef("end_boundary")
                .requireParam("side_of_reference")
                .optionalParams("tolerance"));
        add(relations, spec("metric", "equal_length")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("members")
                .requireRef("members")
                .optionalRefs("reference")
                .optionalParams("tolerance"));
        add(relations, spec("metric", "equal_angle")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("members")
                .requireRef("members")
                .optionalRefs("reference")
                .optionalParams("tolerance"));
        add(relations, spec("metric", "equal_measure_group")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("members")
                .requireRef("members")
                .optionalRefs("reference")
                .optionalParams("measure", "group", "tolerance")
                .enumParam("measure", "angle", "length", "distance_to_line", "radius", "area"));
        add(relations, spec("metric", "distance_between")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("measurement", "label", "object")
                .requireAnyRef("measurement", "label", "object")
                .requireAnyRef("start", "point_a")
                .requireAnyRef("end", "point_b")
                .optionalParams("display", "tolerance"));
        add(relations, spec("construction", "minimum_of")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("marker", "point")
                .requireAnyRef("marker", "point")
                .requireAnyRef("support", "object")
                .optionalParams("objective", "range", "tolerance"));

        add(relations, spec("motion", "moves_on_object")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .motionSensitive()
                .geoGebraDefaultPlaceable()
                .ownerRefs("point")
                .dependencyRefs("support")
                .requireRef("point")
                .requireRef("support")
                .optionalParams("range", "speed", "loop", "tolerance"));
        add(relations, spec("motion", "moves_along_range")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .geoGebraDefaultPlaceable()
                .ownerRefs("object", "point")
                .requireAnyRef("object", "point")
                .requireParam("range")
                .optionalParams("positioning", "speed", "loop")
                .enumParam("positioning", "absolute", "relative"));
        add(relations, spec("motion", "slider_driven")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .geoGebraDefaultPlaceable()
                .ownerRefs("object", "target")
                .requireAnyRef("object", "target")
                .optionalRefs("slider")
                .requireParam("range")
                .optionalParams("parameter", "speed", "loop"));
        add(relations, spec("motion", "follows_path")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .geoGebraDefaultPlaceable()
                .ownerRefs("object", "point")
                .requireAnyRef("object", "point")
                .requireAnyRef("path", "support")
                .optionalParams("range", "speed", "loop"));
        add(relations, spec("motion", "trace_of")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("trace", "object", "path", "locus")
                .dependencyRefs("source", "source_point", "moving_point")
                .requireAnyRef("trace", "object", "path", "locus")
                .requireAnyRef("source", "source_point", "moving_point")
                .optionalRefs("driver", "support")
                .optionalParams("range", "sample_count", "style", "tolerance"));

        add(relations, spec("attachment", "label_for")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("label")
                .dependencyRefs("anchor")
                .requireRef("label")
                .requireRef("anchor")
                .optionalParams("offset", "positioning", "side", "clearance")
                .enumParam("positioning", "absolute", "relative"));
        add(relations, spec("attachment", "fixed_offset_from")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("object", "label", "attached")
                .requireAnyRef("object", "label", "attached")
                .requireRef("anchor")
                .requireParam("offset")
                .optionalParams("positioning", "side", "clearance")
                .enumParam("positioning", "absolute", "relative"));
        add(relations, spec("attachment", "anchored_to")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .coordinateDerived()
                .ownerRefs("object", "attached")
                .requireAnyRef("object", "attached")
                .requireRef("anchor")
                .optionalParams("offset", "positioning", "side")
                .enumParam("positioning", "absolute", "relative"));

        add(relations, spec("attachment", "fixed_overlay")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "objects")
                .requireAnyRef("object", "objects")
                .optionalParams("position", "anchor", "margin"));

        add(relations, spec("layout", "keep_inside_safe_area")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "objects")
                .requireAnyRef("object", "objects")
                .optionalParams("margin", "positioning")
                .enumParam("positioning", "absolute", "relative"));
        add(relations, spec("layout", "avoid_overlap")
                .scopes(Scope.SCENE)
                .ownerRefs("objects")
                .requireRef("objects")
                .optionalParams("padding", "priority"));
        add(relations, spec("layout", "maintain_clearance")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "objects")
                .requireAnyRef("object", "objects")
                .optionalRefs("from", "reference")
                .requireParam("clearance")
                .optionalParams("positioning", "priority")
                .enumParam("positioning", "absolute", "relative"));
        add(relations, spec("layout", "group_alignment")
                .scopes(Scope.SCENE)
                .ownerRefs("objects")
                .requireRef("objects")
                .requireParam("alignment")
                .optionalParams("axis", "spacing"));

        add(relations, spec("visibility", "visible_during")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "objects")
                .requireAnyRef("object", "objects")
                .requireParam("scenes")
                .optionalParams("opacity"));
        add(relations, spec("visibility", "hidden_after")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "objects")
                .requireAnyRef("object", "objects")
                .requireParam("scene"));
        add(relations, spec("visibility", "fade_with")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "objects")
                .requireAnyRef("object", "objects")
                .requireAnyRef("anchor", "reference"));

        add(relations, spec("style", "style_matches")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "objects")
                .requireAnyRef("object", "objects")
                .requireAnyRef("reference", "concept")
                .optionalParams("property", "tolerance"));

        add(relations, spec("lifecycle", "persistent_across_scenes")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "objects")
                .requireAnyRef("object", "objects")
                .requireParam("scenes"));
        add(relations, spec("lifecycle", "exits_after_scene")
                .scopes(Scope.OBJECT, Scope.SCENE)
                .ownerRefs("object", "objects")
                .requireAnyRef("object", "objects")
                .requireParam("scene"));

        addOtherRelations(relations);

        return Collections.unmodifiableMap(relations);
    }

    private static RelationSpec.Builder spec(String domain, String relation) {
        return new RelationSpec.Builder(domain, relation);
    }

    private static void add(Map<String, RelationSpec> relations, RelationSpec.Builder builder) {
        RelationSpec spec = builder.build();
        relations.put(relationKey(spec.domain(), spec.relation()), spec);
    }

    private static void addOtherRelations(Map<String, RelationSpec> relations) {
        for (String domain : DOMAINS) {
            add(relations, spec(domain, "other")
                    .scopes(Scope.OBJECT, Scope.SCENE)
                    .ownerRefs("object", "objects", "owner", "point", "points", "member", "members")
                    .dependencyRefs("source", "reference", "support", "anchor", "target", "driver")
                    .optionalRefs(
                            "object", "objects", "owner",
                            "point", "points", "member", "members",
                            "source", "reference", "support", "anchor", "target", "driver")
                    .optionalParams(
                            "description", "semantics", "details", "formula",
                            "value", "range", "branch", "reason", "tolerance"));
        }
    }

    private static Map<String, Map<String, RelationSpec>> indexRelationsByDomain(Collection<RelationSpec> specs) {
        Map<String, Map<String, RelationSpec>> byDomain = new LinkedHashMap<>();
        for (RelationSpec spec : specs) {
            byDomain.computeIfAbsent(spec.domain(), ignored -> new LinkedHashMap<>())
                    .put(spec.relation(), spec);
        }
        Map<String, Map<String, RelationSpec>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, RelationSpec>> entry : byDomain.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private static String relationKey(String domain, String relation) {
        String normalizedRelation = normalize(relation);
        if (!"other".equals(normalizedRelation)) {
            return normalizedRelation;
        }
        return normalize(domain) + ":" + normalizedRelation;
    }

    private static String jsonStringArray(Collection<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("\"").append(value).append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private static List<String> relationNames() {
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RelationSpec spec : RELATIONS.values()) {
            if (seen.add(spec.relation())) {
                names.add(spec.relation());
            }
        }
        return names;
    }

    /**
     * Compact per-relation reference table for system prompt injection.
     * Format: domain.scope relation: required_refs [optional_refs] | required_params [optional_params] {enum_values}
     */
    public static String detailedCatalogSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Constraint relation catalog (domain.scope relation: "
                + "required_refs [optional_refs] | required_params [optional_params] {enum_values}):\n");
        for (RelationSpec spec : RELATIONS.values()) {
            sb.append("  ").append(spec.domain()).append(".");
            sb.append(spec.scopes.contains(Scope.OBJECT) ? "OBJ" : "_");
            sb.append(spec.scopes.contains(Scope.SCENE) ? "+SCENE" : "");
            sb.append(" ").append(spec.relation()).append(": ");
            // Required refs (canonical = first in each group)
            List<String> refParts = new ArrayList<>();
            for (Set<String> group : spec.requiredRefGroups) {
                refParts.add(group.iterator().next());
            }
            sb.append(String.join(", ", refParts));
            if (!spec.optionalRefs.isEmpty()) {
                sb.append(" [").append(String.join(", ", spec.optionalRefs)).append("]");
            }
            // Parameters
            if (!spec.requiredParameters.isEmpty() || !spec.optionalParameters.isEmpty()) {
                sb.append(" | ");
                if (!spec.requiredParameters.isEmpty()) {
                    sb.append(String.join(", ", spec.requiredParameters));
                }
                if (!spec.optionalParameters.isEmpty()) {
                    sb.append(" [").append(String.join(", ", spec.optionalParameters)).append("]");
                }
            }
            // Enum values
            if (!spec.enumParameters.isEmpty()) {
                for (Map.Entry<String, Set<String>> entry : spec.enumParameters.entrySet()) {
                    sb.append(" {").append(entry.getKey()).append(": ")
                      .append(String.join("|", entry.getValue())).append("}");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static final class RelationSpec {
        private final String domain;
        private final String relation;
        private final Set<Scope> scopes;
        private final List<Set<String>> requiredRefGroups;
        private final Set<String> optionalRefs;
        private final Set<String> requiredParameters;
        private final Set<String> optionalParameters;
        private final Map<String, Set<String>> enumParameters;
        private final boolean coordinateDerived;
        private final boolean geoGebraDefaultPlaceable;
        private final boolean motionSensitive;
        private final Set<String> ownerRefRoles;
        private final Set<String> dependencyRefRoles;

        private RelationSpec(Builder builder) {
            this.domain = builder.domain;
            this.relation = builder.relation;
            this.scopes = Collections.unmodifiableSet(EnumSet.copyOf(builder.scopes));
            this.requiredRefGroups = deepUnmodifiable(builder.requiredRefGroups);
            this.optionalRefs = Collections.unmodifiableSet(new LinkedHashSet<>(builder.optionalRefs));
            this.requiredParameters = Collections.unmodifiableSet(new LinkedHashSet<>(builder.requiredParameters));
            this.optionalParameters = Collections.unmodifiableSet(new LinkedHashSet<>(builder.optionalParameters));
            this.enumParameters = deepUnmodifiableMap(builder.enumParameters);
            this.coordinateDerived = builder.coordinateDerived;
            this.geoGebraDefaultPlaceable = builder.geoGebraDefaultPlaceable;
            this.motionSensitive = builder.motionSensitive
                    || builder.coordinateDerived
                    || "motion".equals(builder.domain)
                    || "attachment".equals(builder.domain);
            this.ownerRefRoles = Collections.unmodifiableSet(resolveOwnerRefRoles(builder));
            this.dependencyRefRoles = Collections.unmodifiableSet(resolveDependencyRefRoles(builder, this.ownerRefRoles));
        }

        public String domain() {
            return domain;
        }

        public String relation() {
            return relation;
        }

        public boolean allowsScope(Scope scope) {
            return scopes.contains(scope);
        }

        public List<Set<String>> requiredRefGroups() {
            return requiredRefGroups;
        }

        public Set<String> optionalRefs() {
            return optionalRefs;
        }

        public Set<String> allowedRefs() {
            Set<String> allowed = new LinkedHashSet<>(optionalRefs);
            for (Set<String> group : requiredRefGroups) {
                allowed.addAll(group);
            }
            return allowed;
        }

        public Set<String> requiredParameters() {
            return requiredParameters;
        }

        public Set<String> optionalParameters() {
            return optionalParameters;
        }

        public Set<String> allowedParameters() {
            Set<String> allowed = new LinkedHashSet<>(requiredParameters);
            allowed.addAll(optionalParameters);
            return allowed;
        }

        public Map<String, Set<String>> enumParameters() {
            return enumParameters;
        }

        public boolean coordinateDerived() {
            return coordinateDerived;
        }

        public boolean geoGebraDefaultPlaceable() {
            return geoGebraDefaultPlaceable;
        }

        public boolean motionSensitive() {
            return motionSensitive;
        }

        public Set<String> ownerRefRoles() {
            return ownerRefRoles;
        }

        public Set<String> dependencyRefRoles() {
            return dependencyRefRoles;
        }

        public String requiredRefDescription() {
            if (requiredRefGroups.isEmpty()) {
                return "non-empty role map";
            }
            List<String> descriptions = new ArrayList<>();
            for (Set<String> group : requiredRefGroups) {
                descriptions.add(group.size() == 1 ? group.iterator().next() : "one of " + String.join("/", group));
            }
            return String.join(", ", descriptions);
        }

        private static List<Set<String>> deepUnmodifiable(List<Set<String>> source) {
            List<Set<String>> copy = new ArrayList<>();
            for (Set<String> set : source) {
                copy.add(Collections.unmodifiableSet(new LinkedHashSet<>(set)));
            }
            return Collections.unmodifiableList(copy);
        }

        private static Map<String, Set<String>> deepUnmodifiableMap(Map<String, Set<String>> source) {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
                copy.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
            }
            return Collections.unmodifiableMap(copy);
        }

        private static Set<String> resolveOwnerRefRoles(Builder builder) {
            if (!builder.ownerRefRoles.isEmpty()) {
                return new LinkedHashSet<>(builder.ownerRefRoles);
            }
            if (!builder.requiredRefGroups.isEmpty()) {
                return new LinkedHashSet<>(builder.requiredRefGroups.get(0));
            }
            return Set.of();
        }

        private static Set<String> resolveDependencyRefRoles(Builder builder, Set<String> ownerRoles) {
            if (!builder.dependencyRefRoles.isEmpty()) {
                return new LinkedHashSet<>(builder.dependencyRefRoles);
            }
            Set<String> roles = new LinkedHashSet<>();
            for (Set<String> group : builder.requiredRefGroups) {
                roles.addAll(group);
            }
            roles.addAll(builder.optionalRefs);
            roles.removeAll(ownerRoles);
            return roles;
        }

        public static final class Builder {
            private final String domain;
            private final String relation;
            private Set<Scope> scopes = EnumSet.of(Scope.OBJECT, Scope.SCENE);
            private final List<Set<String>> requiredRefGroups = new ArrayList<>();
            private final Set<String> optionalRefs = new LinkedHashSet<>();
            private final Set<String> requiredParameters = new LinkedHashSet<>();
            private final Set<String> optionalParameters = new LinkedHashSet<>();
            private final Map<String, Set<String>> enumParameters = new LinkedHashMap<>();
            private final Set<String> ownerRefRoles = new LinkedHashSet<>();
            private final Set<String> dependencyRefRoles = new LinkedHashSet<>();
            private boolean coordinateDerived;
            private boolean geoGebraDefaultPlaceable;
            private boolean motionSensitive;

            private Builder(String domain, String relation) {
                this.domain = normalize(domain);
                this.relation = normalize(relation);
            }

            private Builder scopes(Scope first, Scope... rest) {
                this.scopes = EnumSet.of(first, rest);
                return this;
            }

            /** Marks relations whose owner geometry is computed from dependency refs. */
            private Builder coordinateDerived() {
                this.coordinateDerived = true;
                return this;
            }

            private Builder geoGebraDefaultPlaceable() {
                this.geoGebraDefaultPlaceable = true;
                return this;
            }

            private Builder motionSensitive() {
                this.motionSensitive = true;
                return this;
            }

            private Builder ownerRefs(String... roles) {
                for (String role : roles) {
                    this.ownerRefRoles.add(normalize(role));
                }
                return this;
            }

            private Builder dependencyRefs(String... roles) {
                for (String role : roles) {
                    this.dependencyRefRoles.add(normalize(role));
                }
                return this;
            }

            private Builder requireRef(String role) {
                this.requiredRefGroups.add(Set.of(normalize(role)));
                return this;
            }

            private Builder requireAnyRef(String first, String... rest) {
                Set<String> group = new LinkedHashSet<>();
                group.add(normalize(first));
                for (String role : rest) {
                    group.add(normalize(role));
                }
                this.requiredRefGroups.add(group);
                return this;
            }

            private Builder optionalRefs(String... roles) {
                for (String role : roles) {
                    this.optionalRefs.add(normalize(role));
                }
                return this;
            }

            private Builder requireParam(String parameter) {
                this.requiredParameters.add(normalize(parameter));
                return this;
            }

            private Builder optionalParams(String... parameters) {
                for (String parameter : parameters) {
                    this.optionalParameters.add(normalize(parameter));
                }
                return this;
            }

            private Builder enumParam(String parameter, String... values) {
                Set<String> normalizedValues = new LinkedHashSet<>();
                for (String value : values) {
                    normalizedValues.add(normalize(value));
                }
                this.enumParameters.put(normalize(parameter), normalizedValues);
                return this;
            }

            private RelationSpec build() {
                return new RelationSpec(this);
            }
        }
    }
}
