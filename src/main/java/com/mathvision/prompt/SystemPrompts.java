package com.mathvision.prompt;

import com.mathvision.util.GeoGebraValidationSupport;
import com.mathvision.util.ManimValidationSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared workflow prompt helpers and prompt-resource loading.
 */
public final class SystemPrompts {

    private static final String MANIM_SYNTAX_MANUAL_RESOURCE = "llm/manim_syntax_manual.md";
    private static final String MANIM_STYLE_REFERENCE_RESOURCE = "llm/manim_style_reference.md";
    private static final String MANIM_CONSTRAINT_MATRIX_RESOURCE =
            "llm/manim_constraint_matrix.md";
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

    /** Manim-specific layout and readability budget derived from production planning rules. */
    public static final String MANIM_LAYOUT_FRAME_RULES =
            "Keep important content within x[-6.5,6.5] and y[-3.5,3.5] whenever possible.\n"
                    + "Reserve a readable top title band and a bottom note band instead of packing the whole frame.\n"
                    + "Keep simultaneously active foreground elements around 5 to 6 unless the scene is explicitly comparison-heavy.\n"
                    + "Leave a meaningful empty zone for overlays, captions, or upcoming reveals.\n";

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
                    + "- For constrained motion, prefer explicit documented GeoGebra constructions such as `Point(path)`, `PointIn(region)`, `Intersect(...)`, `Reflect(...)`, `Midpoint(...)`, or slider-driven parameterizations with declared bounds.\n"
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

    /** Storyboard authoring rules for encoding geometry constraints that downstream stages must preserve. */
    public static final String GEOMETRY_CONSTRAINT_AUTHORING_RULES =
            "Storyboard geometry constraint authoring rules:\n"
                    + "- If an object is movable but constrained, keep `behavior` for dependency semantics and encode the motion/path/range constraint explicitly in `geometry_constraints`, `constraint_note`, `dependency_note`, `placement`, or `notes_for_codegen`.\n"
                    + "- When an object depends on another object's position, encode that dependency explicitly with `behavior`, `anchor_id`, and `dependency_note`.\n"
                    + "- When a geometric relationship must survive later layout fixes, record it explicitly in `geometry_constraints` and in object-level `constraint_note`.\n"
                    + "- Treat geometric relationships such as symmetry, reflection, equal length, equal angle, collinearity, intersection, perpendicularity, and shared-center motion as hard constraints, not optional style notes.\n"
                    + "- If a layout risks overflow, prefer planning a smaller or recentered whole construction rather than placing mathematically linked points independently near the edges.\n";

    /** High-contrast color rules to avoid pale-on-pale combinations. */
    public static final String HIGH_CONTRAST_COLOR_RULES =
            "Keep text, labels, strokes, and fills visually distinct from their background.\n"
                    + "Avoid low-contrast pairings such as yellow on white, white on light yellow, or similar pale-on-pale combinations.\n";

    /** High-contrast color rules formatted as bullet lines for direct prompt insertion. */
    public static final String HIGH_CONTRAST_COLOR_RULES_BULLETS =
            "- " + HIGH_CONTRAST_COLOR_RULES.replace("\n", "\n- ").trim() + "\n";

    /** Shared storytelling philosophy for all output targets. */
    public static final String NARRATIVE_PHILOSOPHY =
            "Teaching philosophy:\n"
                    + "- Treat every scene or step as a teaching moment: each should move the learner toward one clear insight.\n"
                    + "- Start from a hook, question, failed intuition, or contrast whenever it improves understanding.\n"
                    + "- Explain why before how when both cannot fit comfortably.\n"
                    + "- Put geometry or visual intuition before algebra whenever possible so formulas feel earned.\n"
                    + "- A problem storyboard may include observation, failed attempt, key insight, evidence, and conclusion beats or steps; it is not limited to raw solving steps.\n";

    /** Shared visual planning rules for all output targets. */
    public static final String VISUAL_PLANNING_RULES =
            "Visual planning rules:\n"
                    + "- One new idea per scene or step.\n"
                    + "- Prefer progressive disclosure: show the simplest readable state first, then add complexity.\n"
                    + "- Keep the same concept in the same region and color across scenes or steps unless the move itself teaches something.\n"
                    + "- Use color semantically: assign colors to concepts, not to arbitrary objects.\n"
                    + "- Prefer transform- or restyle-based continuity over replacing everything.\n"
                    + "- Decide intentionally whether a concept should animate or remain static; motion should clarify change, not add load.\n";

    /** Shared Manim motion and pacing rules. */
    public static final String MANIM_MOTION_AND_PACING_RULES =
            "Manim motion and pacing rules:\n"
                    + "- Write narration with visual beats in mind: what the learner hears should match what the learner sees.\n"
                    + "- Treat one beat as one small `self.play(...)` group or one stable visual hold.\n"
                    + "- After each important reveal, leave breathing room with `self.wait(...)` so the learner can read and absorb it.\n"
                    + "- Vary tempo: slower for core reveals, faster for supporting details, and a longer pause around the key insight.\n"
                    + "- Prefer the \"see, then hear\" timing pattern for major ideas.\n";

    /** Shared composition and empty-space rules for all output targets. */
    public static final String COMPOSITION_RULES =
            "Composition rules:\n"
                    + "- Maintain one clear focus per frame or view using size, color, brightness, or placement.\n"
                    + "- Apply the three-tier opacity hierarchy: primary focus at 1.0, contextual elements at 0.3–0.4, structural scaffolding (axes, grids) at 0.15.\n"
                    + "- Keep visual weight balanced across the frame instead of clustering everything on one side.\n"
                    + "- Preserve intentional empty space and a safe overlay zone; do not solve layout problems by piling overlays or opaque objects over the active geometry.\n"
                    + "- If the view becomes crowded, split the content, dim the old context, or remove temporary annotations instead of squeezing everything tighter.\n"
                    + "- When correcting out-of-bounds elements, reposition them with adequate clearance from every frame edge (minimum 0.5 units on all sides); never fix a boundary violation by placing objects flush against the edge.\n";

    /** Shared Manim text and readability rules. */
    public static final String MANIM_TEXT_AND_READABILITY_RULES =
            "Manim text and readability rules:\n"
                    + "- Use monospace fonts (e.g. Menlo, Courier New, DejaVu Sans Mono) for all `Text(...)` and `MarkupText(...)` content. Manim's Pango renderer produces broken kerning with proportional fonts.\n"
                    + "- Hard minimum `font_size=18` for any on-screen text.\n"
                    + "- Keep supporting text comfortably readable; avoid tiny labels and long edge-to-edge strings.\n"
                    + "- Use `buff=0.5` or larger on every `.to_edge()` call; values below 0.5 risk clipping.\n"
                    + "- After creating long text, check whether `text.width > config.frame_width - 1.0` and call `text.set_width(config.frame_width - 1.0)` if so.\n"
                    + "- MathTex and Tex default to WHITE text. When placing them inside a WHITE BackgroundRectangle or on any light-colored card, always set the text color to BLACK (or another dark color) explicitly with `.set_color(BLACK)`.\n"
                    + "- If text overlaps busy geometry, plan a background box or backstroke-style treatment.\n"
                    + "- Use screen-fixed overlays for explanatory text only when that text should stay independent of world motion.\n";

    /** Shared Manim animation-tool selection rules. */
    public static final String MANIM_ANIMATION_SELECTION_RULES =
            "Manim animation selection rules:\n"
                    + "- Use `Create`, `Write`, `FadeIn`, or `GrowFromCenter` for first appearance.\n"
                    + "- Use `Transform`, `ReplacementTransform`, or `FadeTransform` when the learner should see continuity between states.\n"
                    + "- Use `Indicate`, `Circumscribe`, `Flash`, or `ShowPassingFlash` to direct attention without changing the underlying object.\n"
                    + "- Use `always_redraw(...)`, `add_updater(...)`, or `ValueTracker(...)` for continuous dependencies.\n"
                    + "- Prefer `add_updater(...)` for simple position or color tracking (cheap). Use `always_redraw(...)` only when the mobject's structure or shape must be rebuilt each frame (expensive).\n"
                    + "- For a label that follows a moving object, use `add_updater` to reposition the existing label in-place (`label.add_updater(lambda m: m.next_to(anchor, UP))`). Use `always_redraw` only when the label's text content itself changes dynamically (e.g., live coordinates, counter values).\n"
                    + "- Use `FadeOut`, `Uncreate`, or `ShrinkToCenter` for temporary objects that have served their purpose.\n";

    /** Shared object lifecycle and storyboard contract rules for all output targets. */
    public static final String OBJECT_LIFECYCLE_RULES =
            "Storyboard and object-lifecycle rules:\n"
                    + "- Every learner-visible object that should appear in the scene or construction must be declared explicitly in the storyboard; do not rely on unstated inferred visuals.\n"
                    + "- If an object remains visible across beats or steps, keep the same visual identity instead of silently recreating it.\n"
                    + "- If an object depends on another object's motion, make the dependency explicit in storyboard fields and preserve it in code.\n"
                    + "- Temporary annotations, comparison aids, and helper overlays need an exit plan; once they have taught their point, remove or dim them.\n"
                    + "- End scenes or steps cleanly: use clean breaks, carry-forward anchors, or transition bridges intentionally rather than leaving accidental residue.\n";

    /** Shared Manim implementation and code-hygiene rules. */
    public static final String MANIM_CODE_HYGIENE_RULES =
            "Manim implementation rules:\n"
                    + "- Use the right collection type: prefer `Group(...)` for mixed text-and-shape collections and `VGroup(...)` for vectorized mobjects.\n"
                    + "- Use raw strings for LaTeX and keep `MathTex(...)` segments stable when matching transforms will be needed later.\n"
                    + "- Prefer helper builders, shared style constants, and stable layout helpers over scattered ad hoc coordinates.\n"
                    + "- Keep background color, palette meaning, and typography consistent across the full file.\n"
                    + "- Define shared color constants (BG, PRIMARY, SECONDARY, ACCENT) at the top of the file; never hardcode hex color strings inside scene methods.\n"
                    + "- Set `self.camera.background_color = BG` in every scene's `construct` method.\n"
                    + "- Use `ReplacementTransform(old, new)` when replacing visible text or mobjects; do not `Write` new content on top of old content without removing the old first.\n"
                    + "- After `Transform(A, B)`, variable `A` references the on-screen object while `B` is NOT on screen. Use `ReplacementTransform` when you need to reference `B` afterward.\n"
                    + "- Never animate a mobject that has not been added to the scene.\n"
                    + "- When an updater would fight an animation, call `mob.suspend_updating()` before and `mob.resume_updating()` after the `self.play()` call.\n"
                    + "- Add `self.add_subcaption(...)` or `subcaption=` on every significant animation for accessibility and narration sync, not just major reveals.\n"
                    + "- Use subcaptions or subtitle-ready beats for major reveals when narration alignment matters.\n";

    /** Shared Manim review checklist. */
    public static final String MANIM_REVIEW_CHECKLIST =
            "Manim review checklist:\n"
                    + "- Does each scene have one clear focus and enough empty space?\n"
                    + "- Are major reveals given enough on-screen time and `self.wait(...)` breathing room?\n"
                    + "- Are text size, contrast, edge margins, and overlap risks acceptable?\n"
                    + "- Are dynamic dependencies implemented continuously rather than placed once?\n"
                    + "- Are animation targets guaranteed non-empty and stable at animation start?\n"
                    + "- Are temporary annotations cleaned up instead of lingering as clutter?\n"
                    + "- Do transforms, replacements, subtitles, and camera choices support teaching clarity rather than just motion?\n";

    /** Shared guardrails for common runtime render failures. */
    public static final String COMMON_RENDER_FAILURE_GUARDRAILS =
            "Common render-failure guardrails:\n"
                    + "- Never animate (`Create`, `FadeOut`, `Transform`, `ReplacementTransform`) a conditionally empty redraw result.\n"
                    + "- Avoid long-lived `always_redraw(...)` branches like `if cond else VMobject()` when that object may later be animated directly.\n"
                    + "- Prefer stable base mobjects with visibility/style control (`set_opacity`, `set_stroke`, `become`) over swapping to empty placeholders.\n"
                    + "- Before cleanup animations, freeze or remove related updaters and confirm targets are non-empty and still attached to intended geometry.\n"
                    + "- Ensure animation targets are present in scene and have geometric points before transform/fade operations.\n"
                    + "- Never call `VMobject.set_points()`; use `set_points_as_corners()` or `set_points_smoothly()` instead. Raw `set_points` bypasses bezier alignment and causes crashes during animation.\n";

    /** Angle marker best practices for Manim. */
    public static final String ANGLE_MARKER_RULES =
            "For angle markers, prefer `Angle(...)` built from two lines/rays sharing the true vertex instead of hand-written `Arc(start_angle=..., angle=...)` formulas.\n"
                    + "When an angle is measured against a normal, helper line, or moving segment, construct both rays from the shared point inside `always_redraw(...)`.\n"
                    + "If the intended angle sector could be ambiguous, explicitly set `quadrant=...`; if the storyboard intends the interior/smaller angle, explicitly keep `other_angle=False`.\n";

    /** Manim typography scale for consistent text sizing. */
    public static final String MANIM_TYPOGRAPHY_SCALE =
            "Manim typography scale:\n"
                    + "- Title: font_size=48\n"
                    + "- Heading: font_size=36\n"
                    + "- Body / explanatory text: font_size=30\n"
                    + "- Label / annotation: font_size=24\n"
                    + "- Caption / fine print: font_size=20\n"
                    + "- Hard minimum: font_size=18 — anything smaller blurs at draft quality and is barely legible at production quality.\n";

    /** Opacity hierarchy for visual layering, applicable to all output targets. */
    public static final String OPACITY_LEVELS =
            "Opacity hierarchy:\n"
                    + "- Primary focus elements: opacity 1.0\n"
                    + "- Contextual / previously-introduced elements: opacity 0.3–0.4\n"
                    + "- Structural scaffolding (axes, grids, construction lines): opacity 0.15\n"
                    + "- Never show everything at full brightness simultaneously.\n";

    /** Manim animation timing reference table. */
    public static final String MANIM_TIMING_REFERENCE =
            "Manim animation timing reference:\n"
                    + "- Title / intro appear: run_time=1.5s, self.wait(1.0)\n"
                    + "- Key equation reveal: run_time=2.0s, self.wait(2.0)\n"
                    + "- Transform / morph: run_time=1.5s, self.wait(1.5)\n"
                    + "- Supporting label: run_time=0.8s, self.wait(0.5)\n"
                    + "- FadeOut cleanup: run_time=0.5s, self.wait(0.3)\n"
                    + "- \"Aha moment\" reveal: run_time=2.5s, self.wait(3.0)\n"
                    + "Treat these as defaults; adjust when the storyboard explicitly calls for different pacing.\n";

    /** Manim scene transition rules. */
    public static final String MANIM_SCENE_TRANSITION_RULES =
            "Manim scene transition rules:\n"
                    + "- End every scene with a clean exit: `self.play(FadeOut(Group(*self.mobjects)), run_time=0.5)` followed by `self.wait(0.3)`.\n"
                    + "- Never hard-cut between scenes; always animate the transition.\n"
                    + "- Three transition types: clean break (fade all, pause), carry-forward (keep one anchor, fade rest), transform bridge (end with shape, start next by transforming it).\n";

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

    /** Manim API whitelist rules sourced from the attached syntax manual. */
    public static final String MANIM_MANUAL_ONLY_RULES = buildManimManualOnlyRules();

    /** GeoGebra command whitelist rules sourced from the attached syntax manual. */
    public static final String GEOGEBRA_MANUAL_ONLY_RULES = buildGeoGebraManualOnlyRules();

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
                    + "A = Point({0, 0})\n"
                    + "SetFixed(A, true)\n"
                    + "B = Point({4, 0})\n"
                    + "SetFixed(B, true)\n"
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
                    + "- Treat the storyboard as a complete learner-visible visual specification; do not invent unstated objects or implied labels.\n"
                    + "- If an id persists, keep or transform the same mobject instead of redrawing it.\n"
                    + "- When `content`, `dependency_note`, or related fields mention another object, treat those mentions as object ids only rather than as repeated type declarations.\n"
                    + "- If a scene uses `scene_mode = 3d`, use `ThreeDScene`, follow `camera_plan`, and judge layout in projected screen space.\n"
                    + "- Use `screen_overlay_plan` with `add_fixed_in_frame_mobjects` for fixed explanatory text.\n"
                    + "- Respect `safe_area_plan` and dynamic attachment for labels on moving objects.\n"
                    + "- Read `behavior`, `anchor_id`, and `dependency_note` literally: if an object follows a moving anchor, implement it with `always_redraw(...)` or an updater.\n"
                    + "- Preserve scene beats, scene exits, and overlay zones from the storyboard instead of compressing everything into one crowded final frame.\n"
                    + MANIM_MANUAL_ONLY_RULES
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
                    + GEOGEBRA_MANUAL_ONLY_RULES
                    + "- Prefer GeoGebra's native labels for named geometric objects. If an object is named `A`, `l`, `c`, `AB`, or similar, use that object itself as the visible label instead of creating a separate label helper object.\n"
                    + "- Treat storyboard object ids as the naming source for generated GeoGebra variables. Preserve those ids in code, and when you must introduce a helper name, use concise camelCase or math-style identifiers.\n"
                    + GEOGEBRA_NAMING_RULES
                    + "- This naming convention applies to all generated geometric objects and helpers.\n"
                    + "- Create a separate label/text object only when the visible text is not the object's own native label, such as overlays, formulas, counters, captions, or explanatory annotations.\n"
                    + "- If the storyboard contains a redundant geometry-label pair, prefer keeping the geometry object and dropping the extra label object in the generated GeoGebra commands.\n"
                    + "- Choose readable coordinates and label placement that respect `layout_goal`, `placement`, and `safe_area_plan`.\n"
                    + HIGH_CONTRAST_COLOR_RULES_BULLETS /*
                    + "- For angle markers, use only `Angle(B, vertex, C)` with `SetFilling`; never `CircularArc`. The angle sweeps counterclockwise from ray(vertex→B) to ray(vertex→C), so place the starting-ray point first: e.g. for the small angle between a rightward horizontal and an upper-left segment at vertex P, use `Angle((x(P)+1,0), P, A)` (CCW from right to upper-left = small angle above line).\n";

    // ========================================================================

                    */
                    + "- If the storyboard asks for an effect that would require an undocumented command, preserve the core geometry with documented commands only and do not invent syntax.\n";

    private static final String WORKFLOW_OVERVIEW =
            "Stage 0 Exploration -> Stage 1a Mathematical Enrichment -> Stage 1b Visual Design"
                    + " -> Stage 1c Narrative Composition -> Stage 2 Code Generation"
                    + " -> Stage 3 Code Evaluation -> Stage 4 Code Rendering"
                    + " -> Stage 5 Scene Evaluation"
                    + " (Stages 2–5 may each route to the shared Code Fix node for iterative repair)";

    private SystemPrompts() {}

    private static final class ManimSyntaxManualHolder {
        private static final String VALUE = loadPromptResource(MANIM_SYNTAX_MANUAL_RESOURCE);
    }

    private static final class ManimStyleReferenceHolder {
        private static final String VALUE = loadPromptResource(MANIM_STYLE_REFERENCE_RESOURCE);
    }

    private static final class ManimConstraintMatrixHolder {
        private static final String VALUE = loadPromptResource(MANIM_CONSTRAINT_MATRIX_RESOURCE);
    }

    private static final class GeoGebraSyntaxManualHolder {
        private static final String VALUE = loadPromptResource(GEOGEBRA_SYNTAX_MANUAL_RESOURCE);
    }

    private static final class GeoGebraStyleReferenceHolder {
        private static final String VALUE = loadPromptResource(GEOGEBRA_STYLE_REFERENCE_RESOURCE);
    }

    private static String buildManimManualOnlyRules() {
        return "Treat the attached Manim syntax manual as the authoritative whitelist.\n"
                + "Use only classes, functions, methods, arguments, scene patterns, and code forms documented there.\n"
                + "Never invent Manim APIs, guessed helper methods, unsupported keyword arguments, or private/internal shortcuts.\n"
                + "If the current code uses an undocumented or unstable API, replace it with a documented stable equivalent while preserving the scene intent.\n"
                + "If a desired effect is not covered by the manual, simplify it with documented Manim constructs rather than guessing syntax.\n"
                + "Documented instance methods (snake_case): `"
                + String.join("`, `", ManimValidationSupport.documentedInstanceMethodNames())
                + "`.\n";
    }

    private static String buildGeoGebraManualOnlyRules() {
        return "Treat the attached GeoGebra syntax manual as the authoritative whitelist.\n"
                + "Use only command names and syntax forms documented there.\n"
                + "Never invent aliases, tool names, guessed overloads, shorthand assignments, or undocumented commands.\n"
                + "If the current script contains an undocumented command, replace it with a documented equivalent or remove the unsupported decoration while preserving the construction.\n"
                + "If a requested effect is not covered by the manual, re-express it with documented commands or omit that effect rather than guessing syntax.\n"
                + "Documented construction commands: `"
                + String.join("`, `", GeoGebraValidationSupport.documentedConstructionCommandNames())
                + "`.\n"
                + "Documented scripting commands: `"
                + String.join("`, `", GeoGebraValidationSupport.documentedScriptingCommandNames())
                + "`.\n";
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

    public static String manimCoverageNote() {
        return "Manim source-of-truth is tracked in the internal constraint matrix resource.\n"
                + "Use the shared Manim prompt fragments as the executable form of that matrix.\n";
    }

    public static String getManimConstraintMatrix() {
        return ManimConstraintMatrixHolder.VALUE;
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
