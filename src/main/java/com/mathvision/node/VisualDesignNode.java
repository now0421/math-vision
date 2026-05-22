package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardConstraint;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.prompt.VisualDesignPrompts;
import com.mathvision.prompt.SystemPrompts;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.StoryboardGeometricMarkerValidator;
import com.mathvision.util.StoryboardNormalizer;
import com.mathvision.util.StoryboardPatchResolver;
import com.mathvision.util.StoryboardConstraintUtils;
import com.mathvision.util.TargetDescriptionBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Stage 1b: Visual Design — generates one StoryboardScene per knowledge-graph
 * node in teaching order using conversation history for continuity, accumulates
 * a global object registry, and assembles the complete Narrative in post().
 *
 * Scenes are designed sequentially so each node sees the visible object state
 * left by the immediately preceding scene.
 */
public class VisualDesignNode extends PocketFlow.Node<KnowledgeGraph, KnowledgeGraph, String> {

    private static final Logger log = LoggerFactory.getLogger(VisualDesignNode.class);

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private final AtomicInteger toolCalls = new AtomicInteger(0);
    private boolean parallelEnabled = true;
    private int maxConcurrent = 4;
    private int maxSceneRetries = 3;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private final java.util.Set<String> globalColorPalette = ConcurrentHashMap.newKeySet();
    private ConcurrencyUtils.AsyncLimiter aiCallLimiter;
    private String globalStyleGuide = "";
    private KnowledgeGraph graph;
    private NodeConversationContext conversationContext;

    // Scene accumulation state
    private final List<StoryboardScene> collectedScenes = Collections.synchronizedList(new ArrayList<>());
    private final List<StoryboardObject> objectRegistry = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, StoryboardObject> visibleObjectRegistry = Collections.synchronizedMap(new LinkedHashMap<>());
    private Map<String, Integer> teachingOrderIndex = new LinkedHashMap<>();

    public VisualDesignNode() {
        super(1, 0);
    }

    @Override
    public KnowledgeGraph prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        this.maxSceneRetries = 3;
        if (workflowConfig != null) {
            this.parallelEnabled = workflowConfig.isParallelVisualDesign();
            this.maxConcurrent = workflowConfig.getMaxConcurrent();
            this.maxSceneRetries = Math.max(workflowConfig.getVisualDesignSceneMaxRetries(), 0);
            this.outputTarget = workflowConfig.getOutputTarget();
        }
        return (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
    }

    @Override
    public KnowledgeGraph exec(KnowledgeGraph graph) {
        int concurrency = 1;
        log.info("=== Stage 1b: Visual Design (output_target={}, sequential teaching order) ===", outputTarget);
        if (parallelEnabled && maxConcurrent > 1) {
            log.info("  Visual design ignores configured parallel concurrency={} to preserve scene-to-scene continuity", maxConcurrent);
        }
        toolCalls.set(0);
        globalColorPalette.clear();
        collectedScenes.clear();
        objectRegistry.clear();
        visibleObjectRegistry.clear();
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
        this.conversationContext = new NodeConversationContext(maxInputTokens, 2);
        String solutionChain = TargetDescriptionBuilder.buildSolutionChain(graph, null);
        this.conversationContext.setSystemMessage(VisualDesignPrompts.buildRulesPrompt(outputTarget));
        this.conversationContext.setFixedContextMessage(VisualDesignPrompts.buildFixedContextPrompt(
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
        List<KnowledgeNode> teachingNodes = graph.teachingOrderNodes();
        int expectedSceneCount = teachingOrderIndex.size();

        try {
            for (KnowledgeNode node : teachingNodes) {
                if (!shouldDesignNode(node)) {
                    continue;
                }
                log.info("  Designing scene {} of {}: {}",
                        collectedScenes.size() + 1, expectedSceneCount, node.getStep());
                designTeachingOrderNode(node);
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

    private void designTeachingOrderNode(KnowledgeNode node) {
        SceneDesignResult result = designNodeWithRetry(
                node,
                conversationContext.getMessages(),
                snapshotVisibleObjectRegistry(),
                snapshotPalette(),
                maxSceneRetries,
                maxSceneRetries,
                null
        ).join();
        commitSceneResult(result);
    }

    private CompletableFuture<SceneDesignResult> designNodeWithRetry(
            KnowledgeNode node,
            List<NodeConversationContext.Message> conversationSnapshot,
            List<StoryboardObject> visibleObjectSnapshot,
            List<String> paletteSnapshot,
            int maxRetries,
            int retriesLeft,
            SceneDesignRejection previousRejection) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append(buildCurrentStepPrompt(node));

        // Enrichment data (equations/definitions from MathEnrichmentNode)
        String enrichmentContext = buildEnrichmentContext(node);
        if (!enrichmentContext.isBlank()) {
            userPrompt.append("\n\n").append(enrichmentContext);
        }

        String globalRegistrySummary = buildGlobalObjectRegistrySummary();
        String visibleRegistrySummary = buildVisibleObjectRegistrySummary(visibleObjectSnapshot);
        userPrompt.append("\n\nGlobal style guide:\n").append(globalStyleGuide);
        userPrompt.append("\n\n").append(globalRegistrySummary);
        userPrompt.append("\n\n").append(visibleRegistrySummary);

        String paletteContext = paletteSnapshot.isEmpty()
                ? "No colors have been assigned yet."
                : "Colors already used: " + String.join(", ", paletteSnapshot)
                  + ". Prefer harmonious contrast and avoid unnecessary repetition.";
        userPrompt.append("\n").append(paletteContext);
        if (previousRejection != null) {
            userPrompt.append("\n\n").append(buildSceneRejectionRetryBlock(previousRejection));
        }
        String userPromptText = SystemPrompts.buildCurrentRequestSection(userPrompt.toString());

        return aiCallLimiter.submit(() -> AiRequestUtils.requestJsonObjectResultAsync(
                        aiClient,
                        log,
                        node.getStep(),
                        conversationSnapshot,
                        conversationContext.getMaxInputTokens(),
                        userPromptText,
                        ToolSchemas.sceneDesign(outputTarget),
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
                        List<String> semanticIssues = StoryboardGeometricMarkerValidator.validateSceneDesign(
                                designResult.scene,
                                designResult.newObjects,
                                visibleObjectSnapshot,
                                snapshotObjectRegistry());
                        if (!semanticIssues.isEmpty()) {
                            if (retriesLeft > 0) {
                                log.warn("  Scene design for '{}' failed geometric marker validation, retrying ({} left): {}",
                                        node.getStep(), retriesLeft, semanticIssues);
                                return SceneDesignResult.rejected(node, userPromptText, designResult, semanticIssues);
                            }
                            log.warn("  Scene design for '{}' still has geometric marker validation issues after {} retries; keeping final scene for storyboard validation: {}",
                                    node.getStep(), maxRetries, semanticIssues);
                        }
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
                            node.getStep(), maxRetries);
                    return designResult;
                })
                .thenCompose(designResult -> {
                    if (designResult instanceof RejectedSceneDesignResult) {
                        RejectedSceneDesignResult rejected = (RejectedSceneDesignResult) designResult;
                        return designNodeWithRetry(node,
                                conversationContext.getMessages(),
                                snapshotVisibleObjectRegistry(),
                                snapshotPalette(),
                                maxRetries,
                                retriesLeft - 1,
                                new SceneDesignRejection(rejected.rejectedResult, rejected.issues));
                    }
                    if (designResult != null) {
                        return CompletableFuture.completedFuture(designResult);
                    }
                    // Take fresh snapshots for retry (registry/palette may have changed)
                    return designNodeWithRetry(node,
                            conversationContext.getMessages(),
                            snapshotVisibleObjectRegistry(),
                            snapshotPalette(),
                            maxRetries,
                            retriesLeft - 1,
                            null);
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    if (retriesLeft > 0) {
                        log.warn("  Visual design API error for '{}', retrying ({} left): {}",
                                node.getStep(), retriesLeft, cause.getMessage());
                        // Block on retry since we're in exceptionally handler
                        return designNodeWithRetry(node,
                                conversationContext.getMessages(),
                                snapshotVisibleObjectRegistry(),
                                snapshotPalette(),
                                maxRetries,
                                retriesLeft - 1,
                                null).join();
                    }
                    log.error("  Visual design for '{}' failed after {} retries: {}",
                            node.getStep(), maxRetries, cause.getMessage());
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
            scene = JsonUtils.mapper().treeToValue(sanitizeLoosePlacementFields(sceneNode), StoryboardScene.class);
        } catch (Exception e) {
            log.warn("  Failed to parse scene for '{}': {}", node.getStep(), e.getMessage());
            return new SceneDesignResult(node, userPrompt, assistantTranscript, null, List.of(), List.of());
        }

        int index = teachingOrderIndex.getOrDefault(node.getId(), collectedScenes.size());
        scene.setSceneId("scene_" + (index + 1));
        scene.setStepRefs(List.of(node.getStep()));
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
                    StoryboardObject obj = JsonUtils.mapper().treeToValue(
                            sanitizeLoosePlacementFields(objNode), StoryboardObject.class);
                    if (obj != null && obj.getId() != null && !obj.getId().isBlank()) {
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
                collectStyleColors(obj.getStyle(), paletteColors);
            }
        }
        return new SceneDesignResult(node, userPrompt, assistantTranscript, scene, newObjects, paletteColors);
    }

    private JsonNode sanitizeLoosePlacementFields(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        JsonNode copy = node.deepCopy();
        sanitizeLoosePlacementFieldsInPlace(copy);
        return copy;
    }

    private void sanitizeLoosePlacementFieldsInPlace(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                sanitizeLoosePlacementFieldsInPlace(item);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        ObjectNode objectNode = (ObjectNode) node;
        JsonNode placement = objectNode.get("placement");
        if (placement != null && placement.isValueNode() && !placement.isNull()) {
            objectNode.remove("placement");
        }

        objectNode.fields().forEachRemaining(entry ->
                sanitizeLoosePlacementFieldsInPlace(entry.getValue()));
    }

    private void commitSceneResult(SceneDesignResult result) {
        if (result == null) {
            return;
        }

        List<StoryboardObject> visibleObjectSnapshot = snapshotVisibleObjectRegistry();
        addNewObjectsToRegistry(result.newObjects);

        Map<String, StoryboardObject> registryDefinitions = snapshotObjectRegistryById();
        if (result.scene != null) {
            stripCoordinateDerivedPlacements(
                    result.scene,
                    result.newObjects,
                    registryDefinitions,
                    visibleObjectSnapshot);
            collectedScenes.add(result.scene);
            Map<String, StoryboardObject> nextVisibleState = computeSceneVisibleState(
                    result.scene,
                    visibleObjectSnapshot,
                    registryDefinitions);
            synchronized (visibleObjectRegistry) {
                visibleObjectRegistry.clear();
                visibleObjectRegistry.putAll(nextVisibleState);
            }
        }
        if (!result.paletteColors.isEmpty()) {
            globalColorPalette.addAll(result.paletteColors);
        }
        if (result.scene != null && result.assistantTranscript != null && !result.assistantTranscript.isBlank()) {
            conversationContext.appendTurn(result.userPrompt, result.assistantTranscript);
        }
    }

    private void addNewObjectsToRegistry(List<StoryboardObject> newObjects) {
        if (newObjects == null || newObjects.isEmpty()) {
            return;
        }
        synchronized (objectRegistry) {
            Set<String> existingIds = new LinkedHashSet<>();
            for (StoryboardObject object : objectRegistry) {
                String id = objectId(object);
                if (id != null) {
                    existingIds.add(id);
                }
            }
            for (StoryboardObject object : newObjects) {
                String id = objectId(object);
                if (id == null) {
                    continue;
                }
                if (existingIds.add(id)) {
                    objectRegistry.add(object);
                } else {
                    log.warn("  Ignoring duplicate new_objects registry definition for id='{}'; reuse it via entering_objects instead", id);
                }
            }
        }
    }

    private void stripCoordinateDerivedPlacements(StoryboardScene scene,
                                                   List<StoryboardObject> newObjects,
                                                   Map<String, StoryboardObject> registryDefinitions,
                                                   List<StoryboardObject> batchVisibleObjectSnapshot) {
        Set<String> ownerIds = collectCoordinateDerivedOwnerIds(scene, newObjects, registryDefinitions, batchVisibleObjectSnapshot);
        if (ownerIds.isEmpty()) {
            return;
        }
        stripPlacementFromPatches(scene.getEnteringObjects(), ownerIds);
        stripPlacementFromPatches(scene.getPersistentObjects(), ownerIds);
    }

    private Set<String> collectCoordinateDerivedOwnerIds(StoryboardScene scene,
                                                         List<StoryboardObject> newObjects,
                                                         Map<String, StoryboardObject> registryDefinitions,
                                                         List<StoryboardObject> batchVisibleObjectSnapshot) {
        Set<String> ownerIds = new LinkedHashSet<>();
        collectCoordinateDerivedOwnerIds(ownerIds, scene != null ? scene.getConstraints() : null);
        collectCoordinateDerivedOwnerIds(ownerIds, objectConstraints(scene != null ? scene.getEnteringObjects() : null));
        collectCoordinateDerivedOwnerIds(ownerIds, objectConstraints(scene != null ? scene.getPersistentObjects() : null));
        collectCoordinateDerivedOwnerIds(ownerIds, objectConstraints(newObjects));
        collectCoordinateDerivedOwnerIds(ownerIds, objectConstraints(batchVisibleObjectSnapshot));
        if (registryDefinitions != null) {
            collectCoordinateDerivedOwnerIds(ownerIds, objectConstraints(new ArrayList<>(registryDefinitions.values())));
        }
        return ownerIds;
    }

    private List<StoryboardConstraint> objectConstraints(List<StoryboardObject> objects) {
        List<StoryboardConstraint> constraints = new ArrayList<>();
        if (objects == null) {
            return constraints;
        }
        for (StoryboardObject object : objects) {
            if (object != null && object.getConstraints() != null) {
                constraints.addAll(object.getConstraints());
            }
        }
        return constraints;
    }

    private void collectCoordinateDerivedOwnerIds(Set<String> ownerIds, List<StoryboardConstraint> constraints) {
        if (constraints == null) {
            return;
        }
        for (StoryboardConstraint constraint : constraints) {
            if (StoryboardConstraintUtils.isCoordinateDerivedConstraint(constraint)) {
                ownerIds.addAll(StoryboardConstraintUtils.ownerIds(constraint));
            }
        }
    }

    private void stripPlacementFromPatches(List<StoryboardObject> patches, Set<String> ownerIds) {
        if (patches == null || ownerIds == null || ownerIds.isEmpty()) {
            return;
        }
        for (StoryboardObject patch : patches) {
            String id = objectId(patch);
            if (id != null && ownerIds.contains(id)) {
                patch.setPlacement(null);
            }
        }
    }

    private Map<String, StoryboardObject> computeSceneVisibleState(StoryboardScene scene,
                                                                  List<StoryboardObject> baseVisibleSnapshot,
                                                                  Map<String, StoryboardObject> registryDefinitions) {
        Map<String, StoryboardObject> nextVisibleState = mapObjectsById(baseVisibleSnapshot);
        Map<String, StoryboardObject> baseVisibleById = mapObjectsById(baseVisibleSnapshot);
        mergeSceneObjects(nextVisibleState, scene.getPersistentObjects(), baseVisibleById, registryDefinitions);
        mergeSceneObjects(nextVisibleState, scene.getEnteringObjects(), baseVisibleById, registryDefinitions);
        removeSceneObjects(nextVisibleState, scene.getExitingObjects());
        return nextVisibleState;
    }

    private void mergeSceneObjects(Map<String, StoryboardObject> target,
                                   List<StoryboardObject> patches,
                                   Map<String, StoryboardObject> baseVisibleById,
                                   Map<String, StoryboardObject> registryDefinitions) {
        if (patches == null) {
            return;
        }
        for (StoryboardObject patch : patches) {
            String id = objectId(patch);
            if (id == null) {
                continue;
            }
            StoryboardObject merged = StoryboardPatchResolver.copyObject(baseVisibleById.get(id));
            if (merged == null) {
                merged = StoryboardPatchResolver.copyObject(registryDefinitions.get(id));
            }
            if (merged == null) {
                merged = new StoryboardObject();
                merged.setId(id);
            }
            applyVisiblePatch(merged, patch);
            target.put(id, merged);
        }
    }

    private void removeSceneObjects(Map<String, StoryboardObject> target, List<StoryboardObject> patches) {
        if (patches == null) {
            return;
        }
        for (StoryboardObject patch : patches) {
            String id = objectId(patch);
            if (id != null) {
                target.remove(id);
            }
        }
    }

    private void applyVisiblePatch(StoryboardObject target, StoryboardObject patch) {
        if (target == null || patch == null) {
            return;
        }
        String id = objectId(patch);
        if (id != null) {
            target.setId(id);
        }
        if (patch.getPlacement() != null && patch.getPlacement().hasData()) {
            target.setPlacement(StoryboardPatchResolver.copyObject(patch).getPlacement());
        }
        if (patch.getStyle() != null && patch.getStyle().hasData()) {
            target.setStyle(StoryboardPatchResolver.copyObject(patch).getStyle());
        }
    }

    private Map<String, StoryboardObject> mapObjectsById(List<StoryboardObject> objects) {
        Map<String, StoryboardObject> byId = new LinkedHashMap<>();
        if (objects == null) {
            return byId;
        }
        for (StoryboardObject object : objects) {
            String id = objectId(object);
            if (id != null) {
                byId.put(id, StoryboardPatchResolver.copyObject(object));
            }
        }
        return byId;
    }

    private Map<String, StoryboardObject> snapshotObjectRegistryById() {
        return mapObjectsById(snapshotObjectRegistry());
    }

    private List<StoryboardObject> snapshotObjectRegistry() {
        synchronized (objectRegistry) {
            List<StoryboardObject> snapshot = new ArrayList<>();
            for (StoryboardObject object : objectRegistry) {
                snapshot.add(StoryboardPatchResolver.copyObject(object));
            }
            return snapshot;
        }
    }

    private String objectId(StoryboardObject object) {
        return StoryboardPatchResolver.objectId(object);
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

    private String buildGlobalObjectRegistrySummary() {
        List<StoryboardObject> snapshot;
        synchronized (objectRegistry) {
            snapshot = new ArrayList<>(objectRegistry);
        }
        if (snapshot.isEmpty()) {
            return "Global object registry: empty. Since no object has been defined yet, every visible object introduced in this first scene needs a `new_objects` definition.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Global object registry — all ids already defined in earlier scenes, including objects that may no longer be visible (")
                .append(snapshot.size()).append(" objects):\n");
        for (StoryboardObject obj : snapshot) {
            appendRegistryObjectDefinitionSummary(sb, obj);
        }
        sb.append("Do not repeat these ids in `new_objects`. If one of these objects re-enters the scene, reference its existing id in `entering_objects` with any needed placement/style patch.");
        return sb.toString();
    }

    private String buildVisibleObjectRegistrySummary(List<StoryboardObject> snapshot) {
        if (snapshot.isEmpty()) {
            return "Currently visible object registry: empty (no objects are currently visible). Previously defined global objects may still re-enter via `entering_objects`, but they must not be repeated in `new_objects`.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Currently visible object registry — objects visible immediately before this scene, with current visual state (")
                .append(snapshot.size()).append(" objects):\n");
        for (StoryboardObject obj : snapshot) {
            appendRegistryObjectDefinitionSummary(sb, obj);
            int insertAt = sb.length() - 1;
            StringBuilder visualState = new StringBuilder();
            if (obj.getPlacement() != null && obj.getPlacement().hasData()) {
                visualState.append(", placement=").append(formatPlacementSummary(obj.getPlacement()));
            }
            if (obj.getStyle() != null && obj.getStyle().hasData()) {
                visualState.append(", style=").append(formatStyleSummary(obj.getStyle()));
            }
            if (visualState.length() > 0) {
                sb.insert(insertAt, visualState);
            }
        }
        sb.append("Refer to these currently visible ids in `persistent_objects` and `exiting_objects`; use `entering_objects` for new or re-entering visible objects.");
        return sb.toString();
    }

    private void appendRegistryObjectDefinitionSummary(StringBuilder sb, StoryboardObject obj) {
        sb.append("- id=").append(obj.getId())
                .append(", kind=").append(obj.getKind())
                .append(", content=").append(obj.getContent() == null ? "" : obj.getContent());
        if (obj.getConstraints() != null && !obj.getConstraints().isEmpty()) {
            sb.append(", constraints=").append(JsonUtils.toJson(obj.getConstraints()));
        }
        sb.append("\n");
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

    private static String formatStyleSummary(Narrative.StoryboardStyle style) {
        List<String> parts = new ArrayList<>();
        appendStylePart(parts, "color", style.getColor());
        appendStylePart(parts, "fill_color", style.getFillColor());
        appendStylePart(parts, "stroke_color", style.getStrokeColor());
        appendStylePart(parts, "highlight_color", style.getHighlightColor());
        appendStylePart(parts, "font_family", style.getFontFamily());
        appendStylePart(parts, "font_weight", style.getFontWeight());
        appendStylePart(parts, "font_style", style.getFontStyle());
        appendStylePart(parts, "line_style", style.getLineStyle());
        appendStylePart(parts, "opacity", style.getOpacity());
        appendStylePart(parts, "fill_opacity", style.getFillOpacity());
        appendStylePart(parts, "stroke_opacity", style.getStrokeOpacity());
        appendStylePart(parts, "stroke_width", style.getStrokeWidth());
        appendStylePart(parts, "font_size", style.getFontSize());
        appendStylePart(parts, "padding", style.getPadding());
        appendStylePart(parts, "corner_radius", style.getCornerRadius());
        appendStylePart(parts, "z_index", style.getZIndex());
        appendStylePart(parts, "point_size", style.getPointSize());
        appendStylePart(parts, "radius", style.getRadius());
        appendStylePart(parts, "marker_size", style.getMarkerSize());
        appendStylePart(parts, "point_style", style.getPointStyle());
        appendStylePart(parts, "decoration", style.getDecoration());
        appendStylePart(parts, "label_visible", style.getLabelVisible());
        return "{" + String.join(", ", parts) + "}";
    }

    private static void appendStylePart(List<String> parts, String key, Object value) {
        if (value instanceof String && ((String) value).isBlank()) {
            return;
        }
        if (value != null) {
            parts.add(key + "=" + value);
        }
    }

    private static void collectStyleColors(Narrative.StoryboardStyle style, List<String> colors) {
        if (style == null) {
            return;
        }
        addColor(colors, style.getColor());
        addColor(colors, style.getFillColor());
        addColor(colors, style.getStrokeColor());
        addColor(colors, style.getHighlightColor());
    }

    private static void addColor(List<String> colors, String color) {
        if (color != null && !color.isBlank()) {
            colors.add(color);
        }
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

    private String buildSceneRejectionRetryBlock(SceneDesignRejection rejection) {
        StringBuilder sb = new StringBuilder();
        sb.append("Previous scene design was rejected by local geometric-marker validation.\n");
        sb.append("Regenerate the FULL `scene` and `new_objects` response for the same knowledge node; do not return a partial patch.\n");
        sb.append("Fix these issues exactly:\n");
        for (String issue : rejection.issues) {
            sb.append("- ").append(issue).append("\n");
        }
        sb.append("Geometric marker repair requirements:\n");
        sb.append("- `angle_marker` objects need `marker/angle_between` with marker, vertex, ordered start/end boundaries, and sector.\n");
        sb.append("- `arc` or `arc_marker` objects need `marker/arc_sweep` with marker/arc, center/anchor/vertex, start_boundary, end_boundary, direction, and sector.\n");
        sb.append("- `right_angle_marker` objects need `marker/right_angle_at` with marker, vertex, start_boundary, end_boundary, and side_of_reference.\n");
        sb.append("- Object refs must name existing registry ids or ids introduced in this response; never put object ids in parameters.\n");
        if (rejection.rejectedResult != null) {
            sb.append("Rejected response for reference:\n");
            sb.append(JsonUtils.toPrettyJson(Map.of(
                    "scene", rejection.rejectedResult.scene,
                    "new_objects", rejection.rejectedResult.newObjects)));
        }
        return sb.toString();
    }

    private List<String> snapshotPalette() {
        List<String> palette = new ArrayList<>(globalColorPalette);
        palette.sort(String.CASE_INSENSITIVE_ORDER);
        return palette;
    }

    private List<StoryboardObject> snapshotVisibleObjectRegistry() {
        synchronized (visibleObjectRegistry) {
            List<StoryboardObject> snapshot = new ArrayList<>();
            for (StoryboardObject object : visibleObjectRegistry.values()) {
                snapshot.add(StoryboardPatchResolver.copyObject(object));
            }
            return snapshot;
        }
    }

    private static class SceneDesignResult {
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

        private static SceneDesignResult rejected(KnowledgeNode node,
                                                  String userPrompt,
                                                  SceneDesignResult rejectedResult,
                                                  List<String> issues) {
            return new RejectedSceneDesignResult(node, userPrompt, rejectedResult, issues);
        }
    }

    private static final class RejectedSceneDesignResult extends SceneDesignResult {
        private final SceneDesignResult rejectedResult;
        private final List<String> issues;

        private RejectedSceneDesignResult(KnowledgeNode node,
                                          String userPrompt,
                                          SceneDesignResult rejectedResult,
                                          List<String> issues) {
            super(node, userPrompt, "", null, List.of(), List.of());
            this.rejectedResult = rejectedResult;
            this.issues = issues != null ? issues : List.of();
        }
    }

    private static final class SceneDesignRejection {
        private final SceneDesignResult rejectedResult;
        private final List<String> issues;

        private SceneDesignRejection(SceneDesignResult rejectedResult, List<String> issues) {
            this.rejectedResult = rejectedResult;
            this.issues = issues != null ? issues : List.of();
        }
    }
}
