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
        assertEquals("construction", StoryboardConstraintCatalog.relation("rotate_about").domain());
        assertEquals("construction", StoryboardConstraintCatalog.relation("vector_from_to").domain());
        assertEquals("constraint", StoryboardConstraintCatalog.relation("lies_on").domain());
        assertEquals("metric", StoryboardConstraintCatalog.relation("equal_measure_group").domain());
        assertEquals("marker", StoryboardConstraintCatalog.relation("angle_between").domain());
        assertEquals("motion", StoryboardConstraintCatalog.relation("trace_of").domain());
    }

    @Test
    void pointAtIsExplicitPlacementNotCoordinateDerived() {
        assertFalse(StoryboardConstraintCatalog.isCoordinateDerivedRelation("point_at"));
        assertFalse(StoryboardConstraintCatalog.isMotionSensitiveRelation("point_at"));
    }

    @Test
    void locksCoordinateDerivedRelationsWhoseOwnersAreComputedFromRefs() {
        assertCoordinateDerivedAndMotionSensitive("reflection_across");
        assertCoordinateDerivedAndMotionSensitive("rotate_about");
        assertCoordinateDerivedAndMotionSensitive("vector_from_to");
        assertCoordinateDerivedAndMotionSensitive("midpoint_of");
        assertCoordinateDerivedAndMotionSensitive("intersection_of");
        assertCoordinateDerivedAndMotionSensitive("parallel_through");
        assertCoordinateDerivedAndMotionSensitive("trace_of");
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

    @Test
    void sideOfLineUsesDedicatedRelationInsteadOfLiesOnSideParameter() {
        StoryboardConstraintCatalog.RelationSpec liesOn = StoryboardConstraintCatalog.relation("lies_on");
        assertFalse(liesOn.allowedParameters().contains("side"));

        StoryboardConstraintCatalog.RelationSpec onSideOf = StoryboardConstraintCatalog.relation("on_side_of");
        assertEquals("constraint", onSideOf.domain());
        assertTrue(onSideOf.requiredParameters().contains("side"));
        assertTrue(onSideOf.allowedRefs().contains("object"));
        assertTrue(onSideOf.allowedRefs().contains("reference"));
        assertTrue(onSideOf.ownerRefRoles().contains("object"));
        assertTrue(onSideOf.dependencyRefRoles().contains("reference"));
        assertTrue(onSideOf.enumParameters().get("side").contains("above"));
        assertTrue(onSideOf.enumParameters().get("side").contains("below"));
    }

    @Test
    void positioningParameterReplacesCoordinateSpaceInConstraintCatalog() {
        for (StoryboardConstraintCatalog.RelationSpec spec : StoryboardConstraintCatalog.relations()) {
            assertFalse(spec.allowedParameters().contains("coordinate_space"),
                    spec.relation() + " should not allow the old coordinate_space parameter");
            if (spec.allowedParameters().contains("positioning")) {
                assertTrue(spec.enumParameters().get("positioning").contains("absolute"), spec.relation());
                assertTrue(spec.enumParameters().get("positioning").contains("relative"), spec.relation());
            }
        }
    }

    @Test
    void otherRelationIsDomainSpecificFallback() {
        for (String domain : StoryboardConstraintCatalog.domainList().split("\\|")) {
            StoryboardConstraintCatalog.RelationSpec spec = StoryboardConstraintCatalog.relation(domain, "other");
            assertEquals(domain, spec.domain());
            assertTrue(spec.allowedParameters().contains("description"));
            assertTrue(spec.allowedParameters().contains("formula"));
            assertTrue(spec.allowedRefs().contains("object"));
            assertTrue(spec.allowedRefs().contains("source"));
            assertTrue(spec.requiredRefGroups().isEmpty());
        }
        assertTrue(StoryboardConstraintCatalog.relationList().contains("other"));
        assertTrue(StoryboardConstraintCatalog.relationEnumJson().contains("\"other\""));
        assertFalse(StoryboardConstraintCatalog.relationEnumJson().contains("construction:other"));
    }

    @Test
    void newRelationsUseExpectedRolesAndParameters() {
        StoryboardConstraintCatalog.RelationSpec rotate = StoryboardConstraintCatalog.relation("rotate_about");
        assertTrue(rotate.allowedRefs().contains("image"));
        assertTrue(rotate.allowedRefs().contains("source"));
        assertTrue(rotate.allowedRefs().contains("center"));
        assertTrue(rotate.requiredParameters().contains("angle"));
        assertTrue(rotate.enumParameters().get("direction").contains("counterclockwise"));

        StoryboardConstraintCatalog.RelationSpec trace = StoryboardConstraintCatalog.relation("trace_of");
        assertEquals("motion", trace.domain());
        assertTrue(trace.allowedRefs().contains("trace"));
        assertTrue(trace.allowedRefs().contains("source_point"));

        StoryboardConstraintCatalog.RelationSpec vector = StoryboardConstraintCatalog.relation("vector_from_to");
        assertEquals("construction", vector.domain());
        assertTrue(vector.allowedRefs().contains("vector"));
        assertTrue(vector.allowedRefs().contains("start"));
        assertTrue(vector.allowedRefs().contains("end"));
    }

    private static void assertNotCoordinateDerived(String relation) {
        assertFalse(StoryboardConstraintCatalog.isCoordinateDerivedRelation(relation));
    }

    private static void assertCoordinateDerivedAndMotionSensitive(String relation) {
        assertTrue(StoryboardConstraintCatalog.isCoordinateDerivedRelation(relation));
        assertTrue(StoryboardConstraintCatalog.isMotionSensitiveRelation(relation));
    }
}
