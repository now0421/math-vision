package com.automanim.node;

import com.automanim.config.PipelineConfig;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.Narrative;
import com.automanim.model.PipelineKeys;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Stage 1c: Narrative Composition - composes an animation script
 * from the enriched knowledge graph, with length adapted to concept complexity.
 */
public class NarrativeNode extends PocketFlow.Node<KnowledgeGraph, Narrative, String> {

    private static final Logger log = LoggerFactory.getLogger(NarrativeNode.class);

    private static final int MAX_CONTEXT_CHARS = 18000;

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
    private String inputMode = PipelineConfig.INPUT_MODE_AUTO;

    public NarrativeNode() {
        super(1, 0);
    }

    @Override
    public KnowledgeGraph prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(PipelineKeys.AI_CLIENT);
        PipelineConfig config = (PipelineConfig) ctx.get(PipelineKeys.CONFIG);
        if (config != null) {
            this.inputMode = config.getInputMode();
        }
        return (KnowledgeGraph) ctx.get(PipelineKeys.KNOWLEDGE_GRAPH);
    }

    @Override
    public Narrative exec(KnowledgeGraph graph) {
        log.info("=== Stage 1c: Narrative Composition ===");
        toolCalls = 0;

        String resolvedMode = resolveInputMode(graph);
        List<KnowledgeNode> ordered = PipelineConfig.INPUT_MODE_PROBLEM.equals(resolvedMode)
                ? solutionOrder(graph)
                : graph.topologicalOrder();
        List<String> conceptOrder = ordered.stream()
                .map(KnowledgeNode::getConcept)
                .collect(java.util.stream.Collectors.toList());

        log.info("  Narrative mode: {}, order: {}", resolvedMode, conceptOrder);

        String context = buildTruncatedContext(ordered);
        String userPrompt = PipelineConfig.INPUT_MODE_PROBLEM.equals(resolvedMode)
                ? PromptTemplates.problemNarrativeUserPrompt(graph.getTargetConcept(), context)
                : PromptTemplates.narrativeUserPrompt(graph.getTargetConcept(), context);

        int sceneCount = Math.max(1, conceptOrder.size());
        int totalDuration = sceneCount * 10;
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
        ctx.put(PipelineKeys.NARRATIVE, narrative);

        int prevCalls = (int) ctx.getOrDefault(PipelineKeys.ENRICHMENT_TOOL_CALLS, 0);
        ctx.put(PipelineKeys.ENRICHMENT_TOOL_CALLS, prevCalls + toolCalls);

        Path outputDir = (Path) ctx.get(PipelineKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveNarrative(outputDir, narrative);
        }

        return null;
    }

    private String buildTruncatedContext(List<KnowledgeNode> orderedNodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Narrative context rules:\n");
        sb.append("- Treat visual specifications as primary staging guidance.\n");
        sb.append("- Treat mathematical enrichment as optional supporting material.\n");
        sb.append("- Use equations, definitions, interpretations, and examples only when they help the main point.\n");
        sb.append("- It is acceptable to ignore optional math details that would make scenes crowded or repetitive.\n");
        sb.append("\n");
        int remaining = MAX_CONTEXT_CHARS;

        for (int i = 0; i < orderedNodes.size(); i++) {
            KnowledgeNode node = orderedNodes.get(i);
            String nodeContext = formatNodeContext(i + 1, node);

            if (nodeContext.length() > remaining) {
                if (remaining > 100) {
                    sb.append(nodeContext, 0, remaining - 20);
                    sb.append("\n[...truncated...]\n");
                }
                break;
            }

            sb.append(nodeContext);
            remaining -= nodeContext.length();
        }

        return sb.toString();
    }

    private String formatNodeContext(int index, KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n--- Node %d: %s (type=%s, depth=%d) ---%n",
                index, node.getConcept(), node.getNodeType(), node.getMinDepth()));

        sb.append("Core node identity:\n");
        sb.append("  concept: ").append(node.getConcept()).append("\n");
        sb.append("  node_type: ").append(node.getNodeType()).append("\n");

        Map<String, Object> spec = node.getVisualSpec();
        if (spec != null && !spec.isEmpty()) {
            sb.append("Primary visual guidance:\n");
            for (String key : Arrays.asList("visual_description", "color_scheme", "layout",
                    "animation_description", "duration")) {
                if (spec.containsKey(key)) {
                    sb.append("  ").append(key).append(": ").append(spec.get(key)).append("\n");
                }
            }
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

    private List<KnowledgeNode> solutionOrder(KnowledgeGraph graph) {
        List<KnowledgeNode> topo = graph.topologicalOrder();
        KnowledgeNode root = graph.getRootNode();
        if (root == null) {
            return topo;
        }

        List<KnowledgeNode> ordered = new ArrayList<>();
        ordered.add(root);
        for (KnowledgeNode node : topo) {
            if (!root.getId().equals(node.getId())) {
                ordered.add(node);
            }
        }
        return ordered;
    }

    private String resolveInputMode(KnowledgeGraph graph) {
        if (PipelineConfig.INPUT_MODE_CONCEPT.equalsIgnoreCase(inputMode)
                || PipelineConfig.INPUT_MODE_PROBLEM.equalsIgnoreCase(inputMode)) {
            return inputMode.toLowerCase(Locale.ROOT);
        }

        KnowledgeNode root = graph.getRootNode();
        if (root != null && KnowledgeNode.NODE_TYPE_PROBLEM.equalsIgnoreCase(root.getNodeType())) {
            return PipelineConfig.INPUT_MODE_PROBLEM;
        }
        return PipelineConfig.INPUT_MODE_CONCEPT;
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
