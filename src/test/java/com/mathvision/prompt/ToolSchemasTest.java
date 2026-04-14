package com.mathvision.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ToolSchemas constants.
 */
class ToolSchemasTest {

    @Test
    void prerequisitesTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.PREREQUISITES);
        });
    }

    @Test
    void foundationCheckTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.FOUNDATION_CHECK);
        });
    }

    @Test
    void inputModeTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.INPUT_MODE);
        });
    }

    @Test
    void conceptGraphTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.CONCEPT_GRAPH);
        });
    }

    @Test
    void problemGraphTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.PROBLEM_GRAPH);
        });
    }

    @Test
    void mathEnrichmentTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.MATH_ENRICHMENT);
        });
    }

    @Test
    void visualDesignTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.VISUAL_DESIGN);
        });
    }

    @Test
    void storyboardTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.STORYBOARD);
        });
    }

    @Test
    void manimCodeTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.MANIM_CODE);
        });
    }

    @Test
    void codeReviewTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.CODE_REVIEW);
        });
    }

    @Test
    void prerequisitesTool_hasRequiredFields() {
        assertTrue(ToolSchemas.PREREQUISITES.contains("write_prerequisites"));
        assertTrue(ToolSchemas.PREREQUISITES.contains("prerequisites"));
        assertTrue(ToolSchemas.PREREQUISITES.contains("step"));
        assertTrue(ToolSchemas.PREREQUISITES.contains("reason"));
    }

    @Test
    void conceptGraphTool_hasRequiredFields() {
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("write_concept_graph"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("root_id"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("nodes"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("prerequisite_edges"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("concept"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("observation"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("construction"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("derivation"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("conclusion"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("is_foundation"));
    }

    @Test
    void manimCodeTool_hasRequiredFields() {
        assertTrue(ToolSchemas.MANIM_CODE.contains("write_manim_code"));
        assertTrue(ToolSchemas.MANIM_CODE.contains("manimCode"));
        assertTrue(ToolSchemas.MANIM_CODE.contains("scene_name"));
    }

    @Test
    void codeReviewTool_usesCanonicalFields() {
        assertTrue(ToolSchemas.CODE_REVIEW.contains("approved_for_render"));
        assertTrue(ToolSchemas.CODE_REVIEW.contains("revision_directives"));
        assertTrue(ToolSchemas.CODE_REVIEW.contains("likely_offscreen_risk"));
    }

    @Test
    void storyboardTool_usesPropertiesNotInstructionsForStyle() {
        assertTrue(ToolSchemas.STORYBOARD.contains("\"properties\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"required\": [\"role\", \"type\", \"properties\"]"));
        assertFalse(ToolSchemas.STORYBOARD.contains("\"instructions\""));
    }

    @Test
    void storyboardTool_requiresReferencedObjectsToBeNamedByIdOnly() {
        assertTrue(ToolSchemas.STORYBOARD.contains("mention them by id only"));
        assertTrue(ToolSchemas.STORYBOARD.contains("angle between AP and l at P"));
        assertTrue(ToolSchemas.STORYBOARD.contains("do not restate their kind"));
    }

    @Test
    void sharedSchemasUseBackendNeutralContinuityLanguage() {
        assertFalse(ToolSchemas.VISUAL_DESIGN.contains("Manim color names"));
        assertTrue(ToolSchemas.VISUAL_DESIGN.contains("Backend-compatible color names"));
        assertFalse(ToolSchemas.STORYBOARD.contains("reuse mobjects safely"));
        assertFalse(ToolSchemas.STORYBOARD.contains("Ordered animation operations"));
        assertTrue(ToolSchemas.STORYBOARD.contains("same logical objects safely"));
        assertTrue(ToolSchemas.STORYBOARD.contains("scene or presentation operations"));
    }

    @Test
    void storyboardToolExplainsFixedOverlayAsOverlaySpecific() {
        assertTrue(ToolSchemas.STORYBOARD.contains("Use `fixed_overlay` mainly for explanatory text"));
        assertTrue(ToolSchemas.STORYBOARD.contains("rather than native geometry"));
    }

    @Test
    void schemasCaptureBeatMappingAndExplicitObjectContracts() {
        assertTrue(ToolSchemas.VISUAL_DESIGN.contains("empty breathing room"));
        assertTrue(ToolSchemas.VISUAL_DESIGN.contains("animate-vs-static intent"));
        assertTrue(ToolSchemas.STORYBOARD.contains("learner-visible object"));
        assertTrue(ToolSchemas.STORYBOARD.contains("learner-visible beat"));
        assertTrue(ToolSchemas.STORYBOARD.contains("what the learner should notice"));
    }
}
