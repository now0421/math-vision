package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.AiRequestUtils;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.NodeConversationContext;
import com.automanim.util.PromptTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 1b: Visual Design - adds visual specifications to each node.
 *
 * Depth levels are processed foundation-first (deepest steps to depth 0):
 * - Foundational steps establish reusable motifs before later steps are designed.
 * - Nodes at the same depth run concurrently because they only depend on deeper,
 *   already-finalized prerequisite specs plus the shared global style guide.
 */
public class VisualDesignNode extends PocketFlow.Node<KnowledgeGraph, KnowledgeGraph, String> {

    private static final Logger log = LoggerFactory.getLogger(VisualDesignNode.class);

    private static final String VISUAL_DESIGN_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_visual_design\","
            + "    \"description\": \"Return a visual design spec for a teaching-step animation scene.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"visual_description\": { \"type\": \"string\", \"description\": \"Main visual objects and shapes\" },"
            + "        \"color_scheme\": { \"type\": \"string\", \"description\": \"Color scheme summary\" },"
            + "        \"animation_description\": { \"type\": \"string\", \"description\": \"Animation feel and transitions\" },"
            + "        \"transitions\": { \"type\": \"string\", \"description\": \"Scene transition style\" },"
            + "        \"duration\": { \"type\": \"number\", \"description\": \"Duration in seconds\" },"
            + "        \"layout\": { \"type\": \"string\", \"description\": \"Concrete 16:9 canvas layout\" },"
            + "        \"color_palette\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"Preferred Manim color names\" }"
            + "      },"
            + "      \"required\": [\"visual_description\", \"color_scheme\", \"layout\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private final AtomicInteger toolCalls = new AtomicInteger(0);
    private boolean parallelEnabled = true;
    private int maxConcurrent = 4;
    private final java.util.Set<String> globalColorPalette = ConcurrentHashMap.newKeySet();
    private ConcurrencyUtils.AsyncLimiter aiCallLimiter;
    private String globalStyleGuide = "";
    private KnowledgeGraph graph;
    private NodeConversationContext conversationContext;

    public VisualDesignNode() {
        super(1, 0);
    }

    @Override
    public KnowledgeGraph prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        if (workflowConfig != null) {
            this.parallelEnabled = workflowConfig.isParallelVisualDesign();
            this.maxConcurrent = workflowConfig.getMaxConcurrent();
        }
        return (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
    }

    @Override
    public KnowledgeGraph exec(KnowledgeGraph graph) {
        int concurrency = parallelEnabled ? maxConcurrent : 1;
        log.info("=== Stage 1b: Visual Design (parallel={}, concurrency={}) ===",
                parallelEnabled, concurrency);
        toolCalls.set(0);
        globalColorPalette.clear();
        aiCallLimiter = new ConcurrencyUtils.AsyncLimiter(concurrency);
        this.graph = graph;
        this.globalStyleGuide = buildGlobalStyleGuide(graph);

        int maxInputTokens = (workflowConfig != null && workflowConfig.getModelConfig() != null)
                ? workflowConfig.getModelConfig().getMaxInputTokens()
                : 131072;
        String workflowTarget = graph != null ? graph.getTargetConcept() : "";
        this.conversationContext = new NodeConversationContext(maxInputTokens);
        this.conversationContext.setSystemMessage(PromptTemplates.visualDesignSystemPrompt(
                workflowTarget,
                buildWorkflowTargetDescription(graph)));

        try {
            return designGraph(graph);
        } finally {
            aiCallLimiter = null;
        }
    }

    @Override
    public String post(Map<String, Object> ctx, KnowledgeGraph prepRes, KnowledgeGraph graph) {
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);
        int prevCalls = (int) ctx.getOrDefault(WorkflowKeys.ENRICHMENT_TOOL_CALLS, 0);
        ctx.put(WorkflowKeys.ENRICHMENT_TOOL_CALLS, prevCalls + toolCalls.get());

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveEnrichedGraph(outputDir, graph);
        }
        return null;
    }

    private KnowledgeGraph designGraph(KnowledgeGraph graph) {
        Map<Integer, List<KnowledgeNode>> levels = graph.groupByDepth();

        try {
            for (Map.Entry<Integer, List<KnowledgeNode>> entry : levels.entrySet()) {
                int depth = entry.getKey();
                List<KnowledgeNode> nodes = new ArrayList<>();
                for (KnowledgeNode node : entry.getValue()) {
                    if (shouldDesignNode(node)) {
                        nodes.add(node);
                    }
                }
                if (nodes.isEmpty()) {
                    log.info("  Skipping depth {} (no eligible nodes)", depth);
                    continue;
                }
                log.info("  Designing depth {} ({} nodes{})", depth, nodes.size(),
                        parallelEnabled && nodes.size() > 1 ? ", parallel" : "");
                waitForDepth(nodes);
            }
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            throw new RuntimeException("Visual design failed: " + cause.getMessage(), cause);
        }

        log.info("Visual design complete: {} API calls, palette: {}", toolCalls.get(), snapshotPalette());
        return graph;
    }

    private void waitForDepth(List<KnowledgeNode> nodes) {
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (KnowledgeNode node : nodes) {
            tasks.add(designNodeAsync(node));
        }
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
    }

    private CompletableFuture<Void> designNodeAsync(KnowledgeNode node) {
        Map<String, Object> existingSpec = node.getVisualSpec();
        if (existingSpec != null && existingSpec.containsKey("visual_description")) {
            log.debug("  Skipping already-designed node: {}", node.getStep());
            return CompletableFuture.completedFuture(null);
        }

        String equationsInfo = node.getEquations() != null && !node.getEquations().isEmpty()
                ? String.join(", ", node.getEquations()) : "none";

        String prerequisiteSpecContext = buildPrerequisiteSpecContext(node);

        List<String> paletteSnapshot = snapshotPalette();
        String paletteContext = paletteSnapshot.isEmpty()
                ? "No colors have been assigned yet."
                : "Colors already used: " + String.join(", ", paletteSnapshot)
                  + ". Prefer harmonious contrast and avoid unnecessary repetition.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append(buildCurrentStepPrompt(node, equationsInfo));
        String problemDesignContext = buildProblemDesignContext(node);
        if (!problemDesignContext.isBlank()) {
            userPrompt.append("\n\n").append(problemDesignContext);
        }
        userPrompt.append("\n\nGlobal style guide:\n").append(globalStyleGuide)
                .append("\n\n").append(prerequisiteSpecContext)
                .append("\n").append(paletteContext);

        return aiCallLimiter.submit(() -> AiRequestUtils.requestJsonObjectAsync(
                        aiClient,
                        log,
                        node.getStep(),
                        conversationContext,
                        userPrompt.toString(),
                        VISUAL_DESIGN_TOOL,
                        () -> toolCalls.incrementAndGet()
                ))
                .thenAccept(data -> {
                    if (data != null) {
                        applyVisualSpec(node, data);
                        log.debug("  Visual spec set for: {}", node.getStep());
                    }
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.warn("  Visual design failed for '{}': {}", node.getStep(), cause.getMessage());
                    return null;
                });
    }

    /**
     * Designs from foundations upward, so direct prerequisites are finalized
     * before their dependent step is designed.
     */
    private String buildPrerequisiteSpecContext(KnowledgeNode node) {
        List<KnowledgeNode> prerequisites = getNearestPrerequisites(node);
        if (prerequisites.isEmpty()) {
            return "This is a foundation step. Keep the scene concrete, intuitive, and reusable later.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Already-designed prerequisite steps:\n");
        for (KnowledgeNode prerequisite : prerequisites) {
            Map<String, Object> prerequisiteSpec = prerequisite.getVisualSpec();
            sb.append(String.format("- %s%n", prerequisite.getStep()));
            if (prerequisiteSpec == null || prerequisiteSpec.isEmpty()) {
                sb.append("  No visual spec available yet.\n");
                continue;
            }
            if (prerequisiteSpec.containsKey("color_scheme")) {
                sb.append("  Color scheme: ").append(prerequisiteSpec.get("color_scheme")).append("\n");
            }
            if (prerequisiteSpec.containsKey("layout")) {
                sb.append("  Layout style: ").append(prerequisiteSpec.get("layout")).append("\n");
            }
            if (prerequisiteSpec.containsKey("visual_description")) {
                sb.append("  Visual style: ").append(prerequisiteSpec.get("visual_description")).append("\n");
            }
        }
        sb.append("Reuse motifs from these prerequisites so the full animation feels like one system.");
        return sb.toString();
    }

    private List<KnowledgeNode> getNearestPrerequisites(KnowledgeNode node) {
        List<KnowledgeNode> prerequisites = graph.getPrerequisites(node.getId());
        if (prerequisites.isEmpty()) {
            return prerequisites;
        }

        int expectedDepth = node.getMinDepth() + 1;
        List<KnowledgeNode> nearest = new ArrayList<>();
        for (KnowledgeNode prerequisite : prerequisites) {
            if (prerequisite.getMinDepth() == expectedDepth) {
                nearest.add(prerequisite);
            }
        }

        return nearest.isEmpty() ? prerequisites : nearest;
    }

    private String buildGlobalStyleGuide(KnowledgeGraph graph) {
        return String.format(
                "Treat every scene as part of one coherent animation about %s. "
                + "Start with concrete, approachable visuals for foundational ideas, then "
                + "gradually increase abstraction toward the final teaching step. "
                + "Keep layout grammar, motion rhythm, recurring shapes, and overall palette "
                + "consistent across all nodes.",
                graph.getTargetConcept()
        );
    }

    private boolean shouldDesignNode(KnowledgeNode node) {
        return node != null;
    }

    private String buildWorkflowTargetDescription(KnowledgeGraph graph) {
        KnowledgeNode root = graph != null ? graph.getRootNode() : null;
        if (graph != null && graph.isProblemMode()) {
            StringBuilder sb = new StringBuilder();
            appendLabeledLine(sb, "Original problem", graph.getTargetConcept());
            appendLabeledLine(sb, "Final conclusion", root != null ? root.getStep() : "");
            if (root != null && root.getReason() != null && !root.getReason().isBlank()) {
                appendLabeledLine(sb, "Conclusion reason", root.getReason());
            }
            String solutionChain = buildProblemSolutionChainSummary();
            if (!solutionChain.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("Ordered solution-step chain:\n").append(solutionChain);
            }
            String detailedTarget = sb.toString().trim();
            if (!detailedTarget.isEmpty()) {
                return detailedTarget;
            }
        }
        return PromptTemplates.workflowTargetDescription(
                graph != null ? graph.getTargetConcept() : "",
                root != null ? root.getStep() : "",
                root != null ? root.getReason() : "",
                graph != null && graph.isProblemMode());
    }

    private String buildCurrentStepPrompt(KnowledgeNode node, String equationsInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current step:\n");
        sb.append("- Step: ").append(node.getStep()).append("\n");
        sb.append("- Node type: ").append(node.getNodeType()).append("\n");
        sb.append("- Depth: ").append(node.getMinDepth()).append("\n");
        sb.append("- Relevant equations: ").append(equationsInfo).append("\n");
        if (node.getReason() != null && !node.getReason().isBlank()) {
            sb.append("- Reason from Stage 0: ").append(node.getReason().trim()).append("\n");
        }
        if (graph != null && graph.isProblemMode()) {
            sb.append("- Target problem: ").append(graph.getTargetConcept()).append("\n");

            List<KnowledgeNode> dependents = graph.getDependents(node.getId());
            if (!dependents.isEmpty()) {
                sb.append("Direct downstream steps:\n");
                for (KnowledgeNode dependent : dependents) {
                    appendStepLine(sb, dependent);
                }
            }
        }
        sb.append("Design the visuals for this current step only, while staying consistent with"
                + " the full problem and solution path.\n");
        return sb.toString().trim();
    }

    private String buildProblemDesignContext(KnowledgeNode currentStep) {
        if (graph == null || currentStep == null || !graph.isProblemMode()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Problem context (source of truth):\n");
        appendLabeledLine(sb, "- Statement", graph.getTargetConcept());

        List<KnowledgeNode> prerequisites = graph.getPrerequisites(currentStep.getId());
        if (!prerequisites.isEmpty()) {
            sb.append("Direct prerequisite steps for this node:\n");
            for (KnowledgeNode prerequisite : prerequisites) {
                sb.append(String.format("- [%s] %s", prerequisite.getNodeType(), prerequisite.getStep()));
                if (prerequisite.getReason() != null && !prerequisite.getReason().isBlank()) {
                    sb.append(" - ").append(prerequisite.getReason().trim());
                }
                sb.append("\n");
            }
        }

        String solutionChain = buildProblemSolutionChainSummary(currentStep);
        if (!solutionChain.isBlank()) {
            sb.append("Ordered solution-step chain (do not invent extra steps):\n")
                    .append(solutionChain);
        }
        sb.append("Design only the current step, but keep object identities, notation, and spatial"
                + " anchors consistent with the full solution.\n");
        return sb.toString().trim();
    }

    private String buildProblemSolutionChainSummary() {
        return buildProblemSolutionChainSummary(null);
    }

    private String buildProblemSolutionChainSummary(KnowledgeNode currentStep) {
        if (graph == null || !graph.isProblemMode()) {
            return "";
        }

        List<KnowledgeNode> steps = graph.topologicalOrder();
        if (steps.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            KnowledgeNode step = steps.get(i);
            String marker = currentStep != null && step.getId().equals(currentStep.getId()) ? "-> " : "   ";
            sb.append(marker)
                    .append(i + 1)
                    .append(". [")
                    .append(step.getNodeType())
                    .append("] ")
                    .append(step.getStep());
            if (step.getReason() != null && !step.getReason().isBlank()) {
                sb.append(" - ").append(step.getReason().trim());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void appendLabeledLine(StringBuilder sb, String label, String value) {
        if (sb == null || value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append(": ").append(value.trim()).append("\n");
    }

    private void appendStepLine(StringBuilder sb, KnowledgeNode step) {
        if (sb == null || step == null) {
            return;
        }
        sb.append("- [")
                .append(step.getNodeType())
                .append("] ")
                .append(step.getStep());
        if (step.getReason() != null && !step.getReason().isBlank()) {
            sb.append(" - ").append(step.getReason().trim());
        }
        sb.append("\n");
    }

    private void applyVisualSpec(KnowledgeNode node, JsonNode data) {
        Map<String, Object> visualSpec = node.getVisualSpec();
        if (visualSpec == null) {
            visualSpec = new LinkedHashMap<>();
        }

        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();

            if ("color_palette".equals(key) && value.isArray()) {
                List<String> nodeColors = new ArrayList<>();
                for (JsonNode color : value) {
                    String colorName = color.asText();
                    nodeColors.add(colorName);
                    globalColorPalette.add(colorName);
                }
                visualSpec.put(key, nodeColors);
            } else if ("duration".equals(key) && value.isNumber()) {
                visualSpec.put(key, value.numberValue());
            } else {
                visualSpec.put(key, value.asText());
            }
        }

        node.setVisualSpec(visualSpec);
    }

    private List<String> snapshotPalette() {
        List<String> palette = new ArrayList<>(globalColorPalette);
        palette.sort(String.CASE_INSENSITIVE_ORDER);
        return palette;
    }
}
