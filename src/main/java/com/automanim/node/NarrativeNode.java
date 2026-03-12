package com.automanim.node;

import com.automanim.model.KnowledgeNode;
import com.automanim.model.Narrative;
import com.automanim.model.PipelineKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.JsonUtils;
import com.automanim.util.PromptTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Stage 1c: Narrative Composition — composes an animation script
 * from the enriched knowledge tree, with length adapted to concept complexity.
 */
public class NarrativeNode extends PocketFlow.Node<KnowledgeNode, Narrative, String> {

    private static final Logger log = LoggerFactory.getLogger(NarrativeNode.class);

    private static final int MAX_CONTEXT_CHARS = 18000;

    private static final String NARRATIVE_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_narrative\","
            + "    \"description\": \"Return the narrative animation script for the concept progression.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"narrative\": { \"type\": \"string\", \"description\": \"The full narrative animation script, length adapted to concept complexity\" },"
            + "        \"scene_count\": { \"type\": \"integer\", \"description\": \"Number of scenes\" },"
            + "        \"estimated_duration\": { \"type\": \"integer\", \"description\": \"Total duration in seconds\" }"
            + "      },"
            + "      \"required\": [\"narrative\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private AiClient aiClient;
    private int toolCalls = 0;

    public NarrativeNode() {
        super(1, 0);
    }

    @Override
    public KnowledgeNode prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(PipelineKeys.AI_CLIENT);
        return (KnowledgeNode) ctx.get(PipelineKeys.KNOWLEDGE_TREE);
    }

    @Override
    public Narrative exec(KnowledgeNode tree) {
        log.info("=== Stage 1c: Narrative Composition ===");
        toolCalls = 0;

        List<KnowledgeNode> ordered = topologicalOrder(tree);
        List<String> conceptOrder = ordered.stream()
                .map(KnowledgeNode::getConcept)
                .collect(java.util.stream.Collectors.toList());

        log.info("  Concept order: {}", conceptOrder);

        String context = buildTruncatedContext(ordered);
        String userPrompt = PromptTemplates.narrativeUserPrompt(tree.getConcept(), context);

        String narrativeText = null;
        int sceneCount = Math.max(1, conceptOrder.size());
        int totalDuration = sceneCount * 30;

        try {
            try {
                JsonNode rawResponse = aiClient.chatWithToolsRaw(
                        userPrompt, PromptTemplates.NARRATIVE_SYSTEM, NARRATIVE_TOOL);
                toolCalls++;

                JsonNode toolData = JsonUtils.extractToolCallPayload(rawResponse);
                if (toolData != null && toolData.has("narrative")) {
                    narrativeText = toolData.get("narrative").asText();
                    if (toolData.has("scene_count")) {
                        sceneCount = toolData.get("scene_count").asInt(sceneCount);
                    }
                    if (toolData.has("estimated_duration")) {
                        totalDuration = toolData.get("estimated_duration").asInt(totalDuration);
                    }
                } else {
                    narrativeText = JsonUtils.extractTextFromResponse(rawResponse);
                }
            } catch (Exception e) {
                log.debug("  Tool calling failed, falling back to plain chat: {}", e.getMessage());
                narrativeText = aiClient.chat(userPrompt, PromptTemplates.NARRATIVE_SYSTEM);
                toolCalls++;
            }
        } catch (Exception e) {
            log.error("Narrative composition failed: {}", e.getMessage());
            narrativeText = "Narrative generation failed: " + e.getMessage();
        }

        if (narrativeText == null || narrativeText.isBlank()) {
            narrativeText = "Narrative generation returned empty content.";
        }

        Narrative narrative = new Narrative(
                tree.getConcept(),
                narrativeText,
                conceptOrder,
                totalDuration,
                sceneCount
        );

        log.info("Narrative composed: {} words, {} scenes, ~{}s total",
                narrative.wordCount(), sceneCount, totalDuration);
        return narrative;
    }

    @Override
    public String post(Map<String, Object> ctx, KnowledgeNode prepRes, Narrative narrative) {
        ctx.put(PipelineKeys.NARRATIVE, narrative);

        int prevCalls = (int) ctx.getOrDefault(PipelineKeys.ENRICHMENT_TOOL_CALLS, 0);
        ctx.put(PipelineKeys.ENRICHMENT_TOOL_CALLS, prevCalls + toolCalls);

        Path outputDir = (Path) ctx.get(PipelineKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveNarrative(outputDir, narrative);
        }

        return null;
    }

    private List<KnowledgeNode> topologicalOrder(KnowledgeNode root) {
        List<KnowledgeNode> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectPostOrder(root, result, seen);
        return result;
    }

    private void collectPostOrder(KnowledgeNode node, List<KnowledgeNode> result, Set<String> seen) {
        String key = node.getConcept().toLowerCase();
        if (seen.contains(key)) return;
        seen.add(key);

        for (KnowledgeNode prereq : node.getPrerequisites()) {
            collectPostOrder(prereq, result, seen);
        }
        result.add(node);
    }

    private String buildTruncatedContext(List<KnowledgeNode> orderedNodes) {
        StringBuilder sb = new StringBuilder();
        int remaining = MAX_CONTEXT_CHARS;

        for (int i = 0; i < orderedNodes.size(); i++) {
            KnowledgeNode node = orderedNodes.get(i);
            String nodeContext = formatNodeContext(i + 1, node);

            if (nodeContext.length() > remaining) {
                // Truncate this node's content to fit budget
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
        sb.append(String.format("\n--- Concept %d: %s (depth=%d) ---\n",
                index, node.getConcept(), node.getDepth()));

        if (node.getEquations() != null && !node.getEquations().isEmpty()) {
            sb.append("Equations:\n");
            for (String eq : node.getEquations()) {
                sb.append("  ").append(eq).append("\n");
            }
        }

        if (node.getDefinitions() != null && !node.getDefinitions().isEmpty()) {
            sb.append("Definitions:\n");
            node.getDefinitions().forEach((sym, def) ->
                    sb.append("  ").append(sym).append(": ").append(def).append("\n")
            );
        }

        if (node.getInterpretation() != null && !node.getInterpretation().isBlank()) {
            sb.append("Interpretation: ").append(node.getInterpretation()).append("\n");
        }

        if (node.getExamples() != null && !node.getExamples().isEmpty()) {
            sb.append("Examples:\n");
            for (String ex : node.getExamples()) {
                sb.append("  - ").append(ex).append("\n");
            }
        }

        Map<String, Object> spec = node.getVisualSpec();
        if (spec != null && !spec.isEmpty()) {
            sb.append("Visual spec:\n");
            for (String key : Arrays.asList("visual_description", "color_scheme", "layout",
                    "animation_description", "duration")) {
                if (spec.containsKey(key)) {
                    sb.append("  ").append(key).append(": ").append(spec.get(key)).append("\n");
                }
            }
        }

        return sb.toString();
    }
}
