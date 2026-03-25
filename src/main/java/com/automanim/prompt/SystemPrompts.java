package com.automanim.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared workflow prompt helpers and Manim syntax-manual loading.
 */
public final class SystemPrompts {

    private static final String MANIM_SYNTAX_MANUAL_RESOURCE = "llm/manim_syntax_manual.md";
    private static final String WORKFLOW_OVERVIEW =
            "Stage 0 Exploration -> Stage 1a Mathematical Enrichment -> Stage 1b Visual Design"
                    + " -> Stage 1c Narrative Composition -> Stage 2 Code Generation"
                    + " -> Stage 3 Code Evaluation -> Stage 4 Render Fix";

    private SystemPrompts() {}

    private static final class ManimSyntaxManualHolder {
        private static final String VALUE = loadPromptResource(MANIM_SYNTAX_MANUAL_RESOURCE);
    }

    public static String sanitize(String text, String defaultValue) {
        if (text == null) {
            return defaultValue;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? defaultValue : normalized;
    }

    public static String buildWorkflowPrefix(String stageLabel,
                                             String substepLabel,
                                             String targetTitle,
                                             String targetDescription,
                                             boolean manimSpecific) {
        String workflowLabel = manimSpecific
                ? "multi-stage Manim animation generation workflow"
                : "multi-stage teaching animation generation workflow";
        return "You are working inside a " + workflowLabel + ".\n"
                + "Current workflow stage: " + sanitize(stageLabel, "Unknown stage") + "\n"
                + "Current substep: " + sanitize(substepLabel, "Unknown substep") + "\n"
                + "Overall workflow: " + WORKFLOW_OVERVIEW + "\n"
                + "Final animation target: " + sanitize(targetTitle, "Unknown target") + "\n"
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
