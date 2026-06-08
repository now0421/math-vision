package com.mathvision.util;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.ProblemBundle;
import com.mathvision.prompt.SystemPrompts;

/**
 * Builds prompt context from the authoritative Stage 0 ProblemBundle.
 */
public final class ProblemBundleContextBuilder {

    private ProblemBundleContextBuilder() {}

    public static ProblemBundle legacyBundle(String title) {
        ProblemBundle bundle = new ProblemBundle();
        bundle.setId("legacy");
        bundle.setTitle(TextUtils.firstNonBlank(title, "User-provided math input"));
        bundle.setStatement(TextUtils.firstNonBlank(title, "User-provided math input"));
        return bundle;
    }

    public static String displayTitle(ProblemBundle bundle) {
        if (bundle == null) {
            return "User-provided math input";
        }
        return TextUtils.firstNonBlank(
                bundle.getStatement(),
                bundle.getTitle(),
                bundle.getId(),
                "User-provided math input");
    }

    public static boolean isProblemMode(ProblemBundle bundle) {
        if (bundle == null) {
            return false;
        }
        String inputMode = WorkflowConfig.normalizeInputMode(bundle.getInputMode());
        if (WorkflowConfig.INPUT_MODE_PROBLEM.equals(inputMode)) {
            return true;
        }
        if (WorkflowConfig.INPUT_MODE_CONCEPT.equals(inputMode)) {
            return false;
        }

        String normalized = displayTitle(bundle).trim().toLowerCase(java.util.Locale.ROOT);
        int wordCount = normalized.isBlank() ? 0 : normalized.split("\\s+").length;
        return normalized.contains("?")
                || normalized.contains("problem")
                || normalized.contains("prove")
                || normalized.contains("show that")
                || normalized.contains("solve")
                || normalized.contains("find")
                || normalized.contains("determine")
                || normalized.contains("minimize")
                || normalized.contains("maximize")
                || normalized.contains("minimum")
                || normalized.contains("maximum")
                || normalized.contains("given")
                || normalized.contains("let ")
                || wordCount > 12;
    }

    public static String workflowTargetDescription(ProblemBundle bundle,
                                                   String terminalConcept,
                                                   String terminalDescription,
                                                   String outputTarget) {
        String safeTarget = displayTitle(bundle);
        String safeTerminalConcept = TextUtils.defaultIfBlank(
                TextUtils.firstNonBlank(terminalConcept, safeTarget), safeTarget);
        String safeTerminalDescription = TextUtils.defaultIfBlank(
                TextUtils.firstNonBlank(terminalDescription), "");
        boolean geoGebraTarget = WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equalsIgnoreCase(outputTarget);
        boolean problemMode = isProblemMode(bundle);

        String mediumNoun = geoGebraTarget ? "interactive geometry construction" : "teaching animation";
        String mediumObject = geoGebraTarget ? "construction" : "animation";
        String culminationVerb = geoGebraTarget ? "culminate in the final construction insight" : "culminate in the final conclusion";

        if (problemMode) {
            if (!safeTerminalDescription.isEmpty()) {
                return String.format(
                        "Explain and solve the math problem described by the ProblemBundle through a coherent %s. The"
                                + " goal is not only to reach the answer, but to help the viewer"
                                + " understand why it works. The %s should %s \"%s\": %s",
                        mediumNoun, mediumObject, culminationVerb, safeTerminalConcept, safeTerminalDescription);
            }
            return String.format(
                    "Explain and solve the math problem described by the ProblemBundle through a coherent %s that leads from the opening hook"
                            + " to \"%s\" while helping the viewer understand the reasoning.",
                    mediumNoun, safeTerminalConcept);
        }

        if (!safeTerminalDescription.isEmpty()) {
            return safeTerminalDescription;
        }
        return String.format(
                "Explain the concept described by the ProblemBundle through a coherent %s that progresses from the first teaching beat"
                        + " to \"%s\".",
                mediumNoun, safeTerminalConcept);
    }

    public static String buildProblemBundleAuthorityContext(ProblemBundle bundle) {
        ProblemBundle safeBundle = bundle != null ? bundle : legacyBundle("User-provided math input");
        return "ProblemBundle JSON (authoritative workflow input):\n"
                + JsonUtils.toPrettyJson(safeBundle)
                + "\n\nField roles:\n"
                + "- `statement` is the normalized human-readable problem or concept text.\n"
                + "- `input_mode` selects the concept or problem workflow when explicit.\n"
                + "- `scene_mode` defines the dimensionality all later visual stages must preserve.\n"
                + "- `diagram.description` summarizes the source-observed figure when present.\n"
                + "- `diagram.objects` declares source-observed objects and their identities.\n"
                + "- `diagram.constraints` and object-level `constraints` are authoritative hard geometry contracts.\n"
                + "- `diagram.construction_notes` are source-diagram construction requirements for downstream stages.\n"
                + "When the statement is ambiguous, preserve the diagram constraints and construction notes exactly.";
    }

    public static String buildWorkflowPrefix(String stageLabel,
                                             String substepLabel,
                                             ProblemBundle bundle,
                                             String outputTarget,
                                             String terminalConcept,
                                             String terminalDescription) {
        return SystemPrompts.buildWorkflowPrefix(
                stageLabel,
                substepLabel,
                displayTitle(bundle),
                workflowTargetDescription(bundle, terminalConcept, terminalDescription, outputTarget),
                outputTarget);
    }
}
