package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.EnrichmentPrompts;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.ConceptUtils;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.TargetDescriptionBuilder;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Map<String, CompletableFuture<EnrichmentRequestResult>> cache = new ConcurrentHashMap<>();
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
        String solutionChain = TargetDescriptionBuilder.buildSolutionChain(graph, null);
        this.conversationContext.setSystemMessage(EnrichmentPrompts.systemPrompt(
                workflowTarget,
                TargetDescriptionBuilder.build(graph, null),
                solutionChain));

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
        List<List<KnowledgeNode>> executionBatches = graph.executionBatches();

        try {
            for (int batchIndex = 0; batchIndex < executionBatches.size(); batchIndex++) {
                List<KnowledgeNode> nodes = new ArrayList<>();
                for (KnowledgeNode node : executionBatches.get(batchIndex)) {
                    if (shouldEnrichNode(node)) {
                        nodes.add(node);
                    }
                }
                if (nodes.isEmpty()) {
                    log.info("  Skipping batch {} (no eligible nodes)", batchIndex + 1);
                    continue;
                }
                log.info("  Enriching batch {} ({} nodes{})", batchIndex + 1, nodes.size(),
                        parallelEnabled && nodes.size() > 1 ? ", parallel" : "");
                enrichExecutionBatch(nodes);
            }
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            throw new RuntimeException("Math enrichment failed: " + cause.getMessage(), cause);
        }

        log.info("Mathematical enrichment complete: {} API calls, {} cache entries",
                toolCalls.get(), cache.size());
        return graph;
    }

    private void enrichExecutionBatch(List<KnowledgeNode> nodes) {
        List<NodeConversationContext.Message> batchConversationSnapshot = conversationContext.getMessages();
        List<CompletableFuture<EnrichmentNodeResult>> tasks = new ArrayList<>();
        for (KnowledgeNode node : nodes) {
            tasks.add(enrichNodeAsync(node, batchConversationSnapshot));
        }

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

        List<EnrichmentNodeResult> results = new ArrayList<>();
        for (CompletableFuture<EnrichmentNodeResult> task : tasks) {
            EnrichmentNodeResult result = task.join();
            if (result != null) {
                results.add(result);
            }
        }
        commitBatchConversation(results);
    }

    private CompletableFuture<EnrichmentNodeResult> enrichNodeAsync(KnowledgeNode node,
                                                                    List<NodeConversationContext.Message> batchConversationSnapshot) {
        if (node.isEnriched()) {
            log.debug("  Skipping already-enriched node: {}", node.getStep());
            return CompletableFuture.completedFuture(EnrichmentNodeResult.skipped(node));
        }

        String userPrompt = buildCurrentStepPrompt(node);
        return getCachedContentAsync(node, userPrompt, batchConversationSnapshot)
                .thenApply(result -> {
                    if (result != null && result.payload != null) {
                        applyContent(node, result.payload);
                    }
                    return new EnrichmentNodeResult(node, result);
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.warn("  Math enrichment failed for '{}': {}", node.getStep(), cause.getMessage());
                    return EnrichmentNodeResult.failed(node);
                });
    }

    private CompletableFuture<EnrichmentRequestResult> getCachedContentAsync(
            KnowledgeNode node,
            String userPrompt,
            List<NodeConversationContext.Message> batchConversationSnapshot) {
        String cacheKey = buildCacheKey(node);
        CompletableFuture<EnrichmentRequestResult> existing = cache.get(cacheKey);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<EnrichmentRequestResult> created = fetchMathContentAsync(
                node, userPrompt, batchConversationSnapshot, cacheKey);
        CompletableFuture<EnrichmentRequestResult> prior = cache.putIfAbsent(cacheKey, created);
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

    private CompletableFuture<EnrichmentRequestResult> fetchMathContentAsync(
            KnowledgeNode node,
            String userPrompt,
            List<NodeConversationContext.Message> batchConversationSnapshot,
            String cacheKey) {
        return aiCallLimiter.submit(() -> AiRequestUtils.requestJsonObjectResultAsync(
                aiClient,
                log,
                node.getStep(),
                batchConversationSnapshot,
                conversationContext.getMaxInputTokens(),
                userPrompt,
                ToolSchemas.MATH_ENRICHMENT,
                () -> toolCalls.incrementAndGet()
        )).thenApply(result -> new EnrichmentRequestResult(
                cacheKey,
                userPrompt,
                result != null ? result.getPayload() : null,
                result != null ? result.getAssistantTranscript() : ""
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

    private void commitBatchConversation(List<EnrichmentNodeResult> results) {
        for (EnrichmentNodeResult result : results) {
            if (result == null || result.requestResult == null) {
                continue;
            }
            EnrichmentRequestResult requestResult = result.requestResult;
            if (requestResult.markConversationCommitted()) {
                conversationContext.appendTurn(
                        requestResult.userPrompt,
                        requestResult.assistantTranscript
                );
            }
        }
    }

    private String buildCurrentStepPrompt(KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current step:\n");
        sb.append("- Step: ").append(node.getStep()).append("\n");

        if (graph != null) {
            List<KnowledgeNode> prerequisites = graph.getPrerequisites(node.getId());
            if (!prerequisites.isEmpty()) {
                sb.append("Direct prerequisite steps:\n");
                for (KnowledgeNode prerequisite : prerequisites) {
                    sb.append("- ").append(prerequisite.getStep()).append("\n");
                }
            }
            if (prerequisites.size() > 1) {
                sb.append("Merge node guidance:\n");
                sb.append("- This step merges multiple prerequisite branches.\n");
                sb.append("- Integrate the prerequisite conclusions into one continuation.\n");
                sb.append("- Preserve established naming instead of restarting the explanation.\n");
            }

            List<KnowledgeNode> dependents = graph.getDependents(node.getId());
            if (!dependents.isEmpty()) {
                sb.append("Direct downstream steps:\n");
                for (KnowledgeNode dependent : dependents) {
                    sb.append("- ").append(dependent.getStep()).append("\n");
                }
            }
        }

        sb.append("Return only the mathematical content needed for this current step");
        sb.append(", keeping it concise and useful for the presentation output.");
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

    private static final class EnrichmentRequestResult {
        private final String cacheKey;
        private final String userPrompt;
        private final JsonNode payload;
        private final String assistantTranscript;
        private final AtomicBoolean conversationCommitted = new AtomicBoolean(false);

        private EnrichmentRequestResult(String cacheKey,
                                        String userPrompt,
                                        JsonNode payload,
                                        String assistantTranscript) {
            this.cacheKey = cacheKey;
            this.userPrompt = userPrompt;
            this.payload = payload;
            this.assistantTranscript = assistantTranscript == null ? "" : assistantTranscript;
        }

        private boolean markConversationCommitted() {
            if (assistantTranscript.isBlank()) {
                return false;
            }
            return conversationCommitted.compareAndSet(false, true);
        }
    }

    private static final class EnrichmentNodeResult {
        private final KnowledgeNode node;
        private final EnrichmentRequestResult requestResult;

        private EnrichmentNodeResult(KnowledgeNode node, EnrichmentRequestResult requestResult) {
            this.node = node;
            this.requestResult = requestResult;
        }

        private static EnrichmentNodeResult skipped(KnowledgeNode node) {
            return new EnrichmentNodeResult(node, null);
        }

        private static EnrichmentNodeResult failed(KnowledgeNode node) {
            return new EnrichmentNodeResult(node, null);
        }
    }
}
