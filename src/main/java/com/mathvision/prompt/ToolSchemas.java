package com.mathvision.prompt;

import com.mathvision.util.SceneModeUtils;
import com.mathvision.util.StoryboardConstraintCatalog;

/**
 * Central registry of all tool JSON schemas used for structured LLM output.
 *
 * Design principle: schemas contain only output structure (field names, types,
 * required fields, additionalProperties, and small structural enums).
 * All semantic descriptions, validation rules, and value constraints
 * belong in system prompts, not in the schema. This keeps schemas compact
 * enough for providers with strict tool-schema size limits (e.g., Moonshot).
 */
public final class ToolSchemas {

    private ToolSchemas() {}

    private static final String STORYBOARD_STYLE_FIELD =
            "\"style\": {"
                    + "  \"type\": \"object\","
                    + "  \"properties\": {"
                    + "    \"color\": { \"type\": \"string\" },"
                    + "    \"fill_color\": { \"type\": \"string\" },"
                    + "    \"stroke_color\": { \"type\": \"string\" },"
                    + "    \"highlight_color\": { \"type\": \"string\" },"
                    + "    \"font_family\": { \"type\": \"string\" },"
                    + "    \"font_weight\": { \"type\": \"string\" },"
                    + "    \"font_style\": { \"type\": \"string\" },"
                    + "    \"line_style\": { \"type\": \"string\", \"enum\": [\"solid\", \"dashed\", \"dotted\", \"dash_dot\"] },"
                    + "    \"opacity\": { \"type\": \"number\" },"
                    + "    \"fill_opacity\": { \"type\": \"number\" },"
                    + "    \"stroke_opacity\": { \"type\": \"number\" },"
                    + "    \"stroke_width\": { \"type\": \"number\" },"
                    + "    \"font_size\": { \"type\": \"number\" },"
                    + "    \"padding\": { \"type\": \"number\" },"
                    + "    \"corner_radius\": { \"type\": \"number\" },"
                    + "    \"z_index\": { \"type\": \"number\" },"
                    + "    \"point_size\": { \"type\": \"number\" },"
                    + "    \"radius\": { \"type\": \"number\" },"
                    + "    \"marker_size\": { \"type\": \"number\" },"
                    + "    \"point_style\": { \"type\": \"number\" },"
                    + "    \"decoration\": { \"type\": \"number\" },"
                    + "    \"label_visible\": { \"type\": \"boolean\" }"
                    + "  },"
                    + "  \"additionalProperties\": false"
                    + "}";

    private static final String STORYBOARD_CONSTRAINTS_FIELD =
            "\"constraints\": {"
                    + "  \"type\": \"array\","
                    + "  \"items\": {"
                    + "    \"type\": \"object\","
                    + "    \"properties\": {"
                    + "      \"id\": { \"type\": \"string\" },"
                    + "      \"domain\": { \"type\": \"string\", \"enum\": " + StoryboardConstraintCatalog.domainEnumJson() + " },"
                    + "      \"relation\": { \"type\": \"string\", \"enum\": " + StoryboardConstraintCatalog.relationEnumJson() + " },"
                    + "      \"refs\": {"
                    + "        \"type\": \"object\","
                    + "        \"minProperties\": 1,"
                    + "        \"additionalProperties\": {"
                    + "          \"oneOf\": ["
                    + "            { \"type\": \"string\" },"
                    + "            { \"type\": \"array\", \"items\": { \"type\": \"string\" } }"
                    + "          ]"
                    + "        }"
                    + "      },"
                    + "      \"parameters\": {"
                    + "        \"type\": \"object\","
                    + "        \"additionalProperties\": {"
                    + "          \"oneOf\": ["
                    + "            { \"type\": \"string\" },"
                    + "            { \"type\": \"number\" },"
                    + "            { \"type\": \"boolean\" }"
                    + "          ]"
                    + "        }"
                    + "      },"
                    + "      \"strength\": { \"type\": \"string\", \"enum\": [\"hard\", \"repair_hard\", \"soft\"] },"
                    + "      \"reason\": { \"type\": \"string\" }"
                    + "    },"
                    + "    \"additionalProperties\": false,"
                    + "    \"required\": [\"domain\", \"relation\", \"refs\", \"strength\"]"
                    + "  }"
                    + "}";

    private static final String COORDINATE_BOUNDS_AXIS_SCHEMA =
            "{ \"type\": \"object\", "
                    + "\"properties\": {"
                    + "  \"min\": { \"type\": \"number\" },"
                    + "  \"max\": { \"type\": \"number\" }"
                    + "}, "
                    + "\"additionalProperties\": false, "
                    + "\"required\": [\"min\", \"max\"] }";

    private static final String COORDINATE_BOUNDS_FIELD =
            "\"coordinate_bounds\": {"
                    + "  \"type\": \"object\","
                    + "  \"properties\": {"
                    + "    \"x\": " + COORDINATE_BOUNDS_AXIS_SCHEMA + ","
                    + "    \"y\": " + COORDINATE_BOUNDS_AXIS_SCHEMA + ","
                    + "    \"z\": " + COORDINATE_BOUNDS_AXIS_SCHEMA + ","
                    + "    \"padding\": { \"type\": \"number\" },"
                    + "    \"reason\": { \"type\": \"string\" }"
                    + "  },"
                    + "  \"additionalProperties\": false,"
                    + "  \"required\": [\"x\", \"y\", \"z\"]"
                    + "}";

    public static String storyboard(String outputTarget) {
        return storyboard(outputTarget, SceneModeUtils.MODE_2D);
    }

    public static String storyboard(String outputTarget, String sceneMode) {
        String schema = isManim(outputTarget) ? withVoiceoverActionFields(STORYBOARD) : STORYBOARD;
        schema = applySceneModeSchema(schema, sceneMode);
        return isManim(outputTarget) ? withoutLabelVisible(schema) : schema;
    }

    public static String sceneDesign(String outputTarget) {
        return sceneDesign(outputTarget, SceneModeUtils.MODE_2D);
    }

    public static String sceneDesign(String outputTarget, String sceneMode) {
        String schema = isManim(outputTarget) ? withVoiceoverActionFields(SCENE_DESIGN) : SCENE_DESIGN;
        schema = applySceneModeSchema(schema, sceneMode);
        return isManim(outputTarget) ? withoutLabelVisible(schema) : schema;
    }

    private static String applySceneModeSchema(String schema, String sceneMode) {
        if (schema == null || SceneModeUtils.isThreeD(sceneMode)) {
            return schema;
        }
        return schema
                .replace("    \"z\": " + COORDINATE_BOUNDS_AXIS_SCHEMA + ",", "")
                .replace(",\"required\": [\"x\", \"y\", \"z\"]", ",\"required\": [\"x\", \"y\"]")
                .replace("            \"z\": " + COORDINATE_BOUNDS_AXIS_SCHEMA + ",", "")
                .replace("                      \"y\": { \"type\": \"object\" },"
                                + "                      \"z\": { \"type\": \"object\" }",
                        "                      \"y\": { \"type\": \"object\" }")
                .replace("                        \"y\": { \"type\": \"object\", \"properties\": { \"value\": { \"type\": \"number\" }, \"min\": { \"type\": \"number\" }, \"max\": { \"type\": \"number\" } }, \"additionalProperties\": false },"
                                + "                        \"z\": { \"type\": \"object\", \"properties\": { \"value\": { \"type\": \"number\" }, \"min\": { \"type\": \"number\" }, \"max\": { \"type\": \"number\" } }, \"additionalProperties\": false }",
                        "                        \"y\": { \"type\": \"object\", \"properties\": { \"value\": { \"type\": \"number\" }, \"min\": { \"type\": \"number\" }, \"max\": { \"type\": \"number\" } }, \"additionalProperties\": false }");
    }

    private static boolean isManim(String outputTarget) {
        return outputTarget == null || "manim".equalsIgnoreCase(outputTarget);
    }

    private static String withVoiceoverActionFields(String schema) {
        if (schema == null || schema.isBlank() || schema.contains("\"voiceover_text\"")) {
            return schema;
        }
        return schema.replace(
                "\"description\": { \"type\": \"string\" }",
                "\"description\": { \"type\": \"string\" },"
                        + "                    \"voiceover_text\": { \"type\": \"string\" },"
                        + "                    \"expected_seconds\": { \"type\": \"number\" }");
    }

    private static String withoutLabelVisible(String schema) {
        if (schema == null || schema.isBlank()) {
            return schema;
        }
        return schema.replace(
                "\"marker_size\": { \"type\": \"number\" },"
                        + "    \"point_style\": { \"type\": \"number\" },"
                        + "    \"decoration\": { \"type\": \"number\" },"
                        + "    \"label_visible\": { \"type\": \"boolean\" }",
                "\"marker_size\": { \"type\": \"number\" },"
                        + "    \"point_style\": { \"type\": \"number\" },"
                        + "    \"decoration\": { \"type\": \"number\" }");
    }

    // ========================================================================
    // Stage 0: Problem Normalization
    // ========================================================================

    public static final String PROBLEM_BUNDLE = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_problem_bundle\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"id\": { \"type\": \"string\" },"
            + "        \"title\": { \"type\": \"string\" },"
            + "        \"input_mode\": { \"type\": \"string\", \"enum\": [\"concept\", \"problem\"] },"
            + "        \"scene_mode\": { \"type\": \"string\", \"enum\": [\"2d\", \"3d\"] },"
            + "        \"statement\": { \"type\": \"string\" },"
            + "        \"diagram\": {"
            + "          \"type\": \"object\","
            + "          \"properties\": {"
            + "            \"present\": { \"type\": \"boolean\" },"
            + "            \"source_observed\": { \"type\": \"boolean\" },"
            + "            \"diagram_description\": {"
            + "              \"type\": \"object\","
            + "              \"additionalProperties\": true"
            + "            },"
            + "            \"coordinate_model\": {"
            + "              \"type\": \"object\","
            + "              \"additionalProperties\": true"
            + "            },"
            + "            \"unknowns\": {"
            + "              \"type\": \"array\","
            + "              \"items\": {"
            + "                \"type\": \"object\","
            + "                \"additionalProperties\": true"
            + "              }"
            + "            },"
            + "            \"ambiguities\": {"
            + "              \"type\": \"array\","
            + "              \"items\": {"
            + "                \"type\": \"object\","
            + "                \"additionalProperties\": true"
            + "              }"
            + "            },"
            + "            \"normalization_notes\": {"
            + "              \"type\": \"array\","
            + "              \"items\": { \"type\": \"string\" }"
            + "            }"
            + "          },"
            + "          \"required\": [\"present\"],"
            + "          \"additionalProperties\": false"
            + "        }"
            + "      },"
            + "      \"required\": [\"id\", \"title\", \"input_mode\", \"scene_mode\", \"statement\", \"diagram\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 1: Exploration
    // ========================================================================

    public static final String INPUT_MODE = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_input_mode\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"input_mode\": { \"type\": \"string\" },"
            + "        \"reason\": { \"type\": \"string\" }"
            + "      },"
            + "      \"required\": [\"input_mode\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    public static final String CONCEPT_GRAPH = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_concept_graph\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"start_id\": { \"type\": \"string\" },"
            + "        \"nodes\": {"
            + "          \"type\": \"array\","
            + "          \"items\": {"
            + "            \"type\": \"object\","
            + "            \"properties\": {"
            + "              \"id\": { \"type\": \"string\" },"
            + "              \"step\": { \"type\": \"string\" },"
            + "              \"reason\": { \"type\": \"string\" },"
            + "              \"node_type\": { \"type\": \"string\" },"
            + "              \"min_depth\": { \"type\": \"integer\" }"
            + "            },"
            + "            \"required\": [\"id\", \"step\", \"node_type\", \"min_depth\"]"
            + "          }"
            + "        },"
            + "        \"next_edges\": {"
            + "          \"type\": \"object\","
            + "          \"additionalProperties\": {"
            + "            \"type\": \"array\","
            + "            \"items\": { \"type\": \"string\" }"
            + "          }"
            + "        },"
            + "        \"teaching_order\": {"
            + "          \"type\": \"array\","
            + "          \"items\": { \"type\": \"string\" }"
            + "        }"
            + "      },"
            + "      \"required\": [\"start_id\", \"nodes\", \"next_edges\", \"teaching_order\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    public static final String PROBLEM_GRAPH = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_problem_step_graph\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"start_id\": { \"type\": \"string\" },"
            + "        \"nodes\": {"
            + "          \"type\": \"array\","
            + "          \"items\": {"
            + "            \"type\": \"object\","
            + "            \"properties\": {"
            + "              \"id\": { \"type\": \"string\" },"
            + "              \"step\": { \"type\": \"string\" },"
            + "              \"reason\": { \"type\": \"string\" },"
            + "              \"node_type\": { \"type\": \"string\" },"
            + "              \"min_depth\": { \"type\": \"integer\" }"
            + "            },"
            + "            \"required\": [\"id\", \"step\", \"node_type\", \"min_depth\"]"
            + "          }"
            + "        },"
            + "        \"next_edges\": {"
            + "          \"type\": \"object\","
            + "          \"additionalProperties\": {"
            + "            \"type\": \"array\","
            + "            \"items\": { \"type\": \"string\" }"
            + "          }"
            + "        },"
            + "        \"teaching_order\": {"
            + "          \"type\": \"array\","
            + "          \"items\": { \"type\": \"string\" }"
            + "        }"
            + "      },"
            + "      \"required\": [\"start_id\", \"nodes\", \"next_edges\", \"teaching_order\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 2: Math Enrichment
    // ========================================================================

    public static final String MATH_ENRICHMENT = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_enrichment\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"step\": { \"type\": \"string\" },"
            + "        \"reason\": { \"type\": \"string\" },"
            + "        \"equations\": {"
            + "          \"type\": \"array\","
            + "          \"items\": { \"type\": \"string\" }"
            + "        },"
            + "        \"definitions\": {"
            + "          \"type\": \"object\","
            + "          \"additionalProperties\": { \"type\": \"string\" }"
            + "        },"
            + "        \"interpretation\": { \"type\": \"string\" },"
            + "        \"examples\": {"
            + "          \"type\": \"array\","
            + "          \"items\": { \"type\": \"string\" }"
            + "        }"
            + "      },"
            + "      \"required\": [\"step\", \"reason\", \"equations\", \"definitions\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 4: Storyboard Validation
    // ========================================================================

    public static final String STORYBOARD = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_storyboard\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"continuity_plan\": { \"type\": \"string\" },"
            + "        " + COORDINATE_BOUNDS_FIELD + ","
            + "        \"global_visual_rules\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "        \"object_registry\": {"
            + "          \"type\": \"array\","
            + "          \"items\": {"
            + "            \"type\": \"object\","
            + "            \"properties\": {"
            + "              \"id\": { \"type\": \"string\" },"
            + "              \"kind\": { \"type\": \"string\" },"
            + "              \"content\": { \"type\": \"string\" },"
            + "              " + STORYBOARD_STYLE_FIELD + ","
            + "              " + STORYBOARD_CONSTRAINTS_FIELD
            + "            },"
            + "            \"additionalProperties\": false,"
            + "            \"required\": [\"id\", \"kind\", \"content\"]"
            + "          }"
            + "        },"
            + "        \"scenes\": {"
            + "          \"type\": \"array\","
            + "          \"items\": {"
            + "            \"type\": \"object\","
            + "            \"properties\": {"
            + "              \"scene_id\": { \"type\": \"string\" },"
            + "              \"title\": { \"type\": \"string\" },"
            + "              \"goal\": { \"type\": \"string\" },"
            + "              \"narration\": { \"type\": \"string\" },"
            + "              \"duration_seconds\": { \"type\": \"integer\" },"
            + "              \"camera_anchor\": { \"type\": \"string\" },"
            + "              \"camera_plan\": { \"type\": \"string\" },"
            + "              \"layout_goal\": { \"type\": \"string\" },"
            + "              \"safe_area_plan\": { \"type\": \"string\" },"
            + "              \"screen_overlay_plan\": { \"type\": \"string\" },"
            + "              \"step_refs\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "              " + STORYBOARD_CONSTRAINTS_FIELD + ","
            + "              \"entering_objects\": {"
            + "                \"type\": \"array\","
            + "                \"items\": {"
            + "                  \"type\": \"object\","
            + "                  \"properties\": {"
            + "                    \"id\": { \"type\": \"string\" },"
            + "                    \"placement\": {"
            + "                      \"type\": \"object\","
            + "                      \"properties\": {"
            + "                        \"positioning\": { \"type\": \"string\", \"enum\": [\"absolute\", \"relative\"] },"
            + "                        \"x\": { \"type\": \"object\", \"properties\": { \"value\": { \"type\": \"number\" }, \"min\": { \"type\": \"number\" }, \"max\": { \"type\": \"number\" } }, \"additionalProperties\": false },"
            + "                        \"y\": { \"type\": \"object\", \"properties\": { \"value\": { \"type\": \"number\" }, \"min\": { \"type\": \"number\" }, \"max\": { \"type\": \"number\" } }, \"additionalProperties\": false },"
            + "                        \"z\": { \"type\": \"object\", \"properties\": { \"value\": { \"type\": \"number\" }, \"min\": { \"type\": \"number\" }, \"max\": { \"type\": \"number\" } }, \"additionalProperties\": false }"
            + "                      },"
            + "                      \"additionalProperties\": false"
            + "                    },"
            + "                    " + STORYBOARD_STYLE_FIELD
            + "                  },"
            + "                  \"additionalProperties\": false,"
            + "                  \"required\": [\"id\"]"
            + "                }"
            + "              },"
            + "              \"persistent_objects\": {"
            + "                \"type\": \"array\","
            + "                \"items\": {"
            + "                  \"type\": \"object\","
            + "                  \"properties\": {"
            + "                    \"id\": { \"type\": \"string\" },"
            + "                    \"placement\": { \"type\": \"object\" },"
            + "                    " + STORYBOARD_STYLE_FIELD
            + "                  },"
            + "                  \"additionalProperties\": false,"
            + "                  \"required\": [\"id\"]"
            + "                }"
            + "              },"
            + "              \"exiting_objects\": {"
            + "                \"type\": \"array\","
            + "                \"items\": {"
            + "                  \"type\": \"object\","
            + "                  \"properties\": {"
            + "                    \"id\": { \"type\": \"string\" }"
            + "                  },"
            + "                  \"additionalProperties\": false,"
            + "                  \"required\": [\"id\"]"
            + "                }"
            + "              },"
            + "              \"actions\": {"
            + "                \"type\": \"array\","
            + "                \"items\": {"
            + "                  \"type\": \"object\","
            + "                  \"properties\": {"
            + "                    \"order\": { \"type\": \"integer\" },"
            + "                    \"type\": { \"type\": \"string\", \"enum\": [\"create\", \"write\", \"transform\", \"highlight\", \"move\", \"fade_out\", \"camera\", \"restyle\"] },"
            + "                    \"targets\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "                    \"description\": { \"type\": \"string\" }"
            + "                  },"
            + "                  \"additionalProperties\": false,"
            + "                  \"required\": [\"order\", \"type\", \"description\"]"
            + "                }"
            + "              },"
            + "              \"notes_for_codegen\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } }"
            + "            },"
            + "            \"additionalProperties\": false,"
            + "            \"required\": [\"scene_id\", \"title\", \"goal\", \"narration\", \"layout_goal\", \"safe_area_plan\", \"entering_objects\", \"persistent_objects\", \"exiting_objects\", \"actions\"]"
            + "          }"
            + "        }"
            + "      },"
            + "      \"additionalProperties\": false,"
            + "      \"required\": [\"coordinate_bounds\", \"scenes\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 5: Code Generation
    // ========================================================================

    public static final String MANIM_CODE = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_manim_code\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"manimCode\": { \"type\": \"string\" },"
            + "        \"scene_name\": { \"type\": \"string\" },"
            + "        \"description\": { \"type\": \"string\" }"
            + "      },"
            + "      \"required\": [\"manimCode\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    public static final String GEOGEBRA_CODE = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_geogebra_code\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"geogebraCode\": { \"type\": \"string\" },"
            + "        \"figure_name\": { \"type\": \"string\" },"
            + "        \"description\": { \"type\": \"string\" },"
            + "        \"artifact_format\": { \"type\": \"string\" }"
            + "      },"
            + "      \"required\": [\"geogebraCode\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 3 (scene-level): Scene Design
    // ========================================================================

    public static final String SCENE_DESIGN = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_scene_design\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"scene\": {"
            + "          \"type\": \"object\","
            + "          \"properties\": {"
            + "            \"scene_id\": { \"type\": \"string\" },"
            + "            \"title\": { \"type\": \"string\" },"
            + "            \"goal\": { \"type\": \"string\" },"
            + "            \"narration\": { \"type\": \"string\" },"
            + "            \"duration_seconds\": { \"type\": \"integer\" },"
            + "            \"camera_anchor\": { \"type\": \"string\" },"
            + "            \"camera_plan\": { \"type\": \"string\" },"
            + "            \"layout_goal\": { \"type\": \"string\" },"
            + "            \"safe_area_plan\": { \"type\": \"string\" },"
            + "            \"screen_overlay_plan\": { \"type\": \"string\" },"
            + "            \"step_refs\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "            " + STORYBOARD_CONSTRAINTS_FIELD + ","
            + "            \"entering_objects\": {"
            + "              \"type\": \"array\","
            + "              \"items\": {"
            + "                \"type\": \"object\","
            + "                \"properties\": {"
            + "                  \"id\": { \"type\": \"string\" },"
            + "                  \"placement\": {"
            + "                    \"type\": \"object\","
            + "                    \"properties\": {"
            + "                      \"positioning\": { \"type\": \"string\", \"enum\": [\"absolute\", \"relative\"] },"
            + "                      \"x\": { \"type\": \"object\" },"
            + "                      \"y\": { \"type\": \"object\" },"
            + "                      \"z\": { \"type\": \"object\" }"
            + "                    },"
            + "                    \"additionalProperties\": false"
            + "                  },"
            + "                  " + STORYBOARD_STYLE_FIELD
            + "                },"
            + "                \"additionalProperties\": false,"
            + "                \"required\": [\"id\"]"
            + "              }"
            + "            },"
            + "            \"persistent_objects\": {"
            + "              \"type\": \"array\","
            + "              \"items\": {"
            + "                \"type\": \"object\","
            + "                \"properties\": {"
            + "                  \"id\": { \"type\": \"string\" },"
            + "                  \"placement\": { \"type\": \"object\" },"
            + "                  " + STORYBOARD_STYLE_FIELD
            + "                },"
            + "                \"additionalProperties\": false,"
            + "                \"required\": [\"id\"]"
            + "              }"
            + "            },"
            + "            \"exiting_objects\": {"
            + "              \"type\": \"array\","
            + "              \"items\": {"
            + "                \"type\": \"object\","
            + "                \"properties\": { \"id\": { \"type\": \"string\" } },"
            + "                \"additionalProperties\": false,"
            + "                \"required\": [\"id\"]"
            + "              }"
            + "            },"
            + "            \"actions\": {"
            + "              \"type\": \"array\","
            + "              \"items\": {"
            + "                \"type\": \"object\","
            + "                \"properties\": {"
            + "                  \"order\": { \"type\": \"integer\" },"
            + "                  \"type\": { \"type\": \"string\", \"enum\": [\"create\", \"write\", \"transform\", \"highlight\", \"move\", \"fade_out\", \"camera\", \"restyle\"] },"
            + "                  \"targets\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "                  \"description\": { \"type\": \"string\" }"
            + "                },"
            + "                \"additionalProperties\": false,"
            + "                \"required\": [\"order\", \"type\", \"description\"]"
            + "              }"
            + "            },"
            + "            \"notes_for_codegen\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } }"
            + "          },"
            + "          \"additionalProperties\": false,"
            + "          \"required\": [\"scene_id\", \"title\", \"goal\", \"narration\", \"layout_goal\", \"safe_area_plan\", \"entering_objects\", \"persistent_objects\", \"exiting_objects\", \"actions\"]"
            + "        },"
            + "        \"new_objects\": {"
            + "          \"type\": \"array\","
            + "          \"items\": {"
            + "            \"type\": \"object\","
            + "            \"properties\": {"
            + "              \"id\": { \"type\": \"string\" },"
            + "              \"kind\": { \"type\": \"string\" },"
            + "              \"content\": { \"type\": \"string\" },"
            + "              " + STORYBOARD_STYLE_FIELD + ","
            + "              " + STORYBOARD_CONSTRAINTS_FIELD
            + "            },"
            + "            \"required\": [\"id\", \"kind\", \"content\"]"
            + "          }"
            + "        },"
            + "        \"coordinate_bounds_update\": {"
            + "          \"type\": \"object\","
            + "          \"properties\": {"
            + "            \"x\": " + COORDINATE_BOUNDS_AXIS_SCHEMA + ","
            + "            \"y\": " + COORDINATE_BOUNDS_AXIS_SCHEMA + ","
            + "            \"z\": " + COORDINATE_BOUNDS_AXIS_SCHEMA + ","
            + "            \"padding\": { \"type\": \"number\" },"
            + "            \"reason\": { \"type\": \"string\" }"
            + "          },"
            + "          \"additionalProperties\": false"
            + "        }"
            + "      },"
            + "      \"required\": [\"scene\", \"new_objects\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 5 (scene-level): Skeleton + Per-Scene Code
    // ========================================================================

    public static final String CODE_SKELETON = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_code_skeleton\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"headerCode\": { \"type\": \"string\" }"
            + "      },"
            + "      \"required\": [\"headerCode\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    public static final String SCENE_CODE = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_scene_code\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"sceneCode\": { \"type\": \"string\" },"
            + "        \"sceneMethodName\": { \"type\": \"string\" }"
            + "      },"
            + "      \"required\": [\"sceneCode\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================

    public static final String CODE_REVIEW = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_code_review\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"approved_for_render\": { \"type\": \"boolean\" },"
            + "        \"rule_checks\": {"
            + "          \"type\": \"array\","
            + "          \"items\": {"
            + "            \"type\": \"object\","
            + "            \"properties\": {"
            + "              \"rule_id\": { \"type\": \"string\" },"
            + "              \"requirement\": { \"type\": \"string\" },"
            + "              \"status\": { \"type\": \"string\", \"enum\": [\"pass\", \"warn\", \"fail\", \"not_applicable\"] },"
            + "              \"severity\": { \"type\": \"string\", \"enum\": [\"mandatory\", \"recommended\", \"advisory\"] },"
            + "              \"evidence\": { \"type\": \"string\" }"
            + "            },"
            + "            \"required\": [\"rule_id\", \"requirement\", \"status\", \"severity\", \"evidence\"]"
            + "          }"
            + "        },"
            + "        \"summary\": { \"type\": \"string\" },"
            + "        \"strengths\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "        \"blocking_issues\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "        \"revision_directives\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } }"
            + "      },"
            + "      \"required\": [\"approved_for_render\", \"rule_checks\", \"summary\", \"blocking_issues\", \"revision_directives\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";
}

