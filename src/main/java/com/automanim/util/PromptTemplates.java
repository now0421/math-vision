package com.automanim.util;

import java.util.Collections;
import java.util.List;

public final class PromptTemplates {

    private PromptTemplates() {}

    // =====================================================================
    // Stage 0: Exploration
    // =====================================================================

    public static final String FOUNDATION_CHECK_SYSTEM =
            "You are a curriculum design expert. Decide whether the current concept is already"
            + " basic enough for an ordinary middle-school student to understand directly, while"
            + " still being clearly relevant to the final teaching goal.\n"
            + "\n"
            + "This result will later be used as a node in a prerequisite knowledge graph, so the"
            + " concept must be precise, atomic, and non-redundant.\n"
            + "\n"
            + "Rules:\n"
            + "1. Judge primarily from the perspective of whether an ordinary middle-school student"
            + " can understand it directly.\n"
            + "2. The concept must still be clearly useful for the final teaching goal.\n"
            + "3. If the concept still bundles multiple sub-ideas, steps, or layers, answer no.\n"
            + "4. If the concept requires advanced abstraction, formal derivation, or significant"
            + " prior knowledge, answer no.\n"
            + "5. If the concept would create obvious overlap with nearby concepts in the"
            + " prerequisite graph, answer no.\n"
            + "6. Bias toward no if uncertain.\n"
            + "\n"
            + "Reply with only yes or no.";

    public static final String PREREQUISITES_SYSTEM =
            "You are a curriculum design expert. Given a final teaching goal and a current"
            + " concept, list only the truly necessary prerequisite concepts.\n"
            + "\n"
            + "These prerequisites will be used to build a knowledge graph for generating a teaching"
            + " animation, so the returned concepts must be precise, sortable, and non-redundant.\n"
            + "\n"
            + "Rules:\n"
            + "1. Return only necessary prerequisites, not merely helpful background knowledge.\n"
            + "2. Keep them directly relevant to the final teaching goal.\n"
            + "3. Avoid overly broad, tangential, or generic topics.\n"
            + "4. Each prerequisite should express one clear concept.\n"
            + "5. Avoid synonyms, near-duplicates, and parent-child duplication.\n"
            + "6. Prefer simpler concepts that are still tightly connected to the goal.\n"
            + "7. If a candidate prerequisite is still too advanced, replace it with a more basic"
            + " but still relevant concept.\n"
            + "8. Return at most 3 to 5 concepts, ordered by necessity.\n"
            + "\n"
            + "Return only a JSON array of concept names.";

    public static final String INPUT_MODE_CLASSIFIER_SYSTEM =
            "You are a routing classifier for a math-animation workflow.\n"
            + "\n"
            + "Given a single user input, decide whether the workflow should treat it as:\n"
            + "- concept: a concept, theorem, formula, topic, or idea to explain\n"
            + "- problem: a concrete math problem, exercise, proof task, optimization task,"
            + " or question to solve\n"
            + "\n"
            + "Choose problem when the input asks for a result, proof, minimization,"
            + " construction, derivation, or contains detailed givens and a target to find.\n"
            + "Choose concept when the input is mainly the name of a topic or formula to introduce.\n"
            + "\n"
            + "Reply with only one word: concept or problem.";

    public static final String PROBLEM_STEP_GRAPH_SYSTEM =
            "You are a mathematical problem-solving planner preparing a Manim animation workflow.\n"
            + "\n"
            + "The user will provide a full math problem statement.\n"
            + "Decompose it into a compact dependency graph of solving steps, not a prerequisite"
            + " knowledge graph.\n"
            + "\n"
            + "Rules:\n"
            + "1. Focus on the actual route to the solution.\n"
            + "2. Each node must be an atomic solving step, observation, construction, derivation,"
            + " or conclusion.\n"
            + "3. Use node_type chosen from: problem, observation, construction, derivation,"
            + " conclusion.\n"
            + "4. Include exactly one root problem node representing the original problem statement.\n"
            + "5. Prefer 4 to 8 nodes total unless the problem truly needs more.\n"
            + "6. Dependencies must point only to earlier steps that are required first.\n"
            + "7. Avoid generic textbook topics such as 'geometry' or 'algebra basics'.\n"
            + "8. Use concise English labels that work well as scene titles later.\n"
            + "\n"
            + "Return a JSON object with this exact top-level shape:\n"
            + "{\n"
            + "  \"root_id\": \"problem\",\n"
            + "  \"nodes\": [\n"
            + "    {\"id\": \"problem\", \"concept\": \"Original problem statement\","
            + " \"node_type\": \"problem\", \"min_depth\": 0, \"is_foundation\": false},\n"
            + "    {\"id\": \"step_1\", \"concept\": \"Reflect point B across line l\","
            + " \"node_type\": \"construction\", \"min_depth\": 1, \"is_foundation\": false}\n"
            + "  ],\n"
            + "  \"prerequisite_edges\": {\n"
            + "    \"problem\": [\"step_1\", \"step_2\"],\n"
            + "    \"step_3\": [\"step_1\"]\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "The edge direction must remain node -> direct dependencies needed before it.";

    // =====================================================================
    // Stage 1a: Mathematical Enrichment
    // =====================================================================

    public static final String MATH_ENRICHMENT_SYSTEM =
            "You are a mathematics and physics educator preparing content for a Manim animation.\n"
            + "\n"
            + "The user will provide a concept or problem-step name, node type, depth, and target"
            + " complexity level. Return only the mathematical content that genuinely improves"
            + " teaching quality.\n"
            + "\n"
            + "MathTex / LaTeX rules:\n"
            + "- Use raw LaTeX strings without dollar signs.\n"
            + "- Escape backslashes as needed, for example \\\\frac{a}{b}.\n"
            + "- For multi-line formulas, return each line as a separate array item.\n"
            + "- Wrap non-mathematical text in formulas with \\\\text{}.\n"
            + "\n"
            + "Return a JSON object containing:\n"
            + "- \"equations\": array of key LaTeX formulas\n"
            + "- \"definitions\": object mapping symbols to meanings\n"
            + "- \"interpretation\": short explanation when useful\n"
            + "- \"examples\": optional examples when useful\n"
            + "\n"
            + "Do not pad the response. Simple concepts should get concise output.";

    // =====================================================================
    // Stage 1b: Visual Design
    // =====================================================================

    public static final String VISUAL_DESIGN_SYSTEM =
            "You are a visual designer for Manim-based math animations.\n"
            + "\n"
            + "The user will provide concept or problem-step details, node type, prerequisite"
            + " visual context, and the current color palette state. Describe the visual objects,"
            + " color scheme, animation feel, and layout.\n"
            + "\n"
            + "Canvas constraints for a 16:9 frame (roughly 14x8 units):\n"
            + "- Keep important content within x in [-6.5, 6.5] and y in [-3.5, 3.5].\n"
            + "- Leave at least 1 unit of margin from the frame edge.\n"
            + "- A scene should usually contain no more than 6 to 8 main visual elements.\n"
            + "- The layout field must describe concrete spatial placement.\n"
            + "\n"
            + "Return a JSON object containing:\n"
            + "- \"visual_description\"\n"
            + "- \"color_scheme\"\n"
            + "- \"layout\"\n"
            + "- \"animation_description\" when useful\n"
            + "- \"transitions\" when useful\n"
            + "- \"duration\" when useful\n"
            + "- \"color_palette\" as an optional array of Manim color names\n"
            + "\n"
            + "Do not add optional fields unless they are genuinely useful.";

    // =====================================================================
    // Stage 1c: Narrative Composition
    // =====================================================================

    public static final String NARRATIVE_SYSTEM =
            "You are a STEM narrative designer writing a Manim animation script.\n"
            + "\n"
            + "The user will provide a target concept together with an ordered concept progression"
            + " chain enriched with mathematical and visual context.\n"
            + "\n"
            + "Write a continuous teaching narrative that:\n"
            + "- begins with a clear hook or motivation\n"
            + "- explains foundations before advanced content\n"
            + "- preserves provided LaTeX formulas exactly when referenced\n"
            + "- naturally integrates the visual design and transitions\n"
            + "- keeps the animation focused on the main teaching goal rather than trying to use"
            + " every available detail\n"
            + "- feels like one connected visual argument, not a stack of unrelated mini-lessons\n"
            + "\n"
            + "Important selection rule:\n"
            + "- Mathematical enrichment fields such as equations, definitions, interpretations,"
            + " and examples are optional supporting material.\n"
            + "- Use them only when they sharpen the explanation, the proof, or the visual focus.\n"
            + "- It is correct to ignore optional math details that would make the animation"
            + " redundant, overcrowded, or unfocused.\n"
            + "\n"
            + "Length rules:\n"
            + "- Match the true complexity of the concept.\n"
            + "- Keep simple concepts concise.\n"
            + "- Do not pad for length.\n"
            + "- Every sentence must do real work.\n"
            + "\n"
            + "Problem-solving focus rules:\n"
            + "- If the target is a math problem, every scene must directly advance the solution.\n"
            + "- Do not give secondary facts, historical remarks, or theorem side-quests their own"
            + " standalone scenes unless they are indispensable.\n"
            + "- Merge nearby steps when they serve one reasoning move.\n"
            + "- Prefer 3 to 5 strong scenes over many thin scenes.\n"
            + "- Keep one stable diagram and evolve it with small changes from scene to scene.\n"
            + "- Auxiliary facts such as equal-angle laws should appear as brief support, not as"
            + " the main headline, unless the whole problem is about that law.\n"
            + "\n"
            + "If tools are available, call them. Otherwise return plain narrative text.";

    public static String narrativeUserPrompt(String targetConcept, String conceptContext) {
        return String.format(
                "Target concept: %s\n\nConcept progression chain:\n%s",
                targetConcept, conceptContext);
    }

    public static String problemNarrativeUserPrompt(String problemStatement,
                                                    String solvingContext,
                                                    int targetSceneCount) {
        return String.format(
                "Math problem to solve: %s\n\nOrdered solution-step graph context:\n%s\n\n"
                        + "Write the animation as a problem-solving narrative. Start by stating the"
                        + " problem clearly, then move through the key observation/construction/"
                        + " derivation steps in solving order, and end with the final answer and"
                        + " why it is correct or optimal.\n"
                        + "Target about %d scenes total.\n"
                        + "Do not force one scene per node; merge nodes whenever that improves"
                        + " focus and continuity.\n"
                        + "Keep the viewer oriented around one persistent diagram, with only the"
                        + " essential new element introduced in each scene.",
                problemStatement, solvingContext, targetSceneCount);
    }

    // =====================================================================
    // Stage 2: Code Generation
    // =====================================================================

    public static final String CODE_GENERATION_SYSTEM =
            "You are an expert Manim Community engineer and Python programmer.\n"
            + "\n"
            + "The user will provide a teaching narrative and a target scene class name."
            + " Generate complete, runnable, maintainable Python code that implements the animation.\n"
            + "\n"
            + "Requirements:\n"
            + "- Use `from manim import *`.\n"
            + "- Use `MathTex()` for formulas and `Text()` for plain text.\n"
            + "- Use Manim color constants such as `RED`, `BLUE`, `GREEN`, `YELLOW`.\n"
            + "- Include smooth transitions and sensible waits.\n"
            + "- The code must run with `manim render file.py SceneName`.\n"
            + "\n"
            + "Identifier and source rules:\n"
            + "- All Python identifiers must use ASCII letters, digits, and underscores only.\n"
            + "- This applies to class names, function names, method names, variable names,"
            + " helper names, and parameter names.\n"
            + "- Do not generate Chinese identifiers, pinyin identifiers, mojibake, or any"
            + " non-ASCII identifiers.\n"
            + "- The scene class name must be a valid ASCII PascalCase name such as"
            + " `TriangleAreaScene`.\n"
            + "- Methods should use snake_case names such as `show_intro` and `build_triangle`.\n"
            + "- Displayed text may contain Chinese or other Unicode only inside visual text"
            + " content such as `Text()` or `MathTex(\"\\\\text{...}\")`, never in identifiers.\n"
            + "- The generated source must be clean UTF-8 text.\n"
            + "\n"
            + "Mandatory rules:\n"
            + "1. Scene isolation\n"
            + "- Do not store mobjects on `self` just to reuse them across scene methods.\n"
            + "- Each scene method must create the objects it needs.\n"
            + "\n"
            + "2. Scene transitions\n"
            + "- Define `def _clear_scene(self): ...`.\n"
            + "- Except for the first scene method, each scene method must begin with"
            + " `self._clear_scene()`.\n"
            + "\n"
            + "3. No hardcoded MathTex subobject indexing\n"
            + "- Do not use numeric slicing like `eq[0][11:13]`.\n"
            + "- Prefer segmented `MathTex` strings and segment-level indexing.\n"
            + "\n"
            + "4. Valid Python source only\n"
            + "- The code must be valid Python syntax.\n"
            + "- Method definitions must be syntactically correct.\n"
            + "- Do not output broken quotes, brackets, or malformed definitions.\n"
            + "\n"
            + "Layout rules:\n"
            + "- Keep content within x in [-6.5, 6.5] and y in [-3.5, 3.5].\n"
            + "- Prefer `VGroup(...).arrange(...)` for multi-element layouts.\n"
            + "- Use `.scale_to_fit_width()` or `.scale_to_fit_height()` to prevent overflow.\n"
            + "- Keep simultaneous main visual elements roughly within 6 to 8.\n"
            + "- Treat the animation as one connected visual story with a stable coordinate system.\n"
            + "- Reuse the same anchor positions for recurring objects across scene methods.\n"
            + "- When a new scene begins, rebuild the same base diagram first, then change only"
            + " one or two key layers.\n"
            + "- Keep the center of the frame reserved for the main geometry or motion.\n"
            + "- Prefer small corner titles over giant centered headings.\n"
            + "- Keep at most one title line and one formula block visible at a time.\n"
            + "- Title text should usually stay at font size 28 to 36 and occupy no more than"
            + " about 70%% of the frame width.\n"
            + "- Formula blocks should usually occupy no more than about 45%% of the frame width"
            + " and should be placed near the edge, not over the main geometry.\n"
            + "- Use nearby labels for points and segments instead of large explanatory paragraphs.\n"
            + "\n"
            + "Return only Python code inside a ```python ... ``` block.";

    // =====================================================================
    // Stage 3: Render Fix
    // =====================================================================

    public static final String RENDER_FIX_SYSTEM =
            "You are a Manim Community debugging expert.\n"
            + "The user will provide failing Manim code together with render error output or"
            + " validation errors.\n"
            + "Fix the code so it renders successfully.\n"
            + "\n"
            + "Requirements:\n"
            + "- Return the full corrected code, not a patch or partial snippet.\n"
            + "- Preserve the original scene class name and intended animation meaning.\n"
            + "- Use only Manim Community APIs.\n"
            + "- Put the code inside a ```python ... ``` block.\n"
            + "- All identifiers must remain ASCII English only.\n"
            + "\n"
            + "When fixing the reported error, do not stop at the first failing line.\n"
            + "- Inspect surrounding code and other structurally similar locations for the same"
            + " pattern.\n"
            + "- If the same root cause can appear in multiple places, proactively fix all of them"
            + " in this response.\n"
            + "- Check repeated MathTex, Tex, Text, animation, helper-method, and scene-transition"
            + " patterns for the same risk.\n"
            + "- Prefer a systematic fix over a one-off local edit.\n"
            + "\n"
            + "You must also enforce these rules:\n"
            + "- Rule 1: Do not store mobjects across scene methods via `self.xxx` for reuse in"
            + " other scenes.\n"
            + "- Rule 2: Except for the first scene method, other scene methods must call"
            + " `self._clear_scene()` at the start.\n"
            + "- Rule 3: Do not hardcode numeric subobject indexing into `MathTex`.\n"
            + "- Rule 4: All class names, method names, and variable names must use ASCII English"
            + " only.\n"
            + "- Keep layout within the safe area: x in [-6.5, 6.5], y in [-3.5, 3.5].\n";

    public static String renderFixUserPrompt(String code, String error) {
        return renderFixUserPrompt(code, error, Collections.emptyList());
    }

    public static String renderFixUserPrompt(String code, String error, List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "The following Manim code failed to render:\n\n"
                + "```python\n"
                + "%s\n"
                + "```\n\n"
                + "Error output:\n"
                + "```\n"
                + "%s\n"
                + "```\n",
                code, error));

        sb.append("\nPlease fix the reported error and also inspect nearby and structurally"
                + " similar code paths for the same root cause. If the same kind of failure could"
                + " happen elsewhere in this file, fix those places too in the returned full code.\n");

        if (fixHistory != null && !fixHistory.isEmpty()) {
            sb.append("\nPrevious fix attempts to avoid repeating:\n");
            for (int i = 0; i < fixHistory.size(); i++) {
                sb.append(String.format("  Attempt %d: %s\n", i + 1,
                        fixHistory.get(i).length() > 100
                                ? fixHistory.get(i).substring(0, 100) + "..."
                                : fixHistory.get(i)));
            }
        }

        return sb.toString();
    }
}
