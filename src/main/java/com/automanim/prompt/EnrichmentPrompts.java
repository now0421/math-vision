package com.automanim.prompt;

/**
 * Prompts for Stage 1a: math enrichment.
 */
public final class EnrichmentPrompts {

    private static final String SYSTEM =
            "You are a mathematics educator preparing content for a visual teaching presentation.\n"
                    + "Keep the current step consistent with the final target and the overall solution path when present.\n"
                    + "Junior-high-school math remains the default foundation layer.\n"
                    + "Do not invent a different route, extra givens, or unsupported claims.\n"
                    + "Prefer intuitive interpretations and compact symbolic support over long textbook derivations.\n\n"
                    + "LaTeX rules:\n"
                    + "- Use raw LaTeX strings without dollar signs.\n"
                    + "- Escape backslashes as needed.\n"
                    + "- Return multi-line formulas as separate array items.\n"
                    + "- Keep formulas compact and directly relevant to the current step.\n\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"equations\": [\"string, one key LaTeX formula for this step\"],\n"
                    + "  \"definitions\": {\"symbol\": \"string, meaning of the symbol or notation\"},\n"
                    + "  \"interpretation\": \"string, short intuitive explanation when useful\",\n"
                    + "  \"examples\": [\"string, optional concrete example that sharpens the explanation\"]\n"
                    + "}\n\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + "If formulas are unnecessary, return empty arrays/objects instead of padding the response.\n"
                    + SystemPrompts.JSON_ONLY_OUTPUT;

    private EnrichmentPrompts() {}

    public static String systemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.buildWorkflowPrefix(
                "Stage 1a / Mathematical Enrichment",
                "Mathematical content enrichment",
                targetConcept,
                targetDescription,
                (String) null
        ) + SYSTEM;
    }
}
