package com.mathvision.prompt;

import com.mathvision.model.ProblemBundle;
import com.mathvision.util.ProblemBundleContextBuilder;

import java.util.List;

/**
 * Prompts for Stage 8: geometry-based scene-evaluation fixes.
 */
public final class SceneEvaluationPrompts {

    private static final String MANIM_SYSTEM =
            "You are fixing Manim code in the shared Code Fix stage. Do not assume every Code Fix request already rendered successfully; use the current request's supplied evidence as the repair authority.\n"
                    + "Preserve the teaching goal, visual intent, scene class name, and continuity.\n"
                    + SystemPrompts.STORYBOARD_REPAIR_AUTHORITY_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + SystemPrompts.MANIM_VOICEOVER_RULES
                    + SystemPrompts.MANIM_CHINESE_TEXT_RENDERING_RULES
                    + SystemPrompts.MANIM_CODE_FIX_CLASS_INHERITANCE_RULES
                    + "Use the rendered geometry report as authority for observed layout problems, and use storyboard object_registry dependency facts as semantic authority for how affected geometry must be constructed.\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + "Prefer adjusting positioning, scaling, grouping, and spacing over deleting explanatory content.\n"
                    + "For offscreen world-coordinate geometry, first expand the Manim `Axes`/`NumberPlane` `x_range`/`y_range` and keep placements mapped through `axes.c2p(...)`; use translation/recentering or uniform scaling only when the element is a fixed overlay or the coordinate view would become unreadable.\n"
                    + "If a reported element is dependency-driven or derived, do not fix it by assigning direct coordinates copied from rendered bounds or storyboard placement; adjust upstream dependency objects, the whole constrained group, camera/layout, or the attachment expression so the dependency remains true.\n"
                    + "Also correct semantically wrong geometric attachments you notice, especially angle markers that are drawn on the wrong side or detached from their true vertex.\n"
                    + "Preserve valid voiceover structure and Chinese learner-facing strings while fixing layout issues.\n\n"
                    + "Scene evaluation repair requirements:\n"
                    + "1. First identify the affected code scene(s), reported elements, and any storyboard_dependency_context supplied in the evaluation report.\n"
                    + "2. When a layout issue is detected in a sampled frame, do not assume the problem only exists at that sampled instant. Trace each reported element back to where it is first created, positioned, attached, or updated, then repair the earliest responsible placement, attachment, updater, camera framing, or group layout so it remains valid for all frames after it appears.\n"
                    + "3. Do not fix scene-final layout issues by adding a late one-off animation immediately before the final wait, such as shifting a persistent label only at the end, unless the issue is caused exclusively by a final-scene-only object or final-scene-only transition.\n"
                    + "4. For persistent labels, points, segments, and derived objects, repair their initial placement, `next_to` direction, updater, group transform, camera framing, or upstream geometry instead of adding a terminal patch.\n"
                    + "5. For offscreen repair, first expand the coordinate-system range used by `Axes`/`NumberPlane`; for overlays or unreadably sparse views, then use translation/recentering and uniform scaling of the affected overlay, upstream source objects, or constrained group.\n"
                    + "6. Fix overlap only through text/overlay layout changes, spacing, grouping, recentering, or uniform scaling of constrained groups.\n"
                    + "7. Fix offscreen issues using readable frame composition; storyboard `safe_area_plan` and `layout_goal` are useful hints.\n"
                    + "8. Keep implemented reflections, symmetry, intersections, equal distances, and anchor-follow relationships internally consistent.\n"
                    + "9. Prefer cleaning up temporary annotations or stale overlays over covering them with new opaque cards.\n"
                    + "10. Preserve a readable empty zone for overlays and key conclusions.\n"
                    + "Audit the entire file for similar layout issues, not just the reported elements. The reported issues indicate structural patterns that may appear elsewhere.\n"
                    + "Also proactively check for common Python and Manim runtime mistakes.\n\n"
                    + SystemPrompts.MANIM_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_SYSTEM =
            "You are fixing a GeoGebra command script in the shared Code Fix stage. Do not assume every Code Fix request already executed successfully; use the current request's supplied evidence as the repair authority.\n"
                    + "Preserve the teaching goal, visual intent, and construction meaning.\n"
                    + SystemPrompts.STORYBOARD_REPAIR_AUTHORITY_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + "Use the rendered geometry report as authority for observed layout problems, and use storyboard object_registry dependency facts as semantic authority for how affected geometry must be constructed.\n"
                    + "Prefer adjusting label placement, text positioning, coordinate spacing, and whole-construction scale over removing explanatory content.\n"
                    + "Initial-view readability is mandatory; fix offscreen, underfilled, clustered, text-on-text, and text-on-geometry issues without relying on user zooming.\n"
                    + "For offscreen geometry, first expand the script's `SetCoordSystem(x_min, x_max, y_min, y_max)` range so the existing construction is visible; move/scale construction coordinates only when the expanded viewport would make the scene unreadably sparse or clustered.\n"
                    + "If a reported element is dependency-driven or derived, do not fix it by assigning direct coordinates copied from rendered bounds or storyboard placement; adjust upstream dependency objects, the whole constrained construction, viewport, or native construction command so the dependency remains true.\n"
                    + "Also correct semantically wrong geometric attachments you notice, especially angle markers that sweep the wrong sector.\n"
                    + "Use English GeoGebra command names.\n"
                    + "Preserve Chinese learner-facing visible text from storyboard object content while fixing layout issues.\n"
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + "Do not output Python, JavaScript, or explanations.\n\n"
                    + "Scene evaluation repair requirements:\n"
                    + "1. First identify the affected command/script region, reported elements, and any storyboard_dependency_context supplied in the evaluation report.\n"
                    + "2. Fix text overlap through label repositioning, coordinate spacing, or `SetCaption`/`ShowLabel` adjustments.\n"
                    + "3. Fix offscreen, underfilled, or clustered layouts inside the current code viewport; do not rely on user zooming or panning.\n"
                    + "4. For GeoGebra offscreen issues, prefer expanding `SetCoordSystem(x_min, x_max, y_min, y_max)` before moving construction objects. For Manim offscreen issues, prefer expanding the `Axes`/`NumberPlane` `x_range`/`y_range` used for storyboard coordinates before moving objects; the render frame remains fixed x[-7,7], y[-4,4].\n"
                    + "5. Keep implemented reflections, symmetry, intersections, equal distances, and dependency chains internally consistent.\n"
                    + "Audit the entire command script for similar layout issues, not just the reported elements. The reported issues indicate structural patterns that may appear elsewhere.\n\n"
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT;

    private SceneEvaluationPrompts() {}

    public static String buildLayoutFixRulesPrompt(String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildRulesSection(GEOGEBRA_SYSTEM);
        }
        return SystemPrompts.buildRulesSection(MANIM_SYSTEM);
    }

    public static String buildLayoutFixFixedContextPrompt(ProblemBundle problemBundle,
                                                          String targetDescription,
                                                          String outputTarget) {
        String fixedContext = SystemPrompts.buildWorkflowPrefix(
                "Stage 8 / Scene Evaluation Fix",
                "Revise " + ("geogebra".equalsIgnoreCase(outputTarget) ? "GeoGebra commands" : "Manim code")
                        + " after geometry-based scene evaluation",
                ProblemBundleContextBuilder.displayTitle(problemBundle),
                targetDescription,
                outputTarget)
                + "\n" + ProblemBundleContextBuilder.buildProblemBundleAuthorityContext(problemBundle);
        fixedContext = "geogebra".equalsIgnoreCase(outputTarget)
                ? SystemPrompts.ensureGeoGebraSyntaxManual(fixedContext)
                : SystemPrompts.ensureManimSyntaxManual(fixedContext);
        return SystemPrompts.buildFixedContextSection(fixedContext);
    }

    public static String buildLayoutFixFixedContextPrompt(String legacyTargetConcept,
                                                          String targetDescription,
                                                          String outputTarget) {
        return buildLayoutFixFixedContextPrompt(
                ProblemBundleContextBuilder.legacyBundle(legacyTargetConcept),
                targetDescription,
                outputTarget);
    }

    public static String manimLayoutFixUserPrompt(String storyboardJson,
                                                  String generatedCode,
                                                  String issueSummary,
                                                  String sceneEvaluationJson,
                                                  List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following Manim code is being repaired from a post-render scene evaluation layout report for sampled frames.\n\n")
                .append("Important temporal note:\n")
                .append("The geometry report may sample only selected frames, such as the scene final frame. Reported issues may have existed earlier after the affected object was created. Inspect the full code lifecycle of each reported element and fix the earliest responsible placement/update logic, not only the sampled frame.\n\n")
                .append("Compact storyboard JSON (dependency semantics and scene intent; placement omitted to avoid biasing layout repair):\n```json\n")
                .append(storyboardJson != null && !storyboardJson.isBlank() ? storyboardJson : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON)
                .append("\n```\n\n")
                .append("```python\n").append(generatedCode).append("\n```\n\n")
                .append("Issue summary:\n```\n").append(issueSummary).append("\n```\n\n")
                .append("Scene evaluation report excerpt:\n```json\n").append(sceneEvaluationJson).append("\n```\n")
                .append("When an offscreen issue affects storyboard world-coordinate geometry, repair by expanding the `Axes`/`NumberPlane` coordinate ranges first and keep object placement through the axes coordinate mapping. Do not solve this first by rewriting storyboard coordinates into raw Manim frame positions.\n")
                .append("Preserve the original `class MainScene(...)` base class exactly; do not switch between `Scene`, `VoiceoverScene`, and `ThreeDScene` while applying the layout repair.\n")
                .append("Preserve valid voiceover structure and Chinese learner-facing strings while applying the layout repair.\n");

        PromptUtils.appendFixHistory(sb, fixHistory);
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    public static String geoGebraLayoutFixUserPrompt(String storyboardJson,
                                                     String generatedCode,
                                                     String issueSummary,
                                                     String sceneEvaluationJson,
                                                     List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following GeoGebra command script is being repaired from a post-render scene evaluation layout report.\n\n")
                .append("Compact storyboard JSON (dependency semantics and scene intent; placement omitted to avoid biasing layout repair):\n```json\n")
                .append(storyboardJson != null && !storyboardJson.isBlank() ? storyboardJson : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON)
                .append("\n```\n\n")
                .append("```geogebra\n").append(generatedCode).append("\n```\n\n")
                .append("Issue summary:\n```\n").append(issueSummary).append("\n```\n\n")
                .append("Scene evaluation report excerpt:\n```json\n").append(sceneEvaluationJson).append("\n```\n")
                .append("When an offscreen issue is reported, repair by expanding the script's `SetCoordSystem(...)` range first. Move or rescale construction coordinates only if the expanded viewport would make the construction unreadable.\n")
                .append("Preserve Chinese learner-facing visible text from storyboard object content while applying the layout repair.\n");

        PromptUtils.appendFixHistory(sb, fixHistory);
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }
}
