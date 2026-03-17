package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.AiRequestUtils;
import com.automanim.util.ConceptUtils;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.JsonUtils;
import com.automanim.util.NodeConversationContext;
import com.automanim.util.PromptTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 0: Concept Exploration - builds an explicit prerequisite DAG.
 *
 * Exploration is scheduled asynchronously with depth relaxation: when a concept
 * is rediscovered at a shallower depth, that better depth supersedes older work
 * and expansion continues immediately without a per-level barrier.
 */
public class ExplorationNode extends PocketFlow.Node<String, KnowledgeGraph, String> {

    private static final Logger log = LoggerFactory.getLogger(ExplorationNode.class);

    private static final String PREREQUISITES_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_prerequisites\","
            + "    \"description\": \"Return the list of direct prerequisite concepts.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"prerequisites\": {"
            + "          \"type\": \"array\","
            + "          \"items\": {"
            + "            \"type\": \"object\","
            + "            \"properties\": {"
            + "              \"concept\": { \"type\": \"string\", \"description\": \"Concept name\" },"
            + "              \"description\": { \"type\": \"string\", \"description\": \"How to animate this concept in Manim\" }"
            + "            },"
            + "            \"required\": [\"concept\", \"description\"]"
            + "          }"
            + "        }"
            + "      },"
            + "      \"required\": [\"prerequisites\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private static final String FOUNDATION_CHECK_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_foundation_decision\","
            + "    \"description\": \"Return whether the concept is already foundational enough.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"is_foundation\": { \"type\": \"boolean\", \"description\": \"Whether the concept should stop expanding\" },"
            + "        \"reason\": { \"type\": \"string\", \"description\": \"Short justification\" }"
            + "      },"
            + "      \"required\": [\"is_foundation\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private static final String INPUT_MODE_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_input_mode\","
            + "    \"description\": \"Classify the workflow input mode.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"input_mode\": {"
            + "          \"type\": \"string\","
            + "          \"enum\": [\"concept\", \"problem\"],"
            + "          \"description\": \"Workflow mode for the input\""
            + "        },"
            + "        \"reason\": { \"type\": \"string\", \"description\": \"Short routing rationale\" }"
            + "      },"
            + "      \"required\": [\"input_mode\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private static final String PROBLEM_GRAPH_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_problem_step_graph\","
            + "    \"description\": \"Return a compact dependency graph of problem-solving steps.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"root_id\": { \"type\": \"string\", \"description\": \"Root node id\" },"
            + "        \"nodes\": {"
            + "          \"type\": \"array\","
            + "          \"items\": {"
            + "            \"type\": \"object\","
            + "            \"properties\": {"
            + "              \"id\": { \"type\": \"string\" },"
            + "              \"concept\": { \"type\": \"string\" },"
            + "              \"description\": { \"type\": \"string\" },"
            + "              \"node_type\": { \"type\": \"string\" },"
            + "              \"min_depth\": { \"type\": \"integer\" },"
            + "              \"is_foundation\": { \"type\": \"boolean\" }"
            + "            },"
            + "            \"required\": [\"id\", \"concept\", \"node_type\", \"min_depth\", \"is_foundation\"]"
            + "          }"
            + "        },"
            + "        \"prerequisite_edges\": {"
            + "          \"type\": \"object\","
            + "          \"additionalProperties\": {"
            + "            \"type\": \"array\","
            + "            \"items\": { \"type\": \"string\" }"
            + "          }"
            + "        }"
            + "      },"
            + "      \"required\": [\"root_id\", \"nodes\", \"prerequisite_edges\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private String targetConcept;
    private int maxDepth = 4;
    private int minDepth = 0;
    private int maxConcurrent = 4;
    private String inputMode = WorkflowConfig.INPUT_MODE_AUTO;

    private final AtomicInteger apiCalls = new AtomicInteger(0);
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final Map<String, CompletableFuture<List<String>>> prereqCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> foundationCache = new ConcurrentHashMap<>();
    private final Map<String, String> prereqDescriptions = new ConcurrentHashMap<>();

    private ConcurrencyUtils.AsyncLimiter aiCallLimiter;
    private NodeConversationContext prerequisiteContext;
    private NodeConversationContext foundationContext;
    private NodeConversationContext routingContext;
    private NodeConversationContext problemGraphContext;

    public ExplorationNode() {
        super(1, 0);
    }

    @Override
    public String prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        if (workflowConfig != null) {
            this.maxDepth = workflowConfig.getMaxDepth();
            this.minDepth = workflowConfig.getMinDepth();
            this.maxConcurrent = workflowConfig.getMaxConcurrent();
            this.inputMode = workflowConfig.getInputMode();
        }
        return (String) ctx.get(WorkflowKeys.CONCEPT);
    }

    @Override
    public KnowledgeGraph exec(String concept) {
        this.aiCallLimiter = new ConcurrencyUtils.AsyncLimiter(maxConcurrent);
        this.targetConcept = concept;
        apiCalls.set(0);
        cacheHits.set(0);
        prereqCache.clear();
        foundationCache.clear();
        prereqDescriptions.clear();

        int maxInputTokens = (workflowConfig != null && workflowConfig.getModelConfig() != null)
                ? workflowConfig.getModelConfig().getMaxInputTokens()
                : 131072;
        initializeRoutingContext(maxInputTokens, concept);
        String resolvedMode = resolveInputMode(concept);
        initializeExplorationContexts(maxInputTokens, concept, resolvedMode);
        log.info("=== Stage 0: {} Exploration ===",
                WorkflowConfig.INPUT_MODE_PROBLEM.equals(resolvedMode) ? "Problem" : "Concept");
        log.info("Target input: {}, mode: {}, max depth: {}, min depth: {}, concurrency: {}",
                concept, resolvedMode, maxDepth, minDepth, maxConcurrent);

        try {
            KnowledgeGraph graph = WorkflowConfig.INPUT_MODE_PROBLEM.equals(resolvedMode)
                    ? buildProblemGraph(concept)
                    : buildConceptGraph(concept);
            validateGraph(graph);

            log.info("Exploration complete: {} nodes, {} edges, {} API calls, {} cache hits",
                    graph.countNodes(), graph.countEdges(), apiCalls.get(), cacheHits.get());
            return graph;
        } finally {
            aiCallLimiter = null;
        }
    }

    @Override
    public String post(Map<String, Object> ctx, String concept, KnowledgeGraph graph) {
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);
        ctx.put(WorkflowKeys.EXPLORATION_API_CALLS, apiCalls.get());

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveKnowledgeGraph(outputDir, graph);
        }

        log.info("Knowledge graph: {} nodes, {} edges, max depth {}",
                graph.countNodes(), graph.countEdges(), graph.getMaxDepth());
        return null;
    }

    private KnowledgeGraph buildConceptGraph(String concept) {
        String rootConcept = concept == null ? "" : concept.trim();
        String rootId = ConceptUtils.normalizeConcept(rootConcept);
        ExplorationState state = new ExplorationState(rootId, rootConcept);

        state.nodeIndex.put(rootId, new KnowledgeNode(rootId, rootConcept, 0, false));
        scheduleAnalysis(state, new NodeRef(rootId, rootConcept, 0));

        try {
            return state.completion.join();
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            throw new RuntimeException("Exploration failed: " + cause.getMessage(), cause);
        }
    }

    private KnowledgeGraph buildProblemGraph(String problemStatement) {
        String normalizedProblem = problemStatement == null ? "" : problemStatement.trim();
        String prompt = "Math problem:\n" + normalizedProblem + "\n\n"
                + "Build a compact solution-step dependency graph for this problem.";

        try {
            JsonNode payload = aiCallLimiter.submit(() -> AiRequestUtils.requestJsonObjectAsync(
                    aiClient,
                    log,
                    normalizedProblem,
                    problemGraphContext,
                    prompt,
                    PROBLEM_GRAPH_TOOL,
                    () -> apiCalls.incrementAndGet(),
                    this::parseProblemGraphTextResponse
            )).join();
            return parseProblemGraphPayload(payload, normalizedProblem);
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            throw new RuntimeException("Problem graph generation failed: " + cause.getMessage(), cause);
        }
    }

    private KnowledgeGraph parseProblemGraphPayload(JsonNode payload, String problemStatement) {
        String rootId = payload.hasNonNull("root_id")
                ? ConceptUtils.normalizeConcept(payload.get("root_id").asText())
                : "";

        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        JsonNode nodesArray = payload.get("nodes");
        if (nodesArray != null && nodesArray.isArray()) {
            for (JsonNode nodeJson : nodesArray) {
                String rawId = nodeJson.hasNonNull("id")
                        ? nodeJson.get("id").asText()
                        : nodeJson.path("concept").asText();
                String nodeId = ConceptUtils.normalizeConcept(rawId);
                if (nodeId.isBlank()) {
                    continue;
                }

                String concept = nodeJson.hasNonNull("concept")
                        ? nodeJson.get("concept").asText().trim()
                        : rawId.trim();
                int depth = nodeJson.has("min_depth") ? nodeJson.get("min_depth").asInt(0) : 0;
                boolean foundation = nodeJson.has("is_foundation")
                        && nodeJson.get("is_foundation").asBoolean(false);

                KnowledgeNode node = new KnowledgeNode(nodeId, concept, depth, foundation);
                String nodeType = nodeJson.hasNonNull("node_type")
                        ? nodeJson.get("node_type").asText()
                        : KnowledgeNode.NODE_TYPE_DERIVATION;
                node.setNodeType(nodeType);

                if (nodeJson.hasNonNull("description")) {
                    node.setDescription(nodeJson.get("description").asText().trim());
                }

                nodes.put(nodeId, node);
            }
        }

        Map<String, List<String>> edges = new LinkedHashMap<>();
        JsonNode edgeObject = payload.get("prerequisite_edges");
        if (edgeObject != null && edgeObject.isObject()) {
            edgeObject.fields().forEachRemaining(entry -> {
                String sourceId = ConceptUtils.normalizeConcept(entry.getKey());
                if (!nodes.containsKey(sourceId)) {
                    return;
                }

                List<String> dependencies = new ArrayList<>();
                JsonNode dependencyArray = entry.getValue();
                if (dependencyArray != null && dependencyArray.isArray()) {
                    for (JsonNode dependencyJson : dependencyArray) {
                        String dependencyId = ConceptUtils.normalizeConcept(dependencyJson.asText());
                        if (dependencyId.isBlank()
                                || dependencyId.equals(sourceId)
                                || !nodes.containsKey(dependencyId)
                                || dependencies.contains(dependencyId)) {
                            continue;
                        }
                        dependencies.add(dependencyId);
                    }
                }
                if (!dependencies.isEmpty()) {
                    edges.put(sourceId, dependencies);
                }
            });
        }

        ensureProblemStatementNode(nodes, problemStatement);
        rootId = sanitizeProblemRoot(rootId, nodes, edges);
        recomputeProblemDepths(rootId, nodes, edges);
        return new KnowledgeGraph(rootId, problemStatement, orderNodes(nodes), orderProblemEdges(edges, nodes));
    }

    private String sanitizeProblemRoot(String currentRootId,
                                       Map<String, KnowledgeNode> nodes,
                                       Map<String, List<String>> edges) {
        Set<String> referencedAsPrerequisite = new LinkedHashSet<>();
        for (List<String> dependencies : edges.values()) {
            referencedAsPrerequisite.addAll(dependencies);
        }

        List<String> terminalCandidates = new ArrayList<>();
        for (String nodeId : nodes.keySet()) {
            if (!referencedAsPrerequisite.contains(nodeId)) {
                terminalCandidates.add(nodeId);
            }
        }

        String normalizedRootId = ConceptUtils.normalizeConcept(currentRootId);
        if (terminalCandidates.contains(normalizedRootId)
                && isValidProblemRoot(normalizedRootId, nodes)) {
            return normalizedRootId;
        }

        terminalCandidates.sort(problemRootComparator(nodes));
        for (String candidateId : terminalCandidates) {
            if (isValidProblemRoot(candidateId, nodes)) {
                return candidateId;
            }
        }

        List<String> fallbackCandidates = new ArrayList<>(nodes.keySet());
        fallbackCandidates.sort(problemRootComparator(nodes));
        for (String candidateId : fallbackCandidates) {
            if (isValidProblemRoot(candidateId, nodes)) {
                return candidateId;
            }
        }

        String syntheticRootId = createSyntheticConclusionRoot(nodes, edges);
        String problemNodeId = findProblemStatementNodeId(nodes);
        if (problemNodeId != null && !problemNodeId.equals(syntheticRootId)) {
            edges.put(syntheticRootId, Collections.singletonList(problemNodeId));
        }
        return syntheticRootId;
    }

    private void ensureProblemStatementNode(Map<String, KnowledgeNode> nodes, String problemStatement) {
        if (findProblemStatementNodeId(nodes) != null) {
            return;
        }

        String nodeId = createProblemStatementNodeId(nodes);
        KnowledgeNode problemNode = new KnowledgeNode(nodeId, problemStatement, 1, false);
        problemNode.setNodeType(KnowledgeNode.NODE_TYPE_PROBLEM);
        problemNode.setDescription("Introduce the problem statement, givens, and target clearly.");
        nodes.put(nodeId, problemNode);
    }

    private String findProblemStatementNodeId(Map<String, KnowledgeNode> nodes) {
        for (KnowledgeNode node : nodes.values()) {
            if (node != null && KnowledgeNode.NODE_TYPE_PROBLEM.equalsIgnoreCase(node.getNodeType())) {
                return node.getId();
            }
        }
        return null;
    }

    private String createProblemStatementNodeId(Map<String, KnowledgeNode> nodes) {
        String baseId = nodes.containsKey("problem") ? "problem_statement" : "problem";
        String candidate = baseId;
        int suffix = 2;
        while (nodes.containsKey(candidate)) {
            candidate = baseId + "_" + suffix++;
        }
        return candidate;
    }

    private boolean isValidProblemRoot(String nodeId, Map<String, KnowledgeNode> nodes) {
        if (nodeId == null || nodeId.isBlank() || !nodes.containsKey(nodeId)) {
            return false;
        }

        KnowledgeNode node = nodes.get(nodeId);
        return node != null && !KnowledgeNode.NODE_TYPE_PROBLEM.equalsIgnoreCase(node.getNodeType());
    }

    private Comparator<String> problemRootComparator(Map<String, KnowledgeNode> nodes) {
        return Comparator.comparing((String id) -> {
                    KnowledgeNode node = nodes.get(id);
                    return node == null || !KnowledgeNode.NODE_TYPE_CONCLUSION.equalsIgnoreCase(node.getNodeType());
                })
                .thenComparing((String id) -> {
                    KnowledgeNode node = nodes.get(id);
                    return node != null && KnowledgeNode.NODE_TYPE_PROBLEM.equalsIgnoreCase(node.getNodeType());
                })
                .thenComparing(Comparator.comparingInt((String id) -> {
                    KnowledgeNode node = nodes.get(id);
                    return node != null ? node.getMinDepth() : Integer.MIN_VALUE;
                }).reversed())
                .thenComparing(id -> {
                    KnowledgeNode node = nodes.get(id);
                    return node != null ? node.getConcept() : id;
                }, String.CASE_INSENSITIVE_ORDER);
    }

    private String createSyntheticConclusionRoot(Map<String, KnowledgeNode> nodes,
                                                 Map<String, List<String>> edges) {
        String baseId = "final_answer";
        String candidate = baseId;
        int suffix = 2;
        while (nodes.containsKey(candidate)) {
            candidate = baseId + "_" + suffix++;
        }

        KnowledgeNode rootNode = new KnowledgeNode(candidate, "Final answer and why it is correct", 0, false);
        rootNode.setNodeType(KnowledgeNode.NODE_TYPE_CONCLUSION);
        rootNode.setDescription("Present the final answer and summarize why the solution is correct.");
        nodes.put(candidate, rootNode);

        List<String> prerequisites = new ArrayList<>();
        List<String> fallbackCandidates = new ArrayList<>(nodes.keySet());
        fallbackCandidates.remove(candidate);
        fallbackCandidates.sort(problemRootComparator(nodes));
        if (!fallbackCandidates.isEmpty()) {
            prerequisites.add(fallbackCandidates.get(0));
        }
        if (!prerequisites.isEmpty()) {
            edges.put(candidate, prerequisites);
        }
        return candidate;
    }

    private void recomputeProblemDepths(String rootId,
                                        Map<String, KnowledgeNode> nodes,
                                        Map<String, List<String>> edges) {
        Map<String, Integer> computedDepths = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();

        if (nodes.containsKey(rootId)) {
            computedDepths.put(rootId, 0);
            queue.add(rootId);
        }

        int nextComponentDepth = traverseProblemComponent(queue, computedDepths, edges);

        List<String> remaining = new ArrayList<>(nodes.keySet());
        remaining.sort(Comparator.comparingInt((String id) -> nodes.get(id).getMinDepth()).reversed()
                .thenComparing(id -> nodes.get(id).getConcept(), String.CASE_INSENSITIVE_ORDER));

        for (String nodeId : remaining) {
            if (computedDepths.containsKey(nodeId)) {
                continue;
            }
            computedDepths.put(nodeId, nextComponentDepth);
            queue.add(nodeId);
            nextComponentDepth = traverseProblemComponent(queue, computedDepths, edges);
        }

        for (KnowledgeNode node : nodes.values()) {
            Integer depth = computedDepths.get(node.getId());
            node.setMinDepth(depth != null ? depth : 0);
            node.setFoundation(false);
        }
    }

    private int traverseProblemComponent(ArrayDeque<String> queue,
                                         Map<String, Integer> computedDepths,
                                         Map<String, List<String>> edges) {
        int maxDepth = computedDepths.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        while (!queue.isEmpty()) {
            String currentId = queue.removeFirst();
            int currentDepth = computedDepths.getOrDefault(currentId, 0);
            maxDepth = Math.max(maxDepth, currentDepth);

            for (String dependencyId : edges.getOrDefault(currentId, Collections.emptyList())) {
                int candidateDepth = currentDepth + 1;
                Integer existingDepth = computedDepths.get(dependencyId);
                if (existingDepth == null || candidateDepth < existingDepth) {
                    computedDepths.put(dependencyId, candidateDepth);
                    queue.addLast(dependencyId);
                    maxDepth = Math.max(maxDepth, candidateDepth);
                }
            }
        }

        return maxDepth + 1;
    }

    private Map<String, List<String>> orderProblemEdges(Map<String, List<String>> edgeIndex,
                                                        Map<String, KnowledgeNode> nodes) {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        List<String> sourceIds = new ArrayList<>(edgeIndex.keySet());
        sourceIds.sort(Comparator.comparingInt((String id) -> nodes.get(id).getMinDepth())
                .thenComparing(id -> nodes.get(id).getConcept(), String.CASE_INSENSITIVE_ORDER));

        for (String sourceId : sourceIds) {
            List<String> dependencies = new ArrayList<>(edgeIndex.getOrDefault(sourceId, Collections.emptyList()));
            dependencies.sort(Comparator.comparingInt((String id) -> nodes.get(id).getMinDepth())
                    .thenComparing(id -> nodes.get(id).getConcept(), String.CASE_INSENSITIVE_ORDER));
            ordered.put(sourceId, dependencies);
        }
        return ordered;
    }

    private void scheduleAnalysis(ExplorationState state, NodeRef nodeRef) {
        if (state.completion.isDone()) {
            return;
        }

        DepthDecision decision = relaxDepth(state, nodeRef);
        if (!decision.accepted()) {
            return;
        }

        if (decision.previousDepth() != null) {
            log.debug("  Depth relaxed for '{}' from {} to {}",
                    nodeRef.concept(), decision.previousDepth(), nodeRef.depth());
        } else {
            log.debug("  Discovered '{}' at depth {}", nodeRef.concept(), nodeRef.depth());
        }

        if (nodeRef.depth() > maxDepth) {
            return;
        }

        state.pending.incrementAndGet();
        analyzeConceptAsync(nodeRef).whenComplete((result, error) -> {
            try {
                if (error != null) {
                    failExploration(state, error);
                    return;
                }
                processResult(state, result);
            } catch (Exception e) {
                failExploration(state, e);
            } finally {
                if (state.pending.decrementAndGet() == 0 && !state.completion.isDone()) {
                    state.completion.complete(assembleGraph(state));
                }
            }
        });
    }

    private DepthDecision relaxDepth(ExplorationState state, NodeRef nodeRef) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        AtomicInteger previousDepth = new AtomicInteger(-1);

        state.bestDepth.compute(nodeRef.id(), (id, existing) -> {
            if (existing == null || nodeRef.depth() < existing) {
                accepted.set(true);
                previousDepth.set(existing == null ? -1 : existing);
                return nodeRef.depth();
            }
            return existing;
        });

        KnowledgeNode node = state.nodeIndex.computeIfAbsent(
                nodeRef.id(),
                ignored -> new KnowledgeNode(nodeRef.id(), nodeRef.concept(), nodeRef.depth(), false)
        );
        node.setConcept(nodeRef.concept());
        node.setNodeType(KnowledgeNode.NODE_TYPE_CONCEPT);
        node.updateMinDepth(nodeRef.depth());

        return new DepthDecision(accepted.get(), previousDepth.get() >= 0 ? previousDepth.get() : null);
    }

    private void processResult(ExplorationState state, ExplorationResult result) {
        int bestKnownDepth = state.bestDepth.getOrDefault(result.nodeId(), Integer.MAX_VALUE);
        if (result.depth() != bestKnownDepth) {
            log.debug("  Ignoring stale result for '{}' at depth {} (best={})",
                    result.concept(), result.depth(), bestKnownDepth);
            return;
        }

        KnowledgeNode node = state.nodeIndex.computeIfAbsent(
                result.nodeId(),
                ignored -> new KnowledgeNode(result.nodeId(), result.concept(), result.depth(), false)
        );
        node.setConcept(result.concept());
        node.setNodeType(KnowledgeNode.NODE_TYPE_CONCEPT);
        node.updateMinDepth(result.depth());
        node.setFoundation(result.foundation());

        if (result.terminal()) {
            return;
        }

        for (String prereqConcept : result.prerequisites()) {
            registerPrerequisite(state, result, prereqConcept);
        }
    }

    private void registerPrerequisite(ExplorationState state,
                                      ExplorationResult result,
                                      String prereqConcept) {
        if (prereqConcept == null || prereqConcept.isBlank()) {
            return;
        }

        String trimmedConcept = prereqConcept.trim();
        String prereqId = ConceptUtils.normalizeConcept(trimmedConcept);
        int childDepth = result.depth() + 1;

        if (prereqId.isBlank()) {
            return;
        }

        synchronized (state.graphLock) {
            if (prereqId.equals(result.nodeId())) {
                log.warn("  Skipping self-cycle '{}'", trimmedConcept);
                return;
            }
            if (wouldCreateCycle(result.nodeId(), prereqId, state.edgeIndex)) {
                log.warn("  Skipping cyclic edge {} -> {}", result.concept(), trimmedConcept);
                return;
            }

            KnowledgeNode child = state.nodeIndex.computeIfAbsent(
                    prereqId,
                    ignored -> new KnowledgeNode(prereqId, trimmedConcept, childDepth, false)
            );
            child.setConcept(trimmedConcept);
            child.setNodeType(KnowledgeNode.NODE_TYPE_CONCEPT);
            child.updateMinDepth(childDepth);

            String description = prereqDescriptions.get(prereqId);
            if (description != null && child.getDescription() == null) {
                child.setDescription(description);
            }
            state.edgeIndex.computeIfAbsent(result.nodeId(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(prereqId);
        }

        scheduleAnalysis(state, new NodeRef(prereqId, trimmedConcept, childDepth));
    }

    private CompletableFuture<ExplorationResult> analyzeConceptAsync(NodeRef nodeRef) {
        CompletableFuture<Boolean> foundationFuture;
        if (nodeRef.depth() < minDepth) {
            log.debug("  {} (depth={}) -> forced non-foundation (depth < minDepth={})",
                    nodeRef.concept(), nodeRef.depth(), minDepth);
            foundationFuture = CompletableFuture.completedFuture(false);
        } else {
            foundationFuture = getCachedFoundationAsync(nodeRef.concept());
        }

        return foundationFuture.thenCompose(foundation -> {
            if (foundation || nodeRef.depth() >= maxDepth) {
                log.debug("  {} (depth={}) -> foundation={}, terminal",
                        nodeRef.concept(), nodeRef.depth(), foundation);
                return CompletableFuture.completedFuture(new ExplorationResult(
                        nodeRef.id(),
                        nodeRef.concept(),
                        nodeRef.depth(),
                        foundation,
                        Collections.emptyList(),
                        true
                ));
            }

            return getCachedPrerequisitesAsync(nodeRef.concept()).thenApply(prerequisites -> {
                log.info("  {} (depth={}) -> prerequisites: {}", nodeRef.concept(),
                        nodeRef.depth(), prerequisites);
                return new ExplorationResult(
                        nodeRef.id(),
                        nodeRef.concept(),
                        nodeRef.depth(),
                        foundation,
                        prerequisites,
                        false
                );
            });
        });
    }

    private CompletableFuture<Boolean> getCachedFoundationAsync(String concept) {
        String key = ConceptUtils.normalizeConcept(concept);
        CompletableFuture<Boolean> existing = foundationCache.get(key);
        if (existing != null) {
            cacheHits.incrementAndGet();
            return existing;
        }

        CompletableFuture<Boolean> created = checkFoundationAsync(concept);
        CompletableFuture<Boolean> prior = foundationCache.putIfAbsent(key, created);
        if (prior != null) {
            cacheHits.incrementAndGet();
            return prior;
        }

        created.whenComplete((ignored, error) -> {
            if (error != null) {
                foundationCache.remove(key, created);
            }
        });
        return created;
    }

    private CompletableFuture<Boolean> checkFoundationAsync(String concept) {
        String prompt = String.format(
                "Final teaching goal: %s\n"
                + "Current concept under evaluation: %s\n"
                + "This concept will become a node in a prerequisite knowledge graph.\n"
                + "Decide whether it is already basic enough, precise enough, and directly"
                + " understandable for a middle-school student while still serving the final"
                + " teaching goal.",
                targetConcept, concept);

        return aiCallLimiter.submit(() -> AiRequestUtils.requestJsonObjectAsync(
                        aiClient,
                        log,
                        concept,
                        foundationContext,
                        prompt,
                        FOUNDATION_CHECK_TOOL,
                        () -> apiCalls.incrementAndGet(),
                        this::parseFoundationDecisionTextResponse
                ))
                .thenApply(data -> data != null
                        && data.has("is_foundation")
                        && data.get("is_foundation").asBoolean(false))
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.warn("Foundation check failed for '{}': {}", concept, cause.getMessage());
                    return false;
                });
    }

    private CompletableFuture<List<String>> getCachedPrerequisitesAsync(String concept) {
        String key = ConceptUtils.normalizeConcept(concept);
        CompletableFuture<List<String>> existing = prereqCache.get(key);
        if (existing != null) {
            cacheHits.incrementAndGet();
            return existing;
        }

        CompletableFuture<List<String>> created = getPrerequisitesAsync(concept);
        CompletableFuture<List<String>> prior = prereqCache.putIfAbsent(key, created);
        if (prior != null) {
            cacheHits.incrementAndGet();
            return prior;
        }

        created.whenComplete((ignored, error) -> {
            if (error != null) {
                prereqCache.remove(key, created);
            }
        });
        return created;
    }

    private CompletableFuture<List<String>> getPrerequisitesAsync(String concept) {
        String prompt = String.format(
                "Final teaching goal: %s\n"
                + "Current concept: %s\n"
                + "List the direct prerequisite concepts needed to understand the current"
                + " concept in a teaching animation workflow. Keep the result precise,"
                + " ordered, and free of duplicates or overly broad topics.",
                targetConcept, concept);

        return aiCallLimiter.submit(() -> AiRequestUtils.requestJsonObjectAsync(
                        aiClient,
                        log,
                        concept,
                        prerequisiteContext,
                        prompt,
                        PREREQUISITES_TOOL,
                        () -> apiCalls.incrementAndGet(),
                        this::parsePrerequisitesTextResponse
                ))
                .thenApply(data -> {
                    if (data != null) {
                        JsonNode prereqArray = data.has("prerequisites")
                                ? data.get("prerequisites") : data;
                        if (prereqArray.isArray()) {
                            return parsePrerequisiteArray(prereqArray);
                        }
                    }
                    return Collections.<String>emptyList();
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.warn("Prerequisites extraction failed for '{}': {}", concept, cause.getMessage());
                    return Collections.emptyList();
                });
    }

    /**
     * Parses a prerequisites JSON array (from tool call or plain response).
     * Accepts both [{concept, description}, ...] and plain string arrays.
     */
    private List<String> parsePrerequisiteArray(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (JsonNode element : array) {
            String conceptName;
            String description = null;

            if (element.isObject() && element.has("concept")) {
                conceptName = element.get("concept").asText();
                if (element.has("description")) {
                    description = element.get("description").asText();
                }
            } else {
                conceptName = element.asText();
            }

            if (conceptName == null || conceptName.isBlank()) {
                continue;
            }
            String normalized = ConceptUtils.normalizeConcept(conceptName);
            if (!seen.add(normalized)) {
                continue;
            }

            String trimmed = conceptName.trim();
            cleaned.add(trimmed);

            if (description != null && !description.isBlank()) {
                prereqDescriptions.put(normalized, description.trim());
            }

            if (cleaned.size() >= 5) {
                break;
            }
        }
        return cleaned;
    }

    private boolean wouldCreateCycle(String parentId, String childId, Map<String, Set<String>> edgeIndex) {
        if (parentId.equals(childId)) {
            return true;
        }

        ArrayDeque<String> stack = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        stack.push(childId);

        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(parentId)) {
                return true;
            }
            for (String next : edgeIndex.getOrDefault(current, Collections.emptySet())) {
                stack.push(next);
            }
        }
        return false;
    }

    private KnowledgeGraph assembleGraph(ExplorationState state) {
        return new KnowledgeGraph(
                state.rootId,
                state.rootConcept,
                orderNodes(state.nodeIndex),
                orderEdges(state.edgeIndex)
        );
    }

    private Map<String, KnowledgeNode> orderNodes(Map<String, KnowledgeNode> nodeIndex) {
        List<KnowledgeNode> nodes = new ArrayList<>(nodeIndex.values());
        nodes.sort(Comparator.comparingInt(KnowledgeNode::getMinDepth)
                .thenComparing(KnowledgeNode::getConcept, String.CASE_INSENSITIVE_ORDER));

        Map<String, KnowledgeNode> ordered = new LinkedHashMap<>();
        for (KnowledgeNode node : nodes) {
            ordered.put(node.getId(), node);
        }
        return ordered;
    }

    private Map<String, List<String>> orderEdges(Map<String, Set<String>> edgeIndex) {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        List<String> sourceIds = new ArrayList<>(edgeIndex.keySet());
        sourceIds.sort(String.CASE_INSENSITIVE_ORDER);

        for (String sourceId : sourceIds) {
            TreeSet<String> sortedTargets = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            sortedTargets.addAll(edgeIndex.getOrDefault(sourceId, Collections.emptySet()));
            ordered.put(sourceId, new ArrayList<>(sortedTargets));
        }
        return ordered;
    }

    private void validateGraph(KnowledgeGraph graph) {
        int nodeCount = graph.countNodes();
        int maxGraphDepth = graph.getMaxDepth();

        if (nodeCount < 3) {
            log.warn("Graph validation: only {} nodes for '{}' - graph may be too shallow.",
                    nodeCount, targetConcept);
        }
        if (maxGraphDepth < minDepth) {
            log.warn("Graph validation: max depth {} < minDepth {} for '{}'",
                    maxGraphDepth, minDepth, targetConcept);
        }

        log.info("Graph validation: {} nodes, {} edges, max depth {}, minDepth requirement {}",
                nodeCount, graph.countEdges(), maxGraphDepth, minDepth);
    }

    private void failExploration(ExplorationState state, Throwable error) {
        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
        state.completion.completeExceptionally(cause);
    }

    private String resolveInputMode(String input) {
        if (WorkflowConfig.isExplicitInputMode(inputMode)) {
            return WorkflowConfig.normalizeInputMode(inputMode);
        }

        String llmDecision = classifyInputModeWithLlm(input);
        if (WorkflowConfig.INPUT_MODE_CONCEPT.equals(llmDecision)
                || WorkflowConfig.INPUT_MODE_PROBLEM.equals(llmDecision)) {
            return llmDecision;
        }

        log.warn("Falling back to heuristic auto-mode classification for input: {}", input);
        return classifyInputModeHeuristically(input);
    }

    private String classifyInputModeWithLlm(String input) {
        String normalizedInput = input == null ? "" : input.trim();
        String prompt = "User input:\n" + normalizedInput + "\n\n"
                + "Classify the routing mode for this workflow input.";

        try {
            JsonNode data = aiCallLimiter.submit(() -> AiRequestUtils.requestJsonObjectAsync(
                    aiClient,
                    log,
                    normalizedInput,
                    routingContext,
                    prompt,
                    INPUT_MODE_TOOL,
                    () -> apiCalls.incrementAndGet(),
                    this::parseInputModeTextResponse
            )).join();

            String normalized = data != null && data.has("input_mode")
                    ? data.get("input_mode").asText("").trim().toLowerCase(Locale.ROOT)
                    : "";
            if (normalized.startsWith(WorkflowConfig.INPUT_MODE_PROBLEM)) {
                log.info("Auto mode classified by LLM as problem");
                return WorkflowConfig.INPUT_MODE_PROBLEM;
            }
            if (normalized.startsWith(WorkflowConfig.INPUT_MODE_CONCEPT)) {
                log.info("Auto mode classified by LLM as concept");
                return WorkflowConfig.INPUT_MODE_CONCEPT;
            }

            log.warn("LLM returned unexpected input mode classification payload: {}", data);
            return "";
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            log.warn("LLM input-mode classification failed: {}", cause.getMessage());
            return "";
        }
    }

    private String classifyInputModeHeuristically(String input) {
        String normalized = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        int wordCount = normalized.isBlank() ? 0 : normalized.split("\\s+").length;
        boolean looksLikeProblem = normalized.contains("?")
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
        return looksLikeProblem ? WorkflowConfig.INPUT_MODE_PROBLEM : WorkflowConfig.INPUT_MODE_CONCEPT;
    }

    private void initializeRoutingContext(int maxInputTokens, String input) {
        routingContext = new NodeConversationContext(maxInputTokens);
        routingContext.setSystemMessage(PromptTemplates.inputModeClassifierSystemPrompt(input));
    }

    private void initializeExplorationContexts(int maxInputTokens, String input, String resolvedMode) {
        String targetDescription = buildExplorationTargetDescription(input, resolvedMode);

        prerequisiteContext = new NodeConversationContext(maxInputTokens);
        prerequisiteContext.setSystemMessage(
                PromptTemplates.prerequisitesSystemPrompt(input, targetDescription));

        foundationContext = new NodeConversationContext(maxInputTokens);
        foundationContext.setSystemMessage(
                PromptTemplates.foundationCheckSystemPrompt(input, targetDescription));

        problemGraphContext = new NodeConversationContext(maxInputTokens);
        problemGraphContext.setSystemMessage(
                PromptTemplates.problemStepGraphSystemPrompt(input, targetDescription));
    }

    private String buildExplorationTargetDescription(String input, String resolvedMode) {
        String trimmedInput = input == null ? "" : input.trim();
        if (WorkflowConfig.INPUT_MODE_PROBLEM.equals(resolvedMode)) {
            return PromptTemplates.workflowTargetDescription(
                    trimmedInput, trimmedInput, trimmedInput, true);
        }
        return PromptTemplates.workflowTargetDescription(
                trimmedInput, trimmedInput, "", false);
    }

    private JsonNode parseFoundationDecisionTextResponse(String response) {
        JsonNode existing = tryParseJsonObject(response);
        if (existing != null && existing.has("is_foundation")) {
            return existing;
        }

        String normalized = response == null ? "" : response.trim().toLowerCase(Locale.ROOT);
        boolean isFoundation = normalized.startsWith("yes")
                || normalized.startsWith("y")
                || normalized.startsWith("true");
        ObjectNode payload = JsonUtils.mapper().createObjectNode();
        payload.put("is_foundation", isFoundation);
        return payload;
    }

    private JsonNode parseInputModeTextResponse(String response) {
        JsonNode existing = tryParseJsonObject(response);
        if (existing != null && existing.has("input_mode")) {
            return existing;
        }

        String normalized = response == null ? "" : response.trim().toLowerCase(Locale.ROOT);
        boolean mentionsProblem = normalized.startsWith(WorkflowConfig.INPUT_MODE_PROBLEM)
                || normalized.contains("\"problem\"")
                || normalized.contains("'problem'")
                || normalized.contains("mode: problem")
                || normalized.contains("mode is problem")
                || normalized.contains("classified as problem")
                || normalized.contains("classification: problem")
                || normalized.contains("this is a problem")
                || normalized.contains("it is a problem")
                || (normalized.contains(" problem ") && !normalized.contains("not a problem"));
        boolean mentionsConcept = normalized.startsWith(WorkflowConfig.INPUT_MODE_CONCEPT)
                || normalized.contains("\"concept\"")
                || normalized.contains("'concept'")
                || normalized.contains("mode: concept")
                || normalized.contains("mode is concept")
                || normalized.contains("classified as concept")
                || normalized.contains("classification: concept")
                || normalized.contains("this is a concept")
                || normalized.contains("it is a concept")
                || (normalized.contains(" concept ") && !normalized.contains("not a concept"));

        String inputModeValue;
        if (mentionsProblem && !mentionsConcept) {
            inputModeValue = WorkflowConfig.INPUT_MODE_PROBLEM;
        } else if (mentionsConcept && !mentionsProblem) {
            inputModeValue = WorkflowConfig.INPUT_MODE_CONCEPT;
        } else {
            inputModeValue = classifyInputModeHeuristically(response);
        }

        ObjectNode payload = JsonUtils.mapper().createObjectNode();
        payload.put("input_mode", inputModeValue);
        return payload;
    }

    private JsonNode parsePrerequisitesTextResponse(String response) {
        String normalized = response == null ? "" : response.trim();
        if (normalized.startsWith("[")) {
            return JsonUtils.parseTree(JsonUtils.extractJsonArray(response));
        }

        JsonNode objectNode = tryParseJsonObject(response);
        if (objectNode != null && objectNode.has("prerequisites")) {
            return objectNode;
        }

        JsonNode arrayNode = JsonUtils.parseTree(JsonUtils.extractJsonArray(response));
        if (arrayNode.isArray() && !arrayNode.isEmpty()) {
            return arrayNode;
        }
        return objectNode != null ? objectNode : JsonUtils.parseTree("[]");
    }

    private JsonNode parseProblemGraphTextResponse(String response) {
        JsonNode payload = tryParseJsonObject(response);
        return payload != null ? payload : JsonUtils.parseTree("{}");
    }

    private JsonNode tryParseJsonObject(String response) {
        if (response == null || !response.contains("{")) {
            return null;
        }
        String candidate = JsonUtils.extractJsonObject(response);
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        return JsonUtils.parseTree(candidate);
    }

    private static final class ExplorationState {
        private final String rootId;
        private final String rootConcept;
        private final Map<String, KnowledgeNode> nodeIndex = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> edgeIndex = new ConcurrentHashMap<>();
        private final Map<String, Integer> bestDepth = new ConcurrentHashMap<>();
        private final AtomicInteger pending = new AtomicInteger(0);
        private final CompletableFuture<KnowledgeGraph> completion = new CompletableFuture<>();
        private final Object graphLock = new Object();

        private ExplorationState(String rootId, String rootConcept) {
            this.rootId = rootId;
            this.rootConcept = rootConcept;
        }
    }

    private static final class DepthDecision {
        private final boolean accepted;
        private final Integer previousDepth;

        private DepthDecision(boolean accepted, Integer previousDepth) {
            this.accepted = accepted;
            this.previousDepth = previousDepth;
        }

        private boolean accepted() {
            return accepted;
        }

        private Integer previousDepth() {
            return previousDepth;
        }
    }

    private static final class NodeRef {
        private final String id;
        private final String concept;
        private final int depth;

        private NodeRef(String id, String concept, int depth) {
            this.id = id;
            this.concept = concept;
            this.depth = depth;
        }

        private String id() {
            return id;
        }

        private String concept() {
            return concept;
        }

        private int depth() {
            return depth;
        }
    }

    private static final class ExplorationResult {
        private final String nodeId;
        private final String concept;
        private final int depth;
        private final boolean foundation;
        private final List<String> prerequisites;
        private final boolean terminal;

        private ExplorationResult(String nodeId,
                                  String concept,
                                  int depth,
                                  boolean foundation,
                                  List<String> prerequisites,
                                  boolean terminal) {
            this.nodeId = nodeId;
            this.concept = concept;
            this.depth = depth;
            this.foundation = foundation;
            this.prerequisites = prerequisites;
            this.terminal = terminal;
        }

        private String nodeId() {
            return nodeId;
        }

        private String concept() {
            return concept;
        }

        private int depth() {
            return depth;
        }

        private boolean foundation() {
            return foundation;
        }

        private List<String> prerequisites() {
            return prerequisites;
        }

        private boolean terminal() {
            return terminal;
        }
    }

}
