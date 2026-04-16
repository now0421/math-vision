package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardAction;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.Narrative.StoryboardStyle;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.NarrativePrompts;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.TargetDescriptionBuilder;
import com.mathvision.util.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Stage 1c: Narrative Composition - composes a storyboard
 * from the enriched knowledge graph, with length adapted to graph complexity.
 */
public class NarrativeNode extends PocketFlow.Node<KnowledgeGraph, Narrative, String> {

    private static final Logger log = LoggerFactory.getLogger(NarrativeNode.class);
    private static final String TRUNCATION_MARKER = "\n[...truncated...]\n";

    private AiClient aiClient;
    private int toolCalls = 0;
    private WorkflowConfig workflowConfig;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;

    public NarrativeNode() {
        super(2, 2000);
    }

    @Override
    public KnowledgeGraph prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        WorkflowConfig config = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        if (config != null) {
            this.workflowConfig = config;
            this.outputTarget = config.getOutputTarget();
        }
        this.toolCalls = 0;
        return (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
    }

    @Override
    public Narrative exec(KnowledgeGraph graph) {
        log.info("=== Stage 1c: Narrative Composition ===");

        String resolvedMode = resolveInputMode(graph);
        boolean problemMode = WorkflowConfig.INPUT_MODE_PROBLEM.equals(resolvedMode);
        List<KnowledgeNode> ordered = graph.topologicalOrder();
        List<String> stepOrder = ordered.stream()
                .map(KnowledgeNode::getStep)
                .collect(java.util.stream.Collectors.toList());
        String targetConcept = graph.getTargetConcept();
        String workflowTargetDetails = buildWorkflowTargetDescription(graph, problemMode);
        String systemPrompt = NarrativePrompts.systemPrompt(
                targetConcept, workflowTargetDetails, outputTarget);

        log.info("  Narrative mode: {}, output_target: {}, order: {}", resolvedMode, outputTarget, stepOrder);

        int sceneCount = estimateSceneCount(ordered, problemMode);
        String context = buildTruncatedContext(
                targetConcept, ordered, problemMode, sceneCount, systemPrompt);
        String userPrompt = buildUserPrompt(targetConcept, context, sceneCount, problemMode);

        int totalDuration = estimateTotalDuration(sceneCount, problemMode);
        Storyboard storyboard;

        try {
            NarrativeDraft draft = requestNarrativeAsync(
                    userPrompt, systemPrompt, sceneCount, totalDuration).join();
            storyboard = draft.storyboard;
            sceneCount = draft.sceneCount;
            totalDuration = draft.totalDuration;
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            log.error("Narrative composition failed on attempt {}: {}",
                    currentRetry + 1, cause.getMessage());
            throw new RuntimeException("Narrative composition failed: " + cause.getMessage(), cause);
        }

        String codegenPrompt = NarrativePrompts.storyboardCodegenPrompt(
                targetConcept, storyboard, outputTarget);

        Narrative narrative = new Narrative(
                targetConcept,
                workflowTargetDetails,
                codegenPrompt,
                storyboard,
                stepOrder,
                totalDuration,
                sceneCount
        );

        List<String> sceneTitles = storyboard.getScenes().stream()
                .map(StoryboardScene::getTitle)
                .collect(java.util.stream.Collectors.toList());
        log.info("Narrative storyboard composed: {} scenes, ~{}s total, titles={}",
                sceneCount, totalDuration, sceneTitles);
        return narrative;
    }

    private CompletableFuture<NarrativeDraft> requestNarrativeAsync(String userPrompt,
                                                                    String systemPrompt,
                                                                    int defaultSceneCount,
                                                                    int defaultTotalDuration) {
        return aiClient.chatWithToolsRawAsync(userPrompt, systemPrompt, ToolSchemas.STORYBOARD)
                .thenApply(rawResponse -> {
                    toolCalls++;
                    NarrativeDraft draft = buildNarrativeDraft(
                            JsonUtils.extractToolCallPayload(rawResponse),
                            JsonUtils.extractBestEffortTextFromResponse(rawResponse),
                            defaultSceneCount,
                            defaultTotalDuration);
                    if (draft == null) {
                        throw new IllegalStateException(
                                "Narrative response did not contain a valid storyboard");
                    }
                    return draft;
                });
    }

    @Override
    public String post(Map<String, Object> ctx, KnowledgeGraph prepRes, Narrative narrative) {
        ctx.put(WorkflowKeys.NARRATIVE, narrative);

        int prevCalls = (int) ctx.getOrDefault(WorkflowKeys.ENRICHMENT_TOOL_CALLS, 0);
        ctx.put(WorkflowKeys.ENRICHMENT_TOOL_CALLS, prevCalls + toolCalls);

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveNarrative(outputDir, narrative);
        }

        return null;
    }

    private String buildUserPrompt(String targetConcept,
                                   String context,
                                   int sceneCount,
                                   boolean problemMode) {
        return problemMode
                ? NarrativePrompts.problemUserPrompt(targetConcept, context, sceneCount)
                : NarrativePrompts.conceptUserPrompt(targetConcept, context);
    }

    private String buildTruncatedContext(String targetConcept,
                                         List<KnowledgeNode> orderedNodes,
                                         boolean problemMode,
                                         int sceneCount,
                                         String systemPrompt) {
        String fullContext = buildContext(orderedNodes, problemMode);
        int fullContextTokens = estimateTokens(fullContext);
        int maxInputTokens = workflowConfig.getModelConfig().getMaxInputTokens();
        int promptOverheadTokens = estimateTokens(systemPrompt)
                + estimateTokens(buildUserPrompt(targetConcept, "", sceneCount, problemMode));
        int availableContextTokens = Math.max(0, maxInputTokens - promptOverheadTokens);

        if (fullContextTokens <= availableContextTokens) {
            return fullContext;
        }

        String truncatedContext = truncateToTokenBudget(fullContext, availableContextTokens, TRUNCATION_MARKER);
        log.warn("Narrative context truncated for model {}: ~{} -> ~{} tokens (max_input_token={}, prompt_overhead={})",
                workflowConfig.getModelConfig().getModel(),
                fullContextTokens,
                estimateTokens(truncatedContext),
                maxInputTokens,
                promptOverheadTokens);
        return truncatedContext;
    }

    private String buildContext(List<KnowledgeNode> orderedNodes,
                                boolean problemMode) {
        StringBuilder sb = new StringBuilder();
        sb.append(problemMode
                ? "Ordered problem-solving node context:\n"
                : "Ordered teaching node context:\n");

        for (int i = 0; i < orderedNodes.size(); i++) {
            KnowledgeNode node = orderedNodes.get(i);
            sb.append(formatNodeContext(i + 1, node));
        }

        return sb.toString();
    }

    private NarrativeDraft buildNarrativeDraft(JsonNode toolData,
                                               String rawText,
                                               int defaultSceneCount,
                                               int defaultTotalDuration) {
        Storyboard storyboard = parseStoryboard(toolData, rawText);
        if (storyboard == null
                || storyboard.getScenes() == null
                || storyboard.getScenes().isEmpty()) {
            return null;
        }

        int sceneCount = defaultSceneCount;
        int totalDuration = defaultTotalDuration;
        if (toolData != null) {
            if (toolData.has("scene_count")) {
                sceneCount = toolData.get("scene_count").asInt(defaultSceneCount);
            }
            if (toolData.has("estimated_duration")) {
                totalDuration = toolData.get("estimated_duration").asInt(defaultTotalDuration);
            }
        }

        sceneCount = storyboard.getScenes().size();
        totalDuration = calculateStoryboardDuration(storyboard, totalDuration);

        return new NarrativeDraft(storyboard, sceneCount, totalDuration);
    }

    private Storyboard parseStoryboard(JsonNode toolData, String rawText) {
        Storyboard storyboard = parseStoryboardNode(toolData);
        if (storyboard != null) {
            return storyboard;
        }

        if (rawText == null || rawText.isBlank()) {
            return null;
        }

        try {
            String candidate = JsonUtils.extractJsonObject(rawText);
            if (candidate == null || candidate.isBlank()) {
                log.warn("Storyboard fallback extraction returned empty candidate. excerpt={}",
                        sanitizeExcerpt(rawText));
                return null;
            }
            JsonNode textNode = JsonUtils.parseTreeBestEffort(candidate);
            if (textNode == null) {
                log.warn("Storyboard fallback parse returned null after best-effort repair. excerpt={}",
                        sanitizeExcerpt(candidate));
                return null;
            }
            return parseStoryboardNode(textNode);
        } catch (RuntimeException e) {
            log.warn("Failed to parse storyboard JSON from text response: {}. excerpt={}",
                    e.getMessage(), sanitizeExcerpt(rawText));
            return null;
        }
    }

    private String sanitizeExcerpt(String text) {
        if (text == null || text.isBlank()) {
            return "<empty>";
        }

        String compact = text.replaceAll("\\s+", " ").trim();
        int maxLen = 180;
        return compact.length() <= maxLen ? compact : compact.substring(0, maxLen) + "...";
    }

    private Storyboard parseStoryboardNode(JsonNode rootNode) {
        if (rootNode == null || rootNode.isNull()) {
            return null;
        }

        JsonNode storyboardNode = rootNode.has("storyboard") ? rootNode.get("storyboard") : rootNode;
        if (storyboardNode == null || storyboardNode.isNull() || !storyboardNode.has("scenes")) {
            return null;
        }

        try {
            return normalizeStoryboard(JsonUtils.mapper().treeToValue(storyboardNode, Storyboard.class));
        } catch (Exception e) {
            log.warn("Failed to map storyboard payload: {}", e.getMessage());
            return null;
        }
    }

    private Storyboard normalizeStoryboard(Storyboard storyboard) {
        if (storyboard == null) {
            return null;
        }

        if (storyboard.getGlobalVisualRules() == null) {
            storyboard.setGlobalVisualRules(new ArrayList<>());
        }
        if (storyboard.getScenes() == null) {
            storyboard.setScenes(new ArrayList<>());
        }
        if (storyboard.getContinuityPlan() == null || storyboard.getContinuityPlan().isBlank()) {
            storyboard.setContinuityPlan(
                    "Maintain one stable layout and update existing objects instead of redrawing the whole scene.");
        }
        if (storyboard.getSummary() == null || storyboard.getSummary().isBlank()) {
            storyboard.setSummary("Continuity-aware storyboard for the target lesson.");
        }

        List<String> globalRules = new ArrayList<>(storyboard.getGlobalVisualRules());
        if (globalRules.isEmpty()) {
            globalRules.add("Keep major objects inside the safe frame.");
            globalRules.add("Reuse stable anchors for persistent objects.");
        }
        storyboard.setGlobalVisualRules(globalRules);

        List<StoryboardScene> normalizedScenes = new ArrayList<>();
        for (int i = 0; i < storyboard.getScenes().size(); i++) {
            StoryboardScene scene = storyboard.getScenes().get(i);
            if (scene == null) {
                continue;
            }

            String sceneId = scene.getSceneId() == null || scene.getSceneId().isBlank()
                    ? "scene_" + (i + 1)
                    : scene.getSceneId().trim();
            scene.setSceneId(sceneId);

            if (scene.getTitle() == null || scene.getTitle().isBlank()) {
                scene.setTitle("Scene " + (i + 1));
            }
            if (scene.getGoal() == null || scene.getGoal().isBlank()) {
                scene.setGoal(scene.getTitle());
            }
            if (scene.getNarration() == null || scene.getNarration().isBlank()) {
                scene.setNarration(scene.getGoal());
            }
            if (scene.getDurationSeconds() <= 0) {
                scene.setDurationSeconds(8);
            }
            scene.setSceneMode(normalizeSceneMode(scene.getSceneMode()));
            if (scene.getCameraAnchor() == null || scene.getCameraAnchor().isBlank()) {
                scene.setCameraAnchor("center");
            }
            if (scene.getCameraPlan() == null || scene.getCameraPlan().isBlank()) {
                scene.setCameraPlan(isThreeDSceneMode(scene) ? "Set a readable 3D view before the main reveal."
                        : "Static 2D view.");
            }
            if (scene.getLayoutGoal() == null || scene.getLayoutGoal().isBlank()) {
                scene.setLayoutGoal("Keep the layout stable and uncluttered.");
            }
            if (scene.getSafeAreaPlan() == null || scene.getSafeAreaPlan().isBlank()) {
                scene.setSafeAreaPlan(
                        "Keep important screen-space content inside x in [-7, 7] and y in [-4, 4] with edge margin.");
            }
            if (scene.getScreenOverlayPlan() == null || scene.getScreenOverlayPlan().isBlank()) {
                scene.setScreenOverlayPlan(isThreeDSceneMode(scene)
                        ? "Keep titles and formulas visually separate if they must stay readable during viewpoint changes."
                        : "No separate overlay needed.");
            }
            if (scene.getGeometryConstraints() == null) {
                scene.setGeometryConstraints(new ArrayList<>());
            }
            if (scene.getStepRefs() == null) {
                scene.setStepRefs(new ArrayList<>());
            }
            if (scene.getPersistentObjects() == null) {
                scene.setPersistentObjects(new ArrayList<>());
            }
            if (scene.getExitingObjects() == null) {
                scene.setExitingObjects(new ArrayList<>());
            }
            if (scene.getNotesForCodegen() == null) {
                scene.setNotesForCodegen(new ArrayList<>());
            }

            List<StoryboardObject> normalizedObjects = new ArrayList<>();
            List<StoryboardObject> enteringObjects = scene.getEnteringObjects() == null
                    ? new ArrayList<>()
                    : scene.getEnteringObjects();
            for (int j = 0; j < enteringObjects.size(); j++) {
                StoryboardObject object = enteringObjects.get(j);
                if (object == null) {
                    continue;
                }
                if (object.getId() == null || object.getId().isBlank()) {
                    object.setId(sceneId + "_obj_" + (j + 1));
                }
                if (object.getKind() == null || object.getKind().isBlank()) {
                    object.setKind("visual");
                }
                if (object.getPlacement() == null || object.getPlacement().isBlank()) {
                    object.setPlacement("center");
                }
                if (object.getContent() == null || object.getContent().isBlank()) {
                    object.setContent(object.getId());
                }
                if (object.getStyle() == null) {
                    object.setStyle(new ArrayList<>());
                } else {
                    List<StoryboardStyle> normalizedStyles = new ArrayList<>();
                    for (StoryboardStyle style : object.getStyle()) {
                        if (style == null) {
                            continue;
                        }
                        if (style.getProperties() == null) {
                            style.setProperties(new LinkedHashMap<>());
                        }
                        normalizedStyles.add(style);
                    }
                    object.setStyle(normalizedStyles);
                }
                if (object.getConstraintNote() == null) {
                    object.setConstraintNote("");
                }
                normalizedObjects.add(object);
            }
            scene.setEnteringObjects(normalizedObjects);

            List<StoryboardAction> normalizedActions = new ArrayList<>();
            List<StoryboardAction> actions = scene.getActions() == null
                    ? new ArrayList<>()
                    : scene.getActions();
            for (int j = 0; j < actions.size(); j++) {
                StoryboardAction action = actions.get(j);
                if (action == null) {
                    continue;
                }
                if (action.getOrder() <= 0) {
                    action.setOrder(j + 1);
                }
                if (action.getType() == null || action.getType().isBlank()) {
                    action.setType("transform");
                }
                if (action.getTargets() == null) {
                    action.setTargets(new ArrayList<>());
                }
                if (action.getDescription() == null || action.getDescription().isBlank()) {
                    action.setDescription("Advance the explanation with a precise visual update.");
                }
                normalizedActions.add(action);
            }
            scene.setActions(normalizedActions);

            normalizedScenes.add(scene);
        }
        storyboard.setScenes(normalizedScenes);
        return storyboard;
    }

    private String normalizeSceneMode(String sceneMode) {
        if (sceneMode == null || sceneMode.isBlank()) {
            return "2d";
        }
        return sceneMode.trim().equalsIgnoreCase("3d") ? "3d" : "2d";
    }

    private boolean isThreeDSceneMode(StoryboardScene scene) {
        return scene != null && "3d".equalsIgnoreCase(scene.getSceneMode());
    }

    private int calculateStoryboardDuration(Storyboard storyboard, int fallbackDuration) {
        if (storyboard == null || storyboard.getScenes() == null || storyboard.getScenes().isEmpty()) {
            return fallbackDuration;
        }

        int total = storyboard.getScenes().stream()
                .mapToInt(StoryboardScene::getDurationSeconds)
                .sum();
        return total > 0 ? total : fallbackDuration;
    }

    private String formatNodeContext(int index, KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n--- Node %d ---%n", index));
        sb.append("step: ").append(node.getStep()).append("\n");

        sb.append("equations:\n");
        if (node.getEquations() == null || node.getEquations().isEmpty()) {
            sb.append("  []\n");
        } else {
            for (String eq : node.getEquations()) {
                sb.append("  - ").append(eq).append("\n");
            }
        }

        sb.append("definitions:\n");
        if (node.getDefinitions() == null || node.getDefinitions().isEmpty()) {
            sb.append("  {}\n");
        } else {
            node.getDefinitions().forEach((sym, def) ->
                    sb.append("  ").append(sym).append(": ").append(def).append("\n")
            );
        }

        sb.append("visual_spec:\n");
        Map<String, Object> spec = node.getVisualSpec();
        if (spec == null || spec.isEmpty()) {
            sb.append("  {}\n");
        } else {
            List<String> preferredKeys = Arrays.asList(
                    "layout",
                    "motion_plan",
                    "color_scheme",
                    "screen_overlay_plan",
                    "scene_mode",
                    "camera_plan",
                    "duration",
                    "color_palette"
            );
            appendVisualSpec(sb, spec, preferredKeys);
        }

        return sb.toString();
    }

    private void appendVisualSpec(StringBuilder sb,
                                  Map<String, Object> spec,
                                  List<String> preferredKeys) {
        List<String> emittedKeys = new ArrayList<>();
        for (String key : preferredKeys) {
            if (spec.containsKey(key)) {
                sb.append("  ").append(key).append(": ").append(spec.get(key)).append("\n");
                emittedKeys.add(key);
            }
        }

        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            if (emittedKeys.contains(entry.getKey())) {
                continue;
            }
            sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
    }

    private int estimateSceneCount(List<KnowledgeNode> orderedNodes, boolean problemMode) {
        if (!problemMode) {
            return Math.max(1, orderedNodes.size());
        }

        int nonRootSteps = Math.max(0, orderedNodes.size() - 1);
        if (nonRootSteps <= 2) {
            return 3;
        }
        if (nonRootSteps <= 4) {
            return 4;
        }
        return 5;
    }

    private int estimateTotalDuration(int sceneCount, boolean problemMode) {
        return problemMode ? sceneCount * 7 : sceneCount * 10;
    }

    private int estimateTokens(String text) {
        return TokenEstimator.estimateTokens(text);
    }

    private String truncateToTokenBudget(String text, int maxTokens, String marker) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }

        if (estimateTokens(text) <= maxTokens) {
            return text;
        }

        int markerTokens = estimateTokens(marker);
        int contentBudget = Math.max(0, maxTokens - markerTokens);
        String truncatedContent = truncateToCharBudget(text, contentBudget).stripTrailing();
        if (truncatedContent.isEmpty() || markerTokens > maxTokens) {
            return truncateToCharBudget(text, maxTokens).stripTrailing();
        }
        return truncatedContent + marker;
    }

    private String truncateToCharBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }

        // Approximate: build text incrementally, checking estimated tokens
        // Use a conservative char-per-token ratio to avoid over-scanning
        int estimatedMaxChars = maxTokens * 4;
        if (text.length() <= estimatedMaxChars && estimateTokens(text) <= maxTokens) {
            return text;
        }

        // Binary search for the right cut point
        int low = 0;
        int high = Math.min(text.length(), estimatedMaxChars);
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (estimateTokens(text.substring(0, mid)) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low);
    }

    private String resolveInputMode(KnowledgeGraph graph) {
        String configuredInputMode = workflowConfig.getInputMode();
        if (WorkflowConfig.isExplicitInputMode(configuredInputMode)) {
            return WorkflowConfig.normalizeInputMode(configuredInputMode);
        }

        if (graph != null && graph.isProblemMode()) {
            return WorkflowConfig.INPUT_MODE_PROBLEM;
        }
        return WorkflowConfig.INPUT_MODE_CONCEPT;
    }

    private String buildWorkflowTargetDescription(KnowledgeGraph graph, boolean problemMode) {
        KnowledgeNode root = graph.getRootNode();
        return TargetDescriptionBuilder.workflowTargetDescription(
                graph.getTargetConcept(),
                root != null ? root.getStep() : "",
                "",
                problemMode,
                outputTarget);
    }

    private static final class NarrativeDraft {
        private final Storyboard storyboard;
        private final int sceneCount;
        private final int totalDuration;

        private NarrativeDraft(Storyboard storyboard,
                               int sceneCount, int totalDuration) {
            this.storyboard = storyboard;
            this.sceneCount = sceneCount;
            this.totalDuration = totalDuration;
        }
    }
}
