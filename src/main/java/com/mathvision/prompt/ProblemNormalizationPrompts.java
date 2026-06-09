package com.mathvision.prompt;

/**
 * Prompts for the ProblemNormalizationNode.
 * Converts raw text/image input into a structured ProblemBundle.
 *
 * Split into two parts:
 * - buildRulesPrompt(): hard normalization rules plus output requirements.
 * - buildFixedContextPrompt(): workflow stage background and output target.
 */
public final class ProblemNormalizationPrompts {

    private ProblemNormalizationPrompts() {}

    public static String buildRulesPrompt() {
        String system = "You normalize a math problem or concept for a visualization workflow.\n\n"
                + "Rules:\n"
                + "- The input may be text only, image(s) only, or a mix of text and images.\n"
                + "- A single image may contain both the problem statement AND a diagram.\n"
                + "- Multiple images may be provided: some may contain text, some may contain diagrams, some both.\n"
                + "- An image that contains only printed/handwritten text (no geometric figure) is a text source, not a diagram.\n"
                + "- Extract the complete problem statement from ALL provided sources (text + images) and merge into one coherent `statement`.\n"
                + "- Preserve the mathematical meaning of the problem.\n"
                + "- Normalize the statement into a clear standalone problem statement in the same language as the input.\n"
                + "- Set input_mode to \"concept\" if the input describes a concept to teach, or \"problem\" if it states a problem to solve.\n"
                + "- Set `scene_mode` to \"2d\" or \"3d\" for the whole visualization problem. Use \"2d\" for plane geometry, coordinate graphs, number lines, ordinary function plots, and any case where depth is not mathematically necessary. Use \"3d\" only for spatial geometry, three-dimensional coordinates, solids, surfaces, or reasoning that genuinely requires depth. If uncertain, choose \"2d\".\n"
                + "- `scene_mode` is the mathematical/visual dimensionality of the problem, not draw order. Layering is represented later by `style.z_index`.\n"
                + "- Do not solve the problem or include solution reasoning.\n"
                + "- Do not add future teaching steps, reflection points, proof lines, or conclusion markers.\n\n"
                + "Diagram rules:\n"
                + "- `diagram` means a source-observed problem figure, not a diagram that could be drawn from the text.\n"
                + "- If no image is attached to the current request, set `diagram.present = false` even when the text describes points, lines, graphs, paths, or geometric relationships.\n"
                + "- If images are attached but they contain only printed/handwritten problem text, formulas, or answer text, set `diagram.present = false`.\n"
                + "- Only set `diagram.present = true` when an attached image visibly contains an actual geometric/graphical figure, graph, number line, coordinate figure, or visual math construction.\n"
                + "- When `diagram.present = false`, set `source_observed=false` and use empty objects/arrays for `diagram_description`, `coordinate_model`, `unknowns`, `ambiguities`, and `normalization_notes`.\n"
                + "- When `diagram.present = true`, describe only the initial source-observed problem diagram, not a future solution diagram.\n"
                + "- Use native JSON objects and arrays for diagram description. Do not use the storyboard object schema or the storyboard constraint schema in ProblemBundle.\n"
                + "- `diagram_description` should describe the visible figure in natural language grouped by meaningful keys such as `overall_shape`, `points`, `segments`, `marks`, `regions`, `graphs`, or similar domain-appropriate fields.\n"
                + "- `coordinate_model` is optional but recommended for geometry/graph problems when a simple mathematical model can be established from the statement and source figure. It may contain coordinates, equations, parameter ranges, and dependency formulas.\n"
                + "- `unknowns` lists variable or target quantities from the problem, such as moving points, dependent points, requested lengths, areas, angles, or extrema.\n"
                + "- `ambiguities` records branch choices that the source figure resolves or leaves unresolved. Include mirror-image, same/opposite-side, clockwise/counterclockwise, near/far intersection, minor/major arc, and inside/outside choices when they affect the mathematics or initial diagram.\n"
                + "- If a source figure resolves a branch, state the selected branch in `ambiguities` and repeat it in the relevant natural-language description or coordinate dependency. Do not convert it into storyboard constraints here.\n"
                + "- Prefer mathematical correctness over pixel-perfect copying of source-image proportions.\n"
                + "- If a source image diagram is imprecise, clean up only the initial diagram already visible in the image; do not add inferred auxiliary constructions.\n\n"
                + "Do NOT:\n"
                + "- Create a diagram from text-only input.\n"
                + "- Add auxiliary constructions, optimal points, reflected points, helper lines, measurements, or conclusion markers not visibly present in the original problem diagram.\n"
                + "- Treat text rendered in an image as a diagram. Text-only images contribute to `statement`, not `diagram`.\n\n"
                + "Output requirements:\n"
                + "Return a ProblemBundle JSON object via the provided tool.\n"
                + "Return exactly these top-level fields: `id`, `title`, `input_mode`, `scene_mode`, `statement`, and `diagram`.\n"
                + "`diagram` must contain `present`. When `present` is true, include `source_observed`, `diagram_description`, `coordinate_model`, `unknowns`, `ambiguities`, and `normalization_notes` as appropriate.\n"
                + "Do not output `diagram.objects`, `diagram.constraints`, or `diagram.construction_notes`; concrete storyboard constraints are created later by VisualDesignNode.\n\n"
                + "Output shape:\n"
                + "{\n"
                + "  \"id\": \"string\",\n"
                + "  \"title\": \"string\",\n"
                + "  \"input_mode\": \"concept or problem\",\n"
                + "  \"scene_mode\": \"2d or 3d\",\n"
                + "  \"statement\": \"string\",\n"
                + "  \"diagram\": {\n"
                + "    \"present\": true,\n"
                + "    \"source_observed\": true,\n"
                + "    \"diagram_description\": {\n"
                + "      \"overall_shape\": \"natural-language source figure summary\",\n"
                + "      \"points\": { \"A\": { \"role\": \"...\", \"position\": \"...\" } },\n"
                + "      \"segments\": [ { \"name\": \"AB\", \"description\": \"...\" } ],\n"
                + "      \"marks\": [ { \"type\": \"right_angle\", \"vertex\": \"C\", \"description\": \"...\" } ]\n"
                + "    },\n"
                + "    \"coordinate_model\": {\n"
                + "      \"description\": \"optional mathematical model\",\n"
                + "      \"coordinates\": { \"A\": [0, 0] },\n"
                + "      \"constraints\": { \"example\": \"native JSON, not storyboard constraints\" }\n"
                + "    },\n"
                + "    \"unknowns\": [ { \"name\": \"string\", \"description\": \"string\" } ],\n"
                + "    \"ambiguities\": [ { \"name\": \"string\", \"choices\": [\"...\"], \"selected_by_source_diagram\": \"...\", \"reason\": \"...\" } ],\n"
                + "    \"normalization_notes\": [\"string\"]\n"
                + "  }\n"
                + "}\n\n"
                + "Example output for text-only input with no attached image:\n"
                + "{\n"
                + "  \"id\": \"shortest_path_reflection\",\n"
                + "  \"title\": \"Shortest path via reflection\",\n"
                + "  \"input_mode\": \"problem\",\n"
                + "  \"scene_mode\": \"2d\",\n"
                + "  \"statement\": \"Given points A and B on the same side of line l, find point P on l that minimizes AP + PB.\",\n"
                + "  \"diagram\": {\n"
                + "    \"present\": false,\n"
                + "    \"source_observed\": false,\n"
                + "    \"diagram_description\": {},\n"
                + "    \"coordinate_model\": {},\n"
                + "    \"unknowns\": [],\n"
                + "    \"ambiguities\": [],\n"
                + "    \"normalization_notes\": []\n"
                + "  }\n"
                + "}\n\n"
                + "Example output for an image-backed geometry problem:\n"
                + "{\n"
                + "  \"id\": \"minimum_aq_quarter_circle\",\n"
                + "  \"title\": \"Minimum AQ in a quarter-circle figure\",\n"
                + "  \"input_mode\": \"problem\",\n"
                + "  \"scene_mode\": \"2d\",\n"
                + "  \"statement\": \"In the figure, angle ACB=90 degrees, AC=BC=4, D is the midpoint of BC, P moves on arc AB, and triangle DPQ is right isosceles. Find the minimum value of AQ.\",\n"
                + "  \"diagram\": {\n"
                + "    \"present\": true,\n"
                + "    \"source_observed\": true,\n"
                + "    \"diagram_description\": {\n"
                + "      \"overall_shape\": \"A quarter-circle sector centered at C with radii CA and CB.\",\n"
                + "      \"points\": {\n"
                + "        \"C\": { \"role\": \"right-angle vertex and sector center\", \"position\": \"lower left\" },\n"
                + "        \"A\": { \"role\": \"arc endpoint\", \"position\": \"directly above C\" },\n"
                + "        \"B\": { \"role\": \"arc endpoint\", \"position\": \"directly right of C\" },\n"
                + "        \"D\": { \"role\": \"midpoint of BC\", \"position\": \"middle of horizontal BC\" },\n"
                + "        \"P\": { \"role\": \"moving point\", \"position\": \"on quarter arc AB\" },\n"
                + "        \"Q\": { \"role\": \"point determined by right isosceles triangle DPQ\", \"position\": \"above BC and upper-left of D in the source figure\" }\n"
                + "      },\n"
                + "      \"segments\": [\n"
                + "        { \"name\": \"AC\", \"description\": \"vertical radius, length 4\" },\n"
                + "        { \"name\": \"BC\", \"description\": \"horizontal radius, length 4\" },\n"
                + "        { \"name\": \"DP\", \"description\": \"one leg of the right isosceles triangle\" },\n"
                + "        { \"name\": \"DQ\", \"description\": \"the other leg of the right isosceles triangle\" },\n"
                + "        { \"name\": \"AQ\", \"description\": \"target length to minimize\" }\n"
                + "      ],\n"
                + "      \"marks\": [\n"
                + "        { \"type\": \"right_angle\", \"vertex\": \"C\", \"description\": \"angle ACB is 90 degrees\" },\n"
                + "        { \"type\": \"right_angle\", \"vertex\": \"D\", \"description\": \"angle PDQ is 90 degrees\" }\n"
                + "      ]\n"
                + "    },\n"
                + "    \"coordinate_model\": {\n"
                + "      \"description\": \"Use C as origin, CB as positive x-axis, and CA as positive y-axis.\",\n"
                + "      \"coordinates\": { \"C\": [0, 0], \"A\": [0, 4], \"B\": [4, 0], \"D\": [2, 0] },\n"
                + "      \"arc_constraint\": { \"point\": \"P\", \"equation\": \"x_P^2 + y_P^2 = 16\", \"theta_range\": \"[0, pi/2]\", \"P\": [\"4cos(theta)\", \"4sin(theta)\"] },\n"
                + "      \"triangle_constraint\": { \"description\": \"Q is obtained from DP by rotating about D in the branch shown by the source figure.\", \"if_P\": [\"x\", \"y\"], \"then_Q\": [\"2 - y\", \"x - 2\"] }\n"
                + "    },\n"
                + "    \"unknowns\": [\n"
                + "      { \"name\": \"position of P\", \"description\": \"P varies on arc AB\" },\n"
                + "      { \"name\": \"position of Q\", \"description\": \"Q depends on P through the selected right-isosceles branch\" },\n"
                + "      { \"name\": \"minimum AQ\", \"description\": \"the requested value\" }\n"
                + "    ],\n"
                + "    \"ambiguities\": [\n"
                + "      { \"name\": \"orientation of triangle DPQ\", \"choices\": [\"clockwise branch\", \"counterclockwise branch\"], \"selected_by_source_diagram\": \"counterclockwise branch\", \"reason\": \"Q is visibly above BC and upper-left of D\" }\n"
                + "    ],\n"
                + "    \"normalization_notes\": [\"Coordinate formulas model the source-observed branch without solving the optimization.\"]\n"
                + "  }\n"
                + "}\n\n"
                + SystemPrompts.TOOL_CALL_HINT
                + SystemPrompts.JSON_ONLY_OUTPUT;
        return SystemPrompts.buildRulesSection(system);
    }

    public static String buildReviewRulesPrompt() {
        String system = "You review and repair a generated ProblemBundle against the original math source.\n\n"
                + "Review task:\n"
                + "- Compare the generated ProblemBundle to ALL original source evidence in this request: text plus any attached images.\n"
                + "- Return the corrected ProblemBundle JSON via the provided tool. If the generated bundle is already faithful, return it unchanged except for harmless normalization cleanup.\n"
                + "- Preserve the original problem's mathematical meaning, language, variable names, labels, quantities, and requested target.\n"
                + "- Fix OCR, transcription, omission, hallucination, wrong label, wrong value, wrong diagram-present, wrong scene_mode, and wrong input_mode errors.\n"
                + "- Remove any content that is not supported by the original source.\n"
                + "- Do not solve the problem, add solution reasoning, or introduce auxiliary constructions not present in the source.\n"
                + "- Do not add future teaching steps, reflection points, proof lines, or conclusion markers.\n\n"
                + "Diagram review rules:\n"
                + "- `diagram` means a source-observed problem figure, not a diagram that could be drawn from text alone.\n"
                + "- For text-only review with no attached image, set `diagram.present=false`, `source_observed=false`, and keep diagram payload objects/arrays empty.\n"
                + "- If attached images contain only printed/handwritten text, formulas, or answer text, set `diagram.present=false`.\n"
                + "- Set `diagram.present=true` only when an attached image visibly contains an actual geometric/graphical figure, graph, number line, coordinate figure, or visual math construction.\n"
                + "- When `diagram.present=true`, keep only source-observed initial diagram information. Do not invent auxiliary helper lines, optimal points, reflected points, measurements, or conclusion markers.\n"
                + "- Diagram fields must remain source evidence for downstream stages; do not emit storyboard objects, storyboard constraints, or code-generation instructions.\n\n"
                + "Output requirements:\n"
                + "Return a ProblemBundle JSON object via the provided tool.\n"
                + "Return exactly these top-level fields: `id`, `title`, `input_mode`, `scene_mode`, `statement`, and `diagram`.\n"
                + "`input_mode` must be `concept` or `problem`.\n"
                + "`scene_mode` must be `2d` or `3d`; choose `3d` only when the problem genuinely requires spatial geometry, three-dimensional coordinates, solids, surfaces, or depth reasoning.\n"
                + "`diagram` must contain `present`. Include `source_observed`, `diagram_description`, `coordinate_model`, `unknowns`, `ambiguities`, and `normalization_notes` as appropriate.\n"
                + "Do not output `source`, `output_target`, review notes, approval flags, or separate patch objects.\n\n"
                + SystemPrompts.TOOL_CALL_HINT
                + SystemPrompts.JSON_ONLY_OUTPUT;
        return SystemPrompts.buildRulesSection(system);
    }

    public static String buildFixedContextPrompt(String outputTarget) {
        String fixedContext = SystemPrompts.buildWorkflowPrefix(
                "Stage 0 / Problem Normalization",
                "ProblemBundle normalization",
                "User-provided math input",
                "Merge the available source text and images into the canonical ProblemBundle consumed by all downstream stages.",
                outputTarget
        );
        return SystemPrompts.buildFixedContextSection(fixedContext);
    }

    public static String buildReviewFixedContextPrompt(String outputTarget) {
        String fixedContext = SystemPrompts.buildWorkflowPrefix(
                "Stage 0 / Problem Normalization",
                "ProblemBundle source-fidelity review",
                "User-provided math input",
                "Verify that the generated ProblemBundle faithfully matches the original source text and images, then return the corrected canonical bundle consumed by all downstream stages.",
                outputTarget
        );
        return SystemPrompts.buildFixedContextSection(fixedContext);
    }

    public static String buildUserPrompt(String rawText, String outputTarget) {
        StringBuilder sb = new StringBuilder();
        sb.append("Normalize the following source into a ProblemBundle for output target `")
                .append(outputTarget)
                .append("`.\n");
        if (rawText != null && !rawText.isBlank()) {
            sb.append("Text input:\n").append(rawText).append("\n\n");
        } else {
            sb.append("No text input was provided.\n\n");
        }
        sb.append("Attached image count: 0.\n");
        sb.append("Because this is text-only input, set `diagram.present` to false and keep all diagram arrays empty.\n");
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    public static String buildMultimodalUserPrompt(String rawText, String outputTarget, int imageCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("Normalize the following source into a ProblemBundle for output target `")
                .append(outputTarget)
                .append("`.\n");
        if (rawText != null && !rawText.isBlank()) {
            sb.append("Text input:\n").append(rawText).append("\n\n");
        } else {
            sb.append("No separate text input was provided.\n\n");
        }
        if (imageCount <= 0) {
            sb.append("Image assets were listed, but no image could be attached. Normalize from the available text only.\n");
            sb.append("Since no image is attached to inspect, set `diagram.present` to false and keep all diagram arrays empty.\n");
        } else if (imageCount == 1) {
            sb.append("One image is attached. It may contain the problem text, a diagram, or both. ");
            sb.append("Extract all information from it. Set `diagram.present` to true only if this image visibly contains a non-text problem figure.\n");
        } else {
            sb.append(imageCount).append(" images are attached. They may individually contain problem text, ");
            sb.append("diagrams, or both. Combine all information across all images into one coherent ProblemBundle.\n");
            sb.append("Set `diagram.present` to true only if at least one attached image visibly contains a non-text problem figure.\n");
        }
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    public static String buildReviewUserPrompt(String rawText,
                                               String outputTarget,
                                               int imageCount,
                                               String generatedBundleJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("Review the generated ProblemBundle for output target `")
                .append(outputTarget)
                .append("` against the original source, then return the corrected ProblemBundle.\n\n");
        if (rawText != null && !rawText.isBlank()) {
            sb.append("Original text input:\n").append(rawText).append("\n\n");
        } else {
            sb.append("No separate original text input was provided.\n\n");
        }

        sb.append("Attached image count: ").append(Math.max(imageCount, 0)).append(".\n");
        if (imageCount <= 0) {
            sb.append("No image is attached for this review. The corrected bundle must not claim a source-observed diagram.\n\n");
        } else if (imageCount == 1) {
            sb.append("One source image is attached again for review. Re-check both problem text and any visible source diagram.\n\n");
        } else {
            sb.append(imageCount).append(" source images are attached again for review. Re-check all text and any visible source diagrams across them.\n\n");
        }

        sb.append("Generated ProblemBundle to review:\n```json\n")
                .append(generatedBundleJson != null && !generatedBundleJson.isBlank() ? generatedBundleJson : "{}")
                .append("\n```\n\n");
        sb.append("Return the corrected canonical ProblemBundle only. Do not include a review report.");
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }
}
