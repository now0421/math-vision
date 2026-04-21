package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.prompt.VisualDesignPrompts;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.StoryboardNormalizer;
import com.mathvision.util.TargetDescriptionBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Stage 1b: Visual Design — generates one StoryboardScene per knowledge-graph
 * node using conversation history for continuity, accumulates a global object
 * registry, and assembles the complete Narrative in post().
 *
 * Dependency-ready batches are processed start-first:
 * - Earlier frontier batches establish reusable motifs before merge nodes run.
 * - Nodes inside the same batch share the same pre-batch conversation/object snapshots.
 */
public class VisualDesignNode extends PocketFlow.Node<KnowledgeGraph, KnowledgeGraph, String> {

    private static final Logger log = LoggerFactory.getLogger(VisualDesignNode.class);
    private static final int MAX_SCENE_RETRIES = 3;

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private final AtomicInteger toolCalls = new AtomicInteger(0);
    private boolean parallelEnabled = true;
    private int maxConcurrent = 4;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private final java.util.Set<String> globalColorPalette = ConcurrentHashMap.newKeySet();
    private ConcurrencyUtils.AsyncLimiter aiCallLimiter;
    private String globalStyleGuide = "";
    private KnowledgeGraph graph;
    private NodeConversationContext conversationContext;

    // Scene accumulation state
    private final List<StoryboardScene> collectedScenes = Collections.synchronizedList(new ArrayList<>());
    private final List<StoryboardObject> objectRegistry = Collections.synchronizedList(new ArrayList<>());
    private Map<String, Integer> teachingOrderIndex = new LinkedHashMap<>();

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
            this.outputTarget = workflowConfig.getOutputTarget();
        }
        return (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
    }

    @Override
    public KnowledgeGraph exec(KnowledgeGraph graph) {
        int concurrency = parallelEnabled ? maxConcurrent : 1;
        log.info("=== Stage 1b: Visual Design (output_target={}, parallel={}, concurrency={}) ===",
                outputTarget, parallelEnabled, concurrency);
        toolCalls.set(0);
        globalColorPalette.clear();
        collectedScenes.clear();
        objectRegistry.clear();
        aiCallLimiter = new ConcurrencyUtils.AsyncLimiter(concurrency);
        this.graph = graph;
        this.globalStyleGuide = buildGlobalStyleGuide(graph);

        // Build teaching order index for deterministic scene numbering
        List<KnowledgeNode> teachingNodes = graph.teachingOrderNodes();
        this.teachingOrderIndex = new LinkedHashMap<>();
        for (int i = 0; i < teachingNodes.size(); i++) {
            teachingOrderIndex.put(teachingNodes.get(i).getId(), i);
        }

        int maxInputTokens = TargetDescriptionBuilder.resolveMaxInputTokens(workflowConfig);
        String workflowTarget = graph != null ? graph.getTargetConcept() : "";
        this.conversationContext = new NodeConversationContext(maxInputTokens);
        String solutionChain = TargetDescriptionBuilder.buildSolutionChain(graph, null);
        this.conversationContext.setSystemMessage(VisualDesignPrompts.systemPrompt(
                workflowTarget,
                TargetDescriptionBuilder.build(graph, null),
                outputTarget,
                solutionChain));

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

        // Assemble Narrative from accumulated scenes
        Narrative narrative = assembleNarrative(graph);
        ctx.put(WorkflowKeys.NARRATIVE, narrative);

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveEnrichedGraph(outputDir, graph);
            FileOutputService.saveNarrative(outputDir, narrative);
        }
        return null;
    }

    private KnowledgeGraph designGraph(KnowledgeGraph graph) {
        List<List<KnowledgeNode>> executionBatches = graph.executionBatches();
        int expectedSceneCount = teachingOrderIndex.size();

        try {
            for (int batchIndex = 0; batchIndex < executionBatches.size(); batchIndex++) {
                List<KnowledgeNode> nodes = new ArrayList<>();
                for (KnowledgeNode node : executionBatches.get(batchIndex)) {
                    if (shouldDesignNode(node)) {
                        nodes.add(node);
                    }
                }
                if (nodes.isEmpty()) {
                    log.info("  Skipping batch {} (no eligible nodes)", batchIndex + 1);
                    continue;
                }
                log.info("  Designing batch {} ({} nodes{})", batchIndex + 1, nodes.size(),
                        parallelEnabled && nodes.size() > 1 ? ", parallel" : "");
                designExecutionBatch(nodes);
            }
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            throw new RuntimeException("Visual design failed: " + cause.getMessage(), cause);
        }

        if (collectedScenes.size() < expectedSceneCount) {
            throw new RuntimeException("Visual design incomplete: expected " + expectedSceneCount
                    + " scenes but only " + collectedScenes.size() + " succeeded after retries. Aborting workflow.");
        }

        log.info("Visual design complete: {} API calls, {} scenes, {} registry objects, palette: {}",
                toolCalls.get(), collectedScenes.size(), objectRegistry.size(), snapshotPalette());
        return graph;
    }

    private void designExecutionBatch(List<KnowledgeNode> nodes) {
        List<NodeConversationContext.Message> batchConversationSnapshot = conversationContext.getMessages();
        List<StoryboardObject> batchObjectRegistrySnapshot = snapshotObjectRegistry();
        List<String> batchPaletteSnapshot = snapshotPalette();
        List<CompletableFuture<SceneDesignResult>> tasks = new ArrayList<>();
        for (KnowledgeNode node : nodes) {
            tasks.add(designNodeAsync(
                    node,
                    batchConversationSnapshot,
                    batchObjectRegistrySnapshot,
                    batchPaletteSnapshot
            ));
        }
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

        List<SceneDesignResult> results = new ArrayList<>();
        for (CompletableFuture<SceneDesignResult> task : tasks) {
            SceneDesignResult result = task.join();
            if (result != null) {
                results.add(result);
            }
        }
        commitBatchResults(results);
    }

    private CompletableFuture<SceneDesignResult> designNodeAsync(
            KnowledgeNode node,
            List<NodeConversationContext.Message> batchConversationSnapshot,
            List<StoryboardObject> batchObjectRegistrySnapshot,
            List<String> batchPaletteSnapshot) {
        return designNodeWithRetry(node, batchConversationSnapshot,
                batchObjectRegistrySnapshot, batchPaletteSnapshot, MAX_SCENE_RETRIES);
    }

    private CompletableFuture<SceneDesignResult> designNodeWithRetry(
            KnowledgeNode node,
            List<NodeConversationContext.Message> conversationSnapshot,
            List<StoryboardObject> registrySnapshot,
            List<String> paletteSnapshot,
            int retriesLeft) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append(buildCurrentStepPrompt(node));

        // Enrichment data (equations/definitions from MathEnrichmentNode)
        String enrichmentContext = buildEnrichmentContext(node);
        if (!enrichmentContext.isBlank()) {
            userPrompt.append("\n\n").append(enrichmentContext);
        }

        // Object registry summary
        String registrySummary = buildObjectRegistrySummary(registrySnapshot);
        userPrompt.append("\n\nGlobal style guide:\n").append(globalStyleGuide);
        userPrompt.append("\n\n").append(registrySummary);

        String paletteContext = paletteSnapshot.isEmpty()
                ? "No colors have been assigned yet."
                : "Colors already used: " + String.join(", ", paletteSnapshot)
                  + ". Prefer harmonious contrast and avoid unnecessary repetition.";
        userPrompt.append("\n").append(paletteContext);
        String userPromptText = userPrompt.toString();

        return aiCallLimiter.submit(() -> AiRequestUtils.requestJsonObjectResultAsync(
                        aiClient,
                        log,
                        node.getStep(),
                        conversationSnapshot,
                        conversationContext.getMaxInputTokens(),
                        userPromptText,
                        ToolSchemas.SCENE_DESIGN,
                        () -> toolCalls.incrementAndGet()
                ))
                .thenApply(result -> {
                    SceneDesignResult designResult = parseSceneDesign(
                            node,
                            userPromptText,
                            result != null ? result.getAssistantTranscript() : "",
                            result != null ? result.getPayload() : null
                    );
                    if (designResult.scene != null) {
                        log.debug("  Scene designed for: {}", node.getStep());
                        return designResult;
                    }
                    // Scene parse failed — retry if attempts remain
                    if (retriesLeft > 0) {
                        log.warn("  Scene parse failed for '{}', retrying ({} left)",
                                node.getStep(), retriesLeft);
                        return null; // signal retry needed
                    }
                    log.error("  Scene design for '{}' failed after {} retries",
                            node.getStep(), MAX_SCENE_RETRIES);
                    return designResult;
                })
                .thenCompose(designResult -> {
                    if (designResult != null) {
                        return CompletableFuture.completedFuture(designResult);
                    }
                    // Take fresh snapshots for retry (registry/palette may have changed)
                    return designNodeWithRetry(node,
                            conversationContext.getMessages(),
                            snapshotObjectRegistry(),
                            snapshotPalette(),
                            retriesLeft - 1);
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    if (retriesLeft > 0) {
                        log.warn("  Visual design API error for '{}', retrying ({} left): {}",
                                node.getStep(), retriesLeft, cause.getMessage());
                        // Block on retry since we're in exceptionally handler
                        return designNodeWithRetry(node,
                                conversationContext.getMessages(),
                                snapshotObjectRegistry(),
                                snapshotPalette(),
                                retriesLeft - 1).join();
                    }
                    log.error("  Visual design for '{}' failed after {} retries: {}",
                            node.getStep(), MAX_SCENE_RETRIES, cause.getMessage());
                    return SceneDesignResult.failed(node, userPromptText);
                });
    }

    private SceneDesignResult parseSceneDesign(KnowledgeNode node,
                                               String userPrompt,
                                               String assistantTranscript,
                                               JsonNode data) {
        if (data == null || data.isNull()) {
            return new SceneDesignResult(node, userPrompt, assistantTranscript, null, List.of(), List.of());
        }

        JsonNode sceneNode = data.has("scene") ? data.get("scene") : data;
        StoryboardScene scene;
        try {
            scene = JsonUtils.mapper().treeToValue(sceneNode, StoryboardScene.class);
        } catch (Exception e) {
            log.warn("  Failed to parse scene for '{}': {}", node.getStep(), e.getMessage());
            return new SceneDesignResult(node, userPrompt, assistantTranscript, null, List.of(), List.of());
        }

        int index = teachingOrderIndex.getOrDefault(node.getId(), collectedScenes.size());
        scene.setSceneId("scene_" + (index + 1));
        if (scene.getStepRefs() == null || scene.getStepRefs().isEmpty()) {
            scene.setStepRefs(List.of(node.getStep()));
        }
        StoryboardNormalizer.normalizeScene(scene, index);

        // Diagnostic: detect prompt-schema mismatch where entering_objects still
        // carry full definitions instead of patch-only {id, placement, style}
        if (scene.getEnteringObjects() != null) {
            for (var obj : scene.getEnteringObjects()) {
                if (obj.getId() != null && obj.getKind() != null && !obj.getKind().isBlank()) {
                    log.warn("  Scene '{}' entering_objects item '{}' has kind='{}' — "
                            + "this field should be in new_objects only, not entering_objects. "
                            + "This suggests a prompt-schema mismatch.",
                            scene.getSceneId(), obj.getId(), obj.getKind());
                }
            }
        }

        List<StoryboardObject> newObjects = new ArrayList<>();
        if (data.has("new_objects") && data.get("new_objects").isArray()) {
            for (JsonNode objNode : data.get("new_objects")) {
                try {
                    StoryboardObject obj = JsonUtils.mapper().treeToValue(objNode, StoryboardObject.class);
                    if (obj != null && obj.getId() != null && !obj.getId().isBlank()) {
                        obj.setSourceNode(node.getStep());
                        obj.setPlacement(null);
                        newObjects.add(obj);
                    }
                } catch (Exception e) {
                    log.debug("  Failed to parse registry object: {}", e.getMessage());
                }
            }
        }

        List<String> paletteColors = new ArrayList<>();
        if (scene.getEnteringObjects() != null) {
            for (StoryboardObject obj : scene.getEnteringObjects()) {
                if (obj.getStyle() != null) {
                    for (var style : obj.getStyle()) {
                        if (style.getProperties() != null) {
                            style.getProperties().forEach((key, value) -> {
                                if (key.contains("color") && value instanceof String) {
                                    paletteColors.add((String) value);
                                }
                            });
                        }
                    }
                }
            }
        }
        return new SceneDesignResult(node, userPrompt, assistantTranscript, scene, newObjects, paletteColors);
    }

    private void commitBatchResults(List<SceneDesignResult> results) {
        for (SceneDesignResult result : results) {
            if (result == null) {
                continue;
            }
            if (result.scene != null) {
                collectedScenes.add(result.scene);
            }
            if (!result.newObjects.isEmpty()) {
                objectRegistry.addAll(result.newObjects);
            }
            if (!result.paletteColors.isEmpty()) {
                globalColorPalette.addAll(result.paletteColors);
            }
            if (result.scene != null && result.assistantTranscript != null && !result.assistantTranscript.isBlank()) {
                conversationContext.appendTurn(result.userPrompt, result.assistantTranscript);
            }
            // Back-propagate style/placement from scene objects to registry
            // so subsequent LLM calls see current visual state.
            if (result.scene != null) {
                backPropagateVisualState(result.scene);
            }
        }
    }

    /**
     * Merge style and placement from a scene's entering/persistent objects
     * back into the global object registry. This keeps the registry summary
     * up to date for subsequent LLM calls without persisting these
     * transient fields into the final output.
     */
    private void backPropagateVisualState(StoryboardScene scene) {
        Map<String, StoryboardObject> registryById = new LinkedHashMap<>();
        synchronized (objectRegistry) {
            for (StoryboardObject obj : objectRegistry) {
                registryById.put(obj.getId(), obj);
            }
        }
        List<StoryboardObject> sceneObjects = new ArrayList<>();
        if (scene.getEnteringObjects() != null) {
            sceneObjects.addAll(scene.getEnteringObjects());
        }
        if (scene.getPersistentObjects() != null) {
            sceneObjects.addAll(scene.getPersistentObjects());
        }
        for (StoryboardObject sceneObj : sceneObjects) {
            if (sceneObj.getId() == null) continue;
            StoryboardObject registryObj = registryById.get(sceneObj.getId());
            if (registryObj == null) continue;
            if (sceneObj.getStyle() != null && !sceneObj.getStyle().isEmpty()) {
                registryObj.setStyle(sceneObj.getStyle());
            }
            if (sceneObj.getPlacement() != null && sceneObj.getPlacement().hasData()) {
                registryObj.setPlacement(sceneObj.getPlacement());
            }
        }
    }

    private Narrative assembleNarrative(KnowledgeGraph graph) {
        // Sort scenes by teaching order index embedded in scene_id
        List<StoryboardScene> sorted = new ArrayList<>(collectedScenes);
        sorted.sort((a, b) -> {
            int ia = extractSceneNumber(a.getSceneId());
            int ib = extractSceneNumber(b.getSceneId());
            return Integer.compare(ia, ib);
        });

        // Renumber scene_ids sequentially
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setSceneId("scene_" + (i + 1));
        }

        Storyboard storyboard = new Storyboard();
        storyboard.setScenes(sorted);
        storyboard.setObjectRegistry(new ArrayList<>(objectRegistry));

        // Strip transient style/placement from registry before output —
        // these were accumulated only for LLM context continuity.
        for (StoryboardObject obj : storyboard.getObjectRegistry()) {
            obj.setStyle(null);
            obj.setPlacement(null);
        }

        // Build global metadata
        storyboard.setContinuityPlan("Objects maintain stable ids across scenes via the global object registry.");
        List<String> globalRules = new ArrayList<>();
        globalRules.add("Keep major objects inside the safe frame.");
        globalRules.add("Reuse stable anchors for persistent objects.");
        if (!globalColorPalette.isEmpty()) {
            globalRules.add("Color palette: " + String.join(", ", snapshotPalette()));
        }
        storyboard.setGlobalVisualRules(globalRules);

        storyboard = StoryboardNormalizer.normalize(storyboard);

        String targetConcept = graph.getTargetConcept();
        KnowledgeNode terminal = graph.findPrimaryTerminalNode();
        String targetDescription = TargetDescriptionBuilder.workflowTargetDescription(
                targetConcept,
                terminal != null ? terminal.getStep() : "",
                "",
                graph.isProblemMode(),
                outputTarget);
        Narrative narrative = new Narrative(
                targetConcept,
                targetDescription,
                storyboard
        );

        int totalDuration = StoryboardNormalizer.calculateStoryboardDuration(storyboard, sorted.size() * 8);
        List<String> sceneTitles = sorted.stream()
                .map(StoryboardScene::getTitle)
                .collect(Collectors.toList());
        log.info("Narrative assembled: {} scenes, ~{}s total, titles={}",
                sorted.size(), totalDuration, sceneTitles);
        return narrative;
    }

    private int extractSceneNumber(String sceneId) {
        if (sceneId == null) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(sceneId.replaceAll("\\D+", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private String buildEnrichmentContext(KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        if (node.getEquations() != null && !node.getEquations().isEmpty()) {
            sb.append("Mathematical enrichment for this node:\n");
            sb.append("Equations:\n");
            for (String eq : node.getEquations()) {
                sb.append("- ").append(eq).append("\n");
            }
        }
        if (node.getDefinitions() != null && !node.getDefinitions().isEmpty()) {
            if (sb.length() == 0) {
                sb.append("Mathematical enrichment for this node:\n");
            }
            sb.append("Definitions:\n");
            node.getDefinitions().forEach((symbol, definition) ->
                    sb.append("- ").append(symbol).append(": ").append(definition).append("\n"));
        }
        if (node.getInterpretation() != null && !node.getInterpretation().isBlank()) {
            sb.append("Interpretation: ").append(node.getInterpretation()).append("\n");
        }
        if (node.getExamples() != null && !node.getExamples().isEmpty()) {
            sb.append("Examples:\n");
            for (String example : node.getExamples()) {
                sb.append("- ").append(example).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String buildObjectRegistrySummary(List<StoryboardObject> snapshot) {
        if (snapshot.isEmpty()) {
            return "Object registry: empty (this is the first scene).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Current object registry (").append(snapshot.size()).append(" objects):\n");
        for (StoryboardObject obj : snapshot) {
            sb.append("- id=").append(obj.getId())
                    .append(", kind=").append(obj.getKind())
                    .append(", content=").append(truncate(obj.getContent(), 60));
            if (obj.getBehavior() != null && !obj.getBehavior().isBlank()) {
                sb.append(", behavior=").append(obj.getBehavior());
            }
            if (obj.getPlacement() != null && obj.getPlacement().hasData()) {
                sb.append(", placement=").append(formatPlacementSummary(obj.getPlacement()));
            }
            if (obj.getStyle() != null && !obj.getStyle().isEmpty()) {
                sb.append(", style=").append(formatStyleSummary(obj.getStyle()));
            }
            sb.append("\n");
        }
        sb.append("Refer to these by id in entering_objects, persistent_objects, and exiting_objects.");
        return sb.toString();
    }

    private static String formatPlacementSummary(Narrative.StoryboardPlacement placement) {
        StringBuilder sb = new StringBuilder();
        if (placement.getCoordinateSpace() != null) {
            sb.append(placement.getCoordinateSpace());
        }
        appendAxisSummary(sb, "x", placement.getX());
        appendAxisSummary(sb, "y", placement.getY());
        appendAxisSummary(sb, "z", placement.getZ());
        return sb.toString();
    }

    private static void appendAxisSummary(StringBuilder sb, String name,
                                          Narrative.StoryboardPlacementAxis axis) {
        if (axis == null) return;
        sb.append(" ").append(name).append("=");
        if (axis.getValue() != null) {
            sb.append(axis.getValue());
        }
        if (axis.getMin() != null || axis.getMax() != null) {
            sb.append("[");
            if (axis.getMin() != null) sb.append(axis.getMin());
            sb.append("..");
            if (axis.getMax() != null) sb.append(axis.getMax());
            sb.append("]");
        }
    }

    private static String formatStyleSummary(List<Narrative.StoryboardStyle> styles) {
        return styles.stream()
                .map(s -> {
                    StringBuilder sb = new StringBuilder();
                    if (s.getRole() != null) sb.append(s.getRole());
                    if (s.getProperties() != null && !s.getProperties().isEmpty()) {
                        sb.append("{");
                        sb.append(s.getProperties().entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .collect(Collectors.joining(", ")));
                        sb.append("}");
                    }
                    return sb.toString();
                })
                .collect(Collectors.joining("; "));
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private String buildGlobalStyleGuide(KnowledgeGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("Global visual context:\n");
        sb.append("- Input mode: ").append(graph.isProblemMode() ? "problem" : "concept");
        return sb.toString();
    }

    private boolean shouldDesignNode(KnowledgeNode node) {
        return node != null;
    }

    private String buildCurrentStepPrompt(KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("Design a scene for this knowledge node:\n");
        sb.append("- Step: ").append(node.getStep()).append("\n");
        sb.append("- Node type: ").append(node.getNodeType()).append("\n");
        sb.append("- Depth: ").append(node.getMinDepth()).append("\n");
        if (objectRegistry.isEmpty()) {
            sb.append("- This is the first scene. All objects must be in entering_objects; persistent_objects and exiting_objects must be empty.\n");
        }
        if (node.getReason() != null && !node.getReason().isBlank()) {
            sb.append("- Reason: ").append(node.getReason()).append("\n");
        }
        if (graph != null) {
            List<KnowledgeNode> prerequisites = graph.getPrerequisites(node.getId());
            if (!prerequisites.isEmpty()) {
                sb.append("Direct prerequisite steps:\n");
                for (KnowledgeNode prerequisite : prerequisites) {
                    sb.append("- ").append(prerequisite.getStep()).append("\n");
                }
            }
            if (prerequisites.size() > 1) {
                sb.append("Merge scene guidance:\n");
                sb.append("- This scene merges multiple prerequisite branches.\n");
                sb.append("- Reuse established object ids, color meanings, and continuity anchors.\n");
                sb.append("- Integrate the upstream conclusions in one scene instead of replaying each branch.\n");
            }
            List<KnowledgeNode> dependents = graph.getDependents(node.getId());
            if (!dependents.isEmpty()) {
                sb.append("Direct downstream steps:\n");
                for (KnowledgeNode dependent : dependents) {
                    sb.append("- ").append(dependent.getStep()).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private List<String> snapshotPalette() {
        List<String> palette = new ArrayList<>(globalColorPalette);
        palette.sort(String.CASE_INSENSITIVE_ORDER);
        return palette;
    }

    private List<StoryboardObject> snapshotObjectRegistry() {
        synchronized (objectRegistry) {
            return new ArrayList<>(objectRegistry);
        }
    }

    private static final class SceneDesignResult {
        private final KnowledgeNode node;
        private final String userPrompt;
        private final String assistantTranscript;
        private final StoryboardScene scene;
        private final List<StoryboardObject> newObjects;
        private final List<String> paletteColors;

        private SceneDesignResult(KnowledgeNode node,
                                  String userPrompt,
                                  String assistantTranscript,
                                  StoryboardScene scene,
                                  List<StoryboardObject> newObjects,
                                  List<String> paletteColors) {
            this.node = node;
            this.userPrompt = userPrompt;
            this.assistantTranscript = assistantTranscript == null ? "" : assistantTranscript;
            this.scene = scene;
            this.newObjects = newObjects != null ? newObjects : List.of();
            this.paletteColors = paletteColors != null ? paletteColors : List.of();
        }

        private static SceneDesignResult failed(KnowledgeNode node, String userPrompt) {
            return new SceneDesignResult(node, userPrompt, "", null, List.of(), List.of());
        }
    }
}
