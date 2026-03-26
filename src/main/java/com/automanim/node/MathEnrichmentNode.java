package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.WorkflowKeys;
import com.automanim.prompt.EnrichmentPrompts;
import com.automanim.prompt.ToolSchemas;
import com.automanim.service.AiClient;
import com.automanim.util.AiRequestUtils;
import com.automanim.util.ConceptUtils;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.NodeConversationContext;
import com.automanim.util.TargetDescriptionBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 1a: Mathematical Enrichment - adds equations and definitions to each
 * node in the knowledge graph.
 */
public class MathEnrichmentNode extends PocketFlow.Node<KnowledgeGraph, KnowledgeGraph, String> {

    private static final Logger log = LoggerFactory.getLogger(MathEnrichmentNode.class);

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private final AtomicInteger toolCalls = new AtomicInteger(0);
    private boolean parallelEnabled = true;
    private int maxConcurrent = 4;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private final Map<String, CompletableFuture<JsonNode>> cache = new ConcurrentHashMap<>();
    private ConcurrencyUtils.AsyncLimiter aiCallLimiter;
    private NodeConversationContext conversationContext;
    private KnowledgeGraph graph;

    public MathEnrichmentNode() {
        super(1, 0);
    }

    @Override
    public KnowledgeGraph prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        if (workflowConfig != null) {
            this.parallelEnabled = workflowConfig.isParallelMathEnrichment();
            this.maxConcurrent = workflowConfig.getMaxConcurrent();
            this.outputTarget = workflowConfig.getOutputTarget();
        }
        return (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
    }

    @Override
    public KnowledgeGraph exec(KnowledgeGraph graph) {
        int concurrency = parallelEnabled ? maxConcurrent : 1;
        log.info("=== Stage 1a: Mathematical Enrichment (output_target={}, parallel={}, concurrency={}) ===",
                outputTarget, parallelEnabled, concurrency);
        toolCalls.set(0);
        cache.clear();
        aiCallLimiter = new ConcurrencyUtils.AsyncLimiter(concurrency);
        this.graph = graph;

        int maxInputTokens = TargetDescriptionBuilder.resolveMaxInputTokens(workflowConfig);
        String workflowTarget = graph != null ? graph.getTargetConcept() : "";
        this.conversationContext = new NodeConversationContext(maxInputTokens);
        this.conversationContext.setSystemMessage(EnrichmentPrompts.systemPrompt(
                workflowTarget,
                TargetDescriptionBuilder.build(graph, null)));

        try {
            return enrichGraph(graph);
        } finally {
            aiCallLimiter = null;
            this.graph = null;
        }
    }

    @Override
    public String post(Map<String, Object> ctx, KnowledgeGraph prepRes, KnowledgeGraph graph) {
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);
        int prevCalls = (int) ctx.getOrDefault(WorkflowKeys.ENRICHMENT_TOOL_CALLS, 0);
        ctx.put(WorkflowKeys.ENRICHMENT_TOOL_CALLS, prevCalls + toolCalls.get());
        return null;
    }

    private KnowledgeGraph enrichGraph(KnowledgeGraph graph) {
        Map<Integer, List<KnowledgeNode>> levels = graph.groupByDepth();

        try {
            for (Map.Entry<Integer, List<KnowledgeNode>> entry : levels.entrySet()) {
                int depth = entry.getKey();
                List<KnowledgeNode> nodes = new ArrayList<>();
                for (KnowledgeNode node : entry.getValue()) {
                    if (shouldEnrichNode(node)) {
                        nodes.add(node);
                    }
                }
                if (nodes.isEmpty()) {
                    log.info("  Skipping depth {} (no eligible nodes)", depth);
                    continue;
                }
                log.info("  Enriching depth {} ({} nodes{})", depth, nodes.size(),
                        parallelEnabled && nodes.size() > 1 ? ", parallel" : "");

                List<CompletableFuture<Void>> tasks = new ArrayList<>();
                for (KnowledgeNode node : nodes) {
                    tasks.add(enrichNodeAsync(node));
                }
                CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
            }
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            throw new RuntimeException("Math enrichment failed: " + cause.getMessage(), cause);
        }

        log.info("Mathematical enrichment complete: {} API calls, {} cache entries",
                toolCalls.get(), cache.size());
        return graph;
    }

    private CompletableFuture<Void> enrichNodeAsync(KnowledgeNode node) {
        if (node.isEnriched()) {
            log.debug("  Skipping already-enriched node: {}", node.getStep());
            return CompletableFuture.completedFuture(null);
        }

        return getCachedContentAsync(node)
                .thenAccept(data -> {
                    if (data != null) {
                        applyContent(node, data);
                    }
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.warn("  Math enrichment failed for '{}': {}", node.getStep(), cause.getMessage());
                    return null;
                });
    }

    private CompletableFuture<JsonNode> getCachedContentAsync(KnowledgeNode node) {
        String cacheKey = buildCacheKey(node);
        CompletableFuture<JsonNode> existing = cache.get(cacheKey);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<JsonNode> created = fetchMathContentAsync(node);
        CompletableFuture<JsonNode> prior = cache.putIfAbsent(cacheKey, created);
        if (prior != null) {
            return prior;
        }

        created.whenComplete((ignored, error) -> {
            if (error != null) {
                cache.remove(cacheKey, created);
            }
        });
        return created;
    }

    private CompletableFuture<JsonNode> fetchMathContentAsync(KnowledgeNode node) {
        String userPrompt = buildCurrentStepPrompt(node);

        return aiCallLimiter.submit(() -> AiRequestUtils.requestJsonObjectAsync(
                aiClient,
                log,
                node.getStep(),
                conversationContext,
                userPrompt,
                ToolSchemas.MATH_ENRICHMENT,
                () -> toolCalls.incrementAndGet()
        ));
    }

    private String buildCacheKey(KnowledgeNode node) {
        String stepKey = ConceptUtils.normalizeConcept(node.getStep());
        String reasonKey = node.getReason() == null
                ? ""
                : node.getReason().trim().toLowerCase();
        return stepKey + "||" + reasonKey;
    }

    private boolean shouldEnrichNode(KnowledgeNode node) {
        return node != null;
    }

    private String buildCurrentStepPrompt(KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current step:\n");
        sb.append("- Presentation target: ").append(outputTarget).append("\n");
        sb.append("- Step: ").append(node.getStep()).append("\n");

        if (graph != null && graph.isProblemMode()) {
            sb.append("- Target problem: ").append(graph.getTargetConcept()).append("\n");

            List<KnowledgeNode> prerequisites = graph.getPrerequisites(node.getId());
            if (!prerequisites.isEmpty()) {
                sb.append("Direct prerequisite steps:\n");
                for (KnowledgeNode prerequisite : prerequisites) {
                    sb.append("- ").append(prerequisite.getStep()).append("\n");
                }
            }

            List<KnowledgeNode> dependents = graph.getDependents(node.getId());
            if (!dependents.isEmpty()) {
                sb.append("Direct downstream steps:\n");
                for (KnowledgeNode dependent : dependents) {
                    sb.append("- ").append(dependent.getStep()).append("\n");
                }
            }

            String stepChain = TargetDescriptionBuilder.buildSolutionChain(graph, node);
            if (!stepChain.isBlank()) {
                sb.append("Current step inside the ordered solution-step chain:\n")
                        .append(stepChain);
            }
        }

        sb.append("Return only the mathematical content needed for this current step");
        sb.append(", keeping it concise and useful for a ").append(outputTarget).append(" output.");
        return sb.toString();
    }

    private void applyContent(KnowledgeNode node, JsonNode data) {
        if (node == null || data == null || data.isNull()) {
            return;
        }

        if (data.has("equations")) {
            node.setEquations(readTrimmedStringList(data.get("equations")));
        }
        if (data.has("definitions")) {
            node.setDefinitions(readTrimmedStringMap(data.get("definitions")));
        }
        if (data.has("interpretation")) {
            node.setInterpretation(readOptionalText(data.get("interpretation")));
        }
        if (data.has("examples")) {
            node.setExamples(readTrimmedStringList(data.get("examples")));
        }
    }

    private List<String> readTrimmedStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = readOptionalText(item);
                if (text != null) {
                    values.add(text);
                }
            }
            return values;
        }

        String singleValue = readOptionalText(node);
        if (singleValue != null) {
            values.add(singleValue);
        }
        return values;
    }

    private Map<String, String> readTrimmedStringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node == null || node.isNull() || !node.isObject()) {
            return values;
        }

        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey() == null ? null : entry.getKey().trim();
            String value = readOptionalText(entry.getValue());
            if (key != null && !key.isEmpty() && value != null) {
                values.put(key, value);
            }
        });
        return values;
    }

    private String readOptionalText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        String text = node.asText();
        if (text == null) {
            return null;
        }

        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
