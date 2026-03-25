package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.WorkflowKeys;
import com.automanim.prompt.ToolSchemas;
import com.automanim.prompt.VisualDesignPrompts;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.AiRequestUtils;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.NodeConversationContext;
import com.automanim.util.TargetDescriptionBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
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

        int maxInputTokens = TargetDescriptionBuilder.resolveMaxInputTokens(workflowConfig);
        String workflowTarget = graph != null ? graph.getTargetConcept() : "";
        this.conversationContext = new NodeConversationContext(maxInputTokens);
        this.conversationContext.setSystemMessage(VisualDesignPrompts.systemPrompt(
                workflowTarget,
                TargetDescriptionBuilder.build(graph, null)));

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

        String prerequisiteSpecContext = buildPrerequisiteSpecContext(node);

        List<String> paletteSnapshot = snapshotPalette();
        String paletteContext = paletteSnapshot.isEmpty()
                ? "No colors have been assigned yet."
                : "Colors already used: " + String.join(", ", paletteSnapshot)
                  + ". Prefer harmonious contrast and avoid unnecessary repetition.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append(buildCurrentStepPrompt(node));
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
                        ToolSchemas.VISUAL_DESIGN,
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
                sb.append("  Relative layout: ").append(prerequisiteSpec.get("layout")).append("\n");
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

    private String buildCurrentStepPrompt(KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current step:\n");
        sb.append("- Step: ").append(node.getStep()).append("\n");
        if (node.getEquations() != null && !node.getEquations().isEmpty()) {
            sb.append("Equations:\n");
            for (String equation : node.getEquations()) {
                sb.append("- ").append(equation).append("\n");
            }
        }
        if (node.getDefinitions() != null && !node.getDefinitions().isEmpty()) {
            sb.append("Definitions:\n");
            node.getDefinitions().forEach((symbol, definition) ->
                    sb.append("- ").append(symbol).append(": ").append(definition).append("\n")
            );
        }
        if (graph != null && graph.isProblemMode()) {
            sb.append("- Target problem: ").append(graph.getTargetConcept()).append("\n");

            List<KnowledgeNode> dependents = graph.getDependents(node.getId());
            if (!dependents.isEmpty()) {
                sb.append("Direct downstream steps:\n");
                for (KnowledgeNode dependent : dependents) {
                    sb.append("- ").append(dependent.getStep()).append("\n");
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
        sb.append("- Statement: ").append(graph.getTargetConcept()).append("\n");

        List<KnowledgeNode> prerequisites = graph.getPrerequisites(currentStep.getId());
        if (!prerequisites.isEmpty()) {
            sb.append("Direct prerequisite steps for this node:\n");
            for (KnowledgeNode prerequisite : prerequisites) {
                sb.append("- ").append(prerequisite.getStep()).append("\n");
            }
        }

        String solutionChain = TargetDescriptionBuilder.buildSolutionChain(graph, currentStep);
        if (!solutionChain.isBlank()) {
            sb.append("Ordered solution-step chain (do not invent extra steps):\n")
                    .append(solutionChain);
        }
        sb.append("Design only the current step, but keep object identities, notation, and"
                + " relative spatial relationships consistent with the full solution.\n");
        return sb.toString().trim();
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
