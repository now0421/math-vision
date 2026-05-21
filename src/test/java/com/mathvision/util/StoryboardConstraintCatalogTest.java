package com.mathvision.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardConstraintCatalogTest {

    @Test
    void validatesReclassifiedDomains() {
        assertTrue(StoryboardConstraintCatalog.isValidDomain("placement"));
        assertTrue(StoryboardConstraintCatalog.isValidDomain("construction"));
        assertTrue(StoryboardConstraintCatalog.isValidDomain("constraint"));
        assertTrue(StoryboardConstraintCatalog.isValidDomain("metric"));
        assertTrue(StoryboardConstraintCatalog.isValidDomain("marker"));
        assertFalse(StoryboardConstraintCatalog.isValidDomain("geometry"));
        assertFalse(StoryboardConstraintCatalog.isValidDomain("measurement"));
    }

    @Test
    void mapsRepresentativeRelationsToReclassifiedDomains() {
        assertEquals("placement", StoryboardConstraintCatalog.relation("point_at").domain());
        assertEquals("construction", StoryboardConstraintCatalog.relation("reflection_across").domain());
        assertEquals("constraint", StoryboardConstraintCatalog.relation("lies_on").domain());
        assertEquals("metric", StoryboardConstraintCatalog.relation("equal_measure_group").domain());
        assertEquals("marker", StoryboardConstraintCatalog.relation("angle_between").domain());
    }

    @Test
    void pointAtIsExplicitPlacementNotCoordinateDerived() {
        assertFalse(StoryboardConstraintCatalog.isCoordinateDerivedRelation("point_at"));
        assertFalse(StoryboardConstraintCatalog.isMotionSensitiveRelation("point_at"));
    }

    @Test
    void locksCoordinateDerivedRelationsWhoseOwnersAreComputedFromRefs() {
        assertCoordinateDerivedAndMotionSensitive("reflection_across");
        assertCoordinateDerivedAndMotionSensitive("midpoint_of");
        assertCoordinateDerivedAndMotionSensitive("intersection_of");
        assertCoordinateDerivedAndMotionSensitive("parallel_through");
        assertCoordinateDerivedAndMotionSensitive("angle_between");
        assertCoordinateDerivedAndMotionSensitive("label_for");
    }

    @Test
    void locksNonCoordinateDerivedRelationsThatStillMayNeedPlacement() {
        assertNotCoordinateDerived("point_at");
        assertNotCoordinateDerived("lies_on");
        assertNotCoordinateDerived("parallel_to");
        assertNotCoordinateDerived("perpendicular_to");
        assertNotCoordinateDerived("distance_between");
        assertNotCoordinateDerived("moves_on_object");
        assertNotCoordinateDerived("fixed_overlay");
        assertNotCoordinateDerived("keep_inside_safe_area");
    }

    @Test
    void coordinateDerivedRelationsHaveDependencies() {
        for (StoryboardConstraintCatalog.RelationSpec spec : StoryboardConstraintCatalog.relations()) {
            if (spec.coordinateDerived()) {
                assertFalse(spec.dependencyRefRoles().isEmpty(),
                        spec.relation() + " must have dependency refs when marked coordinate-derived");
            }
        }
    }

    private static void assertNotCoordinateDerived(String relation) {
        assertFalse(StoryboardConstraintCatalog.isCoordinateDerivedRelation(relation));
    }

    private static void assertCoordinateDerivedAndMotionSensitive(String relation) {
        assertTrue(StoryboardConstraintCatalog.isCoordinateDerivedRelation(relation));
        assertTrue(StoryboardConstraintCatalog.isMotionSensitiveRelation(relation));
    }
}