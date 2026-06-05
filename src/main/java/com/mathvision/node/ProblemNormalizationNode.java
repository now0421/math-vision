package com.mathvision.node;

import com.mathvision.config.ModelConfig;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.AiContentPart;
import com.mathvision.model.AiMessage;
import com.mathvision.model.ProblemBundle;
import com.mathvision.model.ProblemSource;
import com.mathvision.model.SourceAsset;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.ProblemNormalizationPrompts;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.SceneModeUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 0: Problem Normalization.
 * Converts a ProblemSource (text/image/mixed) into a structured ProblemBundle.
 */
public class ProblemNormalizationNode extends PocketFlow.Node<ProblemSource, ProblemBundle, String> {

    private static final Logger log = LoggerFactory.getLogger(ProblemNormalizationNode.class);

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private final AtomicInteger apiCalls = new AtomicInteger(0);

    public ProblemNormalizationNode() {
        super(1, 0);
    }

    @Override
    public ProblemSource prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        if (workflowConfig != null) {
            this.outputTarget = workflowConfig.getOutputTarget();
        }
        return (ProblemSource) ctx.get(WorkflowKeys.PROBLEM_SOURCE);
    }

    @Override
    public ProblemBundle exec(ProblemSource source) {
        log.info("=== Stage 0: Problem Normalization ===");
        apiCalls.set(0);

        String rawText = source.getRawText() != null ? source.getRawText().trim() : "";
        boolean hasAssets = source.getAssets() != null && !source.getAssets().isEmpty();
        log.info("Source type: {}, raw text length: {}, assets: {}",
                source.getSourceType(), rawText.length(),
                source.getAssets() != null ? source.getAssets().size() : 0);

        try {
            JsonNode payload;
            if (hasAssets) {
                payload = requestMultimodal(source, rawText);
            } else {
                payload = requestTextOnly(rawText);
            }

            ProblemBundle bundle = parseProblemBundle(payload, source);
            log.info("Normalization complete: id={}, mode={}, diagram.present={}",
                    bundle.getId(), bundle.getInputMode(),
                    bundle.getDiagram() != null && bundle.getDiagram().isPresent());
            return bundle;
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            throw new RuntimeException("Problem normalization failed: " + cause.getMessage(), cause);
        }
    }

    private JsonNode requestTextOnly(String rawText) {
        int maxInputTokens = workflowConfig != null
                ? workflowConfig.resolveMaxInputTokens()
                : ModelConfig.DEFAULT_MAX_INPUT_TOKENS;

        NodeConversationContext context = new NodeConversationContext(maxInputTokens);
        context.setSystemMessage(ProblemNormalizationPrompts.buildRulesPrompt());
        context.setFixedContextMessage(ProblemNormalizationPrompts.buildFixedContextPrompt(outputTarget));

        String userPrompt = ProblemNormalizationPrompts.buildUserPrompt(rawText, outputTarget);

        return AiRequestUtils.requestJsonObjectAsync(
                aiClient,
                log,
                rawText.length() > 60 ? rawText.substring(0, 60) : rawText,
                context,
                userPrompt,
                ToolSchemas.PROBLEM_BUNDLE,
                () -> apiCalls.incrementAndGet()
        ).join();
    }

    private JsonNode requestMultimodal(ProblemSource source, String rawText) {
        int imageCount = 0;
        List<AiContentPart> userParts = new ArrayList<>();

        for (SourceAsset asset : source.getAssets()) {
            if ("image".equals(asset.getType()) && asset.getPath() != null) {
                try {
                    byte[] bytes = Files.readAllBytes(Path.of(asset.getPath()));
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    String mime = asset.getMimeType() != null ? asset.getMimeType() : "image/png";
                    userParts.add(AiContentPart.image(mime, base64));
                    imageCount++;
                } catch (Exception e) {
                    log.warn("Failed to read image asset {}: {}", asset.getPath(), e.getMessage());
                }
            }
        }

        String textPrompt = ProblemNormalizationPrompts.buildMultimodalUserPrompt(
                rawText, outputTarget, imageCount);
        userParts.add(0, AiContentPart.text(textPrompt));

        List<AiMessage> messages = List.of(
                AiMessage.system(ProblemNormalizationPrompts.buildRulesPrompt()),
                AiMessage.system(ProblemNormalizationPrompts.buildFixedContextPrompt(outputTarget)),
                AiMessage.user(userParts)
        );

        apiCalls.incrementAndGet();
        JsonNode rawResponse = aiClient.chatMultimodalWithToolsRawAsync(
                messages, ToolSchemas.PROBLEM_BUNDLE).join();
        JsonNode payload = JsonUtils.extractToolCallPayload(rawResponse);
        if (payload != null && payload.isObject() && payload.size() > 0) {
            return payload;
        }

        String textContent = JsonUtils.extractBestEffortTextFromResponse(rawResponse);
        JsonNode parsed = JsonUtils.parseTreeBestEffort(textContent);
        if (parsed != null && parsed.isObject() && parsed.size() > 0) {
            return parsed;
        }

        log.warn("Multimodal normalization response did not contain a usable ProblemBundle payload");
        return rawResponse;
    }

    @Override
    public String post(Map<String, Object> ctx, ProblemSource source, ProblemBundle bundle) {
        bundle.setOutputTarget(outputTarget);
        bundle.setSource(source);
        ctx.put(WorkflowKeys.PROBLEM_BUNDLE, bundle);
        ctx.put(WorkflowKeys.PROBLEM_NORMALIZATION_API_CALLS, apiCalls.get());

        // Also set TARGET_INPUT for backward compatibility
        ctx.put(WorkflowKeys.TARGET_INPUT, bundle.getStatement());

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveProblemSource(outputDir, source);
            FileOutputService.saveProblemBundle(outputDir, bundle);
        }

        return null;
    }

    private ProblemBundle parseProblemBundle(JsonNode payload, ProblemSource source) {
        if (payload == null || payload.isEmpty()) {
            return buildFallbackBundle(source);
        }
        try {
            ProblemBundle bundle = JsonUtils.mapper().treeToValue(payload, ProblemBundle.class);
            if (bundle.getStatement() == null || bundle.getStatement().isBlank()) {
                bundle.setStatement(source.getRawText());
            }
            if (bundle.getInputMode() == null || bundle.getInputMode().isBlank()) {
                bundle.setInputMode(WorkflowConfig.INPUT_MODE_PROBLEM);
            }
            bundle.setSceneMode(SceneModeUtils.normalize(bundle.getSceneMode()));
            return bundle;
        } catch (Exception e) {
            log.warn("Failed to parse ProblemBundle from LLM response, using fallback: {}", e.getMessage());
            return buildFallbackBundle(source);
        }
    }

    private ProblemBundle buildFallbackBundle(ProblemSource source) {
        ProblemBundle bundle = new ProblemBundle();
        bundle.setId("fallback");
        bundle.setTitle(source.getRawText() != null && source.getRawText().length() > 50
                ? source.getRawText().substring(0, 50) : source.getRawText());
        bundle.setStatement(source.getRawText());
        bundle.setInputMode(WorkflowConfig.INPUT_MODE_PROBLEM);
        bundle.setSceneMode(SceneModeUtils.MODE_2D);
        return bundle;
    }
}
