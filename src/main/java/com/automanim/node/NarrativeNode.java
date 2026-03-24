package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.Narrative;
import com.automanim.model.Narrative.Storyboard;
import com.automanim.model.Narrative.StoryboardAction;
import com.automanim.model.Narrative.StoryboardObject;
import com.automanim.model.Narrative.StoryboardScene;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.JsonUtils;
import com.automanim.util.PromptTemplates;
import com.automanim.util.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Stage 1c: Narrative Composition - composes an animation script
 * from the enriched knowledge graph, with length adapted to graph complexity.
 */
public class NarrativeNode extends PocketFlow.Node<KnowledgeGraph, Narrative, String> {

    private static final Logger log = LoggerFactory.getLogger(NarrativeNode.class);
    private static final String TRUNCATION_MARKER = "\n[...truncated...]\n";
    private static final String NARRATIVE_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_storyboard\","
            + "    \"description\": \"Return a structured storyboard JSON for the planned step progression.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"storyboard\": { \"type\": \"object\", \"description\": \"Structured storyboard with continuity-aware scenes and object ids\" },"
            + "        \"scene_count\": { \"type\": \"integer\", \"description\": \"Estimated number of scenes\" },"
            + "        \"estimated_duration\": { \"type\": \"integer\", \"description\": \"Estimated total duration in seconds\" }"
            + "      },"
            + "      \"required\": [\"storyboard\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private AiClient aiClient;
    private int toolCalls = 0;
    private WorkflowConfig workflowConfig;

    public NarrativeNode() {
        super(2, 2000);
    }

    @Override
    public KnowledgeGraph prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        WorkflowConfig config = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        if (config != null) {
            this.workflowConfig = config;
        }
        this.toolCalls = 0;
        return (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
    }

    @Override
    public Narrative exec(KnowledgeGraph graph) {
        log.info("=== Stage 1c: Narrative Composition ===");

        String resolvedMode = resolveInputMode(graph);
        boolean problemMode = WorkflowConfig.INPUT_MODE_PROBLEM.equals(resolvedMode);
        List<KnowledgeNode> ordered = filterNarrativeNodes(graph.topologicalOrder(), problemMode);
        List<String> stepOrder = ordered.stream()
                .map(KnowledgeNode::getStep)
                .collect(java.util.stream.Collectors.toList());
        String workflowTargetDetails = buildWorkflowTargetDescription(graph, problemMode);
        String workflowTarget = graph.getTargetConcept();
        String systemPrompt = PromptTemplates.narrativeSystemPrompt(
                workflowTarget, workflowTargetDetails);

        log.info("  Narrative mode: {}, order: {}", resolvedMode, stepOrder);

        int sceneCount = estimateSceneCount(ordered, problemMode);
        String promptTarget = graph.getTargetConcept();
        String context = buildTruncatedContext(
                promptTarget, ordered, problemMode, sceneCount, systemPrompt);
        String userPrompt = buildUserPrompt(promptTarget, context, sceneCount, problemMode);

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

        String codegenPrompt = PromptTemplates.storyboardCodegenPrompt(
                graph.getTargetConcept(), storyboard);

        Narrative narrative = new Narrative(
                graph.getTargetConcept(),
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
        return aiClient.chatWithToolsRawAsync(userPrompt, systemPrompt, NARRATIVE_TOOL)
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
                ? PromptTemplates.problemNarrativeUserPrompt(targetConcept, context, sceneCount)
                : PromptTemplates.narrativeUserPrompt(targetConcept, context);
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
        sb.append("Narrative context rules:\n");
        sb.append("- Treat visual specifications as primary staging guidance for object roles, relative layout, and continuity.\n");
        sb.append("- Treat each node's step as the teaching beat to stage in order.\n");
        sb.append("- Treat equations and definitions as optional supporting material.\n");
        sb.append("- Use equations and definitions only when they help the main point.\n");
        sb.append("- It is acceptable to ignore optional math details that would make scenes crowded or repetitive.\n");
        sb.append("- Keep important screen-space content inside x in [-7, 7], y in [-4, 4].\n");
        sb.append("- If a planned layout would overflow, split content across scenes instead of squeezing it.\n");
        sb.append("- Follow the provided topological order when deciding what should be established before later beats.\n");
        sb.append("- Visual design layouts are relative-only guidance; this stage must choose the final absolute coordinates for `layout_goal` and each object's `placement`.\n");
        sb.append("- When assigning coordinates, reason over the whole storyboard so future derived objects and later scenes still fit inside the safe area.\n");
        if (problemMode) {
            sb.append("- Keep the story centered on solving the stated problem, not on surveying related theory.\n");
            sb.append("- Reuse one stable diagram and add only the smallest necessary change per scene.\n");
            sb.append("- Use the problem node to establish the givens and target before later solution moves.\n");
            sb.append("- Merge nearby steps when they belong to the same solving move.\n");
        }
        sb.append("\n");

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

        if (storyboard != null && storyboard.getScenes() != null && !storyboard.getScenes().isEmpty()) {
            sceneCount = storyboard.getScenes().size();
            totalDuration = calculateStoryboardDuration(storyboard, totalDuration);
        }

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
                return null;
            }
            JsonNode textNode = JsonUtils.parseTree(candidate);
            return parseStoryboardNode(textNode);
        } catch (RuntimeException e) {
            log.debug("Failed to parse storyboard JSON from text response: {}", e.getMessage());
            return null;
        }
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
            Storyboard storyboard = JsonUtils.mapper().treeToValue(storyboardNode, Storyboard.class);
            return normalizeStoryboard(storyboard);
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
                        : "Static 2D camera.");
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
                        ? "Keep titles and formulas fixed in frame if they must stay readable during camera motion."
                        : "No fixed screen overlay needed.");
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
                    "visual_description",
                    "color_scheme",
                    "layout",
                    "animation_description",
                    "transitions",
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

    private List<KnowledgeNode> filterNarrativeNodes(List<KnowledgeNode> orderedNodes, boolean problemMode) {
        return orderedNodes;
    }

    private String buildWorkflowTargetDescription(KnowledgeGraph graph, boolean problemMode) {
        KnowledgeNode root = graph.getRootNode();
        return PromptTemplates.workflowTargetDescription(
                graph.getTargetConcept(),
                root != null ? root.getStep() : "",
                "",
                problemMode);
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
