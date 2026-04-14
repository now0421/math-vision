package com.mathvision.prompt;

import java.util.List;

/**
 * Prompts for Stage 5: geometry-based scene-evaluation fixes.
 */
public final class SceneEvaluationPrompts {

    private static final String MANIM_SYSTEM =
            "You are fixing Manim code that rendered but has layout issues detected by geometry analysis.\n"
                    + "Preserve the teaching goal, visual intent, scene class name, and continuity.\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.MANIM_TEXT_AND_READABILITY_RULES
                    + SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + "Naming rules:\n"
                    + SystemPrompts.MANIM_NAMING_RULES
                    + "Prefer adjusting positioning, scaling, grouping, and spacing over deleting explanatory content.\n"
                    + "For frame repair, use translation/recentering and uniform scaling as the default first-choice strategy before changing geometric constructions or attachment logic.\n"
                    + "Also correct semantically wrong geometric attachments you notice, especially angle markers that are drawn on the wrong side or detached from their true vertex.\n"
                    + "Treat storyboard geometric constraints as hard requirements: if a point is defined as a reflection, midpoint, foot, or intersection, preserve that definition while fixing layout.\n"
                    + SystemPrompts.GEOMETRY_CONSTRAINT_RULES
                    + "Do not reintroduce banned dynamic patterns during layout fixes, especially conditionally empty redraw targets that will be animated directly later.\n"
                    + "When a constrained construction goes out of frame, prefer recentering or uniformly scaling the whole related diagram, or moving overlays, instead of moving one constrained point independently.\n\n"
                    + "Maintain a clean final-frame impression: leave breathing room, avoid overlay-on-geometry collisions, and remove temporary annotations once they have taught their point.\n\n"
                    + SystemPrompts.PYTHON_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_SYSTEM =
            "You are fixing a GeoGebra command script that executed but has text overlap issues detected by geometry analysis.\n"
                    + "Preserve the teaching goal, visual intent, construction meaning, and storyboard teaching order.\n"
                    + "Prefer adjusting label placement, text positioning, and coordinate spacing over removing explanatory content.\n"
                    + "GeoGebra constructions are interactive and freely zoomable, so out-of-bounds issues are irrelevant; focus exclusively on fixing text-on-text and text-on-geometry overlaps.\n"
                    + "Also correct semantically wrong geometric attachments you notice, especially angle markers that sweep the wrong sector.\n"
                    + "Treat storyboard geometric constraints as hard requirements: if a point is defined as a reflection, midpoint, foot, or intersection, preserve that definition while fixing overlap.\n"
                    + SystemPrompts.GEOMETRY_CONSTRAINT_RULES
                    + "Use English GeoGebra command names.\n"
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + "Naming rules:\n"
                    + SystemPrompts.GEOGEBRA_NAMING_RULES
                    + "Do not output Python, JavaScript, or explanations.\n"
                    + "Do not break geometric constraints while fixing overlap; keep derived objects derived from their source objects.\n\n"
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT;

    private SceneEvaluationPrompts() {}

    public static String layoutFixSystemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureManimSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 5 / Scene Evaluation Fix",
                "Revise Manim code after geometry-based scene evaluation",
                targetConcept,
                targetDescription,
                "manim"
        ) + MANIM_SYSTEM);
    }

    public static String geoGebraLayoutFixSystemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureGeoGebraSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 5 / Scene Evaluation Fix",
                "Revise GeoGebra commands after geometry-based scene evaluation",
                targetConcept,
                targetDescription,
                "geogebra"
        ) + GEOGEBRA_SYSTEM);
    }

    public static String layoutFixUserPrompt(String storyboardJson,
                                             String generatedCode,
                                             String issueSummary,
                                             String sceneEvaluationJson,
                                             List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following Manim code rendered, but post-render scene evaluation found layout issues in sampled frames.\n\n")
                .append(SystemPrompts.STORYBOARD_FIELD_GUIDE_REPAIR)
                .append("\n\nCompact storyboard JSON (source of truth):\n```json\n")
                .append(storyboardJson != null && !storyboardJson.isBlank() ? storyboardJson : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON)
                .append("\n```\n\n")
                .append("```python\n").append(generatedCode).append("\n```\n\n")
                .append("Issue summary:\n```\n").append(issueSummary).append("\n```\n\n")
                .append("Scene evaluation report excerpt:\n```json\n").append(sceneEvaluationJson).append("\n```\n\n")
                .append("Repair process requirements:\n")
                .append("1. First identify the affected storyboard scene(s) and the ids/constraints tied to the reported elements.\n")
                .append("2. For overlap and offscreen repair, first try translation/recentering and uniform scaling of the affected overlay or constrained group before changing geometry or redefining attachments.\n")
                .append("3. Fix overlap only through text/overlay layout changes, spacing, grouping, recentering, or uniform scaling of constrained groups.\n")
                .append("4. Fix offscreen issues using `safe_area_plan` and `layout_goal`; do not push text farther off frame just to avoid overlap. After repositioning, every element must maintain at least 0.5 units of clearance from every frame edge — never place a corrected element flush against the boundary.\n")
                .append("5. Preserve reflections, symmetry, intersections, equal distances, and anchor-follow relationships exactly.\n")
                .append("6. Prefer cleaning up temporary annotations or stale overlays over covering them with new opaque cards.\n")
                .append("7. Preserve a readable empty zone for overlays and key conclusions.\n\n")
                .append("Please fix the code so the reported sampled frames no longer have elements overlapping or going outside the frame.\n")
                .append("Preserve the intended teaching flow and animation meaning.\n")
                .append("Preserve geometric invariants from the storyboard; do not fix offscreen issues by breaking reflections, symmetry, intersections, equal distances, or other defining constructions.\n")
                .append("Also proactively check for common Python and Manim runtime mistakes.\n")
                .append("Remember: Return ONLY the single Python code block containing the full file. No explanation.\n");

        appendFixHistory(sb, fixHistory);
        return sb.toString();
    }

    public static String geoGebraLayoutFixUserPrompt(String storyboardJson,
                                                     String generatedCode,
                                                     String issueSummary,
                                                     String sceneEvaluationJson,
                                                     List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following GeoGebra command script executed, but post-render scene evaluation found text overlap issues.\n\n")
                .append(SystemPrompts.STORYBOARD_FIELD_GUIDE_GEOGEBRA_REPAIR)
                .append("\n\nCompact storyboard JSON (source of truth):\n```json\n")
                .append(storyboardJson != null && !storyboardJson.isBlank() ? storyboardJson : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON)
                .append("\n```\n\n")
                .append("```geogebra\n").append(generatedCode).append("\n```\n\n")
                .append("Issue summary:\n```\n").append(issueSummary).append("\n```\n\n")
                .append("Scene evaluation report excerpt:\n```json\n").append(sceneEvaluationJson).append("\n```\n\n")
                .append("Repair process requirements:\n")
                .append("1. First identify the affected storyboard scene(s) and the ids/constraints tied to the reported elements.\n")
                .append("2. Fix text overlap through label repositioning, coordinate spacing, or `SetCaption`/`ShowLabel` adjustments.\n")
                .append("3. Do not worry about elements extending outside the initial viewport; GeoGebra is interactive and users can freely zoom and pan.\n")
                .append("4. Preserve reflections, symmetry, intersections, equal distances, and dependency chains exactly.\n\n")
                .append("Please fix the command script so the reported text overlap issues are resolved.\n")
                .append("Preserve the intended teaching flow and construction meaning.\n")
                .append("Preserve geometric invariants from the storyboard; do not fix overlap by breaking reflections, symmetry, intersections, or other defining constructions.\n")
                .append("Remember: Return ONLY the single fenced `geogebra` code block. No explanation.\n");

        appendFixHistory(sb, fixHistory);
        return sb.toString();
    }

    private static void appendFixHistory(StringBuilder sb, List<String> fixHistory) {
        if (fixHistory != null && !fixHistory.isEmpty()) {
            sb.append("\nPrevious fix attempts to avoid repeating:\n");
            for (int i = 0; i < fixHistory.size(); i++) {
                String item = fixHistory.get(i);
                if (item == null) {
                    continue;
                }
                sb.append("  Attempt ").append(i + 1).append(": ")
                        .append(item.length() > 100 ? item.substring(0, 100) + "..." : item)
                        .append("\n");
            }
        }
    }
}
