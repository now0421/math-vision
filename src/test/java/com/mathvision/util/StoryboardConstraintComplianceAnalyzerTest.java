package com.mathvision.util;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardConstraint;
import com.mathvision.model.Narrative.StoryboardObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardConstraintComplianceAnalyzerTest {

    private final StoryboardConstraintComplianceAnalyzer analyzer = new StoryboardConstraintComplianceAnalyzer();

    @Test
    void manimMovingAnchorWithOneShotLabelFails() {
        Storyboard storyboard = storyboard(
                object("P", "point", constraint("P_moves", "motion", "moves_on_object",
                        Map.of("point", "P", "support", "l"), Map.of(), "hard")),
                object("l", "line"),
                object("P_label", "text", constraint("P_label_for_P", "attachment", "label_for",
                        Map.of("label", "P_label", "anchor", "P"), Map.of(), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "l = Line(LEFT, RIGHT)",
                        "P = Dot()",
                        "P_label = Text('P').next_to(P, UP)",
                        "self.play(MoveAlongPath(P, l))"));

        assertTrue(violations.stream().anyMatch(violation ->
                "label_for".equals(violation.getRelation())
                        && "P_label".equals(violation.getOwner())
                        && violation.getEvidence().contains("motion-sensitive")));
    }

    @Test
    void manimMovingAnchorWithUpdaterLabelPasses() {
        Storyboard storyboard = storyboard(
                object("P", "point", constraint("P_moves", "motion", "moves_on_object",
                        Map.of("point", "P", "support", "l"), Map.of(), "hard")),
                object("l", "line"),
                object("P_label", "text", constraint("P_label_for_P", "attachment", "label_for",
                        Map.of("label", "P_label", "anchor", "P"), Map.of(), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "l = Line(LEFT, RIGHT)",
                        "P = Dot()",
                        "P_label = always_redraw(lambda: Text('P').next_to(P, UP))",
                        "self.play(MoveAlongPath(P, l))"));

        assertEquals(List.of(), violations);
    }

    @Test
    void manimStaticAnchorWithOneShotLabelPasses() {
        Storyboard storyboard = storyboard(
                object("P", "point"),
                object("P_label", "text", constraint("P_label_for_P", "attachment", "label_for",
                        Map.of("label", "P_label", "anchor", "P"), Map.of(), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "P = Dot()",
                        "P_label = Text('P').next_to(P, UP)"));

        assertEquals(List.of(), violations);
    }

    @Test
    void manimMovingEndpointWithStaticConnectorFails() {
        Storyboard storyboard = storyboard(
                object("A", "point"),
                object("B", "point", constraint("B_moves", "motion", "moves_on_object",
                        Map.of("point", "B", "support", "l"), Map.of(), "hard")),
                object("l", "line"),
                object("AB", "segment", constraint("AB_connects", "geometry", "connects_points",
                        Map.of("object", "AB", "start", "A", "end", "B"), Map.of(), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "l = Line(LEFT, RIGHT)",
                        "A = Dot(LEFT)",
                        "B = Dot(RIGHT)",
                        "AB = Line(A.get_center(), B.get_center())",
                        "self.play(MoveAlongPath(B, l))"));

        assertTrue(violations.stream().anyMatch(violation ->
                "connects_points".equals(violation.getRelation())
                        && "AB".equals(violation.getOwner())
                        && violation.getEvidence().contains("moving endpoint")));
    }

    @Test
    void manimMovingEndpointWithAlwaysRedrawConnectorPasses() {
        Storyboard storyboard = storyboard(
                object("A", "point"),
                object("B", "point", constraint("B_moves", "motion", "moves_on_object",
                        Map.of("point", "B", "support", "l"), Map.of(), "hard")),
                object("l", "line"),
                object("AB", "segment", constraint("AB_connects", "geometry", "connects_points",
                        Map.of("object", "AB", "start", "A", "end", "B"), Map.of(), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "l = Line(LEFT, RIGHT)",
                        "A = Dot(LEFT)",
                        "B = Dot(RIGHT)",
                        "AB = always_redraw(lambda: Line(A.get_center(), B.get_center()))",
                        "self.play(MoveAlongPath(B, l))"));

        assertEquals(List.of(), violations);
    }

    @Test
    void geogebraConstrainedPointAsFreeCoordinateFails() {
        Storyboard storyboard = storyboard(
                object("P", "point", constraint("P_moves", "motion", "moves_on_object",
                        Map.of("point", "P", "support", "l"), Map.of(), "hard")),
                object("l", "line"));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_GEOGEBRA,
                String.join("\n",
                        "l = Line(A, B)",
                        "P = (1, 2)"));

        assertTrue(violations.stream().anyMatch(violation ->
                "moves_on_object".equals(violation.getRelation())
                        && "P".equals(violation.getOwner())
                        && violation.getEvidence().contains("free coordinates")));
    }

    @Test
    void geogebraPathDependentPointPasses() {
        Storyboard storyboard = storyboard(
                object("P", "point", constraint("P_moves", "motion", "moves_on_object",
                        Map.of("point", "P", "support", "l"), Map.of(), "hard")),
                object("l", "line"));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_GEOGEBRA,
                String.join("\n",
                        "l = Line(A, B)",
                        "P = Point(l)"));

        assertEquals(List.of(), violations);
    }

    @Test
    void geogebraNativeDerivedConstructionCommandsPass() {
        Storyboard storyboard = storyboard(
                object("A", "point"),
                object("B", "point"),
                object("l", "line"),
                object("AB", "segment", constraint("AB_connects", "geometry", "connects_points",
                        Map.of("object", "AB", "start", "A", "end", "B"), Map.of(), "hard")),
                object("B1", "point", constraint("B1_reflection", "geometry", "reflection_across",
                        Map.of("image", "B1", "source", "B", "mirror", "l"), Map.of(), "hard")),
                object("P", "point", constraint("P_intersection", "geometry", "intersection_of",
                        Map.of("point", "P", "object_a", "AB", "object_b", "l"), Map.of(), "hard")),
                object("M", "point", constraint("M_midpoint", "geometry", "midpoint_of",
                        Map.of("point", "M", "endpoint_a", "A", "endpoint_b", "B"), Map.of(), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_GEOGEBRA,
                String.join("\n",
                        "A = (0, 0)",
                        "B = (2, 0)",
                        "l = Line(A, B)",
                        "AB = Segment(A, B)",
                        "B1 = Reflect(B, l)",
                        "P = Intersect(AB, l)",
                        "M = Midpoint(A, B)"));

        assertEquals(List.of(), violations);
    }

    @Test
    void connectorOwnerAliasIsRecognized() {
        Storyboard storyboard = storyboard(
                object("A", "point"),
                object("B", "point"),
                object("AB", "segment", constraint("AB_connects", "geometry", "connects_points",
                        Map.of("segment", "AB", "start", "A", "end", "B"), Map.of(), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_GEOGEBRA,
                String.join("\n",
                        "A = (0, 0)",
                        "B = (2, 0)"));

        assertTrue(violations.stream().anyMatch(violation ->
                "connects_points".equals(violation.getRelation())
                        && "AB".equals(violation.getOwner())
                        && violation.getEvidence().contains("not defined")));
    }

    @Test
    void reflectionOwnerUsesImageInsteadOfMirrorLine() {
        Storyboard storyboard = storyboard(
                object("B", "point"),
                object("l", "line"),
                object("B1", "point", constraint("B1_reflection", "geometry", "reflection_across",
                        Map.of("image", "B1", "source", "B", "mirror", "l"), Map.of(), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_GEOGEBRA,
                String.join("\n",
                        "B = (2, 0)",
                        "l = Line(A, B)"));

        assertTrue(violations.stream().anyMatch(violation ->
                "reflection_across".equals(violation.getRelation())
                        && "B1".equals(violation.getOwner())
                        && violation.getEvidence().contains("not defined")));
    }

    @Test
    void derivedConstructionMustReferenceEveryRequiredDependencyGroup() {
        Storyboard storyboard = storyboard(
                object("A", "point"),
                object("B", "point"),
                object("l", "line"),
                object("AB", "segment"),
                object("P", "point", constraint("P_intersection", "geometry", "intersection_of",
                        Map.of("point", "P", "object_a", "AB", "object_b", "l"), Map.of(), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_GEOGEBRA,
                String.join("\n",
                        "AB = Segment(A, B)",
                        "P = Intersect(AB, c)"));

        assertTrue(violations.stream().anyMatch(violation ->
                "intersection_of".equals(violation.getRelation())
                        && "P".equals(violation.getOwner())
                        && violation.getEvidence().contains("required source ref group")));
    }

    private Storyboard storyboard(StoryboardObject... objects) {
        Storyboard storyboard = new Storyboard();
        storyboard.setObjectRegistry(List.of(objects));
        return storyboard;
    }

    private StoryboardObject object(String id, String kind, StoryboardConstraint... constraints) {
        StoryboardObject object = new StoryboardObject();
        object.setId(id);
        object.setKind(kind);
        object.setConstraints(new ArrayList<>(List.of(constraints)));
        return object;
    }

    private StoryboardConstraint constraint(String id,
                                            String domain,
                                            String relation,
                                            Map<String, Object> refs,
                                            Map<String, Object> parameters,
                                            String strength) {
        StoryboardConstraint constraint = new StoryboardConstraint();
        constraint.setId(id);
        constraint.setDomain(domain);
        constraint.setRelation(relation);
        constraint.setRefs(refs);
        constraint.setParameters(parameters);
        constraint.setStrength(strength);
        return constraint;
    }
}
