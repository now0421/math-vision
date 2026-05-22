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

class StoryboardConstraintComplianceAnalyzerTest {

    private final StoryboardConstraintComplianceAnalyzer analyzer = new StoryboardConstraintComplianceAnalyzer();

    @Test
    void backendSemanticConstraintsAreLeftToCodeEvaluationReview() {
        Storyboard storyboard = storyboard(
                object("A", "point"),
                object("B", "point"),
                object("l", "line"),
                object("AB", "segment", constraint("AB_connects", "construction", "connects_points",
                        Map.of("object", "AB", "start", "A", "end", "B"), Map.of(), "hard")),
                object("P", "point", constraint("P_intersection", "construction", "intersection_of",
                        Map.of("point", "P", "object_a", "AB", "object_b", "l"), Map.of(), "hard")),
                object("P_label", "text", constraint("P_label_for_P", "attachment", "label_for",
                        Map.of("label", "P_label", "anchor", "P"), Map.of(), "hard")));

        List<StoryboardConstraintComplianceAnalyzer.Violation> manimViolations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_MANIM,
                String.join("\n",
                        "A = Dot()",
                        "B = Dot()",
                        "l = Line(LEFT, RIGHT)",
                        "P = Dot([0.6, -1, 0])"));
        List<StoryboardConstraintComplianceAnalyzer.Violation> geogebraViolations = analyzer.analyze(
                storyboard,
                WorkflowConfig.OUTPUT_TARGET_GEOGEBRA,
                String.join("\n",
                        "A = (0, 0)",
                        "B = (2, 0)",
                        "P = (0.6, -1)"));

        assertEquals(List.of(), manimViolations);
        assertEquals(List.of(), geogebraViolations);
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
