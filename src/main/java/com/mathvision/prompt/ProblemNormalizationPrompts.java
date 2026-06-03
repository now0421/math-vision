package com.mathvision.prompt;

import com.mathvision.util.StoryboardConstraintCatalog;

/**
 * Prompts for the ProblemNormalizationNode.
 * Converts raw text/image input into a structured ProblemBundle.
 */
public final class ProblemNormalizationPrompts {

    private ProblemNormalizationPrompts() {}

    public static String buildRulesPrompt() {
        return "You normalize a math problem or concept for a visualization workflow.\n\n"
                + "Return a ProblemBundle JSON object via the provided tool.\n\n"
                + "Input understanding rules:\n"
                + "- The input may be text only, image(s) only, or a mix of text and images.\n"
                + "- A single image may contain both the problem statement AND a diagram.\n"
                + "- Multiple images may be provided: some may contain text, some may contain diagrams, some both.\n"
                + "- An image that contains only printed/handwritten text (no geometric figure) is a text source, not a diagram.\n"
                + "- Extract the complete problem statement from ALL provided sources (text + images) and merge into one coherent `statement`.\n"
                + "- Only set diagram.present = true if there is an actual geometric/graphical figure (not just text rendered as an image).\n\n"
                + "Rules:\n"
                + "- Preserve the mathematical meaning of the problem.\n"
                + "- Normalize the statement into a clear standalone problem statement in the same language as the input.\n"
                + "- Set input_mode to \"concept\" if the input describes a concept to teach, or \"problem\" if it states a problem to solve.\n"
                + "- If a geometric diagram is present, set diagram.present = true and describe a mathematically valid initial diagram.\n"
                + "- If no geometric diagram exists (pure text or algebraic), set diagram.present = false and leave diagram.objects empty.\n"
                + "- Use the storyboard object schema for diagram objects (id, kind, content, constraints).\n"
                + "- Use the storyboard constraint schema for diagram constraints.\n"
                + "- Prefer mathematical correctness over pixel-perfect copying of source images.\n"
                + "- If the source diagram is imprecise, construct the clean mathematical diagram implied by the problem.\n\n"
                + "Do NOT:\n"
                + "- Solve the problem.\n"
                + "- Add auxiliary constructions not in the original problem diagram.\n"
                + "- Add future teaching steps, reflection points, proof lines, or conclusion markers.\n"
                + "- Include solution reasoning in the diagram.\n"
                + "- Treat text rendered in an image as a diagram. Text-only images contribute to `statement`, not `diagram`.\n\n"
                + "Constraint catalog (valid domain values): " + StoryboardConstraintCatalog.domainEnumJson() + "\n"
                + "Constraint catalog (valid relation values): " + StoryboardConstraintCatalog.relationEnumJson() + "\n\n"
                + "Object kind values: point, line, ray, segment, circle, arc, polygon, angle_marker, "
                + "text, equation, text_card, number_line, coordinate_system, vector, region, graph, group, slider, image.\n\n"
                + "construction_notes should include backend-specific requirements such as:\n"
                + "- For GeoGebra: which points must be draggable (use Point on Object), which are free vs dependent.\n"
                + "- \"Construct a mathematically valid diagram; do not copy source-image proportions if they conflict with stated conditions.\"\n"
                + "- \"The first storyboard scene must construct the initial diagram before introducing solution reasoning.\"\n";
    }

    public static String buildUserPrompt(String rawText, String outputTarget) {
        StringBuilder sb = new StringBuilder();
        sb.append("Normalize the following input into a ProblemBundle.\n");
        sb.append("Output target: ").append(outputTarget).append("\n\n");
        if (rawText != null && !rawText.isBlank()) {
            sb.append("Text input:\n").append(rawText).append("\n\n");
        }
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    public static String buildMultimodalUserPrompt(String rawText, String outputTarget, int imageCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("Normalize the following input into a ProblemBundle.\n");
        sb.append("Output target: ").append(outputTarget).append("\n\n");
        if (rawText != null && !rawText.isBlank()) {
            sb.append("Text input:\n").append(rawText).append("\n\n");
        }
        if (imageCount == 1) {
            sb.append("One image is attached. It may contain the problem text, a diagram, or both. ");
            sb.append("Extract all information from it.\n");
        } else {
            sb.append(imageCount).append(" images are attached. They may individually contain problem text, ");
            sb.append("diagrams, or both. Combine all information across all images into one coherent ProblemBundle.\n");
        }
        return sb.toString();
    }
}
