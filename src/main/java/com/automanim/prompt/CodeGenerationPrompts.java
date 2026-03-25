package com.automanim.prompt;

import java.util.List;

/**
 * Prompts for Stage 2: code generation and validation fixes.
 */
public final class CodeGenerationPrompts {

    private static final String CODE_GENERATION_SYSTEM =
            "You are an expert Manim Community engineer and Python programmer.\n"
                    + "Generate complete, runnable, maintainable Python code that implements the storyboard.\n"
                    + "\n"
                    + "Mandatory rules:\n"
                    + "- Use `from manim import *`.\n"
                    + "- Keep all Python identifiers ASCII only.\n"
                    + "- Preserve scene continuity instead of clearing the scene between beats.\n"
                    + "- Do not store mobjects on `self` just to reuse them across scene methods.\n"
                    + "- Do not hardcode numeric MathTex subobject indexing.\n"
                    + "- Use `ThreeDScene` only when needed and keep overlays fixed in frame when appropriate.\n"
                    + "- Keep content inside x[-7,7], y[-4,4] and prefer stable anchors plus `arrange`/`next_to`.\n"
                    + "- Keep labels dynamically attached to moving objects.\n"
                    + "\n"
                    + "Output format:\n"
                    + "Return exactly one fenced Python code block containing the full runnable file.\n"
                    + "\n"
                    + "Example output:\n"
                    + "```python\n"
                    + "from manim import *\n"
                    + "\n"
                    + "class SceneName(Scene):\n"
                    + "    def construct(self):\n"
                    + "        pass\n"
                    + "```\n"
                    + "\n"
                    + "Do NOT provide explanations before or after the code block.\n"
                    + "The output must be ONLY the code block.";

    private static final String VALIDATION_FIX_SYSTEM =
            "You are a Manim code correction specialist.\n"
                    + "You will receive generated Manim code together with validation failures.\n"
                    + "Rewrite the full file so it becomes valid, consistent, and ready for the next workflow stage.\n"
                    + "Fix every reported validation problem, preserve the teaching content, keep the requested scene class name, and proactively fix nearby Python/Manim mistakes.\n"
                    + "\n"
                    + "Output format:\n"
                    + "Return exactly one fenced Python code block containing the full corrected file.\n"
                    + "\n"
                    + "Example output:\n"
                    + "```python\n"
                    + "from manim import *\n"
                    + "\n"
                    + "class RequestedSceneName(Scene):\n"
                    + "    def construct(self):\n"
                    + "        pass\n"
                    + "```\n"
                    + "\n"
                    + "Do not add any explanation before or after the code block.";

    private CodeGenerationPrompts() {}

    public static String systemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureManimSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Generation",
                "Generate executable Manim code",
                targetConcept,
                targetDescription,
                true
        ) + CODE_GENERATION_SYSTEM);
    }

    public static String validationFixSystemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureManimSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Fix",
                "Repair generated code after validation findings",
                targetConcept,
                targetDescription,
                true
        ) + VALIDATION_FIX_SYSTEM);
    }

    public static String validationFixUserPrompt(String sceneName,
                                                 String manimCode,
                                                 List<String> violations) {
        String problemList = (violations == null || violations.isEmpty())
                ? "- Validation failed for an unspecified reason."
                : "- " + String.join("\n- ", violations);
        return String.format(
                "The generated Manim code failed validation checks.\n\n"
                        + "Required scene class name: %s\n\n"
                        + "Current code:\n```python\n%s\n```\n\n"
                        + "Problems found:\n%s\n\n"
                        + "Rewrite the FULL code so it satisfies all validation rules while preserving the teaching goal.\n"
                        + "Keep `%s` as the exact scene class name, use ASCII-only Python identifiers, and also fix nearby Python/Manim mistakes.\n"
                        + "Return ONLY the full Python code block.",
                sceneName, manimCode, problemList, sceneName);
    }
}
