package com.automanim.node;

import com.automanim.config.PipelineConfig;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.PipelineKeys;
import com.automanim.service.AiClient;
import com.automanim.util.JsonUtils;
import com.automanim.util.PromptTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 1a: Mathematical Enrichment — adds equations and definitions to each
 * node in the knowledge tree.
 *
 * Nodes at the same depth level are enriched in parallel (deepest-first).
 * Each node's enrichment is independent; no cross-node dependency within a level.
 */
public class MathEnrichmentNode extends PocketFlow.Node<KnowledgeNode, KnowledgeNode, String> {

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
            + "        \"equations\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"LaTeX equation strings\" },"
            + "        \"definitions\": { \"type\": \"object\", \"additionalProperties\": { \"type\": \"string\" }, \"description\": \"Symbol to meaning mapping\" },"
            + "        \"interpretation\": { \"type\": \"string\", \"description\": \"Brief interpretation (optional, omit if obvious)\" },"
            + "        \"examples\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"Examples (optional, only if genuinely helpful)\" }"
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
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

    public MathEnrichmentNode() {
        super(1, 0);
    }

    @Override
    public KnowledgeNode prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(PipelineKeys.AI_CLIENT);
        PipelineConfig config = (PipelineConfig) ctx.get(PipelineKeys.CONFIG);
        if (config != null) {
            this.parallelEnabled = config.isParallelMathEnrichment();
            this.maxConcurrent = config.getMaxConcurrent();
        }
        return (KnowledgeNode) ctx.get(PipelineKeys.KNOWLEDGE_TREE);
    }

    @Override
    public KnowledgeNode exec(KnowledgeNode tree) {
        log.info("=== Stage 1a: Mathematical Enrichment (parallel={}) ===", parallelEnabled);
        toolCalls.set(0);
        cache.clear();

        Map<Integer, List<KnowledgeNode>> levels = tree.groupByDepth();
        List<Integer> depths = new ArrayList<>(levels.keySet());
        depths.sort(Collections.reverseOrder());

        ExecutorService executor = parallelEnabled
                ? Executors.newFixedThreadPool(maxConcurrent) : null;

        try {
            for (int depth : depths) {
                List<KnowledgeNode> nodes = levels.get(depth);
                log.info("  Enriching depth {} ({} nodes{})", depth, nodes.size(),
                        parallelEnabled && nodes.size() > 1 ? ", parallel" : "");

                if (parallelEnabled && nodes.size() > 1 && executor != null) {
                    List<Future<?>> futures = new ArrayList<>();
                    for (KnowledgeNode node : nodes) {
                        futures.add(executor.submit(() -> enrichNode(node)));
                    }
                    for (Future<?> f : futures) {
                        try { f.get(); }
                        catch (Exception e) { log.warn("  Parallel math enrichment error: {}", e.getMessage()); }
                    }
                } else {
                    for (KnowledgeNode node : nodes) {
                        enrichNode(node);
                    }
                }
            }
        } finally {
            if (executor != null) executor.shutdown();
        }

        log.info("Mathematical enrichment complete: {} API calls, {} cache entries",
                toolCalls.get(), cache.size());
        return tree;
    }

    @Override
    public String post(Map<String, Object> ctx, KnowledgeNode prepRes, KnowledgeNode tree) {
        ctx.put(PipelineKeys.KNOWLEDGE_TREE, tree);
        int prevCalls = (int) ctx.getOrDefault(PipelineKeys.ENRICHMENT_TOOL_CALLS, 0);
        ctx.put(PipelineKeys.ENRICHMENT_TOOL_CALLS, prevCalls + toolCalls.get());
        return null;
    }

    private void enrichNode(KnowledgeNode node) {
        if (node.isEnriched()) {
            log.debug("  Skipping already-enriched node: {}", node.getConcept());
            return;
        }

        String cacheKey = node.getConcept().toLowerCase().trim();
        JsonNode cachedData = cache.get(cacheKey);
        if (cachedData != null) {
            applyContent(node, cachedData);
            return;
        }

        String complexity = node.isFoundation() ? "junior-high level" : "upper-undergraduate level";
        String userPrompt = String.format(
                "Concept: %s\nDepth: %d\nComplexity target: %s",
                node.getConcept(), node.getDepth(), complexity);

        try {
            JsonNode data = null;
            try {
                JsonNode rawResponse = aiClient.chatWithToolsRaw(
                        userPrompt, PromptTemplates.MATH_ENRICHMENT_SYSTEM, MATH_CONTENT_TOOL);
                toolCalls.incrementAndGet();
                data = JsonUtils.extractToolCallPayload(rawResponse);

                if (data == null) {
                    String textContent = JsonUtils.extractTextFromResponse(rawResponse);
                    if (textContent != null && !textContent.isBlank()) {
                        data = JsonUtils.parseTree(JsonUtils.extractJsonObject(textContent));
                    }
                }
            } catch (Exception e) {
                log.debug("  Tool calling failed for '{}', falling back to plain chat", node.getConcept());
                String response = aiClient.chat(userPrompt, PromptTemplates.MATH_ENRICHMENT_SYSTEM);
                toolCalls.incrementAndGet();
                data = JsonUtils.parseTree(JsonUtils.extractJsonObject(response));
            }

            if (data != null) {
                cache.put(cacheKey, data);
                applyContent(node, data);
            }
        } catch (Exception e) {
            log.warn("  Math enrichment failed for '{}': {}", node.getConcept(), e.getMessage());
        }
    }

    private void applyContent(KnowledgeNode node, JsonNode data) {
        if (data.has("equations")) {
            List<String> equations = new ArrayList<>();
            for (JsonNode eq : data.get("equations")) { equations.add(eq.asText()); }
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
            for (JsonNode ex : data.get("examples")) { examples.add(ex.asText()); }
            node.setExamples(examples);
        }
    }
}
