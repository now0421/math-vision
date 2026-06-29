package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.ProblemBundle;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.node.support.NodeSupport;
import com.mathvision.prompt.EnrichmentPrompts;
import com.mathvision.prompt.SystemPrompts;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.TargetDescriptionBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 2: Mathematical Enrichment - adds equations and definitions to each
 * node in the knowledge graph.
 */
public class MathEnrichmentNode extends PocketFlow.Node<KnowledgeGraph, KnowledgeGraph, String> {

    private static final Logger log = LoggerFactory.getLogger(MathEnrichmentNode.class);
    private static final int ROLLING_CONTEXT_ROUNDS = 10;

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private final AtomicInteger toolCalls = new AtomicInteger(0);
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private NodeConversationContext conversationContext;
    private ProblemBundle problemBundle;

    public MathEnrichmentNode() {
        super(1, 0);
    }

    @Override
    public KnowledgeGraph prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        this.problemBundle = (ProblemBundle) ctx.get(WorkflowKeys.PROBLEM_BUNDLE);
        if (workflowConfig != null) {
            this.outputTarget = workflowConfig.getOutputTarget();
        }
        return (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
    }

    @Override
    public KnowledgeGraph exec(KnowledgeGraph graph) {
        log.info("=== Stage 2: Mathematical Enrichment (output_target={}, order=teaching_order, rolling_rounds={}) ===",
                outputTarget, ROLLING_CONTEXT_ROUNDS);
        toolCalls.set(0);

        int maxInputTokens = TargetDescriptionBuilder.resolvePromptInputBudgetTokens(workflowConfig);
        this.conversationContext = new NodeConversationContext(maxInputTokens, ROLLING_CONTEXT_ROUNDS);
        String solutionChain = TargetDescriptionBuilder.buildSolutionChain(graph, null);
        this.conversationContext.setSystemMessage(EnrichmentPrompts.buildRulesPrompt());
        this.conversationContext.setFixedContextMessage(EnrichmentPrompts.buildFixedContextPrompt(
                problemBundle,
                TargetDescriptionBuilder.build(problemBundle, graph, null),
                solutionChain));

        try {
            return enrichGraph(graph);
        } finally {
            this.conversationContext = null;
        }
    }

    @Override
    public String post(Map<String, Object> ctx, KnowledgeGraph prepRes, KnowledgeGraph graph) {
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);
        int prevCalls = (int) ctx.getOrDefault(WorkflowKeys.ENRICHMENT_TOOL_CALLS, 0);
        ctx.put(WorkflowKeys.ENRICHMENT_TOOL_CALLS, prevCalls + toolCalls.get());
        return null;
    }

    private KnowledgeGraph enrichGraph(KnowledgeGraph graph) {
        if (graph == null) {
            return null;
        }

        List<KnowledgeNode> teachingOrder = graph.teachingOrderNodes();
        int enrichedCount = 0;
        int skippedCount = 0;

        for (int index = 0; index < teachingOrder.size(); index++) {
            KnowledgeNode node = teachingOrder.get(index);
            if (!shouldEnrichNode(node)) {
                skippedCount++;
                continue;
            }
            log.info("  Enriching step {} of {}: {}", index + 1, teachingOrder.size(), node.getStep());
            if (enrichNode(node)) {
                enrichedCount++;
            } else {
                skippedCount++;
            }
        }

        log.info("Mathematical enrichment complete: {} API calls, {} steps enriched, {} skipped",
                toolCalls.get(), enrichedCount, skippedCount);
        return graph;
    }

    private boolean enrichNode(KnowledgeNode node) {
        if (node.isEnriched()) {
            log.debug("  Skipping already-enriched node: {}", node.getStep());
            return false;
        }

        String userPrompt = buildCurrentStepPrompt(node);
        try {
            EnrichmentRequestResult result = fetchMathContent(node, userPrompt);
            if (result != null && result.payload != null) {
                applyContent(node, result.payload);
            }
            appendConversationTurn(result);
            return true;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("  Math enrichment failed for '{}': {}", node.getStep(), cause.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("  Math enrichment failed for '{}': {}", node.getStep(), e.getMessage());
            return false;
        }
    }

    private EnrichmentRequestResult fetchMathContent(KnowledgeNode node, String userPrompt) {
        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                log,
                node.getStep(),
                NodeSupport.buildAiRequest(conversationContext, userPrompt, ToolSchemas.MATH_ENRICHMENT),
                AiRequestUtils.JsonRequestOptions.of(() -> toolCalls.incrementAndGet())
        ).join();

        return new EnrichmentRequestResult(
                userPrompt,
                result != null ? result.getPayload() : null,
                result != null ? result.getAssistantTranscript() : ""
        );
    }

    private boolean shouldEnrichNode(KnowledgeNode node) {
        return node != null;
    }

    private void appendConversationTurn(EnrichmentRequestResult result) {
        if (result != null && !result.assistantTranscript.isBlank()) {
            conversationContext.appendTurn(result.userPrompt, result.assistantTranscript);
        }
    }

    private String buildCurrentStepPrompt(KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("[CURRENT_STEP]\n");
        sb.append("- step: ").append(node.getStep()).append("\n");
        sb.append("- node_role: ").append(
                node.getNodeType() != null ? node.getNodeType() : "concept").append("\n");

        sb.append("[RESPONSE_SCOPE]\n");
        sb.append("Return only the mathematical content needed for this step.\n");
        sb.append("Do not restate the whole solution.\n");
        sb.append("Keep it concise and presentation-oriented.");
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    private void applyContent(KnowledgeNode node, JsonNode data) {
        if (node == null || data == null || data.isNull()) {
            return;
        }

        if (data.has("step")) {
            String correctedStep = readOptionalText(data.get("step"));
            if (correctedStep != null && !correctedStep.isBlank()) {
                node.setStep(correctedStep);
            }
        }
        if (data.has("reason")) {
            String correctedReason = readOptionalText(data.get("reason"));
            if (correctedReason != null && !correctedReason.isBlank()) {
                node.setReason(correctedReason);
            }
        }
        if (data.has("equations")) {
            node.setEquations(readTrimmedStringList(data.get("equations")));
        }
        if (data.has("definitions")) {
            node.setDefinitions(readTrimmedStringMap(data.get("definitions")));
        }
        if (data.has("interpretation")) {
            node.setInterpretation(readOptionalText(data.get("interpretation")));
        }
        if (data.has("examples")) {
            node.setExamples(readTrimmedStringList(data.get("examples")));
        }
    }

    private List<String> readTrimmedStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = readOptionalText(item);
                if (text != null) {
                    values.add(text);
                }
            }
            return values;
        }

        String singleValue = readOptionalText(node);
        if (singleValue != null) {
            values.add(singleValue);
        }
        return values;
    }

    private Map<String, String> readTrimmedStringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node == null || node.isNull() || !node.isObject()) {
            return values;
        }

        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey() == null ? null : entry.getKey().trim();
            String value = readOptionalText(entry.getValue());
            if (key != null && !key.isEmpty() && value != null) {
                values.put(key, value);
            }
        });
        return values;
    }

    private String readOptionalText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        String text = node.asText();
        if (text == null) {
            return null;
        }

        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static final class EnrichmentRequestResult {
        private final String userPrompt;
        private final JsonNode payload;
        private final String assistantTranscript;

        private EnrichmentRequestResult(String userPrompt,
                                        JsonNode payload,
                                        String assistantTranscript) {
            this.userPrompt = userPrompt;
            this.payload = payload;
            this.assistantTranscript = assistantTranscript == null ? "" : assistantTranscript;
        }
    }
}
