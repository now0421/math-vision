package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.Narrative;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.JsonUtils;
import com.automanim.util.PromptTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Stage 1c: Narrative Composition - composes an animation script
 * from the enriched knowledge graph, with length adapted to concept complexity.
 */
public class NarrativeNode extends PocketFlow.Node<KnowledgeGraph, Narrative, String> {

    private static final Logger log = LoggerFactory.getLogger(NarrativeNode.class);
    private static final int TOKEN_UNIT_DIVISOR = 4;
    private static final int ASCII_TOKEN_UNITS = 1;
    private static final int NON_ASCII_TOKEN_UNITS = 2;
    private static final String TRUNCATION_MARKER = "\n[...truncated...]\n";

    private static final String NARRATIVE_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_narrative\","
            + "    \"description\": \"Return a narrative animation script for the concept progression.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"narrative\": { \"type\": \"string\", \"description\": \"Complete animation narrative script\" },"
            + "        \"scene_count\": { \"type\": \"integer\", \"description\": \"Estimated number of scenes\" },"
            + "        \"estimated_duration\": { \"type\": \"integer\", \"description\": \"Estimated total duration in seconds\" }"
            + "      },"
            + "      \"required\": [\"narrative\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private AiClient aiClient;
    private int toolCalls = 0;
    private WorkflowConfig workflowConfig;

    public NarrativeNode() {
        super(1, 0);
    }

    @Override
    public KnowledgeGraph prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        WorkflowConfig config = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        if (config != null) {
            this.workflowConfig = config;
        }
        return (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
    }

    @Override
    public Narrative exec(KnowledgeGraph graph) {
        log.info("=== Stage 1c: Narrative Composition ===");
        toolCalls = 0;

        String resolvedMode = resolveInputMode(graph);
        boolean problemMode = WorkflowConfig.INPUT_MODE_PROBLEM.equals(resolvedMode);
        List<KnowledgeNode> ordered = graph.topologicalOrder();
        List<String> conceptOrder = ordered.stream()
                .map(KnowledgeNode::getConcept)
                .collect(java.util.stream.Collectors.toList());

        log.info("  Narrative mode: {}, order: {}", resolvedMode, conceptOrder);

        int sceneCount = estimateSceneCount(ordered, problemMode);
        String context = buildTruncatedContext(graph.getTargetConcept(), ordered, problemMode, sceneCount);
        String userPrompt = buildUserPrompt(graph.getTargetConcept(), context, sceneCount, problemMode);

        int totalDuration = estimateTotalDuration(sceneCount, problemMode);
        String narrativeText;

        try {
            NarrativeDraft draft = requestNarrativeAsync(userPrompt, sceneCount, totalDuration).join();
            narrativeText = draft.narrativeText;
            sceneCount = draft.sceneCount;
            totalDuration = draft.totalDuration;
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            log.error("Narrative composition failed: {}", cause.getMessage());
            narrativeText = "Narrative generation failed: " + cause.getMessage();
        }

        if (narrativeText == null || narrativeText.isBlank()) {
            narrativeText = "Narrative generation returned empty content.";
        }

        Narrative narrative = new Narrative(
                graph.getTargetConcept(),
                narrativeText,
                conceptOrder,
                totalDuration,
                sceneCount
        );

        log.info("Narrative composed: {} words, {} scenes, ~{}s total",
                narrative.wordCount(), sceneCount, totalDuration);
        return narrative;
    }

    private CompletableFuture<NarrativeDraft> requestNarrativeAsync(String userPrompt,
                                                                    int defaultSceneCount,
                                                                    int defaultTotalDuration) {
        return aiClient.chatWithToolsRawAsync(userPrompt, PromptTemplates.NARRATIVE_SYSTEM, NARRATIVE_TOOL)
                .thenApply(rawResponse -> {
                    toolCalls++;
                    JsonNode toolData = JsonUtils.extractToolCallPayload(rawResponse);
                    if (toolData != null && toolData.has("narrative")) {
                        return new NarrativeDraft(
                                toolData.get("narrative").asText(),
                                toolData.has("scene_count")
                                        ? toolData.get("scene_count").asInt(defaultSceneCount)
                                        : defaultSceneCount,
                                toolData.has("estimated_duration")
                                        ? toolData.get("estimated_duration").asInt(defaultTotalDuration)
                                        : defaultTotalDuration
                        );
                    }

                    String text = JsonUtils.extractTextFromResponse(rawResponse);
                    if (text != null && !text.isBlank()) {
                        return new NarrativeDraft(text, defaultSceneCount, defaultTotalDuration);
                    }
                    return null;
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.debug("  Tool calling failed, falling back to plain chat: {}", cause.getMessage());
                    return null;
                })
                .thenCompose(draft -> {
                    if (draft != null && draft.hasContent()) {
                        return CompletableFuture.completedFuture(draft);
                    }
                    return aiClient.chatAsync(userPrompt, PromptTemplates.NARRATIVE_SYSTEM)
                            .thenApply(response -> {
                                toolCalls++;
                                return new NarrativeDraft(response, defaultSceneCount, defaultTotalDuration);
                            });
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
                                         int sceneCount) {
        String fullContext = buildContext(orderedNodes, problemMode);
        int fullContextTokens = estimateTokens(fullContext);
        int maxInputTokens = workflowConfig.getModelConfig().getMaxInputTokens();
        int promptOverheadTokens = estimateTokens(PromptTemplates.NARRATIVE_SYSTEM)
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

    private String buildContext(List<KnowledgeNode> orderedNodes, boolean problemMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Narrative context rules:\n");
        sb.append("- Treat visual specifications as primary staging guidance.\n");
        sb.append("- Treat mathematical enrichment as optional supporting material.\n");
        sb.append("- Use equations, definitions, interpretations, and examples only when they help the main point.\n");
        sb.append("- It is acceptable to ignore optional math details that would make scenes crowded or repetitive.\n");
        if (problemMode) {
            sb.append("- Keep the story centered on solving the stated problem, not on surveying related theory.\n");
            sb.append("- Reuse one stable diagram and add only the smallest necessary change per scene.\n");
            sb.append("- The root problem node is for the opening setup only; do not use it to preload the full solution.\n");
            sb.append("- Merge nearby steps when they belong to the same solving move.\n");
        }
        sb.append("\n");

        for (int i = 0; i < orderedNodes.size(); i++) {
            KnowledgeNode node = orderedNodes.get(i);
            sb.append(formatNodeContext(i + 1, node, problemMode));
        }

        return sb.toString();
    }

    private String formatNodeContext(int index, KnowledgeNode node, boolean problemMode) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n--- Node %d: %s (type=%s, depth=%d) ---%n",
                index, node.getConcept(), node.getNodeType(), node.getMinDepth()));

        sb.append("Core node identity:\n");
        sb.append("  concept: ").append(node.getConcept()).append("\n");
        sb.append("  node_type: ").append(node.getNodeType()).append("\n");

        boolean rootProblemNode = problemMode
                && KnowledgeNode.NODE_TYPE_PROBLEM.equalsIgnoreCase(node.getNodeType());

        if (rootProblemNode) {
            sb.append("Narrative role:\n");
            sb.append("  Use this node only to introduce the problem setup, givens, and goal.\n");
            sb.append("  Do not reveal the reflection trick, final formula, or optimality proof yet.\n");
        } else if (problemMode) {
            sb.append("Narrative role:\n");
            sb.append("  Use this node only if it advances the main solution path.\n");
            sb.append("  Prefer brief support over a standalone detour when possible.\n");
        }

        Map<String, Object> spec = node.getVisualSpec();
        if (spec != null && !spec.isEmpty()) {
            sb.append("Primary visual guidance:\n");
            List<String> visualKeys = rootProblemNode
                    ? Arrays.asList("visual_description", "layout")
                    : Arrays.asList("visual_description", "color_scheme", "layout",
                    "animation_description", "duration");
            for (String key : visualKeys) {
                if (spec.containsKey(key)) {
                    sb.append("  ").append(key).append(": ").append(spec.get(key)).append("\n");
                }
            }
        }

        if (rootProblemNode) {
            return sb.toString();
        }

        boolean hasOptionalMath = (node.getEquations() != null && !node.getEquations().isEmpty())
                || (node.getDefinitions() != null && !node.getDefinitions().isEmpty())
                || (node.getInterpretation() != null && !node.getInterpretation().isBlank())
                || (node.getExamples() != null && !node.getExamples().isEmpty());

        if (hasOptionalMath) {
            sb.append("Optional mathematical enrichment (use only if helpful):\n");
        }

        if (node.getEquations() != null && !node.getEquations().isEmpty()) {
            sb.append("  equations:\n");
            for (String eq : node.getEquations()) {
                sb.append("    ").append(eq).append("\n");
            }
        }

        if (node.getDefinitions() != null && !node.getDefinitions().isEmpty()) {
            sb.append("  definitions:\n");
            node.getDefinitions().forEach((sym, def) ->
                    sb.append("    ").append(sym).append(": ").append(def).append("\n")
            );
        }

        if (node.getInterpretation() != null && !node.getInterpretation().isBlank()) {
            sb.append("  interpretation: ").append(node.getInterpretation()).append("\n");
        }

        if (node.getExamples() != null && !node.getExamples().isEmpty()) {
            sb.append("  examples:\n");
            for (String ex : node.getExamples()) {
                sb.append("    - ").append(ex).append("\n");
            }
        }

        return sb.toString();
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
        return (estimateTokenUnits(text) + TOKEN_UNIT_DIVISOR - 1) / TOKEN_UNIT_DIVISOR;
    }

    private int estimateTokenUnits(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int units = 0;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            units += estimateTokenUnits(codePoint);
            i += Character.charCount(codePoint);
        }
        return units;
    }

    private int estimateTokenUnits(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return 0;
        }
        return codePoint <= 0x7F ? ASCII_TOKEN_UNITS : NON_ASCII_TOKEN_UNITS;
    }

    private String truncateToTokenBudget(String text, int maxTokens, String marker) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }

        int maxUnits = maxTokens * TOKEN_UNIT_DIVISOR;
        if (estimateTokenUnits(text) <= maxUnits) {
            return text;
        }

        int markerUnits = estimateTokenUnits(marker);
        int contentBudget = Math.max(0, maxUnits - markerUnits);
        String truncatedContent = truncateToUnitBudget(text, contentBudget).stripTrailing();
        if (truncatedContent.isEmpty() || markerUnits > maxUnits) {
            return truncateToUnitBudget(text, maxUnits).stripTrailing();
        }
        return truncatedContent + marker;
    }

    private String truncateToUnitBudget(String text, int maxUnits) {
        if (text == null || text.isEmpty() || maxUnits <= 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int usedUnits = 0;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            int codePointUnits = estimateTokenUnits(codePoint);
            if (usedUnits + codePointUnits > maxUnits) {
                break;
            }
            sb.appendCodePoint(codePoint);
            usedUnits += codePointUnits;
            i += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    private String resolveInputMode(KnowledgeGraph graph) {
        String configuredInputMode = workflowConfig.getInputMode();
        if (WorkflowConfig.isExplicitInputMode(configuredInputMode)) {
            return WorkflowConfig.normalizeInputMode(configuredInputMode);
        }

        KnowledgeNode root = graph.getRootNode();
        if (root != null && KnowledgeNode.NODE_TYPE_PROBLEM.equalsIgnoreCase(root.getNodeType())) {
            return WorkflowConfig.INPUT_MODE_PROBLEM;
        }
        return WorkflowConfig.INPUT_MODE_CONCEPT;
    }

    private static final class NarrativeDraft {
        private final String narrativeText;
        private final int sceneCount;
        private final int totalDuration;

        private NarrativeDraft(String narrativeText, int sceneCount, int totalDuration) {
            this.narrativeText = narrativeText;
            this.sceneCount = sceneCount;
            this.totalDuration = totalDuration;
        }

        private boolean hasContent() {
            return narrativeText != null && !narrativeText.isBlank();
        }
    }
}
