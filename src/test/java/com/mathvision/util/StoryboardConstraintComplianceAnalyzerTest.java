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
                object("AB", "segment", constraint("AB_connects", "construction", "connects_points",
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
                object("AB", "segment", constraint("AB_connects", "construction", "connects_points",
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
                object("AB", "segment", constraint("AB_connects", "construction", "connects_points",
                        Map.of("object", "AB", "start", "A", "end", "B"), Map.of(), "hard")),
                object("B1", "point", constraint("B1_reflection", "construction", "reflection_across",
                        Map.of("image", "B1", "source", "B", "mirror", "l"), Map.of(), "hard")),
                object("P", "point", constraint("P_intersection", "construction", "intersection_of",
                        Map.of("point", "P", "object_a", "AB", "object_b", "l"), Map.of(), "hard")),
                object("M", "point", constraint("M_midpoint", "construction", "midpoint_of",
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
                object("AB", "segment", constraint("AB_connects", "construction", "connects_points",
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
                object("B1", "point", constraint("B1_reflection", "construction", "reflection_across",
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
                object("P", "point", constraint("P_intersection", "construction", "intersection_of",
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

    @Test
    void angleLineImplementationPassesWhenBoundariesPassThroughVertex() {
        Storyboard storyboard = storyboard(
                object("P", "point"),
                object("A", "point"),
                object("N", "point"),
                object("AP", "segment", constraint("AP_connects", "construction", "connects_points",
                        Map.of("object", "AP", "start", "P", "end", "A"), Map.of(), "hard")),
                object("l", "line", constraint("l_through_P", "construction", "line_through_points",
                        Map.of("line", "l", "point_a", "P", "point_b", "N"), Map.of(), "hard")),
                object("theta", "angle_marker", constraint("theta_angle", "marker", "angle_between",
                        Map.of("marker", "theta", "vertex", "P", "line_a", "AP", "line_b", "l"),
                        Map.of("sector", "smaller"), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "P = Dot()",
                        "A = Dot()",
                        "N = Dot()",
                        "AP = Line(P.get_center(), A.get_center())",
                        "l = Line(P.get_center(), N.get_center())",
                        "theta = Angle(AP, l, quadrant=(1, 1), other_angle=False)"));

        assertEquals(List.of(), violations);
    }

    @Test
    void angleThreePointImplementationPassesWhenPointsComeFromDeclaredBoundaries() {
        Storyboard storyboard = storyboard(
                object("P", "point"),
                object("A", "point"),
                object("N", "point"),
                object("AP", "segment", constraint("AP_connects", "construction", "connects_points",
                        Map.of("object", "AP", "start", "P", "end", "A"), Map.of(), "hard")),
                object("l", "line", constraint("l_through_P", "construction", "line_through_points",
                        Map.of("line", "l", "point_a", "P", "point_b", "N"), Map.of(), "hard")),
                object("theta", "angle_marker", constraint("theta_angle", "marker", "angle_between",
                        Map.of("marker", "theta", "vertex", "P", "line_a", "AP", "line_b", "l"),
                        Map.of("sector", "smaller"), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "P = Dot()",
                        "A = Dot()",
                        "N = Dot()",
                        "AP = Line(P.get_center(), A.get_center())",
                        "l = Line(P.get_center(), N.get_center())",
                        "theta = Angle(Line(P.get_center(), A.get_center()), Line(P.get_center(), N.get_center()), other_angle=False)"));

        assertEquals(List.of(), violations);
    }

    @Test
    void angleLineImplementationFailsWhenBoundaryDoesNotPassThroughVertex() {
        Storyboard storyboard = storyboard(
                object("P", "point"),
                object("A", "point"),
                object("Q", "point"),
                object("N", "point"),
                object("AP", "segment", constraint("AP_connects", "construction", "connects_points",
                        Map.of("object", "AP", "start", "P", "end", "A"), Map.of(), "hard")),
                object("l", "line", constraint("l_through_Q", "construction", "line_through_points",
                        Map.of("line", "l", "point_a", "Q", "point_b", "N"), Map.of(), "hard")),
                object("theta", "angle_marker", constraint("theta_angle", "marker", "angle_between",
                        Map.of("marker", "theta", "vertex", "P", "line_a", "AP", "line_b", "l"),
                        Map.of("sector", "smaller"), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "P = Dot()",
                        "A = Dot()",
                        "Q = Dot()",
                        "N = Dot()",
                        "AP = Line(P.get_center(), A.get_center())",
                        "l = Line(Q.get_center(), N.get_center())",
                        "theta = Angle(AP, l, quadrant=(1, 1), other_angle=False)"));

        assertTrue(violations.stream().anyMatch(violation ->
                "angle_between".equals(violation.getRelation())
                        && "theta".equals(violation.getOwner())
                        && violation.getEvidence().contains("declared vertex")));
    }

    @Test
    void rightAngleImplementationUsesSameVertexBoundarySemantics() {
        Storyboard storyboard = storyboard(
                object("P", "point"),
                object("A", "point"),
                object("N", "point"),
                object("AP", "segment", constraint("AP_connects", "construction", "connects_points",
                        Map.of("object", "AP", "start", "P", "end", "A"), Map.of(), "hard")),
                object("normal", "line", constraint("normal_through_P", "construction", "line_through_points",
                        Map.of("line", "normal", "point_a", "P", "point_b", "N"), Map.of(), "hard")),
                object("right", "right_angle_marker", constraint("right_angle", "marker", "right_angle_at",
                        Map.of("marker", "right", "vertex", "P", "start_boundary", "AP", "end_boundary", "normal"),
                        Map.of("side_of_reference", "inside"), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "P = Dot()",
                        "A = Dot()",
                        "N = Dot()",
                        "AP = Line(P.get_center(), A.get_center())",
                        "normal = Line(P.get_center(), N.get_center())",
                        "right = RightAngle(AP, normal, quadrant=(1, 1))"));

        assertEquals(List.of(), violations);
    }

    @Test
    void rightAngleImplementationFailsWhenBoundaryMissesVertex() {
        Storyboard storyboard = storyboard(
                object("P", "point"),
                object("A", "point"),
                object("Q", "point"),
                object("N", "point"),
                object("AP", "segment", constraint("AP_connects", "construction", "connects_points",
                        Map.of("object", "AP", "start", "P", "end", "A"), Map.of(), "hard")),
                object("normal", "line", constraint("normal_through_Q", "construction", "line_through_points",
                        Map.of("line", "normal", "point_a", "Q", "point_b", "N"), Map.of(), "hard")),
                object("right", "right_angle_marker", constraint("right_angle", "marker", "right_angle_at",
                        Map.of("marker", "right", "vertex", "P", "start_boundary", "AP", "end_boundary", "normal"),
                        Map.of("side_of_reference", "inside"), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "P = Dot()",
                        "A = Dot()",
                        "Q = Dot()",
                        "N = Dot()",
                        "AP = Line(P.get_center(), A.get_center())",
                        "normal = Line(Q.get_center(), N.get_center())",
                        "right = RightAngle(AP, normal, quadrant=(1, 1))"));

        assertTrue(violations.stream().anyMatch(violation ->
                "right_angle_at".equals(violation.getRelation())
                        && "right".equals(violation.getOwner())
                        && violation.getEvidence().contains("declared vertex")));
    }

    @Test
    void arcSweepImplementationPreservesAnchorAndOrderedBoundaries() {
        Storyboard storyboard = storyboard(
                object("O", "point"),
                object("A", "point"),
                object("B", "point"),
                object("OA", "ray", constraint("OA_ray", "construction", "ray_from_to",
                        Map.of("ray", "OA", "start", "O", "through", "A"), Map.of(), "hard")),
                object("OB", "ray", constraint("OB_ray", "construction", "ray_from_to",
                        Map.of("ray", "OB", "start", "O", "through", "B"), Map.of(), "hard")),
                object("sweep", "arc_marker", constraint("sweep_arc", "marker", "arc_sweep",
                        Map.of("arc", "sweep", "center", "O", "start_boundary", "OA", "end_boundary", "OB"),
                        Map.of("direction", "counterclockwise", "sector", "minor"), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "O = Dot()",
                        "A = Dot()",
                        "B = Dot()",
                        "OA = Line(O.get_center(), A.get_center())",
                        "OB = Line(O.get_center(), B.get_center())",
                        "sweep = Arc(radius=0.5).move_arc_center_to(O.get_center())",
                        "sweep.add_updater(lambda m: m.become(ArcBetweenPoints(OA.get_end(), OB.get_end(), radius=0.5)))"));

        assertEquals(List.of(), violations);
    }

    @Test
    void arcSweepImplementationFailsWhenAnchorIsDropped() {
        Storyboard storyboard = storyboard(
                object("O", "point"),
                object("A", "point"),
                object("B", "point"),
                object("OA", "ray", constraint("OA_ray", "construction", "ray_from_to",
                        Map.of("ray", "OA", "start", "O", "through", "A"), Map.of(), "hard")),
                object("OB", "ray", constraint("OB_ray", "construction", "ray_from_to",
                        Map.of("ray", "OB", "start", "O", "through", "B"), Map.of(), "hard")),
                object("sweep", "arc_marker", constraint("sweep_arc", "marker", "arc_sweep",
                        Map.of("arc", "sweep", "center", "O", "start_boundary", "OA", "end_boundary", "OB"),
                        Map.of("direction", "counterclockwise", "sector", "minor"), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "O = Dot()",
                        "A = Dot()",
                        "B = Dot()",
                        "OA = Line(O.get_center(), A.get_center())",
                        "OB = Line(O.get_center(), B.get_center())",
                        "sweep = ArcBetweenPoints(OA.get_end(), OB.get_end(), radius=0.5)"));

        assertTrue(violations.stream().anyMatch(violation ->
                "arc_sweep".equals(violation.getRelation())
                        && "sweep".equals(violation.getOwner())
                        && violation.getEvidence().contains("anchor/center")));
    }

    @Test
    void rightAngleImplementationFailsWhenGenericAngleDropsRightAngleSemantics() {
        Storyboard storyboard = storyboard(
                object("P", "point"),
                object("A", "point"),
                object("N", "point"),
                object("AP", "segment", constraint("AP_connects", "construction", "connects_points",
                        Map.of("object", "AP", "start", "P", "end", "A"), Map.of(), "hard")),
                object("normal", "line", constraint("normal_through_P", "construction", "line_through_points",
                        Map.of("line", "normal", "point_a", "P", "point_b", "N"), Map.of(), "hard")),
                object("right", "right_angle_marker", constraint("right_angle", "marker", "right_angle_at",
                        Map.of("marker", "right", "vertex", "P", "start_boundary", "AP", "end_boundary", "normal"),
                        Map.of("side_of_reference", "inside"), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "P = Dot()",
                        "A = Dot()",
                        "N = Dot()",
                        "AP = Line(P.get_center(), A.get_center())",
                        "normal = Line(P.get_center(), N.get_center())",
                        "right = Angle(AP, normal, quadrant=(1, 1), other_angle=False)"));

        assertTrue(violations.stream().anyMatch(violation ->
                "right_angle_at".equals(violation.getRelation())
                        && "right".equals(violation.getOwner())
                        && violation.getEvidence().contains("right-angle marker evidence")));
    }

    @Test
    void arcSweepImplementationFailsWhenBoundariesAppearReversed() {
        Storyboard storyboard = storyboard(
                object("O", "point"),
                object("A", "point"),
                object("B", "point"),
                object("OA", "ray", constraint("OA_ray", "construction", "ray_from_to",
                        Map.of("ray", "OA", "start", "O", "through", "A"), Map.of(), "hard")),
                object("OB", "ray", constraint("OB_ray", "construction", "ray_from_to",
                        Map.of("ray", "OB", "start", "O", "through", "B"), Map.of(), "hard")),
                object("sweep", "arc_marker", constraint("sweep_arc", "marker", "arc_sweep",
                        Map.of("arc", "sweep", "center", "O", "start_boundary", "OA", "end_boundary", "OB"),
                        Map.of("direction", "counterclockwise", "sector", "minor"), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> violations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "O = Dot()",
                        "A = Dot()",
                        "B = Dot()",
                        "OA = Line(O.get_center(), A.get_center())",
                        "OB = Line(O.get_center(), B.get_center())",
                        "sweep = always_redraw(lambda: ArcBetweenPoints(OB.get_end(), OA.get_end(), angle=PI/2).move_arc_center_to(O.get_center()))"));

        assertTrue(violations.stream().anyMatch(violation ->
                "arc_sweep".equals(violation.getRelation())
                        && "sweep".equals(violation.getOwner())
                        && violation.getEvidence().contains("reverse ordered boundaries")));
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
