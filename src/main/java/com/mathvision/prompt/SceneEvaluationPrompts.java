package com.mathvision.prompt;

import java.util.List;

/**
 * Prompts for Stage 5: geometry-based scene-evaluation fixes.
 */
public final class SceneEvaluationPrompts {

    private static final String MANIM_SYSTEM =
            "You are fixing Manim code that rendered but has layout issues detected by geometry analysis.\n"
                    + "Preserve the teaching goal, visual intent, scene class name, and continuity.\n"
                    + SystemPrompts.STORYBOARD_REPAIR_AUTHORITY_RULES
                    + "Use the rendered geometry report as authority for observed layout problems, and use storyboard object_registry dependency facts as semantic authority for how affected geometry must be constructed.\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + "Prefer adjusting positioning, scaling, grouping, and spacing over deleting explanatory content.\n"
                    + "For frame repair, use translation/recentering and uniform scaling of independent overlays, source objects, or whole constrained groups as the default first-choice strategy before changing geometric constructions or attachment logic.\n"
                    + "If a reported element is dependency-driven or derived, do not fix it by assigning direct coordinates copied from rendered bounds or storyboard placement; adjust upstream dependency objects, the whole constrained group, camera/layout, or the attachment expression so the dependency remains true.\n"
                    + "Also correct semantically wrong geometric attachments you notice, especially angle markers that are drawn on the wrong side or detached from their true vertex.\n\n"
                    + "Scene evaluation repair requirements:\n"
                    + "1. First identify the affected code scene(s), reported elements, and any storyboard_dependency_context supplied in the evaluation report.\n"
                    + "2. When a layout issue is detected in a sampled frame, do not assume the problem only exists at that sampled instant. Trace each reported element back to where it is first created, positioned, attached, or updated, then repair the earliest responsible placement, attachment, updater, camera framing, or group layout so it remains valid for all frames after it appears.\n"
                    + "3. Do not fix scene-final layout issues by adding a late one-off animation immediately before the final wait, such as shifting a persistent label only at the end, unless the issue is caused exclusively by a final-scene-only object or final-scene-only transition.\n"
                    + "4. For persistent labels, points, segments, and derived objects, repair their initial placement, `next_to` direction, updater, group transform, camera framing, or upstream geometry instead of adding a terminal patch.\n"
                    + "5. For overlap and offscreen repair, first try translation/recentering and uniform scaling of the affected overlay, upstream source objects, or constrained group before changing geometry or redefining attachments.\n"
                    + "6. Fix overlap only through text/overlay layout changes, spacing, grouping, recentering, or uniform scaling of constrained groups.\n"
                    + "7. Fix offscreen issues using readable frame composition; storyboard `safe_area_plan` and `layout_goal` are hints, not strict requirements.\n"
                    + "8. Keep implemented reflections, symmetry, intersections, equal distances, and anchor-follow relationships internally consistent.\n"
                    + "9. Prefer cleaning up temporary annotations or stale overlays over covering them with new opaque cards.\n"
                    + "10. Preserve a readable empty zone for overlays and key conclusions.\n"
                    + "Audit the entire file for similar layout issues, not just the reported elements. The reported issues indicate structural patterns that may appear elsewhere.\n"
                    + "Also proactively check for common Python and Manim runtime mistakes.\n\n"
                    + SystemPrompts.MANIM_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_SYSTEM =
            "You are fixing a GeoGebra command script that executed but has layout issues detected by geometry analysis.\n"
                    + "Preserve the teaching goal, visual intent, and construction meaning.\n"
                    + SystemPrompts.STORYBOARD_REPAIR_AUTHORITY_RULES
                    + "Use the rendered geometry report as authority for observed layout problems, and use storyboard object_registry dependency facts as semantic authority for how affected geometry must be constructed.\n"
                    + "Prefer adjusting label placement, text positioning, coordinate spacing, and whole-construction scale over removing explanatory content.\n"
                    + "Initial-view readability is mandatory; fix offscreen, underfilled, clustered, text-on-text, and text-on-geometry issues without relying on user zooming.\n"
                    + "If a reported element is dependency-driven or derived, do not fix it by assigning direct coordinates copied from rendered bounds or storyboard placement; adjust upstream dependency objects, the whole constrained construction, viewport, or native construction command so the dependency remains true.\n"
                    + "Also correct semantically wrong geometric attachments you notice, especially angle markers that sweep the wrong sector.\n"
                    + "Use English GeoGebra command names.\n"
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + "Do not output Python, JavaScript, or explanations.\n\n"
                    + "Scene evaluation repair requirements:\n"
                    + "1. First identify the affected command/script region, reported elements, and any storyboard_dependency_context supplied in the evaluation report.\n"
                    + "2. Fix text overlap through label repositioning, coordinate spacing, or `SetCaption`/`ShowLabel` adjustments.\n"
                    + "3. Fix offscreen, underfilled, or clustered layouts inside the initial viewport; do not rely on user zooming or panning.\n"
                    + "4. Preserve `SetCoordSystem(-7, 7, -4, 4)` unless the evaluation report explicitly asks for a different fixed view.\n"
                    + "5. Keep implemented reflections, symmetry, intersections, equal distances, and dependency chains internally consistent.\n"
                    + "Audit the entire command script for similar layout issues, not just the reported elements. The reported issues indicate structural patterns that may appear elsewhere.\n\n"
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT;

    private SceneEvaluationPrompts() {}

    public static String buildLayoutFixRulesPrompt(String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildRulesSection(
                    SystemPrompts.ensureGeoGebraSyntaxManual(GEOGEBRA_SYSTEM));
        }
        return SystemPrompts.buildRulesSection(
                SystemPrompts.ensureManimSyntaxManual(MANIM_SYSTEM));
    }

    public static String buildLayoutFixFixedContextPrompt(String targetConcept,
                                                          String targetDescription,
                                                          String outputTarget) {
        return SystemPrompts.buildFixedContextSection(SystemPrompts.buildWorkflowPrefix(
                "Stage 5 / Scene Evaluation Fix",
                "Revise " + ("geogebra".equalsIgnoreCase(outputTarget) ? "GeoGebra commands" : "Manim code")
                        + " after geometry-based scene evaluation",
                targetConcept,
                targetDescription,
                outputTarget
        ));
    }

    public static String manimLayoutFixUserPrompt(String storyboardJson,
                                             String generatedCode,
                                             String issueSummary,
                                             String sceneEvaluationJson,
                                             List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following Manim code rendered, but post-render scene evaluation found layout issues in sampled frames.\n\n")
                .append("Important temporal note:\n")
                .append("The geometry report may sample only selected frames, such as the scene final frame. Reported issues may have existed earlier after the affected object was created. Inspect the full code lifecycle of each reported element and fix the earliest responsible placement/update logic, not only the sampled frame.\n\n")
                .append("Compact storyboard JSON (dependency semantic authority; derived-object placements are intentionally omitted):\n```json\n")
                .append(storyboardJson != null && !storyboardJson.isBlank() ? storyboardJson : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON)
                .append("\n```\n\n")
                .append("```python\n").append(generatedCode).append("\n```\n\n")
                .append("Issue summary:\n```\n").append(issueSummary).append("\n```\n\n")
                .append("Scene evaluation report excerpt:\n```json\n").append(sceneEvaluationJson).append("\n```\n");

        PromptUtils.appendFixHistory(sb, fixHistory);
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    public static String geoGebraLayoutFixUserPrompt(String storyboardJson,
                                                     String generatedCode,
                                                     String issueSummary,
                                                     String sceneEvaluationJson,
                                                     List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following GeoGebra command script executed, but post-render scene evaluation found layout issues.\n\n")
                .append("Compact storyboard JSON (dependency semantic authority; derived-object placements are intentionally omitted):\n```json\n")
                .append(storyboardJson != null && !storyboardJson.isBlank() ? storyboardJson : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON)
                .append("\n```\n\n")
                .append("```geogebra\n").append(generatedCode).append("\n```\n\n")
                .append("Issue summary:\n```\n").append(issueSummary).append("\n```\n\n")
                .append("Scene evaluation report excerpt:\n```json\n").append(sceneEvaluationJson).append("\n```\n");

        PromptUtils.appendFixHistory(sb, fixHistory);
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }
}
