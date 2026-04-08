package com.automanim.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared workflow prompt helpers and prompt-resource loading.
 */
public final class SystemPrompts {

    private static final String MANIM_SYNTAX_MANUAL_RESOURCE = "llm/manim_syntax_manual.md";
    private static final String MANIM_STYLE_REFERENCE_RESOURCE = "llm/manim_style_reference.md";
    private static final String GEOGEBRA_SYNTAX_MANUAL_RESOURCE = "llm/geogebra_syntax_manual.md";
    private static final String GEOGEBRA_STYLE_REFERENCE_RESOURCE = "llm/geogebra_style_reference.md";

    // ========================================================================
    // Shared prompt fragments for deduplication
    // ========================================================================

    /** Layout frame rules: safe area bounds and element count guidance. */
    public static final String LAYOUT_FRAME_RULES =
            "Keep important content within x[-7,7] and y[-4,4].\n"
                    + "Leave about 1 unit of edge margin.\n"
                    + "Usually keep each step to 6 to 8 main visual elements.\n";

    /** Storyboard field interpretation guide — core fields for code generation and evaluation stages. */
    public static final String STORYBOARD_FIELD_GUIDE_CORE =
            "How to interpret the storyboard fields:\n"
                    + "- `behavior`: dependency semantics — `static` means independently defined, `derived` means defined from other geometry, `follows_anchor` means attached to `anchor_id`, `fixed_overlay` means screen-space overlay.\n"
                    + "- `constraint_note`: object-level hard geometry that must be preserved.\n"
                    + "- `dependency_note`: attachment or update logic describing what source objects define this object.\n"
                    + "- `geometry_constraints`: scene-level invariants such as reflections, symmetry, collinearity, intersections, equal lengths.\n"
                    + "- `placement`: initial layout intent, not the full geometric definition.\n"
                    + "- `anchor_id`: id of the object this one should stay attached to.\n";

    /** Storyboard field interpretation guide — extended fields for object lifecycle and actions. */
    public static final String STORYBOARD_FIELD_GUIDE_EXTENDED =
            "- `entering_objects`: objects that must exist for the teaching beat; `id` is a stable visual identity.\n"
                    + "- `persistent_objects`: object ids that should stay on screen from earlier beats; do not recreate them.\n"
                    + "- `exiting_objects`: object ids that should explicitly leave the scene.\n"
                    + "- `actions`: the main execution plan; respect their order, targets, and visible intent.\n"
                    + "- `content`: what must be shown by the object.\n"
                    + "- `notes_for_codegen`: implementation hints; follow unless they conflict with correctness.\n"
                    + "- `step_refs`, `title`, `narration`: teaching purpose; help choose clear animation structure.\n"
                    + "- `continuity_plan`, `global_visual_rules`: global constraints that shape the whole file.\n";

    /** Storyboard field interpretation guide — layout and camera fields. */
    public static final String STORYBOARD_FIELD_GUIDE_LAYOUT =
            "- `layout_goal`: intended screen composition and relative placement of major elements.\n"
                    + "- `safe_area_plan`: how important content stays readable and inside the safe frame.\n"
                    + "- `screen_overlay_plan`: text or formulas that stay fixed in screen space.\n"
                    + "- `camera_anchor`, `camera_plan`: camera focus and behavior.\n"
                    + "- `goal`: what the scene must accomplish for understanding or solution progress.\n";

    /** Combined storyboard field guide for backward compatibility. */
    public static final String STORYBOARD_FIELD_GUIDE =
            STORYBOARD_FIELD_GUIDE_CORE;

    /** Full storyboard field guide combining all sections. */
    public static final String STORYBOARD_FIELD_GUIDE_FULL =
            "How to interpret the storyboard fields:\n"
                    + STORYBOARD_FIELD_GUIDE_CORE.replace("How to interpret the storyboard fields:\n", "")
                    + STORYBOARD_FIELD_GUIDE_EXTENDED
                    + STORYBOARD_FIELD_GUIDE_LAYOUT;

    /** Storyboard field guide for GeoGebra code generation. */
    public static final String STORYBOARD_FIELD_GUIDE_GEOGEBRA =
            "How to interpret the storyboard fields:\n"
                    + "- `entering_objects`: objects that must exist for the teaching beat.\n"
                    + "- `persistent_objects`: earlier objects that should remain available in later steps.\n"
                    + "- `exiting_objects`: may be translated into hidden helper objects or omitted if persistent visibility would cause clutter.\n"
                    + "- `actions`: state changes; convert into construction order, visibility changes, highlight states, or helper toggles rather than literal animation.\n"
                    + "- `placement`, `layout_goal`, `safe_area_plan`, `screen_overlay_plan`: guide readable coordinates, label placement, and visibility choices.\n"
                    + "- `behavior = follows_anchor` or `derived`: object should be defined from its source geometry so it updates automatically.\n"
                    + "- `behavior = static`: independently defined object; can still be fixed, draggable, or moved later depending on `actions`, `geometry_constraints`, `constraint_note`, and `notes_for_codegen`.\n"
                    + "- For constrained motion, prefer explicit GeoGebra constructions such as `Point(path)`, `Intersect(...)`, `Reflect(...)`, `Midpoint(...)`, `PerpendicularLine(...)`, `ParallelLine(...)`, or slider-driven parameterizations with declared bounds.\n"
                    + "- When a point should remain on a line, segment, circle, or similar object, the generated command should visibly encode that incidence relation.\n";

    /** Storyboard field guide for scene evaluation/repair pass. */
    public static final String STORYBOARD_FIELD_GUIDE_REPAIR =
            "Storyboard field guide for this repair pass:\n"
                    + "- `goal` and `layout_goal`: preserve what the scene is trying to teach and how the frame should be composed.\n"
                    + "- `safe_area_plan` and `screen_overlay_plan`: use these first when fixing overlap and offscreen issues.\n"
                    + "- `geometry_constraints` and each object's `constraint_note`: treat these as hard geometric invariants.\n"
                    + "- `behavior`, `anchor_id`, and `dependency_note`: preserve attachment logic for derived lines, reflected points, moving labels, and overlays.\n"
                    + "- `persistent_objects`, `exiting_objects`, and `actions`: preserve continuity and scene flow instead of redrawing the construction arbitrarily.\n"
                    + "- If a reported object is a reflection, midpoint, foot, or intersection, recompute it from its source construction instead of moving it freely.\n";

    /** Storyboard field guide for GeoGebra scene evaluation/repair pass. */
    public static final String STORYBOARD_FIELD_GUIDE_GEOGEBRA_REPAIR =
            "Storyboard field guide for this GeoGebra repair pass:\n"
                    + "- `goal` and `layout_goal`: preserve what the scene is trying to teach and how the construction should be laid out.\n"
                    + "- GeoGebra is interactive and freely zoomable, so out-of-bounds issues are not a concern. Focus on text overlap only.\n"
                    + "- `geometry_constraints` and each object's `constraint_note`: treat these as hard geometric invariants.\n"
                    + "- `behavior`, `anchor_id`, and `dependency_note`: preserve dependency-safe construction order for reflected points, intersections, midpoints, and derived objects.\n"
                    + "- `persistent_objects`, `exiting_objects`, and `actions`: preserve object visibility progression instead of rewriting the construction arbitrarily.\n"
                    + "- If a reported object is a reflection, midpoint, foot, or intersection, keep it defined from its source objects via GeoGebra dependency commands.\n";

    /** Geometry constraint preservation rules. */
    public static final String GEOMETRY_CONSTRAINT_RULES =
            "Preserve storyboard geometric invariants such as symmetry, reflection, collinearity, equal distances, and intersection definitions.\n"
                    + "For reflected points, intersections, midpoints, feet of perpendiculars, or similar derived geometry, compute them from source objects instead of assigning unrelated coordinates.\n"
                    + "If layout is unsafe, prefer moving/scaling whole constrained groups or recentering, not editing derived coordinates independently.\n";

    /** High-contrast color rules to avoid pale-on-pale combinations. */
    public static final String HIGH_CONTRAST_COLOR_RULES =
            "Keep text, labels, strokes, and fills visually distinct from their background.\n"
                    + "Avoid low-contrast pairings such as yellow on white, white on light yellow, or similar pale-on-pale combinations.\n";

    /** High-contrast color rules formatted as bullet lines for direct prompt insertion. */
    public static final String HIGH_CONTRAST_COLOR_RULES_BULLETS =
            "- " + HIGH_CONTRAST_COLOR_RULES.replace("\n", "\n- ").trim() + "\n";

    /** Angle marker best practices for Manim. */
    public static final String ANGLE_MARKER_RULES =
            "For angle markers, prefer `Angle(...)` built from two lines/rays sharing the true vertex instead of hand-written `Arc(start_angle=..., angle=...)` formulas.\n"
                    + "When an angle is measured against a normal, helper line, or moving segment, construct both rays from the shared point inside `always_redraw(...)`.\n"
                    + "If the intended angle sector could be ambiguous, explicitly set `quadrant=...`; if the storyboard intends the interior/smaller angle, explicitly keep `other_angle=False`.\n";

    /** ASCII identifier rules. */
    public static final String ASCII_IDENTIFIER_RULES =
            "Keep all Python identifiers and object names ASCII only.\n";

    /** Shared Manim naming rules for storyboard ids and generated identifiers. */
    public static final String MANIM_NAMING_RULES =
            "- Keep all Python identifiers and object names ASCII only.\n"
                    + "- When object ids become Python variable names, keep them concise and non-redundant because the role/type is already carried elsewhere; prefer `river` over `line_river` and `A` over `pointA`.\n"
                    + "- Single uppercase letters are acceptable for geometric points (e.g. `A`, `B`, `P`); use camelCase or snake_case for compound names.\n"
                    + "- Do not use Python reserved words (`class`, `def`, `lambda`, `for`, `if`, `in`, `is`, `not`, `None`, `True`, `False`, etc.) or Manim built-in class names (`Scene`, `Mobject`, `Line`, `Circle`, `Text`, etc.) as identifiers.\n";

    /** Shared GeoGebra naming rules for storyboard ids and generated identifiers. */
    public static final String GEOGEBRA_NAMING_RULES =
            "- Keep object names concise and non-redundant because the role/type is already carried elsewhere; prefer `l` over `lineL` and `c` over `circleC`.\n"
                    + "- Prefer native GeoGebra math-style names. Point names must start with an uppercase letter (e.g. `A`, `P_1`); vector names must start with a lowercase letter (e.g. `v`, `u`); lines, circles, and other non-point objects may start with a lowercase letter (e.g. `l`, `c`, `tri`). Use `_` for subscripts and `'` for primes.\n"
                    + "- Translate ASCII-spelled ids to GeoGebra-native math names when needed: `Bprime` -> `B'`, `ABprime` -> `AB'`, `Pstar` -> `P_{*}`, `Popt` -> `P_{opt}`, `P1` -> `P_1`. If native names like `B'` or `P_{opt}` are already used, keep them verbatim.\n"
                    + "- Do not use reserved names such as `x`, `y`, `z`, `xAxis`, `yAxis`, `zAxis`, `e`, `i`, `pi`, `sin`, `cos`, `tan`, `exp`, `log`, `ln`, `abs`, `sqrt`, `floor`, `ceil`, `round`, `random`, `arg`, `gamma`, `beta`, `sec`, `csc`, or `cot` as object identifiers.\n";

    /** Tool call hint for structured output prompts. */
    public static final String TOOL_CALL_HINT =
            "If tools are available, call them.\n";

    /** JSON-only output reminder. */
    public static final String JSON_ONLY_OUTPUT =
            "Return JSON only.";

    /** Python code block output format. */
    public static final String PYTHON_CODE_OUTPUT_FORMAT =
            "Output format:\n"
                    + "Return exactly one fenced Python code block containing the full corrected file.\n\n"
                    + "Example output:\n"
                    + "```python\n"
                    + "from manim import *\n\n"
                    + "class SceneName(Scene):\n"
                    + "    def construct(self):\n"
                    + "        pass\n"
                    + "```\n\n"
                    + "Do not add any explanation before or after the code block.";

    /** GeoGebra code block output format. */
    public static final String GEOGEBRA_CODE_OUTPUT_FORMAT =
            "Output format:\n"
                    + "Return exactly one fenced `geogebra` code block containing the full corrected command script.\n\n"
                    + "Example output:\n"
                    + "```geogebra\n"
                    + "A = (0, 0)\n"
                    + "B = (4, 0)\n"
                    + "lineAB = Line(A, B)\n"
                    + "```\n\n"
                    + "Do not add any explanation before or after the code block.";

    /** Common fix instructions appended to user prompts. */
    public static final String COMMON_FIX_INSTRUCTIONS =
            "Also proactively check for common Python and Manim runtime mistakes.\n"
                    + "Remember: Return ONLY the single Python code block containing the full file. No explanation.\n";

    /** Storyboard codegen preamble for Manim output. */
    public static final String STORYBOARD_CODEGEN_INTRO_MANIM =
            "Use the following compact storyboard JSON as the source of truth for staging, object identity, continuity, and scene execution.\n"
                    + "- Treat every object id as a stable visual identity.\n"
                    + "- If an id persists, keep or transform the same mobject instead of redrawing it.\n"
                    + "- When `content`, `dependency_note`, or related fields mention another object, treat those mentions as object ids only rather than as repeated type declarations.\n"
                    + "- If a scene uses `scene_mode = 3d`, use `ThreeDScene`, follow `camera_plan`, and judge layout in projected screen space.\n"
                    + "- Use `screen_overlay_plan` with `add_fixed_in_frame_mobjects` for fixed explanatory text.\n"
                    + "- Respect `safe_area_plan` and dynamic attachment for labels on moving objects.\n"
                    + "- Read `behavior`, `anchor_id`, and `dependency_note` literally: if an object follows a moving anchor, implement it with `always_redraw(...)` or an updater.\n"
                    + "- Treat `geometry_constraints` and `constraint_note` as hard invariants. If the frame is tight, preserve the construction and recenter/scale the whole constrained group instead of breaking the math.\n";

    /** Storyboard codegen preamble for GeoGebra output. */
    public static final String STORYBOARD_CODEGEN_INTRO_GEOGEBRA =
            "Use the following compact storyboard JSON as the source of truth for GeoGebra construction order, object identity, continuity, and teaching progression.\n"
                    + "- Keep the same object identities stable across steps.\n"
                    + "- Convert `actions` into construction order, visibility changes, highlights, or helper toggles rather than literal animation.\n"
                    + "- Preserve `geometry_constraints`, `behavior`, `anchor_id`, `dependency_note`, and `constraint_note` through dependency-safe GeoGebra commands.\n"
                    + "- Interpret `behavior` by dependency semantics, not by whether the object can move: `static` means independently defined base object, not automatically a free point.\n"
                    + "- If a point is described as movable/draggable and also constrained to a line, segment, ray, circle, polygon edge, or other object, generate it as a point on that object or with an equivalent dependency-safe parameterization, never as an unconstrained coordinate point.\n"
                    + "- If a point is fixed and no dependency is stated, define it as an independent anchor and keep it fixed unless the storyboard explicitly asks for dragging.\n"
                    + "- If a storyboard specifies a bounded range for motion, encode that bound in the construction itself, such as a segment, ray, restricted path, or slider domain, instead of leaving the point free on an unbounded line.\n"
                    + "- When `actions` move an object, preserve its constraint during that move; do not redefine the object as free just to make the motion easy.\n"
                    + "- When `content`, `dependency_note`, or other object fields mention another object, treat those mentions as object ids only. Do not reinterpret kind words from prose and do not invent a second object type for the same id.\n"
                    + "- Prefer GeoGebra's native labels for named geometric objects. If an object is named `A`, `l`, `c`, `AB`, or similar, use that object itself as the visible label instead of creating a separate text object like `aLabel = Text(...)`.\n"
                    + "- Treat storyboard object ids as the naming source for generated GeoGebra variables. Preserve those ids in code, and when you must introduce a helper name, use concise camelCase or math-style identifiers.\n"
                    + GEOGEBRA_NAMING_RULES
                    + "- For functions, use parenthesized variables such as `f(x) = ...`; for lines, circles, or conics defined by equations, use equation-label syntax such as `g: y = x + 3`.\n"
                    + "- This naming convention applies to all geometric objects with native names: points, lines, segments, rays, circles, polygons, angles, vectors, and functions.\n"
                    + "- Create a separate label/text object only when the visible text is not the object's own native label, such as overlays, formulas, counters, captions, or explanatory annotations.\n"
                    + "- If the storyboard contains a redundant geometry-label pair, prefer keeping the geometry object and dropping the extra label object in the generated GeoGebra commands.\n"
                    + "- Choose readable coordinates and label placement that respect `layout_goal`, `placement`, and `safe_area_plan`.\n"
                    + HIGH_CONTRAST_COLOR_RULES_BULLETS
                    + "- For angle markers, use only `Angle(B, vertex, C)` with `SetFilling`; never `CircularArc`. The angle sweeps counterclockwise from ray(vertex→B) to ray(vertex→C), so place the starting-ray point first: e.g. for the small angle between a rightward horizontal and an upper-left segment at vertex P, use `Angle((x(P)+1,0), P, A)` (CCW from right to upper-left = small angle above line).\n";

    // ========================================================================

    private static final String WORKFLOW_OVERVIEW =
            "Stage 0 Exploration -> Stage 1a Mathematical Enrichment -> Stage 1b Visual Design"
                    + " -> Stage 1c Narrative Composition -> Stage 2 Code Generation"
                    + " -> Stage 3 Code Evaluation -> Stage 4 Render Fix";

    private SystemPrompts() {}

    private static final class ManimSyntaxManualHolder {
        private static final String VALUE = loadPromptResource(MANIM_SYNTAX_MANUAL_RESOURCE);
    }

    private static final class ManimStyleReferenceHolder {
        private static final String VALUE = loadPromptResource(MANIM_STYLE_REFERENCE_RESOURCE);
    }

    private static final class GeoGebraSyntaxManualHolder {
        private static final String VALUE = loadPromptResource(GEOGEBRA_SYNTAX_MANUAL_RESOURCE);
    }

    private static final class GeoGebraStyleReferenceHolder {
        private static final String VALUE = loadPromptResource(GEOGEBRA_STYLE_REFERENCE_RESOURCE);
    }

    public static String sanitize(String text, String defaultValue) {
        if (text == null) {
            return defaultValue;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? defaultValue : normalized;
    }

    /**
     * Build workflow prefix with explicit output target.
     *
     * @param outputTarget {@code "manim"}, {@code "geogebra"}, or {@code null} for generic
     */
    public static String buildWorkflowPrefix(String stageLabel,
                                             String substepLabel,
                                             String targetTitle,
                                             String targetDescription,
                                             String outputTarget) {
        String workflowLabel;
        String targetLabel;

        if ("manim".equalsIgnoreCase(outputTarget)) {
            workflowLabel = "multi-stage Manim animation generation workflow";
            targetLabel = "Final animation target";
        } else if ("geogebra".equalsIgnoreCase(outputTarget)) {
            workflowLabel = "multi-stage GeoGebra construction generation workflow";
            targetLabel = "Final construction target";
        } else {
            workflowLabel = "multi-stage teaching content generation workflow";
            targetLabel = "Final target";
        }

        return "You are working inside a " + workflowLabel + ".\n"
                + "Current workflow stage: " + sanitize(stageLabel, "Unknown stage") + "\n"
                + "Current substep: " + sanitize(substepLabel, "Unknown substep") + "\n"
                + "Overall workflow: " + WORKFLOW_OVERVIEW + "\n"
                + targetLabel + ": " + sanitize(targetTitle, "Unknown target") + "\n"
                + "Final target description: "
                + sanitize(targetDescription, "No explicit target description is available yet.")
                + "\n"
                + "Keep the full target in mind, but perform only the responsibility of the current substep.\n\n";
    }

    public static String ensureManimSyntaxManual(String prompt) {
        String base = prompt == null ? "" : prompt;
        if (base.contains(ManimSyntaxManualHolder.VALUE)) {
            return base;
        }
        return base
                + "\n\nManim syntax reference manual:\n"
                + "Follow the guidance below whenever you generate or revise Manim code.\n\n"
                + ManimSyntaxManualHolder.VALUE;
    }

    public static String ensureManimStyleReference(String prompt) {
        String base = prompt == null ? "" : prompt;
        if (base.contains(ManimStyleReferenceHolder.VALUE)) {
            return base;
        }
        return base
                + "\n\nManim style reference:\n"
                + "Follow the guidance below whenever you assign storyboard-level colors or style properties.\n\n"
                + ManimStyleReferenceHolder.VALUE;
    }

    public static String ensureGeoGebraSyntaxManual(String prompt) {
        String base = prompt == null ? "" : prompt;
        if (base.contains(GeoGebraSyntaxManualHolder.VALUE)) {
            return base;
        }
        return base
                + "\n\nGeoGebra syntax reference manual:\n"
                + "Follow the guidance below whenever you generate or revise GeoGebra code.\n\n"
                + GeoGebraSyntaxManualHolder.VALUE;
    }

    public static String ensureGeoGebraStyleReference(String prompt) {
        String base = prompt == null ? "" : prompt;
        if (base.contains(GeoGebraStyleReferenceHolder.VALUE)) {
            return base;
        }
        return base
                + "\n\nGeoGebra style reference:\n"
                + "Follow the guidance below whenever you assign storyboard-level colors or style properties.\n\n"
                + GeoGebraStyleReferenceHolder.VALUE;
    }

    private static String loadPromptResource(String resourceName) {
        try (InputStream input = SystemPrompts.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Classpath resource not found: " + resourceName);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load classpath resource: " + resourceName, e);
        }
    }
}
