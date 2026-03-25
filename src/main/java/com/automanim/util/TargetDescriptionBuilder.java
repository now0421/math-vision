package com.automanim.util;

import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;

import java.util.List;
import java.util.Map;

/**
 * Builds workflow target descriptions and compact node context shared across stages.
 */
public final class TargetDescriptionBuilder {

    private static final int MAX_CHAIN_LENGTH = 15;
    private static final int MAX_REASON_LENGTH = 200;

    private TargetDescriptionBuilder() {}

    private static String sanitize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    /**
     * Builds a workflow target description including concept and problem context.
     */
    public static String build(KnowledgeGraph graph, KnowledgeNode currentNode) {
        if (graph == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        String targetConcept = graph.getTargetConcept();
        if (targetConcept != null && !targetConcept.isBlank()) {
            sb.append("Workflow target: ").append(targetConcept.trim());
        }

        if (graph.isProblemMode()) {
            sb.append("\n\nThis is a problem-solving workflow. The target is a math problem to solve.");
        }

        if (currentNode != null) {
            sb.append("\n\nCurrent step: ").append(currentNode.getStep());
            if (currentNode.getReason() != null && !currentNode.getReason().isBlank()) {
                String reason = currentNode.getReason().trim();
                if (reason.length() > MAX_REASON_LENGTH) {
                    reason = reason.substring(0, MAX_REASON_LENGTH) + "...";
                }
                sb.append("\nWhy this step matters: ").append(reason);
            }
        }

        return sb.toString().trim();
    }

    /**
     * Builds the workflow target description used by prompt stages.
     */
    public static String workflowTargetDescription(String targetConcept,
                                                   String rootConcept,
                                                   String rootDescription,
                                                   boolean problemMode) {
        String safeTarget = sanitize(targetConcept, "Unknown target");
        String safeRootConcept = sanitize(rootConcept, safeTarget);
        String safeRootDescription = sanitize(rootDescription, "");

        if (problemMode) {
            if (!safeRootDescription.isEmpty()) {
                return String.format(
                        "Explain and solve the math problem \"%s\" through a coherent teaching"
                                + " animation. The goal is not only to reach the answer, but to"
                                + " help the viewer understand why it works. The animation should"
                                + " culminate in the final conclusion \"%s\": %s",
                        safeTarget, safeRootConcept, safeRootDescription);
            }
            return String.format(
                    "Explain and solve the math problem \"%s\" through a coherent teaching"
                            + " animation that leads to the final conclusion \"%s\" while helping"
                            + " the viewer understand the reasoning.",
                    safeTarget, safeRootConcept);
        }

        if (!safeRootDescription.isEmpty()) {
            return safeRootDescription;
        }
        return String.format(
                "Explain the concept \"%s\" through a coherent teaching animation built from the"
                        + " necessary prerequisites up to the final idea.",
                safeTarget);
    }

    /**
     * Builds a compact problem solution chain summary for prompts.
     */
    public static String buildSolutionChain(KnowledgeGraph graph, KnowledgeNode currentStep) {
        if (graph == null || !graph.isProblemMode()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Problem: ").append(graph.getTargetConcept()).append("\n\n");
        sb.append("Solution step chain:\n");

        List<KnowledgeNode> ordered = graph.topologicalOrder();
        int stepNumber = 1;
        int currentStepNumber = -1;

        for (KnowledgeNode node : ordered) {
            if (stepNumber > MAX_CHAIN_LENGTH) {
                sb.append("... (").append(ordered.size() - MAX_CHAIN_LENGTH).append(" more steps)\n");
                break;
            }

            String marker = "";
            if (currentStep != null && node.getId().equals(currentStep.getId())) {
                marker = " <-- current";
                currentStepNumber = stepNumber;
            }

            sb.append(stepNumber).append(". ").append(node.getStep());

            String nodeType = node.getNodeType();
            if (nodeType != null && !nodeType.isBlank()
                    && !KnowledgeNode.NODE_TYPE_CONCEPT.equals(nodeType)) {
                sb.append(" [").append(nodeType).append("]");
            }

            sb.append(marker).append("\n");

            if (node.getReason() != null && !node.getReason().isBlank()) {
                String reason = node.getReason().trim();
                if (reason.length() > MAX_REASON_LENGTH) {
                    reason = reason.substring(0, MAX_REASON_LENGTH) + "...";
                }
                sb.append("   -> ").append(reason).append("\n");
            }

            stepNumber++;
        }

        if (currentStepNumber > 0) {
            sb.append("\nCurrently processing step ").append(currentStepNumber)
                    .append(" of ").append(Math.min(ordered.size(), MAX_CHAIN_LENGTH)).append(".");
        }

        return sb.toString().trim();
    }

    /**
     * Builds prerequisite context for a node, including enrichment and visual specs.
     */
    public static String buildPrerequisiteContext(KnowledgeGraph graph, KnowledgeNode node) {
        if (graph == null || node == null) {
            return "";
        }

        List<KnowledgeNode> prerequisites = graph.getPrerequisites(node.getId());
        if (prerequisites.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Prerequisites for this step:\n");

        for (KnowledgeNode prereq : prerequisites) {
            sb.append("- ").append(prereq.getStep());
            if (prereq.isFoundation()) {
                sb.append(" [foundation]");
            }
            sb.append("\n");

            if (prereq.getInterpretation() != null && !prereq.getInterpretation().isBlank()) {
                String interp = prereq.getInterpretation().trim();
                if (interp.length() > 150) {
                    interp = interp.substring(0, 150) + "...";
                }
                sb.append("  Interpretation: ").append(interp).append("\n");
            }

            if (prereq.hasVisualSpec()) {
                Map<String, Object> spec = prereq.getVisualSpec();
                if (spec.containsKey("visual_description")) {
                    String desc = String.valueOf(spec.get("visual_description")).trim();
                    if (desc.length() > 150) {
                        desc = desc.substring(0, 150) + "...";
                    }
                    sb.append("  Visual: ").append(desc).append("\n");
                }
            }
        }

        return sb.toString().trim();
    }

    /**
     * Builds the default max input tokens from config or fallback.
     */
    public static int resolveMaxInputTokens(com.automanim.config.WorkflowConfig config) {
        if (config != null && config.getModelConfig() != null) {
            return config.getModelConfig().getMaxInputTokens();
        }
        return 131072;
    }
}
