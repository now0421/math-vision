package com.automanim.node;

import com.automanim.config.PipelineConfig;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.PipelineKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.JsonUtils;
import com.automanim.util.PromptTemplates;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 0: Concept Exploration - builds the knowledge prerequisite tree.
 *
 * Sibling prerequisites are explored in parallel via ExecutorService.
 * The visited set uses atomic add to prevent TOCTOU races.
 * The target concept is anchored in all prompts to prevent drift.
 */
public class ExplorationNode extends PocketFlow.Node<String, KnowledgeNode, String> {

    private static final Logger log = LoggerFactory.getLogger(ExplorationNode.class);

    private AiClient aiClient;
    private String targetConcept;
    private int maxDepth = 4;
    private int minDepth = 0;
    private final AtomicInteger apiCalls = new AtomicInteger(0);
    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final Map<String, List<String>> prereqCache = new ConcurrentHashMap<>();
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private ExecutorService executor;
    private int maxConcurrent = 4;

    public ExplorationNode() {
        super(1, 0);
    }

    @Override
    public String prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(PipelineKeys.AI_CLIENT);
        PipelineConfig config = (PipelineConfig) ctx.get(PipelineKeys.CONFIG);
        if (config != null) {
            this.maxDepth = config.getMaxDepth();
            this.minDepth = config.getMinDepth();
            this.maxConcurrent = config.getMaxConcurrent();
        }
        return (String) ctx.get(PipelineKeys.CONCEPT);
    }

    @Override
    public KnowledgeNode exec(String concept) {
        log.info("=== Stage 0: Concept Exploration ===");
        log.info("Target concept: {}, max depth: {}, min depth: {}, concurrency: {}",
                concept, maxDepth, minDepth, maxConcurrent);

        this.targetConcept = concept;
        apiCalls.set(0);
        cacheHits.set(0);
        visited.clear();
        prereqCache.clear();

        executor = Executors.newFixedThreadPool(maxConcurrent);

        KnowledgeNode tree;
        try {
            tree = exploreAsync(concept, 0).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Exploration failed: " + cause.getMessage(), cause);
        } finally {
            executor.shutdown();
        }

        validateTree(tree, concept);

        log.info("Exploration complete: {} nodes, {} API calls, {} cache hits",
                tree.countNodes(), apiCalls.get(), cacheHits.get());
        return tree;
    }

    @Override
    public String post(Map<String, Object> ctx, String concept, KnowledgeNode tree) {
        ctx.put(PipelineKeys.KNOWLEDGE_TREE, tree);
        ctx.put(PipelineKeys.EXPLORATION_API_CALLS, apiCalls.get());

        Path outputDir = (Path) ctx.get(PipelineKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveKnowledgeTree(outputDir, tree);
        }

        log.info("Knowledge tree: {} nodes, max depth {}", tree.countNodes(), tree.getMaxDepth());
        return null;
    }

    private CompletableFuture<KnowledgeNode> exploreAsync(String concept, int depth) {
        String key = concept.toLowerCase().trim();

        // Atomic visited check: add returns false if already present, preventing TOCTOU race
        if (!visited.add(key) || depth > maxDepth) {
            return CompletableFuture.completedFuture(new KnowledgeNode(concept, depth, true));
        }

        return CompletableFuture
                .supplyAsync(() -> analyzeConcept(concept, depth), executor)
                .thenCompose(step -> {
                    if (step.terminal()) {
                        return CompletableFuture.completedFuture(step.node());
                    }

                    List<CompletableFuture<KnowledgeNode>> childFutures = new ArrayList<>();
                    for (String prereqConcept : step.prerequisites()) {
                        childFutures.add(exploreAsync(prereqConcept, depth + 1));
                    }

                    if (childFutures.isEmpty()) {
                        return CompletableFuture.completedFuture(step.node());
                    }

                    CompletableFuture<Void> allChildren = CompletableFuture.allOf(
                            childFutures.toArray(new CompletableFuture[0])
                    );
                    return allChildren.thenApply(ignored -> {
                        for (CompletableFuture<KnowledgeNode> childFuture : childFutures) {
                            step.node().getPrerequisites().add(childFuture.join());
                        }
                        return step.node();
                    });
                });
    }

    private ExplorationStep analyzeConcept(String concept, int depth) {
        boolean isFoundation;
        if (depth < minDepth) {
            isFoundation = false;
            log.debug("  {} (depth={}) -> forced non-foundation (depth < minDepth={})",
                    concept, depth, minDepth);
        } else {
            isFoundation = checkFoundation(concept);
        }

        KnowledgeNode node = new KnowledgeNode(concept, depth, isFoundation);
        if (isFoundation || depth >= maxDepth) {
            log.debug("  {} (depth={}) -> foundation={}", concept, depth, isFoundation);
            return new ExplorationStep(node, Collections.emptyList(), true);
        }

        List<String> prereqs = getCachedPrerequisites(concept);
        log.info("  {} (depth={}) -> prerequisites: {}", concept, depth, prereqs);
        return new ExplorationStep(node, prereqs, false);
    }

    private static final class ExplorationStep {
        private final KnowledgeNode node;
        private final List<String> prerequisites;
        private final boolean terminal;

        private ExplorationStep(KnowledgeNode node, List<String> prerequisites, boolean terminal) {
            this.node = node;
            this.prerequisites = prerequisites;
            this.terminal = terminal;
        }

        private KnowledgeNode node() {
            return node;
        }

        private List<String> prerequisites() {
            return prerequisites;
        }

        private boolean terminal() {
            return terminal;
        }
    }

    private boolean checkFoundation(String concept) {
        try {
            String prompt = String.format(
                    "Final teaching goal: %s\n"
                    + "Current concept under evaluation: %s\n"
                    + "This concept will become a node in a prerequisite knowledge tree.\n"
                    + "Decide whether it is already basic enough, precise enough, and directly"
                    + " understandable for a middle-school student while still serving the final"
                    + " teaching goal.",
                    targetConcept, concept);
            String response = aiClient.chat(prompt, PromptTemplates.FOUNDATION_CHECK_SYSTEM);
            apiCalls.incrementAndGet();
            String normalized = response.trim().toLowerCase(Locale.ROOT);
            return normalized.startsWith("yes") || normalized.startsWith("y");
        } catch (Exception e) {
            log.warn("Foundation check failed for '{}': {}", concept, e.getMessage());
            return false;
        }
    }

    private List<String> getCachedPrerequisites(String concept) {
        String key = concept.toLowerCase().trim();
        List<String> existing = prereqCache.get(key);
        if (existing != null) {
            cacheHits.incrementAndGet();
            log.debug("  Cache hit for '{}'", concept);
            return existing;
        }
        return prereqCache.computeIfAbsent(key, ignored -> getPrerequisites(concept));
    }

    private List<String> getPrerequisites(String concept) {
        try {
            String prompt = String.format(
                    "Final teaching goal: %s\n"
                    + "Current concept: %s\n"
                    + "List the direct prerequisite concepts needed to understand the current"
                    + " concept in a teaching animation pipeline. Keep the result precise,"
                    + " ordered, and free of duplicates or overly broad topics.",
                    targetConcept, concept);
            String response = aiClient.chat(prompt, PromptTemplates.PREREQUISITES_SYSTEM);
            apiCalls.incrementAndGet();

            String jsonArray = JsonUtils.extractJsonArray(response);
            List<String> prereqs = JsonUtils.mapper().readValue(
                    jsonArray, new TypeReference<List<String>>() {}
            );

            if (prereqs.size() > 5) {
                prereqs = new ArrayList<>(prereqs.subList(0, 5));
            }
            return prereqs;
        } catch (Exception e) {
            log.warn("Prerequisites extraction failed for '{}': {}", concept, e.getMessage());
            return Collections.emptyList();
        }
    }

    private void validateTree(KnowledgeNode tree, String concept) {
        int nodeCount = tree.countNodes();
        int treeMaxDepth = tree.getMaxDepth();

        if (nodeCount < 3) {
            log.warn("Tree validation: only {} nodes for '{}' - tree may be too shallow.",
                    nodeCount, concept);
        }
        if (treeMaxDepth < minDepth) {
            log.warn("Tree validation: max depth {} < minDepth {} for '{}'",
                    treeMaxDepth, minDepth, concept);
        }

        log.info("Tree validation: {} nodes, max depth {}, minDepth requirement {}",
                nodeCount, treeMaxDepth, minDepth);
    }
}
