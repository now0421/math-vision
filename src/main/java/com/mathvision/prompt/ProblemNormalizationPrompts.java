package com.mathvision.prompt;

import com.mathvision.util.StoryboardConstraintCatalog;

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
                + "- When `diagram.present = false`, leave `diagram.description` blank or omitted, leave `diagram.objects` empty, leave `diagram.constraints` empty, and leave `diagram.construction_notes` empty.\n"
                + "- When `diagram.present = true`, describe only the initial source-observed problem diagram, not a future solution diagram.\n"
                + "- Use the storyboard object schema for source-observed diagram objects (id, kind, content, constraints).\n"
                + "- Use the storyboard constraint schema for source-observed diagram constraints.\n"
                + "- Prefer mathematical correctness over pixel-perfect copying of source-image proportions.\n"
                + "- If a source image diagram is imprecise, clean up only the initial diagram already visible in the image; do not add inferred auxiliary constructions.\n\n"
                + "Do NOT:\n"
                + "- Create a diagram from text-only input.\n"
                + "- Add auxiliary constructions, optimal points, reflected points, helper lines, measurements, or conclusion markers not visibly present in the original problem diagram.\n"
                + "- Treat text rendered in an image as a diagram. Text-only images contribute to `statement`, not `diagram`.\n\n"
                + "Constraint catalog (valid domain values): " + StoryboardConstraintCatalog.domainEnumJson() + "\n"
                + "Constraint catalog (valid relation values): " + StoryboardConstraintCatalog.relationEnumJson() + "\n\n"
                + "Object kind values: point, line, ray, segment, circle, arc, polygon, angle_marker, "
                + "text, equation, text_card, number_line, coordinate_system, vector, region, graph, group, slider, image.\n\n"
                + "When `diagram.present = true`, construction_notes may include backend-specific requirements for the source-observed initial diagram, such as:\n"
                + "- For GeoGebra: which points must be draggable (use Point on Object), which are free vs dependent.\n"
                + "- \"Construct a mathematically valid diagram; do not copy source-image proportions if they conflict with stated conditions.\"\n"
                + "- \"The first storyboard scene must construct the initial diagram before introducing solution reasoning.\"\n\n"
                + "Output requirements:\n"
                + "Return a ProblemBundle JSON object via the provided tool.\n"
                + "Return exactly these top-level fields: `id`, `title`, `input_mode`, `scene_mode`, `statement`, and `diagram`.\n"
                + "`diagram` must contain `present`; when `present` is false, use empty arrays for `objects`, `constraints`, and `construction_notes`.\n\n"
                + "Example output for text-only input with no attached image:\n"
                + "{\n"
                + "  \"id\": \"shortest_path_reflection\",\n"
                + "  \"title\": \"Shortest path via reflection\",\n"
                + "  \"input_mode\": \"problem\",\n"
                + "  \"scene_mode\": \"2d\",\n"
                + "  \"statement\": \"Given points A and B on the same side of line l, find point P on l that minimizes AP + PB.\",\n"
                + "  \"diagram\": {\n"
                + "    \"present\": false,\n"
                + "    \"objects\": [],\n"
                + "    \"constraints\": [],\n"
                + "    \"construction_notes\": []\n"
                + "  }\n"
                + "}\n\n"
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
}
