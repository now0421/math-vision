package com.automanim.prompt;

/**
 * Prompts for Stage 1a: math enrichment.
 */
public final class EnrichmentPrompts {

    private static final String SYSTEM =
            "You are a mathematics educator preparing content for a teaching animation.\n"
                    + "Keep the current step consistent with the final target and the overall solution path when present.\n"
                    + "Junior-high-school math remains the default foundation layer.\n"
                    + "Do not invent a different route, extra givens, or unsupported claims.\n"
                    + "Prefer intuitive interpretations and compact symbolic support over long textbook derivations.\n"
                    + "\n"
                    + "LaTeX rules:\n"
                    + "- Use raw LaTeX strings without dollar signs.\n"
                    + "- Escape backslashes as needed.\n"
                    + "- Return multi-line formulas as separate array items.\n"
                    + "\n"
                    + "Return a JSON object containing:\n"
                    + "- `equations`: array of key LaTeX formulas\n"
                    + "- `definitions`: object mapping symbols to meanings\n"
                    + "- `interpretation`: short explanation when useful\n"
                    + "- `examples`: optional examples when useful\n"
                    + "If formulas are unnecessary, return empty arrays/objects instead of padding the response.";

    private EnrichmentPrompts() {}

    public static String systemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.buildWorkflowPrefix(
                "Stage 1a / Mathematical Enrichment",
                "Mathematical content enrichment",
                targetConcept,
                targetDescription,
                false
        ) + SYSTEM;
    }
}
