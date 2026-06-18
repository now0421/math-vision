package com.mathvision.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ToolSchemas constants.
 */
class ToolSchemasTest {

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
    void storyboardTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.STORYBOARD);
        });
    }

    @Test
    void placementPatchTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.placementPatches("2d"));
            mapper.readTree(ToolSchemas.placementPatches("3d"));
        });
    }

    @Test
    void sceneDesignTool_isValidJson() {
        assertDoesNotThrow(() -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(ToolSchemas.SCENE_DESIGN);
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
    void conceptGraphTool_hasRequiredFields() {
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("write_concept_graph"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("start_id"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("nodes"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("next_edges"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("teaching_order"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("node_type"));
        assertTrue(ToolSchemas.CONCEPT_GRAPH.contains("min_depth"));
        assertFalse(ToolSchemas.CONCEPT_GRAPH.contains("prerequisite_edges"));
    }

    @Test
    void manimCodeTool_hasRequiredFields() {
        assertTrue(ToolSchemas.MANIM_CODE.contains("write_manim_code"));
        assertTrue(ToolSchemas.MANIM_CODE.contains("manimCode"));
        assertTrue(ToolSchemas.MANIM_CODE.contains("scene_name"));
    }

    @Test
    void sceneCodeTool_returnsOnlySceneCodeBody() {
        assertTrue(ToolSchemas.SCENE_CODE.contains("write_scene_code"));
        assertTrue(ToolSchemas.SCENE_CODE.contains("sceneCode"));
        assertFalse(ToolSchemas.SCENE_CODE.contains("sceneMethodName"));
    }

    @Test
    void codeReviewTool_usesCanonicalFields() {
        assertTrue(ToolSchemas.CODE_REVIEW.contains("approved_for_render"));
        assertTrue(ToolSchemas.CODE_REVIEW.contains("rule_checks"));
        assertTrue(ToolSchemas.CODE_REVIEW.contains("rule_id"));
        assertTrue(ToolSchemas.CODE_REVIEW.contains("not_applicable"));
        assertTrue(ToolSchemas.CODE_REVIEW.contains("revision_directives"));
        assertFalse(ToolSchemas.CODE_REVIEW.contains("layout_score"));
        assertFalse(ToolSchemas.CODE_REVIEW.contains("pacing_score"));
    }

    @Test
    void codeReviewToolRequiresSeverityField() {
        assertTrue(ToolSchemas.CODE_REVIEW.contains("\"severity\""));
        assertTrue(ToolSchemas.CODE_REVIEW.contains("\"mandatory\""));
        assertTrue(ToolSchemas.CODE_REVIEW.contains("\"recommended\""));
        assertTrue(ToolSchemas.CODE_REVIEW.contains("\"advisory\""));
        assertTrue(ToolSchemas.CODE_REVIEW.contains("\"status\", \"severity\", \"evidence\""));
    }

    @Test
    void storyboardTool_usesTypedStyleObjectWithoutInstructions() {
        assertTrue(ToolSchemas.STORYBOARD.contains("\"style\": {"));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"properties\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"additionalProperties\": false"));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"line_style\": { \"type\": \"string\", \"enum\": [\"solid\", \"dashed\", \"dotted\", \"dash_dot\"] }"));
        assertFalse(ToolSchemas.STORYBOARD.contains("\"required\": [\"role\", \"type\", \"properties\"]"));
        assertFalse(ToolSchemas.STORYBOARD.contains("\"instructions\""));
    }

    @Test
    void storyboardTool_usesIdOnlyScenePatches() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode schema = mapper.readTree(ToolSchemas.STORYBOARD);

        com.fasterxml.jackson.databind.JsonNode sceneProperties = schema.get(0)
                .path("function")
                .path("parameters")
                .path("properties")
                .path("scenes")
                .path("items")
                .path("properties");
        com.fasterxml.jackson.databind.JsonNode enteringProperties = sceneProperties.path("entering_objects")
                .path("items")
                .path("properties");
        com.fasterxml.jackson.databind.JsonNode persistentProperties = sceneProperties.path("persistent_objects")
                .path("items")
                .path("properties");
        com.fasterxml.jackson.databind.JsonNode exitingProperties = sceneProperties.path("exiting_objects")
                .path("items")
                .path("properties");

        assertTrue(enteringProperties.has("id"));
        assertTrue(enteringProperties.has("placement"));
        assertTrue(enteringProperties.has("style"));
        assertFalse(enteringProperties.has("kind"));
        assertFalse(enteringProperties.has("content"));
        assertFalse(enteringProperties.has("constraints"));
        assertTrue(persistentProperties.has("id"));
        assertTrue(persistentProperties.has("placement"));
        assertTrue(persistentProperties.has("style"));
        assertFalse(persistentProperties.has("kind"));
        assertFalse(persistentProperties.has("content"));
        assertFalse(persistentProperties.has("constraints"));
        assertEquals(1, exitingProperties.size());
        assertTrue(exitingProperties.has("id"));
    }

    @Test
    void sharedSchemasUseBackendNeutralContinuityLanguage() {
        assertFalse(ToolSchemas.STORYBOARD.contains("reuse mobjects safely"));
        assertFalse(ToolSchemas.STORYBOARD.contains("Ordered animation operations"));
        assertTrue(ToolSchemas.STORYBOARD.contains("continuity_plan"));
        assertTrue(ToolSchemas.STORYBOARD.contains("actions"));
    }

    @Test
    void storyboardToolKeepsOverlaySemanticsOutOfCompactSchema() {
        assertTrue(ToolSchemas.STORYBOARD.contains("fixed_overlay"));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"relation\": { \"type\": \"string\", \"enum\""));
        assertFalse(ToolSchemas.STORYBOARD.contains("Use `fixed_overlay`"));
        assertFalse(ToolSchemas.STORYBOARD.contains("rather than native geometry"));
    }

    @Test
    void schemasCaptureBeatMappingAndExplicitObjectContracts() {
        assertTrue(ToolSchemas.STORYBOARD.contains("object_registry"));
        assertTrue(ToolSchemas.STORYBOARD.contains("entering_objects"));
        assertTrue(ToolSchemas.STORYBOARD.contains("persistent_objects"));
        assertTrue(ToolSchemas.STORYBOARD.contains("exiting_objects"));
        assertTrue(ToolSchemas.STORYBOARD.contains("actions"));
        assertTrue(ToolSchemas.STORYBOARD.contains("step_refs"));
    }

    @Test
    void storyboardSchemaUsesStrictObjectContractsAndEnums() {
        assertTrue(ToolSchemas.STORYBOARD.contains("\"additionalProperties\": false"));
        assertFalse(ToolSchemas.STORYBOARD.contains("\"behavior\""));
        assertFalse(ToolSchemas.STORYBOARD.contains("\"source_node\""));
        assertFalse(ToolSchemas.STORYBOARD.contains("\"anchor_id\""));
        assertFalse(ToolSchemas.STORYBOARD.contains("\"dependency_objects\""));
        assertFalse(ToolSchemas.STORYBOARD.contains("\"dependency_relation\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"coordinate_bounds\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"required\": [\"coordinate_bounds\", \"scenes\"]"));
        assertFalse(ToolSchemas.STORYBOARD.contains("coordinate_space"));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"enum\": [\"absolute\", \"relative\"]"));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"enum\": [\"create\", \"write\", \"transform\", \"highlight\", \"move\", \"fade_out\", \"camera\", \"restyle\"]"));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"enum\": [\"solid\", \"dashed\", \"dotted\", \"dash_dot\"]"));
    }

    @Test
    void storyboardSchemaAddsTypedStylePropertiesGuardrails() {
        assertFalse(ToolSchemas.STORYBOARD.contains("\"patternProperties\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"highlight_color\": { \"type\": \"string\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"point_style\": { \"type\": \"number\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"font_size\": { \"type\": \"number\" }"));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"label_visible\": { \"type\": \"boolean\" }"));
    }

    @Test
    void storyboardSchemaIncludesStructuredConstraintsContract() {
        assertTrue(ToolSchemas.STORYBOARD.contains("\"constraints\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"domain\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"relation\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"refs\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"parameters\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"strength\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("angle_between"));
        assertFalse(ToolSchemas.STORYBOARD.contains("angle_between_rays"));
        assertFalse(ToolSchemas.STORYBOARD.contains("constraint_note"));
    }

    @Test
    void storyboardSchemaUsesReclassifiedConstraintDomains() {
        assertTrue(ToolSchemas.STORYBOARD.contains("\"placement\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"construction\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"constraint\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"metric\""));
        assertTrue(ToolSchemas.STORYBOARD.contains("\"marker\""));
        assertFalse(ToolSchemas.STORYBOARD.contains("\"geometry\""));
        assertFalse(ToolSchemas.STORYBOARD.contains("\"measurement\""));
    }

    @Test
    void manimStoryboardSchemasIncludeVoiceoverFieldsButGeoGebraSchemasDoNot() {
        String manimStoryboard = ToolSchemas.storyboard("manim");
        String manimSceneDesign = ToolSchemas.sceneDesign("manim");
        String geoGebraStoryboard = ToolSchemas.storyboard("geogebra");
        String geoGebraSceneDesign = ToolSchemas.sceneDesign("geogebra");

        assertTrue(manimStoryboard.contains("\"voiceover_text\""));
        assertTrue(manimStoryboard.contains("\"expected_seconds\""));
        assertTrue(manimSceneDesign.contains("\"voiceover_text\""));
        assertTrue(manimSceneDesign.contains("\"expected_seconds\""));
        assertFalse(geoGebraStoryboard.contains("voiceover_text"));
        assertFalse(geoGebraStoryboard.contains("expected_seconds"));
        assertFalse(geoGebraStoryboard.contains("VoiceoverScene"));
        assertFalse(geoGebraStoryboard.contains("GTTSService"));
        assertFalse(geoGebraStoryboard.contains("self.voiceover"));
        assertFalse(geoGebraSceneDesign.contains("voiceover_text"));
        assertFalse(geoGebraSceneDesign.contains("expected_seconds"));
        assertFalse(geoGebraSceneDesign.contains("manim_voiceover"));
    }

    @Test
    void placementPatchSchemaIsCompactAndSceneModeAware() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode twoD = mapper.readTree(ToolSchemas.placementPatches("2d"));
        com.fasterxml.jackson.databind.JsonNode threeD = mapper.readTree(ToolSchemas.placementPatches("3d"));

        assertTrue(ToolSchemas.placementPatches("2d").contains("write_placement_patches"));
        assertTrue(ToolSchemas.placementPatches("2d").contains("placement_patches"));
        assertFalse(ToolSchemas.placementPatches("2d").contains("object_registry"));
        assertFalse(ToolSchemas.placementPatches("2d").contains("narration"));

        com.fasterxml.jackson.databind.JsonNode twoDPlacementProperties = twoD.get(0)
                .path("function")
                .path("parameters")
                .path("properties")
                .path("placement_patches")
                .path("items")
                .path("properties")
                .path("placement")
                .path("properties");
        com.fasterxml.jackson.databind.JsonNode threeDPlacementProperties = threeD.get(0)
                .path("function")
                .path("parameters")
                .path("properties")
                .path("placement_patches")
                .path("items")
                .path("properties")
                .path("placement")
                .path("properties");

        assertTrue(twoDPlacementProperties.has("x"));
        assertTrue(twoDPlacementProperties.has("y"));
        assertFalse(twoDPlacementProperties.has("z"));
        assertTrue(threeDPlacementProperties.has("z"));
    }

    @Test
    void sceneDesignSchemaIncludesSceneLevelConstraintsContract() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode schema = mapper.readTree(ToolSchemas.SCENE_DESIGN);

        com.fasterxml.jackson.databind.JsonNode sceneProperties = schema.get(0)
                .path("function")
                .path("parameters")
                .path("properties")
                .path("scene")
                .path("properties");

        assertFalse(sceneProperties.has("geometry_constraints"));
        assertTrue(sceneProperties.has("constraints"));
        assertEquals("array", sceneProperties.path("constraints").path("type").asText());
        assertEquals("object", sceneProperties.path("constraints").path("items").path("type").asText());
        assertTrue(sceneProperties.path("constraints").path("items").path("properties").has("relation"));
    }

    @Test
    void sceneDesignSchemaIncludesCoordinateBoundsUpdate() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode schema = mapper.readTree(ToolSchemas.SCENE_DESIGN);

        com.fasterxml.jackson.databind.JsonNode properties = schema.get(0)
                .path("function")
                .path("parameters")
                .path("properties");
        com.fasterxml.jackson.databind.JsonNode boundsUpdate = properties.path("coordinate_bounds_update");

        assertEquals("object", boundsUpdate.path("type").asText());
        assertFalse(boundsUpdate.path("properties").has("coordinate_space"));
        assertTrue(boundsUpdate.path("properties").path("x").path("properties").has("min"));
        assertTrue(boundsUpdate.path("properties").path("x").path("properties").has("max"));
        assertTrue(boundsUpdate.path("properties").has("reason"));
    }

    @Test
    void sceneDesignSchemaUsesProblemSceneModeForZFields() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode twoD = mapper.readTree(ToolSchemas.sceneDesign("manim", "2d"));
        com.fasterxml.jackson.databind.JsonNode threeD = mapper.readTree(ToolSchemas.sceneDesign("manim", "3d"));

        com.fasterxml.jackson.databind.JsonNode twoDProperties = twoD.get(0)
                .path("function")
                .path("parameters")
                .path("properties");
        com.fasterxml.jackson.databind.JsonNode threeDProperties = threeD.get(0)
                .path("function")
                .path("parameters")
                .path("properties");

        assertFalse(twoDProperties.path("coordinate_bounds_update").path("properties").has("z"));
        assertTrue(threeDProperties.path("coordinate_bounds_update").path("properties").has("z"));

        com.fasterxml.jackson.databind.JsonNode twoDPlacement = twoDProperties.path("scene")
                .path("properties")
                .path("entering_objects")
                .path("items")
                .path("properties")
                .path("placement")
                .path("properties");
        com.fasterxml.jackson.databind.JsonNode threeDPlacement = threeDProperties.path("scene")
                .path("properties")
                .path("entering_objects")
                .path("items")
                .path("properties")
                .path("placement")
                .path("properties");

        assertFalse(twoDPlacement.has("z"));
        assertTrue(threeDPlacement.has("z"));
        assertFalse(twoDProperties.path("scene").path("properties").has("scene_mode"));
        assertFalse(threeDProperties.path("scene").path("properties").has("scene_mode"));
    }
}
