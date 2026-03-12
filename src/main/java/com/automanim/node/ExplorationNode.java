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
import java.util.*;
import java.util.concurrent.*;
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
            tree = explore(concept, 0);
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

    private KnowledgeNode explore(String concept, int depth) {
        String key = concept.toLowerCase().trim();

        // Atomic visited check: add returns false if already present, preventing TOCTOU race
        if (!visited.add(key) || depth > maxDepth) {
            return new KnowledgeNode(concept, depth, true);
        }

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
            return node;
        }

        List<String> prereqs = getCachedPrerequisites(concept);
        log.info("  {} (depth={}) -> prerequisites: {}", concept, depth, prereqs);

        if (prereqs.size() > 1 && executor != null && !executor.isShutdown()) {
            List<Future<KnowledgeNode>> futures = new ArrayList<>();
            for (String prereqConcept : prereqs) {
                futures.add(executor.submit(() -> explore(prereqConcept, depth + 1)));
            }
            for (Future<KnowledgeNode> future : futures) {
                try {
                    node.getPrerequisites().add(future.get());
                } catch (Exception e) {
                    log.warn("  Parallel exploration failed for a prerequisite of '{}': {}",
                            concept, e.getMessage());
                }
            }
        } else {
            for (String prereqConcept : prereqs) {
                node.getPrerequisites().add(explore(prereqConcept, depth + 1));
            }
        }

        return node;
    }

    private boolean checkFoundation(String concept) {
        try {
            String prompt = String.format(
                    "最终教学目标：%s\n"
                    + "当前待判断概念：%s\n"
                    + "请只围绕这个最终教学目标来判断该概念是否已经足够基础。\n"
                    + "不要把概念泛化到无关主题，也不要脱离当前目标单独判断。",
                    targetConcept, concept);
            String response = aiClient.chat(prompt, PromptTemplates.FOUNDATION_CHECK_SYSTEM);
            apiCalls.incrementAndGet();
            String normalized = response.trim().toLowerCase(Locale.ROOT);
            return normalized.startsWith("是") || normalized.startsWith("yes");
        } catch (Exception e) {
            log.warn("Foundation check failed for '{}': {}", concept, e.getMessage());
            return false;
        }
    }

    private List<String> getCachedPrerequisites(String concept) {
        String key = concept.toLowerCase().trim();
        List<String> cached = prereqCache.get(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            log.debug("  Cache hit for '{}'", concept);
            return cached;
        }

        List<String> prereqs = getPrerequisites(concept);
        prereqCache.put(key, prereqs);
        return prereqs;
    }

    private List<String> getPrerequisites(String concept) {
        try {
            String prompt = String.format(
                    "最终教学目标：%s\n"
                    + "当前概念：%s\n"
                    + "请为这个当前概念找出前置概念。\n"
                    + "要求这些前置概念必须直接服务于最终教学目标，不能过度偏离主题。\n"
                    + "如果某个候选概念虽然相关，但过于宽泛、像旁支主题、或与目标链路距离太远，请不要返回。",
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
