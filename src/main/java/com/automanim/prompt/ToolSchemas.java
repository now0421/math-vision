package com.automanim.prompt;

/**
 * Central registry of all tool JSON schemas used for structured LLM output.
 *
 * Consolidates tool definitions previously scattered across node classes.
 */
public final class ToolSchemas {

    private ToolSchemas() {}

    // ========================================================================
    // Stage 0: Exploration
    // ========================================================================

    public static final String PREREQUISITES = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_prerequisites\","
            + "    \"description\": \"Return the list of direct prerequisite teaching beats.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"prerequisites\": {"
            + "          \"type\": \"array\","
            + "          \"items\": {"
            + "            \"type\": \"object\","
            + "            \"properties\": {"
            + "              \"step\": { \"type\": \"string\", \"description\": \"Short description of the prerequisite teaching beat\" },"
            + "              \"reason\": { \"type\": \"string\", \"description\": \"Why this prerequisite beat matters in the learning path\" }"
            + "            },"
            + "            \"required\": [\"step\", \"reason\"]"
            + "          }"
            + "        }"
            + "      },"
            + "      \"required\": [\"prerequisites\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    public static final String FOUNDATION_CHECK = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_foundation_decision\","
            + "    \"description\": \"Return whether the concept is already foundational enough.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"is_foundation\": { \"type\": \"boolean\", \"description\": \"Whether the concept should stop expanding\" },"
            + "        \"reason\": { \"type\": \"string\", \"description\": \"Short justification\" }"
            + "      },"
            + "      \"required\": [\"is_foundation\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    public static final String INPUT_MODE = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_input_mode\","
            + "    \"description\": \"Classify the workflow input mode.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"input_mode\": {"
            + "          \"type\": \"string\","
            + "          \"enum\": [\"concept\", \"problem\"],"
            + "          \"description\": \"Workflow mode for the input\""
            + "        },"
            + "        \"reason\": { \"type\": \"string\", \"description\": \"Short routing rationale\" }"
            + "      },"
            + "      \"required\": [\"input_mode\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    public static final String PROBLEM_GRAPH = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_problem_step_graph\","
            + "    \"description\": \"Return a compact graph of animation-ready teaching beats for solving the problem.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"root_id\": { \"type\": \"string\", \"description\": \"Root node id\" },"
            + "        \"nodes\": {"
            + "          \"type\": \"array\","
            + "          \"items\": {"
            + "            \"type\": \"object\","
            + "            \"properties\": {"
            + "              \"id\": { \"type\": \"string\" },"
            + "              \"step\": { \"type\": \"string\" },"
            + "              \"reason\": { \"type\": \"string\", \"description\": \"Why this teaching beat matters\" },"
            + "              \"node_type\": { \"type\": \"string\" },"
            + "              \"min_depth\": { \"type\": \"integer\" },"
            + "              \"is_foundation\": { \"type\": \"boolean\" }"
            + "            },"
            + "            \"required\": [\"id\", \"step\", \"node_type\", \"min_depth\", \"is_foundation\"]"
            + "          }"
            + "        },"
            + "        \"prerequisite_edges\": {"
            + "          \"type\": \"object\","
            + "          \"additionalProperties\": {"
            + "            \"type\": \"array\","
            + "            \"items\": { \"type\": \"string\" }"
            + "          }"
            + "        }"
            + "      },"
            + "      \"required\": [\"root_id\", \"nodes\", \"prerequisite_edges\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 1a: Math Enrichment
    // ========================================================================

    public static final String MATH_ENRICHMENT = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_enrichment\","
            + "    \"description\": \"Return mathematical enrichment for the concept.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"equations\": {"
            + "          \"type\": \"array\","
            + "          \"items\": { \"type\": \"string\" },"
            + "          \"description\": \"Key LaTeX formulas\""
            + "        },"
            + "        \"definitions\": {"
            + "          \"type\": \"object\","
            + "          \"additionalProperties\": { \"type\": \"string\" },"
            + "          \"description\": \"Term to definition mapping\""
            + "        },"
            + "        \"interpretation\": { \"type\": \"string\", \"description\": \"Intuitive explanation\" },"
            + "        \"examples\": {"
            + "          \"type\": \"array\","
            + "          \"items\": { \"type\": \"string\" },"
            + "          \"description\": \"Concrete examples\""
            + "        }"
            + "      },"
            + "      \"required\": [\"equations\", \"definitions\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 1b: Visual Design
    // ========================================================================

    public static final String VISUAL_DESIGN = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_visual_spec\","
            + "    \"description\": \"Return visual design specification for the concept.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"visual_description\": { \"type\": \"string\", \"description\": \"What appears on screen and what mathematical relation becomes visible\" },"
            + "        \"scene_mode\": { \"type\": \"string\", \"enum\": [\"2d\", \"3d\"], \"description\": \"2d by default, 3d only when depth is needed\" },"
            + "        \"camera_plan\": { \"type\": \"string\", \"description\": \"Camera orientation or motion for 3d scenes\" },"
            + "        \"screen_overlay_plan\": { \"type\": \"string\", \"description\": \"Text or formulas that stay fixed in frame\" },"
            + "        \"color_scheme\": { \"type\": \"string\", \"description\": \"Main colors and emphasis roles\" },"
            + "        \"layout\": { \"type\": \"string\", \"description\": \"Relative placement only; describe spatial relationships\" },"
            + "        \"animation_description\": { \"type\": \"string\", \"description\": \"How the scene evolves\" },"
            + "        \"transitions\": { \"type\": \"string\", \"description\": \"Transition style\" },"
            + "        \"duration\": { \"type\": \"number\", \"description\": \"Approximate seconds\" },"
            + "        \"color_palette\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"Manim color names\" }"
            + "      },"
            + "      \"required\": [\"visual_description\", \"color_scheme\", \"layout\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 1c: Narrative
    // ========================================================================

    public static final String STORYBOARD = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_storyboard\","
            + "    \"description\": \"Return a structured storyboard for the animation.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"hook\": { \"type\": \"string\" },"
            + "        \"summary\": { \"type\": \"string\" },"
            + "        \"continuity_plan\": { \"type\": \"string\" },"
            + "        \"global_visual_rules\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "        \"scenes\": { \"type\": \"array\", \"description\": \"Scene objects with scene_id, title, goal, narration, etc.\" }"
            + "      },"
            + "      \"required\": [\"scenes\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 2: Code Generation
    // ========================================================================

    public static final String MANIM_CODE = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_manim_code\","
            + "    \"description\": \"Return complete Manim Community Python animation code.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"code\": { \"type\": \"string\", \"description\": \"Complete Manim Python source code\" },"
            + "        \"scene_name\": { \"type\": \"string\", \"description\": \"Primary scene class name in ASCII\" },"
            + "        \"description\": { \"type\": \"string\", \"description\": \"Short summary of the animation\" }"
            + "      },"
            + "      \"required\": [\"code\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // ========================================================================
    // Stage 3: Code Evaluation
    // ========================================================================

    public static final String CODE_REVIEW = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_code_review\","
            + "    \"description\": \"Return structured code review for Manim animation quality.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"approved_for_render\": { \"type\": \"boolean\", \"description\": \"Whether code is approved for render\" },"
            + "        \"layout_score\": { \"type\": \"integer\", \"minimum\": 1, \"maximum\": 10 },"
            + "        \"continuity_score\": { \"type\": \"integer\", \"minimum\": 1, \"maximum\": 10 },"
            + "        \"pacing_score\": { \"type\": \"integer\", \"minimum\": 1, \"maximum\": 10 },"
            + "        \"clutter_risk\": { \"type\": \"integer\", \"minimum\": 1, \"maximum\": 10 },"
            + "        \"likely_offscreen_risk\": { \"type\": \"integer\", \"minimum\": 1, \"maximum\": 10 },"
            + "        \"summary\": { \"type\": \"string\" },"
            + "        \"strengths\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "        \"blocking_issues\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "        \"revision_directives\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } }"
            + "      },"
            + "      \"required\": [\"approved_for_render\", \"layout_score\", \"continuity_score\", \"pacing_score\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";
}
