package com.mathvision.node;

import com.mathvision.config.ModelConfig;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.ExplorationPrompts;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.ConceptUtils;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.TargetDescriptionBuilder;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 0: direct knowledge-graph planning for concept and problem inputs.
 */
public class ExplorationNode extends PocketFlow.Node<String, KnowledgeGraph, String> {

    private static final Logger log = LoggerFactory.getLogger(ExplorationNode.class);

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private String targetConcept;
    private int maxDepth = 4;
    private int minDepth = 0;
    private String inputMode = WorkflowConfig.INPUT_MODE_AUTO;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;

    private final AtomicInteger apiCalls = new AtomicInteger(0);

    private NodeConversationContext routingContext;
    private NodeConversationContext conceptGraphContext;
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
            this.inputMode = workflowConfig.getInputMode();
            this.outputTarget = workflowConfig.getOutputTarget();
        }
        return (String) ctx.get(WorkflowKeys.CONCEPT);
    }

    @Override
    public KnowledgeGraph exec(String concept) {
        this.targetConcept = concept;
        apiCalls.set(0);

        int maxInputTokens = workflowConfig != null
                ? workflowConfig.resolveMaxInputTokens()
                : ModelConfig.DEFAULT_MAX_INPUT_TOKENS;
        initializeRoutingContext(maxInputTokens, concept);
        String resolvedMode = resolveInputMode(concept);
        initializeGraphContexts(maxInputTokens, concept, resolvedMode);

        log.info("=== Stage 0: {} Graph Planning ===",
                WorkflowConfig.INPUT_MODE_PROBLEM.equals(resolvedMode) ? "Problem" : "Concept");
        log.info("Target input: {}, mode: {}, output_target: {}, max depth: {}, min depth: {}",
                concept, resolvedMode, outputTarget, maxDepth, minDepth);

        KnowledgeGraph graph = WorkflowConfig.INPUT_MODE_PROBLEM.equals(resolvedMode)
                ? buildProblemGraph(concept)
                : buildConceptGraph(concept);
        validateGraph(graph);

        log.info("Exploration complete: {} nodes, {} edges, {} API calls",
                graph.countNodes(), graph.countEdges(), apiCalls.get());
        return graph;
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
        String normalizedConcept = concept == null ? "" : concept.trim();
        String prompt = "Math concept:\n" + normalizedConcept + "\n\n"
                + "Plan a compact teaching DAG for explaining this concept.\n"
                + "The downstream presentation target is " + outputTarget + ".\n"
                + "Return 5 to 9 learner-facing teaching beats when possible.\n"
                + "Use node types from: concept, observation, construction, derivation, conclusion.\n"
                + "Root the graph at the final culmination beat at depth 0.\n"
                + "Prefer a compact teaching path over exhaustive prerequisite coverage.\n"
                + "Keep the graph acyclic, visually teachable, and easy to present in topological order.\n"
                + depthBudgetInstruction();

        JsonNode payload = requestDirectGraphPayload(
                normalizedConcept,
                conceptGraphContext,
                prompt,
                ToolSchemas.CONCEPT_GRAPH,
                "Concept graph generation failed"
        );
        return parseDirectGraphPayload(payload, normalizedConcept, DirectGraphMode.CONCEPT);
    }

    private KnowledgeGraph buildProblemGraph(String problemStatement) {
        String normalizedProblem = problemStatement == null ? "" : problemStatement.trim();
        String prompt = "Math problem:\n" + normalizedProblem + "\n\n"
                + "Plan the presentation-ready teaching beats for solving this problem.\n"
                + "The downstream presentation target is " + outputTarget + ".\n"
                + "Return the small set of major beats that a strong teaching presentation should"
                + " present, in a dependency graph format.\n"
                + "Use `step` for what each beat does or shows, and `reason` for why that beat"
                + " is needed before the next one.\n"
                + "Prefer intuitive, visually teachable reasoning over textbook-style"
                + " decomposition, and make the key insight or transformation explicit.\n"
                + depthBudgetInstruction();

        JsonNode payload = requestDirectGraphPayload(
                normalizedProblem,
                problemGraphContext,
                prompt,
                ToolSchemas.PROBLEM_GRAPH,
                "Problem graph generation failed"
        );
        return parseDirectGraphPayload(payload, normalizedProblem, DirectGraphMode.PROBLEM);
    }

    private JsonNode requestDirectGraphPayload(String subject,
                                               NodeConversationContext graphContext,
                                               String prompt,
                                               String toolSchema,
                                               String failureMessage) {
        try {
            return AiRequestUtils.requestJsonObjectAsync(
                    aiClient,
                    log,
                    subject,
                    graphContext,
                    prompt,
                    toolSchema,
                    () -> apiCalls.incrementAndGet(),
                    this::parseDirectGraphTextResponse
            ).join();
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            throw new RuntimeException(failureMessage + ": " + cause.getMessage(), cause);
        }
    }

    private String depthBudgetInstruction() {
        return String.format(
                Locale.ROOT,
                "Stay within an overall depth budget of about %d levels when possible, and try to"
                        + " make the graph at least %d levels deep when the teaching flow naturally"
                        + " supports it.",
                Math.max(1, maxDepth),
                Math.max(0, minDepth)
        );
    }

    private KnowledgeGraph parseDirectGraphPayload(JsonNode payload,
                                                   String targetInput,
                                                   DirectGraphMode graphMode) {
        Map<String, KnowledgeNode> nodes = parseDirectGraphNodes(payload, graphMode);
        Map<String, List<String>> edges = parseDirectGraphEdges(payload, nodes);

        String requestedRootId = payload != null && payload.hasNonNull("root_id")
                ? ConceptUtils.normalizeConcept(payload.get("root_id").asText())
                : "";
        String rootId = selectDirectGraphRoot(requestedRootId, nodes, edges, graphMode);
        recomputeDirectGraphDepths(rootId, nodes, edges);

        return new KnowledgeGraph(
                rootId,
                targetInput,
                orderNodes(nodes),
                orderDirectGraphEdges(edges, nodes)
        );
    }

    private Map<String, KnowledgeNode> parseDirectGraphNodes(JsonNode payload, DirectGraphMode graphMode) {
        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        JsonNode nodesArray = payload != null ? payload.get("nodes") : null;
        if (nodesArray == null || !nodesArray.isArray()) {
            return nodes;
        }

        for (JsonNode nodeJson : nodesArray) {
            String rawId = nodeJson.hasNonNull("id")
                    ? nodeJson.get("id").asText()
                    : nodeJson.path("step").asText("");
            String nodeId = ConceptUtils.normalizeConcept(rawId);
            if (nodeId.isBlank() || nodes.containsKey(nodeId)) {
                continue;
            }

            String step = nodeJson.path("step").asText("").trim();
            if (step.isBlank()) {
                step = rawId == null ? "" : rawId.trim();
            }
            if (step.isBlank()) {
                continue;
            }

            int depth = nodeJson.has("min_depth") ? nodeJson.get("min_depth").asInt(0) : 0;
            boolean foundation = nodeJson.has("is_foundation")
                    && nodeJson.get("is_foundation").asBoolean(false);

            KnowledgeNode node = new KnowledgeNode(nodeId, step, depth, foundation);
            node.setNodeType(sanitizeNodeType(
                    nodeJson.hasNonNull("node_type") ? nodeJson.get("node_type").asText() : "",
                    graphMode
            ));

            String reason = nodeJson.path("reason").asText("").trim();
            if (!reason.isBlank()) {
                node.setReason(reason);
            }
            nodes.put(nodeId, node);
        }

        return nodes;
    }

    private Map<String, List<String>> parseDirectGraphEdges(JsonNode payload,
                                                            Map<String, KnowledgeNode> nodes) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        JsonNode edgeObject = payload != null ? payload.get("prerequisite_edges") : null;
        if (edgeObject == null || !edgeObject.isObject()) {
            return edges;
        }

        edgeObject.fields().forEachRemaining(entry -> {
            String sourceId = ConceptUtils.normalizeConcept(entry.getKey());
            if (sourceId.isBlank() || !nodes.containsKey(sourceId)) {
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
        return edges;
    }

    private String sanitizeNodeType(String rawNodeType, DirectGraphMode graphMode) {
        String normalized = rawNodeType == null ? "" : rawNodeType.trim().toLowerCase(Locale.ROOT);
        return graphMode.allowedNodeTypes.contains(normalized)
                ? normalized
                : graphMode.defaultNodeType;
    }

    private String selectDirectGraphRoot(String requestedRootId,
                                         Map<String, KnowledgeNode> nodes,
                                         Map<String, List<String>> edges,
                                         DirectGraphMode graphMode) {
        Set<String> referencedAsPrerequisite = new LinkedHashSet<>();
        for (List<String> dependencies : edges.values()) {
            referencedAsPrerequisite.addAll(dependencies);
        }

        List<String> terminalCandidates = new ArrayList<>();
        for (String nodeId : nodes.keySet()) {
            if (!referencedAsPrerequisite.contains(nodeId) && isValidDirectGraphRoot(nodeId, nodes, graphMode)) {
                terminalCandidates.add(nodeId);
            }
        }

        String normalizedRequestedRootId = ConceptUtils.normalizeConcept(requestedRootId);
        if (terminalCandidates.contains(normalizedRequestedRootId)) {
            return normalizedRequestedRootId;
        }

        if (!terminalCandidates.isEmpty()) {
            terminalCandidates.sort(directGraphRootComparator(nodes, graphMode));
            return terminalCandidates.get(0);
        }

        return createSyntheticConclusionRoot(nodes, edges, graphMode);
    }

    private boolean isValidDirectGraphRoot(String nodeId,
                                           Map<String, KnowledgeNode> nodes,
                                           DirectGraphMode graphMode) {
        if (nodeId == null || nodeId.isBlank() || !nodes.containsKey(nodeId)) {
            return false;
        }

        KnowledgeNode node = nodes.get(nodeId);
        if (node == null) {
            return false;
        }

        if (DirectGraphMode.PROBLEM == graphMode) {
            return !KnowledgeNode.NODE_TYPE_PROBLEM.equalsIgnoreCase(node.getNodeType());
        }
        return true;
    }

    private Comparator<String> directGraphRootComparator(Map<String, KnowledgeNode> nodes,
                                                         DirectGraphMode graphMode) {
        return Comparator.comparingInt((String id) -> {
                    KnowledgeNode node = nodes.get(id);
                    return rootTypeRank(node != null ? node.getNodeType() : "", graphMode);
                })
                .thenComparingInt(id -> {
                    KnowledgeNode node = nodes.get(id);
                    return node != null ? node.getMinDepth() : Integer.MAX_VALUE;
                })
                .thenComparing(id -> {
                    KnowledgeNode node = nodes.get(id);
                    return node != null ? node.getStep() : id;
                }, String.CASE_INSENSITIVE_ORDER);
    }

    private int rootTypeRank(String nodeType, DirectGraphMode graphMode) {
        String normalized = nodeType == null ? "" : nodeType.trim().toLowerCase(Locale.ROOT);
        if (KnowledgeNode.NODE_TYPE_CONCLUSION.equals(normalized)) {
            return 0;
        }
        if (KnowledgeNode.NODE_TYPE_DERIVATION.equals(normalized)) {
            return 1;
        }
        if (KnowledgeNode.NODE_TYPE_CONSTRUCTION.equals(normalized)) {
            return 2;
        }
        if (KnowledgeNode.NODE_TYPE_OBSERVATION.equals(normalized)) {
            return 3;
        }
        if (KnowledgeNode.NODE_TYPE_CONCEPT.equals(normalized)) {
            return 4;
        }
        if (KnowledgeNode.NODE_TYPE_PROBLEM.equals(normalized)) {
            return DirectGraphMode.PROBLEM == graphMode ? 5 : 4;
        }
        return 6;
    }

    private String createSyntheticConclusionRoot(Map<String, KnowledgeNode> nodes,
                                                 Map<String, List<String>> edges,
                                                 DirectGraphMode graphMode) {
        String candidate = graphMode.syntheticRootBaseId;
        int suffix = 2;
        while (nodes.containsKey(candidate)) {
            candidate = graphMode.syntheticRootBaseId + "_" + suffix++;
        }

        KnowledgeNode rootNode = new KnowledgeNode(candidate, graphMode.syntheticRootStep, 0, false);
        rootNode.setNodeType(KnowledgeNode.NODE_TYPE_CONCLUSION);
        rootNode.setReason(graphMode.syntheticRootReason);
        nodes.put(candidate, rootNode);

        List<String> prerequisites = new ArrayList<>();
        List<String> fallbackCandidates = new ArrayList<>(nodes.keySet());
        fallbackCandidates.remove(candidate);
        if (!fallbackCandidates.isEmpty()) {
            fallbackCandidates.sort(directGraphRootComparator(nodes, graphMode));
            prerequisites.add(fallbackCandidates.get(0));
        }
        if (!prerequisites.isEmpty()) {
            edges.put(candidate, prerequisites);
        }
        return candidate;
    }

    private void recomputeDirectGraphDepths(String rootId,
                                            Map<String, KnowledgeNode> nodes,
                                            Map<String, List<String>> edges) {
        Map<String, Integer> computedDepths = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();

        if (nodes.containsKey(rootId)) {
            computedDepths.put(rootId, 0);
            queue.add(rootId);
        }

        int nextComponentDepth = traverseDirectGraphComponent(queue, computedDepths, edges);

        List<String> remaining = new ArrayList<>(nodes.keySet());
        remaining.sort(Comparator.comparingInt((String id) -> nodes.get(id).getMinDepth()).reversed()
                .thenComparing(id -> nodes.get(id).getStep(), String.CASE_INSENSITIVE_ORDER));

        for (String nodeId : remaining) {
            if (computedDepths.containsKey(nodeId)) {
                continue;
            }
            computedDepths.put(nodeId, nextComponentDepth);
            queue.add(nodeId);
            nextComponentDepth = traverseDirectGraphComponent(queue, computedDepths, edges);
        }

        for (KnowledgeNode node : nodes.values()) {
            Integer depth = computedDepths.get(node.getId());
            node.setMinDepth(depth != null ? depth : 0);
        }
    }

    private int traverseDirectGraphComponent(ArrayDeque<String> queue,
                                             Map<String, Integer> computedDepths,
                                             Map<String, List<String>> edges) {
        int maxSeenDepth = computedDepths.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        while (!queue.isEmpty()) {
            String currentId = queue.removeFirst();
            int currentDepth = computedDepths.getOrDefault(currentId, 0);
            maxSeenDepth = Math.max(maxSeenDepth, currentDepth);

            for (String dependencyId : edges.getOrDefault(currentId, Collections.emptyList())) {
                int candidateDepth = currentDepth + 1;
                Integer existingDepth = computedDepths.get(dependencyId);
                if (existingDepth == null || candidateDepth < existingDepth) {
                    computedDepths.put(dependencyId, candidateDepth);
                    queue.addLast(dependencyId);
                    maxSeenDepth = Math.max(maxSeenDepth, candidateDepth);
                }
            }
        }

        return maxSeenDepth + 1;
    }

    private Map<String, KnowledgeNode> orderNodes(Map<String, KnowledgeNode> nodeIndex) {
        List<KnowledgeNode> nodes = new ArrayList<>(nodeIndex.values());
        nodes.sort(Comparator.comparingInt(KnowledgeNode::getMinDepth)
                .thenComparing(KnowledgeNode::getStep, String.CASE_INSENSITIVE_ORDER));

        Map<String, KnowledgeNode> ordered = new LinkedHashMap<>();
        for (KnowledgeNode node : nodes) {
            ordered.put(node.getId(), node);
        }
        return ordered;
    }

    private Map<String, List<String>> orderDirectGraphEdges(Map<String, List<String>> edgeIndex,
                                                            Map<String, KnowledgeNode> nodes) {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        List<String> sourceIds = new ArrayList<>(edgeIndex.keySet());
        sourceIds.sort(Comparator.comparingInt((String id) -> nodes.get(id).getMinDepth())
                .thenComparing(id -> nodes.get(id).getStep(), String.CASE_INSENSITIVE_ORDER));

        for (String sourceId : sourceIds) {
            List<String> dependencies = new ArrayList<>(edgeIndex.getOrDefault(sourceId, Collections.emptyList()));
            dependencies.sort(Comparator.comparingInt((String id) -> nodes.get(id).getMinDepth())
                    .thenComparing(id -> nodes.get(id).getStep(), String.CASE_INSENSITIVE_ORDER));
            ordered.put(sourceId, dependencies);
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
            JsonNode data = AiRequestUtils.requestJsonObjectAsync(
                    aiClient,
                    log,
                    normalizedInput,
                    routingContext,
                    prompt,
                    ToolSchemas.INPUT_MODE,
                    () -> apiCalls.incrementAndGet(),
                    this::parseInputModeTextResponse
            ).join();

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
        routingContext.setSystemMessage(ExplorationPrompts.inputModeSystemPrompt(input));
    }

    private void initializeGraphContexts(int maxInputTokens, String input, String resolvedMode) {
        String targetDescription = buildGraphTargetDescription(input, resolvedMode);

        conceptGraphContext = new NodeConversationContext(maxInputTokens);
        conceptGraphContext.setSystemMessage(
                ExplorationPrompts.conceptGraphSystemPrompt(input, targetDescription));

        problemGraphContext = new NodeConversationContext(maxInputTokens);
        problemGraphContext.setSystemMessage(
                ExplorationPrompts.problemGraphSystemPrompt(input, targetDescription));
    }

    private String buildGraphTargetDescription(String input, String resolvedMode) {
        String trimmedInput = input == null ? "" : input.trim();
        if (WorkflowConfig.INPUT_MODE_PROBLEM.equals(resolvedMode)) {
            return TargetDescriptionBuilder.workflowTargetDescription(
                    trimmedInput, trimmedInput, trimmedInput, true, outputTarget);
        }
        return TargetDescriptionBuilder.workflowTargetDescription(
                trimmedInput, trimmedInput, "", false, outputTarget);
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

    private JsonNode parseDirectGraphTextResponse(String response) {
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

    private enum DirectGraphMode {
        CONCEPT(
                Set.of(
                        KnowledgeNode.NODE_TYPE_CONCEPT,
                        KnowledgeNode.NODE_TYPE_OBSERVATION,
                        KnowledgeNode.NODE_TYPE_CONSTRUCTION,
                        KnowledgeNode.NODE_TYPE_DERIVATION,
                        KnowledgeNode.NODE_TYPE_CONCLUSION
                ),
                KnowledgeNode.NODE_TYPE_CONCEPT,
                "final_conclusion",
                "Present the final concept takeaway",
                "This beat culminates the concept explanation and states the key takeaway."
        ),
        PROBLEM(
                Set.of(
                        KnowledgeNode.NODE_TYPE_PROBLEM,
                        KnowledgeNode.NODE_TYPE_OBSERVATION,
                        KnowledgeNode.NODE_TYPE_CONSTRUCTION,
                        KnowledgeNode.NODE_TYPE_DERIVATION,
                        KnowledgeNode.NODE_TYPE_CONCLUSION
                ),
                KnowledgeNode.NODE_TYPE_DERIVATION,
                "final_answer",
                "Present the final answer and why it is correct",
                "This beat resolves the problem and summarizes why the solution is correct."
        );

        private final Set<String> allowedNodeTypes;
        private final String defaultNodeType;
        private final String syntheticRootBaseId;
        private final String syntheticRootStep;
        private final String syntheticRootReason;

        DirectGraphMode(Set<String> allowedNodeTypes,
                        String defaultNodeType,
                        String syntheticRootBaseId,
                        String syntheticRootStep,
                        String syntheticRootReason) {
            this.allowedNodeTypes = allowedNodeTypes;
            this.defaultNodeType = defaultNodeType;
            this.syntheticRootBaseId = syntheticRootBaseId;
            this.syntheticRootStep = syntheticRootStep;
            this.syntheticRootReason = syntheticRootReason;
        }
    }
}
