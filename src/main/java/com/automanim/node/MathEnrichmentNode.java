package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.util.AiRequestUtils;
import com.automanim.util.ConceptUtils;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.JsonUtils;
import com.automanim.util.PromptTemplates;
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
 *
 * Enrichment is fully independent across nodes, so the whole graph is scheduled
 * immediately and only limited by maxConcurrent. Repeated concepts share the
 * same in-flight future so concurrent duplicates do not trigger duplicate calls.
 */
public class MathEnrichmentNode extends PocketFlow.Node<KnowledgeGraph, KnowledgeGraph, String> {

    private static final Logger log = LoggerFactory.getLogger(MathEnrichmentNode.class);

    private static final String MATH_CONTENT_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_mathematical_content\","
            + "    \"description\": \"Return mathematical content for a concept.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"equations\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"LaTeX equations\" },"
            + "        \"definitions\": { \"type\": \"object\", \"additionalProperties\": { \"type\": \"string\" }, \"description\": \"Symbol-to-meaning map\" },"
            + "        \"interpretation\": { \"type\": \"string\", \"description\": \"Short explanation when useful\" },"
            + "        \"examples\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"Optional examples when useful\" }"
            + "      },"
            + "      \"required\": [\"equations\", \"definitions\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private AiClient aiClient;
    private final AtomicInteger toolCalls = new AtomicInteger(0);
    private boolean parallelEnabled = true;
    private int maxConcurrent = 4;
    private final Map<String, CompletableFuture<JsonNode>> cache = new ConcurrentHashMap<>();
    private ConcurrencyUtils.AsyncLimiter aiCallLimiter;

    public MathEnrichmentNode() {
        super(1, 0);
    }

    @Override
    public KnowledgeGraph prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        WorkflowConfig config = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        if (config != null) {
            this.parallelEnabled = config.isParallelMathEnrichment();
            this.maxConcurrent = config.getMaxConcurrent();
        }
        return (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
    }

    @Override
    public KnowledgeGraph exec(KnowledgeGraph graph) {
        int concurrency = parallelEnabled ? maxConcurrent : 1;
        log.info("=== Stage 1a: Mathematical Enrichment (parallel={}, concurrency={}) ===",
                parallelEnabled, concurrency);
        toolCalls.set(0);
        cache.clear();
        aiCallLimiter = new ConcurrencyUtils.AsyncLimiter(concurrency);

        try {
            return enrichGraph(graph);
        } finally {
            aiCallLimiter = null;
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
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (KnowledgeNode node : graph.getNodes().values()) {
            tasks.add(enrichNodeAsync(node));
        }

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
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
            log.debug("  Skipping already-enriched node: {}", node.getConcept());
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
                    log.warn("  Math enrichment failed for '{}': {}", node.getConcept(), cause.getMessage());
                    return null;
                });
    }

    private CompletableFuture<JsonNode> getCachedContentAsync(KnowledgeNode node) {
        String cacheKey = ConceptUtils.normalizeConcept(node.getConcept());
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
        String complexity = node.isFoundation() ? "middle-school level" : "upper-undergraduate level";
        String userPrompt = String.format(
                "Concept: %s\nNode type: %s\nDepth: %d\nTarget complexity: %s",
                node.getConcept(), node.getNodeType(), node.getMinDepth(), complexity);

        return aiCallLimiter.submit(() -> AiRequestUtils.requestJsonObjectAsync(
                aiClient,
                log,
                node.getConcept(),
                userPrompt,
                PromptTemplates.MATH_ENRICHMENT_SYSTEM,
                MATH_CONTENT_TOOL,
                () -> toolCalls.incrementAndGet()
        ));
    }

    private void applyContent(KnowledgeNode node, JsonNode data) {
        if (data.has("equations")) {
            List<String> equations = new ArrayList<>();
            for (JsonNode eq : data.get("equations")) {
                equations.add(eq.asText());
            }
            node.setEquations(equations);
        }
        if (data.has("definitions")) {
            Map<String, String> defs = new LinkedHashMap<>();
            data.get("definitions").fields().forEachRemaining(
                    entry -> defs.put(entry.getKey(), entry.getValue().asText()));
            node.setDefinitions(defs);
        }
        if (data.has("interpretation")) {
            node.setInterpretation(data.get("interpretation").asText());
        }
        if (data.has("examples")) {
            List<String> examples = new ArrayList<>();
            for (JsonNode ex : data.get("examples")) {
                examples.add(ex.asText());
            }
            node.setExamples(examples);
        }
    }
}
