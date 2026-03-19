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
import com.automanim.util.NodeConversationContext;
import com.automanim.util.PromptTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
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
 * immediately and only limited by maxConcurrent. Repeated concepts with the
 * same animation intent share the same in-flight future so concurrent
 * duplicates do not trigger duplicate calls.
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
    private WorkflowConfig workflowConfig;
    private final AtomicInteger toolCalls = new AtomicInteger(0);
    private boolean parallelEnabled = true;
    private int maxConcurrent = 4;
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
        this.graph = graph;

        int maxInputTokens = (workflowConfig != null && workflowConfig.getModelConfig() != null)
                ? workflowConfig.getModelConfig().getMaxInputTokens()
                : 131072;
        String workflowTarget = graph != null ? graph.getTargetConcept() : "";
        this.conversationContext = new NodeConversationContext(maxInputTokens);
        this.conversationContext.setSystemMessage(PromptTemplates.mathEnrichmentSystemPrompt(
                workflowTarget,
                buildWorkflowTargetDescription(graph)));

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
                node.getConcept(),
                conversationContext,
                userPrompt,
                MATH_CONTENT_TOOL,
                () -> toolCalls.incrementAndGet()
        ));
    }

    private String buildCacheKey(KnowledgeNode node) {
        String conceptKey = ConceptUtils.normalizeConcept(node.getConcept());
        String descriptionKey = node.getDescription() == null
                ? ""
                : node.getDescription().trim().toLowerCase();
        return conceptKey + "||" + descriptionKey;
    }

    private boolean shouldEnrichNode(KnowledgeNode node) {
        return node != null;
    }

    private String buildWorkflowTargetDescription(KnowledgeGraph graph) {
        KnowledgeNode root = graph != null ? graph.getRootNode() : null;
        boolean problemMode = graph != null && graph.isProblemMode();
        if (problemMode && graph != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("Target problem: ").append(graph.getTargetConcept()).append("\n");
            if (root != null) {
                sb.append("Final conclusion: ").append(root.getConcept()).append("\n");
                if (root.getDescription() != null && !root.getDescription().isBlank()) {
                    sb.append("Conclusion role: ").append(root.getDescription().trim()).append("\n");
                }
            }
            String stepChain = buildProblemSolutionChainSummary(graph, null);
            if (!stepChain.isBlank()) {
                sb.append("Ordered solution-step chain:\n").append(stepChain);
            }
            return sb.toString().trim();
        }
        return PromptTemplates.workflowTargetDescription(
                graph != null ? graph.getTargetConcept() : "",
                root != null ? root.getConcept() : "",
                root != null ? root.getDescription() : "",
                problemMode);
    }

    private String buildCurrentStepPrompt(KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current step:\n");
        sb.append("- Concept: ").append(node.getConcept()).append("\n");
        sb.append("- Node type: ").append(node.getNodeType()).append("\n");
        sb.append("- Depth: ").append(node.getMinDepth()).append("\n");
        if (node.getDescription() != null && !node.getDescription().isBlank()) {
            sb.append("- Planning summary: ").append(node.getDescription().trim()).append("\n");
        }

        if (graph != null && graph.isProblemMode()) {
            sb.append("- Target problem: ").append(graph.getTargetConcept()).append("\n");

            List<KnowledgeNode> prerequisites = graph.getPrerequisites(node.getId());
            if (!prerequisites.isEmpty()) {
                sb.append("Direct prerequisite steps:\n");
                for (KnowledgeNode prerequisite : prerequisites) {
                    appendStepLine(sb, prerequisite);
                }
            }

            List<KnowledgeNode> dependents = graph.getDependents(node.getId());
            if (!dependents.isEmpty()) {
                sb.append("Direct downstream steps:\n");
                for (KnowledgeNode dependent : dependents) {
                    appendStepLine(sb, dependent);
                }
            }

            String stepChain = buildProblemSolutionChainSummary(graph, node);
            if (!stepChain.isBlank()) {
                sb.append("Current step inside the ordered solution-step chain:\n")
                        .append(stepChain);
            }
        }

        sb.append("Return only the mathematical content needed for this current step.");
        return sb.toString();
    }

    private String buildProblemSolutionChainSummary(KnowledgeGraph graph, KnowledgeNode currentNode) {
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
            String marker = currentNode != null && step.getId().equals(currentNode.getId()) ? "-> " : "   ";
            sb.append(marker)
                    .append(i + 1)
                    .append(". [")
                    .append(step.getNodeType())
                    .append("] ")
                    .append(step.getConcept());
            if (step.getDescription() != null && !step.getDescription().isBlank()) {
                sb.append(" - ").append(step.getDescription().trim());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void appendStepLine(StringBuilder sb, KnowledgeNode step) {
        if (sb == null || step == null) {
            return;
        }
        sb.append("- [")
                .append(step.getNodeType())
                .append("] ")
                .append(step.getConcept());
        if (step.getDescription() != null && !step.getDescription().isBlank()) {
            sb.append(" - ").append(step.getDescription().trim());
        }
        sb.append("\n");
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
