package com.mathvision.prompt;

import com.mathvision.util.GeoGebraValidationSupport;
import com.mathvision.util.ManimValidationSupport;
import com.mathvision.util.StoryboardConstraintCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared workflow prompt constants and resource-loading helpers.
 */
public final class SystemPrompts {

    // ========================================================================
    // Resource paths
    // ========================================================================

    private static final String MANIM_SYNTAX_MANUAL_RESOURCE = "llm/manim_syntax_manual.md";
    private static final String MANIM_STYLE_REFERENCE_RESOURCE = "llm/manim_style_reference.md";
    private static final String GEOGEBRA_SYNTAX_MANUAL_RESOURCE = "llm/geogebra_syntax_manual.md";
    private static final String GEOGEBRA_STYLE_REFERENCE_RESOURCE = "llm/geogebra_style_reference.md";

    // ========================================================================
    // Storyboard field guides
    //
    // Organized by semantic scope so each pipeline stage includes only the
    // guides it needs:
    //   OBJECT_SEMANTICS - object identity, kind, content, dependency, style
    //   SCENE_STRUCTURE  - scene metadata, object lifecycle, actions
    //   SCENE_LAYOUT     - spatial layout, constraints, camera, and intent
    //   MANIM            - all three combined (for Manim code generation/evaluation)
    //   GEOGEBRA         - GeoGebra-specific field interpretations
    //   MANIM_REPAIR     - Manim field semantics for repair passes
    //   GEOGEBRA_REPAIR  - GeoGebra-specific repair interpretations
    // ========================================================================

    /** Object-level fields: identity, kind, content, dependency, style, and placement. */
    public static final String STORYBOARD_FIELD_GUIDE_OBJECT_SEMANTICS =
            "How to interpret the storyboard fields:\n"
                    + "- `id`: unique object identifier used across scenes, registry, and actions.\n"
                    + "- `kind`: geometric or visual type - point, line, segment, ray, circle, polygon, arc, angle_marker, text, etc. Determines the construction or rendering primitive.\n"
                    + "- `content`: mathematical description or display text (e.g. \"A(0, 3)\" or \"x^2 + y^2 = r^2\"). For `text` objects this is the visible string; for geometry objects it is a label or coordinate hint.\n"
                    + "- `constraints`: machine-readable object-level semantic contract. Each entry has `domain`, `relation`, `refs`, optional `parameters`, `strength`, and `reason`; this is the only source for placement, construction, constraint, metric, marker, attachment, motion, layout, visibility, style, and lifecycle semantics.\n"
                    + "- `placement`: structured scene-level visual placement patch with `coordinate_space` plus optional x/y/z `value` or `min/max`; use it for initial layout or allowed visual range, not as the source of geometric dependencies.\n"
                    + "- `style`: optional single typed object of visual properties such as color, fill_color, stroke_color, opacity, stroke_width, line_style, font_size, padding, and z_index. Do not invent custom style keys. Style is pure visual styling for this object only; create separate constrained objects for labels, badges, helper outlines, cards, or callouts that have their own identity.\n";

    /** Scene-structure fields: scene metadata, object lifecycle, and actions. */
    public static final String STORYBOARD_FIELD_GUIDE_SCENE_STRUCTURE =
            "- `scene_id`: unique identifier for the scene; used for cross-referencing and code organization.\n"
                    + "- `title`, `narration`: teaching purpose; help choose clear animation structure and pacing.\n"
                    + "- `duration_seconds`: intended scene duration in seconds; guide pacing and animation timing.\n"
                    + "- `scene_mode`: `2d` (default) or `3d`; determines scene class and camera setup.\n"
                    + "- `entering_objects`: scene patches for newly entering objects; raw storyboard entries should contain `id` plus optional `placement`/`style` only.\n"
                    + "- `persistent_objects`: scene patches for carried objects; raw storyboard entries should contain `id` plus optional changed `placement`/`style` only.\n"
                    + "- `exiting_objects`: id-only entries for objects that should explicitly leave the scene.\n"
                    + "- `actions`: the main execution plan; respect their order, targets, and visible intent.\n"
                    + "- `object_registry`: canonical global object definitions; keep stable identity, kind, content, style, and structured constraints here, not scene-specific placement.\n"
                    + "- Scene `constraints`: machine-readable scene-level invariants and relation groups.\n"
                    + "- `notes_for_codegen`: scene-level hard implementation constraints for downstream code generation and repair, including concrete motion ranges, endpoints, lifecycle, visibility, palette, transform, and layout instructions. Follow them exactly unless they conflict with runtime validity, backend support, or stronger geometric constraints; when exact wording is unsupported, preserve the same constraint intent with a documented equivalent.\n"
                    + "- `continuity_plan`, `global_visual_rules`: global constraints that shape the whole file.\n";

    /** Scene-layout fields: spatial composition, geometric constraints, camera, and intent. */
    public static final String STORYBOARD_FIELD_GUIDE_SCENE_LAYOUT =
            "- `goal`: what the scene must accomplish for understanding or solution progress.\n"
                    + "- `layout_goal`: intended screen composition and relative placement of major elements.\n"
                    + "- `constraints`: scene-level structured invariants such as reflections, symmetry, collinearity, intersections, equal lengths, equal-angle groups, motion limits, and lifecycle requirements.\n"
                    + "- `safe_area_plan`: how important content stays readable and inside the safe frame.\n"
                    + "- `screen_overlay_plan`: text or formulas that stay fixed in screen space.\n"
                    + "- `camera_anchor`, `camera_plan`: camera focus and behavior.\n";

    /** Full Manim storyboard field guide combining all sections. */
    public static final String STORYBOARD_FIELD_GUIDE_MANIM =
            "How to interpret the storyboard fields:\n"
                    + STORYBOARD_FIELD_GUIDE_OBJECT_SEMANTICS.replace("How to interpret the storyboard fields:\n", "")
                    + STORYBOARD_FIELD_GUIDE_SCENE_STRUCTURE
                    + STORYBOARD_FIELD_GUIDE_SCENE_LAYOUT;

    /** Field guide for GeoGebra code generation. */
    public static final String STORYBOARD_FIELD_GUIDE_GEOGEBRA =
            "How to interpret the storyboard fields:\n"
                    + "- `kind`: determines the GeoGebra construction primitive (point -> named coordinate or path-point, line -> Line/Segment/Ray, circle -> Circle, text -> Text or native label, etc.).\n"
                    + "- `content`: display text or coordinate hint; for text objects this is the visible string, for geometry it is a label or math expression.\n"
                    + "- `style`: optional single typed object of visual properties (color, fill_color, stroke_color, stroke_width, line_style, opacity, label_visible, etc.) to apply via SetColor, SetFilling, SetLineThickness, SetLineStyle, etc.\n"
                    + "- `entering_objects`: scene patches for newly entering objects; use `id` plus optional `placement`/`style` changes.\n"
                    + "- `persistent_objects`: scene patches for carried objects; use `id` plus optional changed `placement`/`style`.\n"
                    + "- `exiting_objects`: id-only entries that may be translated into hidden helper objects or omitted if persistent visibility would cause clutter.\n"
                    + "- `actions`: state changes; convert into construction order, visibility changes, highlight states, or helper toggles rather than literal animation.\n"
                    + "- `placement`, `layout_goal`, `safe_area_plan`, `screen_overlay_plan`: guide readable coordinates, allowed ranges, label placement, and visibility choices.\n"
                    + "- Object and scene `constraints`: preserve dependency relationships, geometric invariants, attachments, motion limits, lifecycle requirements, and measured constructions with dependency-safe GeoGebra commands.\n"
                    + "- For constrained motion, prefer explicit documented GeoGebra constructions such as `Point(path)`, `PointIn(region)`, `Intersect(...)`, `Reflect(...)`, `Midpoint(...)`, or slider-driven parameterizations with declared bounds.\n"
                    + "- When a point should remain on a line, segment, circle, or similar object, the generated command should visibly encode that incidence relation.\n";

    /** Field guide for Manim scene evaluation/repair pass. */
    public static final String STORYBOARD_FIELD_GUIDE_MANIM_REPAIR =
            "Storyboard field guide for this repair pass:\n"
                    + "- `goal` and `layout_goal`: preserve what the scene is trying to teach and how the frame should be composed.\n"
                    + "- `safe_area_plan` and `screen_overlay_plan`: use these first when fixing overlap and offscreen issues.\n"
                    + "- Scene/object `constraints`: treat hard and repair_hard entries as the primary geometric, attachment, motion, lifecycle, and construction invariants.\n"
                    + "- `kind` and `content`: preserve the geometric or textual identity of each object when repositioning.\n"
                    + "- `style`: preserve visual hierarchy and emphasis when adjusting layout.\n"
                    + "- `persistent_objects`, `exiting_objects`, and `actions`: preserve continuity and scene flow instead of redrawing the construction arbitrarily.\n"
                    + "- If a reported object is a reflection, midpoint, foot, or intersection, recompute it from its source construction instead of moving it freely.\n";

    /** Field guide for GeoGebra scene evaluation/repair pass. */
    public static final String STORYBOARD_FIELD_GUIDE_GEOGEBRA_REPAIR =
            "Storyboard field guide for this GeoGebra repair pass:\n"
                    + "- `goal` and `layout_goal`: preserve what the scene is trying to teach and how the construction should be laid out.\n"
                    + "- The initial GeoGebra visible coordinate window is part of the output contract; do not rely on user zooming or panning to make the construction readable.\n"
                    + "- Fix out-of-bounds, underfilled, clustered, or overlapping layouts by moving/scaling/recentering whole constrained groups while preserving the construction.\n"
                    + "- Scene/object `constraints`: treat hard and repair_hard entries as the primary geometric, attachment, motion, lifecycle, and construction invariants.\n"
                    + "- `kind` and `content`: preserve the geometric or textual identity of each object when repositioning.\n"
                    + "- `style`: preserve visual properties (color, thickness, dash) when adjusting layout.\n"
                    + "- `persistent_objects`, `exiting_objects`, and `actions`: preserve object visibility progression instead of rewriting the construction arbitrarily.\n"
                    + "- If a reported object is a reflection, midpoint, foot, or intersection, keep it defined from its source objects via GeoGebra dependency commands.\n";

    /** Shared element-selection model for stages that consume a validated storyboard. */
    public static final String STORYBOARD_ELEMENT_SELECTION_RULES =
            "Storyboard element selection rules:\n"
                    + "- Treat storyboard objects as candidate teaching elements, not a mandatory one-for-one rendering checklist.\n"
                    + "- Create or keep elements that carry core teaching reasoning, hard geometry, dependency relationships, conclusion evidence, or the current narration focus.\n"
                    + "- Omit, merge, dim, or replace elements that are decorative, redundant, clutter-causing, naturally expressed by existing objects, or not helpful for the current teaching beat.\n"
                    + "- Omitting an object must not break the semantics of structured `constraints`, `notes_for_codegen`, or `actions.targets`.\n"
                    + "- If an omitted object is referenced by an action target, preserve that action's teaching intent through an equivalent existing object, style change, label, caption, or dependency-safe construction.\n"
                    + "- Do not create learner-visible objects that are not declared in the storyboard. If a label, caption, badge, marker, helper overlay, or explanatory text is useful enough to appear on screen, it must already have a storyboard id.\n"
                    + "- Backend-only helper variables or invisible helper mobjects/commands may be used only when required by the API or geometry calculation; they must not be shown, labeled, animated, highlighted, or treated as teaching elements.\n";

    /** Shared authority model for stages that consume a validated storyboard. */
    public static final String STORYBOARD_AUTHORITY_RULES =
            "Storyboard authority rules:\n"
                    + "- Treat `object_registry` as the canonical authority for object identity, kind, content, style, and hard geometric meaning.\n"
                    + "- Treat scene `entering_objects`, `persistent_objects`, and `exiting_objects` as per-scene state patches: their `placement`, `style`, and visibility describe the momentary visual state for that scene, not the object's full semantic definition.\n"
                    + "- Treat structured `constraints` and `notes_for_codegen` as hard semantic requirements. Constraints are the only source for geometry, dependency, attachment, motion, measurement, lifecycle, and construction semantics.\n"
                    + "- Use scene-level `placement.x/y/z.value`, `min`, and `max` as preferred visual-state coordinates for non-derived objects. If they cause offscreen, overlap, poor readability, or conflict with rendered evidence, adjust them minimally or move/scale the whole constrained group while preserving structured constraints.\n"
                    + "- For dependency-driven objects, compute or attach them from their source objects according to structured `constraints` refs and catalog relation semantics.\n"
                    + "- Treat scene order, action order, narration, layout_goal, safe_area_plan, screen_overlay_plan, and camera_plan as planning guidance for presentation, continuity, and readability; adapt them when runtime correctness or a clearer implementation requires it. Do not adapt away explicit `notes_for_codegen` constraints unless they are unsupported or contradictory.\n"
                    + STORYBOARD_ELEMENT_SELECTION_RULES
                    + "- Do not treat backend-specific notes, unsupported API names, undocumented commands, or purely decorative effects as hard requirements when they conflict with the active backend manual or runtime correctness.\n"
                    + "- When a requested effect is unsupported, preserve the same teaching intent with documented backend features; omit only non-essential decoration that cannot be implemented safely.\n"
                    + "- If storyboard placement conflicts with safe-area requirements, rendered evidence, or geometric correctness, preserve the semantic construction and repair the layout instead of blindly copying the bad placement.\n";

    /** Shared authority model for code repair stages. */
    public static final String STORYBOARD_REPAIR_AUTHORITY_RULES =
            "Use the storyboard JSON as semantic repair context, not as an instruction to preserve broken implementation details.\n"
                    + STORYBOARD_AUTHORITY_RULES
                    + "Do not re-add non-essential storyboard elements merely to match the storyboard. When fixing overlap, offscreen, or clutter, prefer removing, merging, dimming, or simplifying non-essential elements before moving essential teaching evidence.\n";

    /** Shared reference model for later stages that should not be constrained by the storyboard. */
    public static final String STORYBOARD_REFERENCE_RULES =
            "Storyboard reference rules:\n"
                    + "- Treat storyboard JSON as helpful reference context for the intended topic, prior scene plan, object names, and possible teaching ideas, not as a strict semantic authority.\n"
                    + "- When you use storyboard semantics, consider object_registry constraints together with scene patch placement/style details; scene patches are useful visual-state guidance for the current scene.\n"
                    + "- Use scene-level `placement.x/y/z.value`, `min`, and `max` as preferred layout coordinates for non-derived objects, but adjust them when needed for safe-area, readability, rendered evidence, or internal geometric consistency.\n"
                    + "- Do not block, rewrite, or over-constrain code solely because it omits, merges, renames, simplifies, or reorders storyboard details when the result is runnable, clear, and aligned with the overall user request.\n"
                    + "- Use storyboard geometry, constraints, and placements as hints. Preserve them when they are already implemented consistently or when doing so is low-risk, but runtime correctness, visual clarity, and internally consistent code take precedence.\n"
                    + "- If storyboard details conflict with safer code, rendered evidence, backend limitations, or a clearer implementation, choose a coherent implementation and keep object names, coordinates, dependencies, and layout internally consistent.\n";

    /** Shared reference model for code repair stages that should not strictly enforce storyboard details. */
    public static final String STORYBOARD_REPAIR_REFERENCE_RULES =
            "Use the storyboard JSON as optional repair context, not as an instruction to preserve exact storyboard details or broken implementation details.\n"
                    + STORYBOARD_REFERENCE_RULES
                    + "Do not re-add non-essential storyboard elements merely to match the storyboard. When fixing overlap, offscreen, runtime errors, or clutter, prefer simplifying the implementation and keeping the resulting scene coherent.\n";

    // ========================================================================
    // Geometry constraint rules
    // ========================================================================

    /** Geometry constraint preservation rules. */
    public static final String GEOMETRY_CONSTRAINT_RULES =
            "Preserve storyboard geometric invariants such as symmetry, reflection, collinearity, equal distances, and intersection definitions.\n"
                    + "For reflected points, intersections, midpoints, feet of perpendiculars, or similar derived geometry, compute them from source objects instead of assigning unrelated coordinates.\n"
                    + "If layout is unsafe, prefer moving/scaling whole constrained groups or recentering, not editing derived coordinates independently.\n";

    /** Storyboard authoring rules for encoding geometry constraints that downstream stages must preserve. */
    public static final String GEOMETRY_CONSTRAINT_AUTHORING_RULES =
            "Storyboard geometry constraint authoring rules:\n"
                    + "- If an object is movable but constrained, encode the motion/path/range constraint explicitly in structured `constraints`, structured `placement`, or `notes_for_codegen`.\n"
                    + "- When an object depends on another object's position, encode that dependency explicitly with a structured `constraints` entry whose refs name the owner and source objects.\n"
                    + "- When a geometric relationship must survive later layout fixes, record it explicitly in scene/object `constraints`.\n"
                    + "- CRITICAL: Each constraint MUST use the exact domain, relation name, ref roles, and parameter names from the catalog below. Do NOT invent your own role names (no seg1/seg2, line1/line2, ray/support, markers, phase, etc.).\n"
                    + "- For ref groups with alternatives shown as 'one of a/b/c', always use the FIRST listed name (the canonical form).\n"
                    + "- OBJECT-level constraints must include the owner object id in refs.\n"
                    + "- Place each OBJECT-level constraint under the object named by its catalog owner ref role. For example, `point_at` refs {\"point\":\"lLeft\"} belongs under object `lLeft`, not under a parent line object `l`; `label_for` refs {\"label\":\"labelA\",\"anchor\":\"A\"} belongs under object `labelA`, not under point `A`.\n"
                    + "- Parameters must contain only non-object values (strings, numbers, booleans); put object references in refs, NEVER in parameters.\n"
                    + "- Refs values: use a single string for one object id, or an array of strings for multiple ids (e.g. {\"members\": [\"segAB\", \"segCD\"]}).\n"
                    + "- Use `lies_on` only when the point is exactly on the referenced support/path. Do NOT use it for above/below/left/right side placement.\n"
                    + "- Use `on_side_of` for absolute side constraints such as a point above or below a line; use `same_side_of` or `opposite_side_of` only for relative side relationships between multiple objects.\n"
                    + "\n"
                    + StoryboardConstraintCatalog.detailedCatalogSummary()
                    + "\n"
                    + "- Treat geometric relationships such as symmetry, reflection, equal length, equal angle, collinearity, intersection, perpendicularity, and shared-center motion as hard constraints, not optional style notes.\n"
                    + "- If a layout risks overflow, prefer planning a smaller or recentered whole construction rather than placing mathematically linked points independently near the edges.\n";

    /** Storyboard authoring rules for geometric markers whose visual side/sector matters. */
    public static final String GEOMETRIC_MARKER_AUTHORING_RULES =
            "Geometric marker authoring rules:\n"
                    + "- For angle markers, arcs, right-angle marks, braces, ticks, and similar derived annotations, the storyboard must define the geometry they measure, not just their visual placement.\n"
                    + "- For any angle or arc that represents an angle, the structured marker constraint refs must include the marker, vertex/anchor, ordered start boundary, and ordered end boundary source objects.\n"
                    + "- Also add a structured `constraints` entry with `domain=marker`, `relation=angle_between`, `arc_sweep`, or `right_angle_at`, `refs` naming marker/vertex or center/anchor plus ordered start_boundary/end_boundary, and `parameters` naming sector, direction, and side_of_reference when relevant.\n"
                    + "- For any visual arc drawn from one point/ray to another, say explicitly where the arc starts and where it ends, for example `arc at P from ray P->A to normal ray P->N` or `arc from point U to point V on circle c`.\n"
                    + "- `constraints[].parameters` must say whether the displayed sector is the smaller/interior angle, a reflex/exterior angle, a directed angle, clockwise/counterclockwise sweep, or a specific side of a reference line or normal.\n"
                    + "- Label clearance and visibility are layout constraints only; never replace sector geometry with vague wording such as `quadrant chosen to stay in view` unless the measured sector is also defined.\n"
                    + "- If an angle is measured against a normal or perpendicular, state which normal ray or side is used when the side matters for the teaching point.\n"
                    + "- If equal angles are shown, define both angle markers symmetrically from their source rays so downstream code can preserve equality without guessing a quadrant.\n"
                    + "- Every boundary line referenced in an angle constraint must pass through the angle vertex. A segment passes through the vertex when the vertex is one of its endpoints in a structured connector constraint. A perpendicular or normal must be constructed at the vertex, not at some other point.\n"
                    + "- Common error: referencing a perpendicular from a different point as the angle boundary. For example, a perpendicular from A to line l is NOT the normal at point P_min unless P_min lies on that perpendicular. When the angle vertex is P_min, use a normal at P_min instead.\n";

    // ========================================================================
    // Visual design rules (shared across output targets)
    // ========================================================================

    /** Layout frame rules: safe area bounds and element count guidance. */
    public static final String LAYOUT_FRAME_RULES =
            "Keep important content within x[-7,7] and y[-4,4].\n"
                    + "Leave about 1 unit of edge margin.\n"
                    + "Usually keep each step to about 7 to 10 main visual elements unless several are quiet carry-over context.\n";

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
                    + "- Use color semantically: assign colors to concepts, not to arbitrary objects, and keep each color's meaning consistent across the storyboard.\n"
                    + "- Prefer transform- or restyle-based continuity over replacing everything.\n"
                    + "- Decide intentionally whether a concept should animate or remain static; motion should clarify change, not add load.\n";

    /** Shared composition and empty-space rules for all output targets. */
    public static final String COMPOSITION_RULES =
            "Composition rules:\n"
                    + "- Maintain one clear focus per frame or view using size, color, brightness, or placement.\n"
                    + "- Apply the three-tier opacity hierarchy: primary focus at 1.0, contextual elements at 0.3-0.4, structural scaffolding (axes, grids) at 0.15.\n"
                    + "- Plan where the eye should look first, what remains as dim context, and what area stays open for overlays or later reveals.\n"
                    + "- Keep visual weight balanced across the frame instead of clustering everything on one side.\n"
                    + "- Preserve intentional empty space and a safe overlay zone; do not solve layout problems by piling overlays or opaque objects over the active geometry.\n"
                    + "- Place formulas near edges, not over the main geometry.\n"
                    + "- If the view becomes crowded, reduce cognitive load by choosing among splitting content, dimming context, grouping, scaling, repositioning, or exiting objects. Base the choice on whether each object will help upcoming reasoning; exiting is allowed, but persistence is not automatically better.\n"
                    + "- When correcting out-of-bounds elements, reposition them with adequate clearance from every frame edge (minimum 0.5 units on all sides); never fix a boundary violation by placing objects flush against the edge.\n"
                    + "- When a derived object (reflection, projection, intersection, etc.) extends outside the frame, do NOT change its placement directly - it is computed from its source objects at render time. Instead, trace the structured constraints in object_registry to identify the upstream source object(s) and adjust their coordinates so the derived result lands inside the frame. For example, if a reflected point B' is offscreen because it mirrors B across line l, move B closer to l or shift l itself; never override B' with an arbitrary coordinate that contradicts its geometric definition.\n";

    /** Backend-neutral color syntax and contrast rules. */
    public static final String COLOR_FORMAT_RULES =
            "Use 6-digit hex colors only (`#RRGGBB`) in storyboard style properties; do not use named colors, fixed color whitelists, 8-digit hex, RGB strings, CSS colors, gradients, shadows, blur, glow, or alpha embedded in the color.\n"
                    + "Represent transparency separately with `opacity`, `fill_opacity`, or `stroke_opacity` fields.\n"
                    + "Keep text, labels, strokes, and fills visually distinct from their background.\n"
                    + "Avoid low-contrast pairings such as yellow on white, white on light yellow, dark blue on black, or similar low-contrast combinations.\n";

    /** Backward-compatible alias for shared color syntax and contrast rules. */
    public static final String HIGH_CONTRAST_COLOR_RULES = COLOR_FORMAT_RULES;

    /** Color syntax and contrast rules formatted as bullet lines for direct prompt insertion. */
    public static final String COLOR_FORMAT_RULES_BULLETS =
            "- " + COLOR_FORMAT_RULES.replace("\n", "\n- ").trim() + "\n";

    /** Backward-compatible alias for shared color syntax and contrast rules. */
    public static final String HIGH_CONTRAST_COLOR_RULES_BULLETS = COLOR_FORMAT_RULES_BULLETS;

    public static final String GEOGEBRA_COLOR_RULES =
            "GeoGebra uses a white background by default (`#FFFFFF`).\n"
                    + "Non-text foreground colors must contrast against the default white background `#FFFFFF` at ratio >= 3.0.\n"
                    + "Text, titles, formulas, labels, and callouts must contrast at ratio >= 4.5 against their own background color; if no background is defined, use `#FFFFFF` as the background.\n";

    public static final String GEOGEBRA_COLOR_RULES_BULLETS =
            "- " + GEOGEBRA_COLOR_RULES.replace("\n", "\n- ").trim() + "\n";

    public static final String MANIM_COLOR_RULES =
            "Manim uses a black background by default (`#000000`).\n"
                    + "Do not use Manim named color constants in storyboard JSON.\n"
                    + "Non-text foreground colors must contrast against the default black background `#000000` at ratio >= 3.0.\n"
                    + "Text, titles, formulas, labels, and callouts must contrast at ratio >= 4.5 against their own background color; if no background is defined, use `#000000` as the background.\n";

    public static final String MANIM_COLOR_RULES_BULLETS =
            "- " + MANIM_COLOR_RULES.replace("\n", "\n- ").trim() + "\n";

    /** ASCII-only rules for backend identifiers, not learner-facing prose. */
    public static final String ASCII_TEXT_RULES =
            "Identifier ASCII rules:\n"
                    + "- Keep backend identifiers ASCII-only: scene_id, object id, constraint id, action target id, generated method names, Python variable names, and GeoGebra object names.\n"
                    + "- Do not apply ASCII-only cleanup to learner-facing text such as titles, goals, narration, captions, subtitles, explanatory notes, or object content shown on screen.\n"
                    + "- Normalize Unicode punctuation only inside backend identifiers: U+2018 and U+2019 -> `'`; U+201C and U+201D -> `\"`; U+2013 and U+2014 -> `-`; U+2212 -> `-`; U+00D7 -> `x`; U+2260 -> `!=`; U+2264 -> `<=`; U+2265 -> `>=`.\n"
                    + "- For identifiers, replace Unicode arrows, prime/star glyphs, checkmarks, circled numbers, full-width spaces, and Unicode math operators with ASCII-safe names or symbols.\n"
                    + "- Before returning JSON, scan identifier fields only; if an identifier contains a character code greater than 0x7F, rewrite that identifier while preserving references to it.\n";

    /** Backend-neutral learner-facing Chinese text rules. */
    public static final String VISIBLE_CHINESE_TEXT_RULES =
            "Learner-facing visible text rules:\n"
                    + "- Any natural-language text that appears on screen in `object_registry[].content` must be written in Chinese.\n"
                    + "- This applies to text, text_card, captions, callouts, labels that contain prose, titles shown as objects, and explanatory object content.\n"
                    + "- Short symbolic mathematical labels such as `A`, `B`, `B′`, `AB`, `l`, `P_1`, `\\angle APB`, and formula/equation content should stay symbolic instead of being expanded into Chinese prose.\n"
                    + "- For `kind=text` objects that label mathematical elements, `content` is the exact on-screen label and must be as concise as the mathematical name: use `B′`, not `反射点B′`; use `l`, not `直线l`; use `AB`, not `线段AB`. Put explanatory wording in narration, action descriptions, or goals instead.\n"
                    + "- Preserve Chinese learner-facing text during cleanup, code generation, review, and repair; do not translate it to English or pinyin.\n";

    /** Manim-only action narration and voiceover planning rules. */
    public static final String MANIM_VOICEOVER_RULES =
            "Manim voiceover rules:\n"
                    + "- Use Chinese `voiceover_text` only for key teaching beats: a major setup, conceptual turn, non-obvious construction, important reveal, comparison, or conclusion.\n"
                    + "- Most routine actions should omit `voiceover_text`, including simple object entry/exit, obvious movement, incremental styling, label appearance, and visual changes that the screen already explains clearly.\n"
                    + "- Prefer 0-2 narrated actions per scene; use more only when a long scene has multiple genuinely independent key ideas.\n"
                    + "- Use `expected_seconds` as the intended audio/animation duration only for narrated key actions where timing matters.\n"
                    + "- Generated Manim code may use `VoiceoverScene`, `GTTSService(lang=\"zh-CN\")`, and `with self.voiceover(text=...) as tracker:` to synchronize selected audio and animation.\n"
                    + "- Keep `voiceover_text` concise, natural Chinese; do not write English narration, pinyin, or backend implementation notes in it.\n";

    /** Manim-only Chinese text rendering rules. */
    public static final String MANIM_CHINESE_TEXT_RENDERING_RULES =
            "Manim Chinese text rendering rules:\n"
                    + "- Render Chinese prose with `Text(...)` using a Chinese-capable font such as `Microsoft YaHei`.\n"
                    + "- Keep formulas in `MathTex(...)`; do not force formula strings into Chinese prose.\n"
                    + "- Preserve Chinese strings exactly unless a repair must shorten wording for readability.\n";

    /** Opacity hierarchy for visual layering, applicable to all output targets. */
    public static final String OPACITY_LEVELS =
            "Opacity hierarchy:\n"
                    + "- Primary focus elements: opacity 1.0\n"
                    + "- Contextual / previously-introduced elements: opacity 0.3-0.4\n"
                    + "- Structural scaffolding (axes, grids, construction lines): opacity 0.15\n"
                    + "- Never show everything at full brightness simultaneously.\n";

    /** Shared object lifecycle and storyboard contract rules for all output targets. */
    public static final String OBJECT_LIFECYCLE_RULES =
            "Storyboard and object-lifecycle rules:\n"
                    + "- Every learner-visible object that should appear in the scene or construction must be declared explicitly in the storyboard; do not rely on unstated inferred visuals.\n"
                    + "- If an object remains visible across beats or steps, keep the same visual identity instead of silently recreating it.\n"
                    + "- If an object depends on another object's motion, make the dependency explicit in structured constraints and preserve it in code.\n"
                    + "- Decide each object's lifecycle intentionally: persist, dim, transform, or exit it based on whether it supports upcoming reasoning, continuity, comparison, dependency evidence, or learner orientation.\n"
                    + "- Temporary annotations, comparison aids, and helper overlays need an exit-or-dim plan once they have taught their point; do not let them linger by accident.\n"
                    + "- End scenes or steps cleanly by balancing continuity and cognitive load. A clean break, carry-forward anchor, transition bridge, dimmed context, or object exit can all be correct when chosen for teaching clarity.\n";

    // ========================================================================
    // Manim-specific rules
    // ========================================================================

    /** Manim-specific layout and readability budget. */
    public static final String MANIM_LAYOUT_FRAME_RULES =
            "Keep important content within x[-6.5,6.5] and y[-3.5,3.5] whenever possible.\n"
                    + "Reserve a readable top title band and a bottom note band instead of packing the whole frame.\n"
                    + "Keep simultaneously active foreground elements around 6 to 8 when possible; brief bursts up to about 10 are acceptable when staging and hierarchy stay clear.\n"
                    + "If a scene would have more than 12 simultaneously visible foreground elements, decide which objects still support upcoming reasoning: dim context objects, group or scale supporting elements, or exit completed elements rather than showing everything at full strength.\n"
                    + "Prefer staggered reveals when they clarify the teaching flow: create groups of 3-4 objects, then dim, transform, or exit according to their future usefulness before creating the next group.\n"
                    + "Leave a meaningful empty zone for overlays, captions, or upcoming reveals.\n";

    /** Shared Manim motion and pacing rules. */
    public static final String MANIM_MOTION_AND_PACING_RULES =
            "Manim motion and pacing rules:\n"
                    + "- Write narration with visual beats in mind: what the learner hears should match what the learner sees.\n"
                    + "- Treat one beat as one small `self.play(...)` group or one stable visual hold.\n"
                    + "- After each important reveal, leave breathing room with `self.wait(...)` so the learner can read and absorb it.\n"
                    + "- After every significant equation reveal, include `self.wait(2.0)` minimum before the next animation.\n"
                    + "- Vary tempo: slower for core reveals, faster for supporting details, and a longer pause around the key insight.\n"
                    + "- Prefer the \"see, then hear\" timing pattern for major ideas.\n"
                    + "- If the storyboard narration has more than 20 words for a scene under 10 seconds, reduce narration density.\n";

    /** Shared Manim text and readability rules. */
    public static final String MANIM_TEXT_AND_READABILITY_RULES =
            "Manim text and readability rules:\n"
                    + "- Use monospace fonts (e.g. Menlo, Courier New, DejaVu Sans Mono) for all `Text(...)` and `MarkupText(...)` content. Manim's Pango renderer produces broken kerning with proportional fonts.\n"
                    + "- Do not apply the monospace-font requirement to `MathTex(...)` or `Tex(...)`; those are LaTeX-rendered mobjects. Check their constructor choice, LaTeX validity, font size, color contrast, and layout instead.\n"
                    + "- Hard minimum `font_size=18` for any on-screen text.\n"
                    + "- Keep supporting text comfortably readable; avoid tiny labels and long edge-to-edge strings.\n"
                    + "- Use `buff=0.5` or larger on every `.to_edge()` call; values below 0.5 risk clipping.\n"
                    + "- After creating long text, check whether `text.width > config.frame_width - 1.0` and call `text.set_width(config.frame_width - 1.0)` if so.\n"
                    + "- MathTex and Tex default to `#FFFFFF` text. When placing them on any light-colored surface, explicitly set a dark text color such as `#111827`.\n"
                    + "- If text overlaps busy geometry, prefer adjusting placement or using opacity to separate layers. Only add a background box when the text truly cannot be read without one.\n"
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

    /** Shared Manim implementation and code-hygiene rules. */
    public static final String MANIM_CODE_HYGIENE_RULES =
            "Manim implementation rules:\n"
                    + "- Use the right collection type: prefer `Group(...)` for mixed text-and-shape collections and `VGroup(...)` for vectorized mobjects.\n"
                    + "- Use raw strings for LaTeX and keep `MathTex(...)` segments stable when matching transforms will be needed later.\n"
                    + "- Prefer helper builders, shared style constants, and stable layout helpers over scattered ad hoc coordinates.\n"
                    + "- Keep background color, palette meaning, and typography consistent across the full file.\n"
                    + "- Define shared color constants (BG, PRIMARY, SECONDARY, ACCENT) at the top of the file from 6-digit hex values such as `ManimColor(\"#000000\")` or `\"#3498DB\"`; do not use Manim named color constants for storyboard colors.\n"
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
    public static final String MANIM_ANGLE_MARKER_RULES =
            "For angle, right-angle, and arc sweep markers, treat the storyboard's `angle_between`, `right_angle_at`, or `arc_sweep` constraint as the semantic source of truth: preserve the declared marker/arc, vertex or center/anchor, ordered boundaries, sector, direction, and side.\n"
                    + "Choose the documented Manim API form that most faithfully preserves those refs; do not prefer a two-line, three-point, or hand-drawn arc implementation when it drops vertex or sector semantics.\n"
                    + "Reuse storyboard-declared boundary objects where possible; introduce backend-only helper rays, points, or arcs only when required, and derive them from the declared refs.\n"
                    + "When an angle is measured against a normal, helper line, or moving segment, construct the relevant boundaries from the shared vertex inside `always_redraw(...)`.\n"
                    + "If the intended angle sector could be ambiguous, explicitly set `quadrant=...`; if the storyboard intends the interior/smaller angle, explicitly keep `other_angle=False`.\n";

    /** Angle marker best practices for GeoGebra. */
    public static final String GEOGEBRA_ANGLE_MARKER_RULES =
            "GeoGebra angle marker rules:\n"
                    + "- Treat `angle_between`, `right_angle_at`, and `arc_sweep` refs as the semantic source of truth: preserve the declared marker/arc, vertex or center/anchor, ordered boundaries, sector, direction, and side.\n"
                    + "- Choose the documented GeoGebra Angle form that best preserves those refs; do not prefer line-based, vector-based, three-point, or helper-object syntax when it drops vertex or sector semantics.\n"
                    + "- Reuse storyboard-declared boundary objects where possible. If GeoGebra needs helper rays, lines, vectors, or points, derive them from the declared vertex and boundary refs rather than inventing unrelated proxy objects.\n"
                    + "- When the storyboard describes an angle with a vertex and two boundary rays (e.g. \"angle at P between AP and l\"), ensure both referenced boundaries actually pass through P before using them to construct the angle marker.\n";

    /** Rules for minimizing auxiliary/helper objects in storyboard authoring (Visual Design, Storyboard Validation). */
    public static final String MINIMIZE_HELPER_OBJECTS_AUTHORING_RULES =
            "Minimize redundant storyboard objects:\n"
                    + "- Add a new storyboard object only when it is teaching-essential, improves clarity, or carries a distinct geometric/dependency role.\n"
                    + "- Prefer reusing, restyling, moving, dimming, or relabeling an existing object over creating a new object with the same meaning.\n"
                    + "- Avoid redundant labels, repeated formula copies, duplicate highlights, and decorative objects that do not advance the current teaching beat.\n"
                    + "- Prefer `kind = text` or `kind = equation` over `kind = text_card` or `kind = formula_card`. Display text directly without a background box unless the card itself is teaching-essential.\n"
                    + "- A required label, callout, or annotation is not redundant when it gives the learner a distinct name/value, has its own attachment behavior, or is needed to identify the parent object.\n"
                    + "- Keep `new_objects` and `object_registry` lean: do not register objects that can be expressed as style changes, action descriptions, built-in labels, or references to existing ids.\n"
                    + "- When a derived object (angle marker, midpoint, intersection, reflection, etc.) can be fully defined by referencing existing objects, do so directly rather than introducing separate helper/scaffold objects.\n"
                    + "- For angle, right-angle, and arc sweep markers, define the full measured geometry in structured `angle_between`, `right_angle_at`, or `arc_sweep` constraint refs: marker/arc, vertex or center/anchor, ordered boundaries, and sector/direction parameters.\n"
                    + "- Avoid creating helper points, helper lines, or other scaffolding objects whose sole purpose is to serve as an intermediate input to another object when a direct dependency reference is possible.\n"
                    + "- If an object exists on the construction (a line, a segment, a ray, a circle), reference it directly instead of creating a duplicate or a proxy point on it.\n"
                    + "- When the target platform has concise syntax that works with existing objects, record enough structured `constraints` for downstream code generation to use it without losing vertex, side, or sector semantics.\n";

    /** Rules for minimizing auxiliary/helper objects in code generation and code repair stages. */
    public static final String MINIMIZE_HELPER_OBJECTS_CODEGEN_RULES =
            "Minimize auxiliary helper objects in generated code:\n"
                    + "- When the target platform has a direct syntax that works with existing objects and preserves the declared constraint semantics, use it instead of creating intermediate helper objects.\n"
                    + "- Do not create auxiliary points on existing lines, segments, or circles just to satisfy a syntax form when existing declared refs already preserve the same vertex, side, sector, and dependency semantics.\n"
                    + "- Do not create helper lines or segments that merely duplicate existing geometry for the purpose of feeding them into another command.\n"
                    + "- When a construction or measurement can reference an existing named object directly, do so rather than reconstructing an equivalent from scratch.\n"
                    + "- Remove or replace any auxiliary object that exists solely as a workaround for an avoidable syntax limitation.\n";

    /** Manim typography scale for consistent text sizing. */
    public static final String MANIM_TYPOGRAPHY_SCALE =
            "Manim typography scale:\n"
                    + "- Title: font_size=48\n"
                    + "- Heading: font_size=36\n"
                    + "- Body / explanatory text: font_size=30\n"
                    + "- Label / annotation: font_size=24\n"
                    + "- Caption / fine print: font_size=20\n"
                    + "- Hard minimum: font_size=18 - anything smaller blurs at draft quality and is barely legible at production quality.\n";

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

    /** Shared Manim naming rules for storyboard ids and generated identifiers. */
    public static final String MANIM_NAMING_RULES =
            "- Keep all Python identifiers and object names ASCII only.\n"
                    + "- When object ids become Python variable names, keep them concise and non-redundant because `kind` already carries the type; prefer `river` over `line_river` and `A` over `pointA`.\n"
                    + "- Single uppercase letters are acceptable for geometric points (e.g. `A`, `B`, `P`); use camelCase or snake_case for compound names.\n"
                    + "- Do not use Python reserved words (`class`, `def`, `lambda`, `for`, `if`, `in`, `is`, `not`, `None`, `True`, `False`, etc.) or Manim built-in class names (`Scene`, `Mobject`, `Line`, `Circle`, `Text`, etc.) as identifiers.\n";

    /** Manim API whitelist rules sourced from the attached syntax manual. */
    public static final String MANIM_MANUAL_ONLY_RULES = buildManimManualOnlyRules();

    /** Text constructor mapping rules shared between code generation and evaluation. */
    public static final String MANIM_TEXT_CONSTRUCTOR_MAPPING =
            "Text constructor mapping is mandatory:\n"
                    + "- `kind = equation` must render with `MathTex(...)`.\n"
                    + "- `kind = text` must render with `Text(...)`.\n"
                    + "- `kind = text_card` or `kind = formula_card` should be avoided; if present, render as plain `Text(...)` without a background box unless the card is teaching-essential.\n"
                    + "- Avoid `Tex(...)` unless the storyboard explicitly calls for non-math LaTeX text.\n"
                    + "- If the kind/content disagree, infer from content: standalone formulas, Greek letters used as formulas, angle notation, superscripts, subscripts, and LaTeX control sequences are math text; ordinary labels and prose fragments are plain text. Chinese or natural-language prose that only embeds a short symbolic label such as `P₀`, `B′`, or `A_1` remains plain `Text(...)`.\n";

    // ========================================================================
    // GeoGebra-specific rules
    // ========================================================================

    /** Shared GeoGebra naming rules for storyboard ids and generated identifiers. */
    public static final String GEOGEBRA_NAMING_RULES =
            "- Keep object names concise and non-redundant because `kind` already carries the type; prefer `l` over `lineL` and `c` over `circleC`.\n"
                    + "- Prefer native GeoGebra math-style names. Point names must start with an uppercase letter (e.g. `A`, `P_1`); vector names must start with a lowercase letter (e.g. `v`, `u`); lines, circles, and other non-point objects may start with a lowercase letter (e.g. `l`, `c`, `tri`). Use `_` for subscripts and `'` for primes.\n"
                    + "- Translate ASCII-spelled ids to GeoGebra-native math names when needed by applying these structural rules:\n"
                    + "  - A trailing `prime` suffix becomes a prime mark: `<base>prime` -> `<base>'`\n"
                    + "  - A trailing `star` suffix becomes a star subscript: `<base>star` -> `<base>_{*}`\n"
                    + "  - A trailing numeric suffix becomes a subscript: `<base><digits>` -> `<base>_{<digits>}`\n"
                    + "  - A trailing alphabetic suffix that reads as a known math modifier (opt, max, min, avg) becomes a subscript: `<base><modifier>` -> `<base>_{<modifier>}`\n"
                    + "  If native names like `B'` or `P_{opt}` are already used in the storyboard, keep them verbatim.\n"
                    + "- Do not use reserved names such as `x`, `y`, `z`, `xAxis`, `yAxis`, `zAxis`, `e`, `i`, `pi`, `sin`, `cos`, `tan`, `exp`, `log`, `ln`, `abs`, `sqrt`, `floor`, `ceil`, `round`, `random`, `arg`, `gamma`, `beta`, `sec`, `csc`, or `cot` as object identifiers.\n";

    /** GeoGebra command whitelist rules sourced from the attached syntax manual. */
    public static final String GEOGEBRA_MANUAL_ONLY_RULES = buildGeoGebraManualOnlyRules();

    /** GeoGebra viewport and coordinate layout rules. */
    public static final String GEOGEBRA_VIEWPORT_RULES =
            "GeoGebra viewport rules:\n"
                    + "- Treat the initial visible coordinate window as fixed at x[-7,7] and y[-4,4] unless the renderer explicitly changes it.\n"
                    + "- The generated script should include or tolerate `SetCoordSystem(-7, 7, -4, 4)` as the initial view contract.\n"
                    + "- Keep important learner-visible geometry, labels, and text inside x[-6.5,6.5] and y[-3.5,3.5] with margin.\n"
                    + "- Do not solve layout by zooming out to a much larger visible range; if objects would become tiny or clustered, scale or spread the construction coordinates instead.\n"
                    + "- Aim for the main construction to occupy roughly 45%-80% of the visible width and 35%-75% of the visible height when the math allows it.\n"
                    + "- For large mathematical values, separate mathematical labels from visual coordinates: use readable display coordinates and labels/captions/text for the original values.\n";

    // ========================================================================
    // Output format constants
    // ========================================================================

    /** Tool call hint for structured output prompts. */
    public static final String TOOL_CALL_HINT =
            "If tools are available, call them.\n";

    /** JSON-only output reminder. */
    public static final String JSON_ONLY_OUTPUT =
            "Return JSON only.";

    /** GeoGebra scene button metadata schema consumed by Java post-processing. */
    public static final String GEOGEBRA_SCENE_DIRECTIVE_RULES =
            "GeoGebra scene directive metadata rules:\n"
                    + "- `# @scene` lines are Java-consumed metadata comments for scene buttons, not GeoGebra commands.\n"
                    + "- Each `# @scene` JSON object may contain ONLY these fields: `id`, `title`, `show`, and `hide`.\n"
                    + "- Required schema: `{\"id\":\"scene_1\",\"title\":\"...\",\"show\":[\"objectId\"],\"hide\":[\"objectId\"]}`.\n"
                    + "- Do not add `setValue`, `commands`, `actions`, `state`, `sceneState`, or any other extra fields to `# @scene` JSON.\n"
                    + "- Express scene-specific progression through normal GeoGebra construction/style/visibility commands and the `show`/`hide` object lists only.\n";

    /** Manim code block output format. */
    public static final String MANIM_CODE_OUTPUT_FORMAT =
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

    // ========================================================================
    // Storyboard codegen intro constants
    // ========================================================================

    /** Storyboard codegen preamble for Manim output. */
    public static final String STORYBOARD_CODEGEN_INTRO_MANIM =
            "Use the following compact storyboard JSON as the execution plan for staging, object identity, continuity, and scene execution.\n"
                    + STORYBOARD_AUTHORITY_RULES
                    + "- Treat every object id as a stable visual identity.\n"
                    + "- Treat storyboard objects as candidate visual elements; create only the elements that are necessary or helpful for the teaching beat.\n"
                    + "- If an id persists, keep or transform the same mobject instead of redrawing it.\n"
                    + "- When `content`, constraint refs, or related fields mention another object, treat those mentions as object ids only rather than as repeated type declarations.\n"
                    + "- If a scene uses `scene_mode = 3d`, use `ThreeDScene`, follow `camera_plan`, and judge layout in projected screen space.\n"
                    + "- Use `screen_overlay_plan` with `add_fixed_in_frame_mobjects` for fixed explanatory text.\n"
                    + "- Respect `safe_area_plan` and dynamic attachment for labels on moving objects.\n"
                    + "- Read structured attachment constraints such as `label_for`, `anchored_to`, and `fixed_offset_from` literally: if an object follows a moving anchor, implement it with `always_redraw(...)` or an updater.\n"
                    + "- Preserve scene beats, scene exits, and overlay zones from the storyboard instead of compressing everything into one crowded final frame.\n"
                    + MANIM_MANUAL_ONLY_RULES
                    + "- Treat structured `constraints` and `notes_for_codegen` as hard invariants. If the frame is tight, preserve the construction and recenter/scale the whole constrained group instead of breaking the math or the stated implementation constraint.\n";

    /** Storyboard codegen preamble for GeoGebra output. */
    public static final String STORYBOARD_CODEGEN_INTRO_GEOGEBRA =
            "Use the following compact storyboard JSON as the execution plan for GeoGebra construction order, object identity, continuity, and teaching progression.\n"
                    + STORYBOARD_AUTHORITY_RULES
                    + "- Keep the same object identities stable across steps.\n"
                    + "- Convert `actions` into construction order, visibility changes, highlights, or helper toggles rather than literal animation.\n"
                    + "- Preserve structured `constraints` and `notes_for_codegen` through dependency-safe GeoGebra commands.\n"
                    + "- Use constraint relations to decide dependency semantics: independent anchors may be free/fixed, while constrained or derived objects must remain dependency-driven.\n"
                    + "- If a point is described as movable/draggable and also constrained to a line, segment, ray, circle, polygon edge, or other object, generate it as a point on that object or with an equivalent dependency-safe parameterization, never as an unconstrained coordinate point.\n"
                    + "- If a point is fixed and no dependency is stated, define it as an independent anchor and keep it fixed unless the storyboard explicitly asks for dragging.\n"
                    + "- If a storyboard specifies a bounded range for motion, encode that bound in the construction itself, such as a segment, ray, restricted path, or slider domain, instead of leaving the point free on an unbounded line.\n"
                    + "- When `actions` move an object, preserve its constraint during that move; do not redefine the object as free just to make the motion easy.\n"
                    + "- When `content`, constraint refs, or other object fields mention another object, treat those mentions as object ids only. Do not reinterpret kind words from prose and do not invent a second object type for the same id.\n"
                    + GEOGEBRA_MANUAL_ONLY_RULES
                    + "- Prefer GeoGebra's native labels for named geometric objects. If an object is named `A`, `l`, `c`, `AB`, or similar, use that object itself as the visible label instead of creating a separate label helper object.\n"
                    + "- Treat storyboard object ids as the naming source for generated GeoGebra variables. Preserve those ids in code, and when you must introduce a helper name, use concise camelCase or math-style identifiers.\n"
                    + GEOGEBRA_NAMING_RULES
                    + "- This naming convention applies to all generated geometric objects and helpers.\n"
                    + "- Create a separate label/text object only when the visible text is not the object's own native label, such as overlays, formulas, counters, captions, or explanatory annotations.\n"
                    + "- If the storyboard contains a redundant geometry-label pair, prefer keeping the geometry object and dropping the extra label object in the generated GeoGebra commands.\n"
                    + "- Choose readable coordinates and label placement that respect `layout_goal`, `placement`, and `safe_area_plan`.\n"
                    + GEOGEBRA_VIEWPORT_RULES
                    + COLOR_FORMAT_RULES_BULLETS
                    + GEOGEBRA_COLOR_RULES_BULLETS
                    + "- If the storyboard asks for an effect that would require an undocumented command, preserve the core geometry with documented commands only and do not invent syntax.\n";

    // ========================================================================
    // Helper methods
    // ========================================================================

    private static final String WORKFLOW_OVERVIEW =
            "Stage 0 Exploration -> Stage 1a Mathematical Enrichment -> Stage 1b Visual Design"
                    + " -> Stage 1c Storyboard Validation -> Stage 2 Code Generation"
                    + " -> Stage 3 Code Evaluation -> Stage 4 Code Rendering"
                    + " -> Stage 5 Scene Evaluation"
                    + " (Stages 2-5 may each route to the shared Code Fix node for iterative repair)";

    private SystemPrompts() {}

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

    public static String buildRulesSection(String content) {
        return buildSection("[RULES]", content);
    }

    public static String buildFixedContextSection(String content) {
        return buildSection("[FIXED_CONTEXT]", content);
    }

    public static String buildCurrentRequestSection(String content) {
        return buildSection("[CURRENT_REQUEST]", content);
    }

    private static String buildSection(String label, String content) {
        String normalized = sanitize(content, "").trim();
        if (normalized.isEmpty()) {
            return label;
        }
        return label + "\n" + normalized;
    }

    // ========================================================================
    // Resource loading
    // ========================================================================

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

    private static String buildManimManualOnlyRules() {
        return "Treat the attached Manim syntax manual as the authoritative whitelist.\n"
                + "Use only classes, functions, methods, arguments, scene patterns, and code forms documented there.\n"
                + "Never invent Manim APIs, guessed helper methods, unsupported keyword arguments, or private/internal shortcuts.\n"
                + "Imported external libraries and aliases that appear in the code, such as `import numpy as np`, are allowed; do not flag calls like `np.array(...)` or `np.linalg.norm(...)` when the import is present.\n"
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
                + "When replacing an undocumented command that provides a visual effect (such as font sizing, background boxes, or card styling), you must re-express that effect using a combination of documented commands that achieves equivalent visual results; never remove the effect entirely without providing a documented replacement.\n"
                + "Documented construction commands: `"
                + String.join("`, `", GeoGebraValidationSupport.documentedConstructionCommandNames())
                + "`.\n"
                + "Documented scripting commands: `"
                + String.join("`, `", GeoGebraValidationSupport.documentedScriptingCommandNames())
                + "`.\n";
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
