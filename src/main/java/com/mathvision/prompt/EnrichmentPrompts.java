package com.mathvision.prompt;

/**
 * Prompts for Stage 1a: math enrichment.
 *
 * Split into two parts:
 * - buildRulesPrompt(): hard rules (role, output format, LaTeX, schema, operation rules)
 * - buildFixedContextPrompt(): workflow prefix + solution chain (fixed background)
 */
public final class EnrichmentPrompts {

    private static final String SYSTEM =
            "You are a mathematics educator preparing content for a visual teaching presentation.\n"
                    + "Keep the current step consistent with the final target and the overall solution path when present.\n"
                    + "Junior-high-school math remains the default foundation layer.\n"
                    + "Do not invent a different route, extra givens, or unsupported claims.\n"
                    + "Prefer intuitive interpretations and compact symbolic support over long textbook derivations.\n"
                    + "Write with narration-first teaching intent: the math here should support what a learner will hear and see later.\n"
                    + "Explain why before how when both cannot fit comfortably.\n"
                    + "Let equations feel earned by intuition rather than appearing as isolated symbols.\n"
                    + "Keep only formulas, definitions, and examples that materially support a later visual explanation.\n"
                    + "Only include definitions for symbols, variables, geometric objects, or notations that will appear visually or in formulas. Do not define abstract concepts, reasoning strategies, or teaching methods.\n"
                    + "When the current step merges multiple prerequisite branches, integrate those branch conclusions into one continuation.\n"
                    + "For merge steps, preserve established naming and avoid restarting the explanation from scratch.\n\n"
                    + "LaTeX rules:\n"
                    + "- Use raw LaTeX strings without dollar signs.\n"
                    + "- Escape backslashes as needed.\n"
                    + "- Return multi-line formulas as separate array items.\n"
                    + "- Keep formulas compact and directly relevant to the current step.\n\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"equations\": [\"string, one key LaTeX formula for this step\"],\n"
                    + "  \"definitions\": {\"symbol\": \"string, meaning of a visual or mathematical symbol, variable, geometric object, or notation that appears in the diagram or formulas\"},\n"
                    + "  \"interpretation\": \"string, short learner-facing intuition that explains what the formula means or why it matters\",\n"
                    + "  \"examples\": [\"string, optional concrete example that sharpens the explanation without overloading the future scene\"]\n"
                    + "}\n\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + "If formulas are unnecessary, return empty arrays/objects instead of padding the response.\n"
                    + SystemPrompts.JSON_ONLY_OUTPUT;

    private EnrichmentPrompts() {}

    /**
     * Returns hard rules for enrichment: role, output format, LaTeX rules,
     * schema constraints, operation rules.
     */
    public static String buildRulesPrompt() {
        return SystemPrompts.buildRulesSection(SYSTEM);
    }

    /**
     * Returns fixed background context: workflow prefix + solution chain.
     */
    public static String buildFixedContextPrompt(String targetConcept,
                                                  String targetDescription,
                                                  String solutionChain) {
        StringBuilder sb = new StringBuilder();
        sb.append(SystemPrompts.buildWorkflowPrefix(
                "Stage 1a / Mathematical Enrichment",
                "Mathematical content enrichment",
                targetConcept,
                targetDescription,
                (String) null));
        if (solutionChain != null && !solutionChain.isBlank()) {
            sb.append("\n\n").append(solutionChain);
        }
        return SystemPrompts.buildFixedContextSection(sb.toString());
    }
}
