package com.mathvision.util;

import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardConstraint;
import com.mathvision.model.Narrative.StoryboardObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class StoryboardNormalizerTest {

    @Test
    void movesObjectLevelConstraintsToCatalogOwnerObject() {
        StoryboardObject left = object("lLeft", "point");
        StoryboardObject right = object("lRight", "point");
        StoryboardObject line = object("l", "line");
        StoryboardConstraint connectsLine = constraint("construction", "connects_points",
                Map.of("object", "l", "start", "lLeft", "end", "lRight"),
                Map.of());
        StoryboardConstraint placesLeft = constraint("placement", "point_at",
                Map.of("point", "lLeft"),
                Map.of("coordinate", List.of(-6.5, 0.0)));
        StoryboardConstraint placesRight = constraint("placement", "point_at",
                Map.of("point", "lRight"),
                Map.of("coordinate", List.of(6.5, 0.0)));
        line.setConstraints(new ArrayList<>(List.of(connectsLine, placesLeft, placesRight)));

        Storyboard storyboard = new Storyboard();
        storyboard.setObjectRegistry(List.of(left, right, line));

        StoryboardNormalizer.normalize(storyboard);

        assertEquals(List.of(connectsLine), line.getConstraints());
        assertEquals(List.of(placesLeft), left.getConstraints());
        assertEquals(List.of(placesRight), right.getConstraints());
        assertSame(placesLeft, left.getConstraints().get(0));
        assertSame(placesRight, right.getConstraints().get(0));
    }

    @Test
    void movesLabelForConstraintsFromAnchorToLabelObject() {
        StoryboardObject point = object("A", "point");
        StoryboardObject label = object("labelA", "text");
        StoryboardConstraint labelFor = constraint("attachment", "label_for",
                Map.of("label", "labelA", "anchor", "A"),
                Map.of("side", "up_left", "offset", 0.25));
        point.setConstraints(new ArrayList<>(List.of(labelFor)));

        Storyboard storyboard = new Storyboard();
        storyboard.setObjectRegistry(List.of(point, label));

        StoryboardNormalizer.normalize(storyboard);

        assertEquals(List.of(), point.getConstraints());
        assertEquals(List.of(labelFor), label.getConstraints());
        assertSame(labelFor, label.getConstraints().get(0));
    }

    @Test
    void leavesConstraintsInPlaceWhenOwnerIsAmbiguous() {
        StoryboardObject line = object("l", "line");
        StoryboardConstraint multiOwner = constraint("layout", "keep_inside_safe_area",
                Map.of("objects", List.of("A", "B")),
                Map.of("margin", 0.5));
        line.setConstraints(new ArrayList<>(List.of(multiOwner)));

        Storyboard storyboard = new Storyboard();
        storyboard.setObjectRegistry(List.of(line, object("A", "point"), object("B", "point")));

        StoryboardNormalizer.normalize(storyboard);

        assertEquals(List.of(multiOwner), line.getConstraints());
    }

    private static StoryboardObject object(String id, String kind) {
        StoryboardObject object = new StoryboardObject();
        object.setId(id);
        object.setKind(kind);
        return object;
    }

    private static StoryboardConstraint constraint(String domain,
                                                   String relation,
                                                   Map<String, Object> refs,
                                                   Map<String, Object> parameters) {
        StoryboardConstraint constraint = new StoryboardConstraint();
        constraint.setDomain(domain);
        constraint.setRelation(relation);
        constraint.setRefs(refs);
        constraint.setParameters(parameters);
        constraint.setStrength("hard");
        return constraint;
    }
}
