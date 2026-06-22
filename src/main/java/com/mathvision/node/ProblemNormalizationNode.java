package com.mathvision.node;

import com.mathvision.config.ModelConfig;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.AiContentPart;
import com.mathvision.model.AiMessage;
import com.mathvision.model.AiRequest;
import com.mathvision.model.ProblemBundle;
import com.mathvision.model.ProblemSource;
import com.mathvision.model.SourceAsset;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.node.support.NodeSupport;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
            bundle = reviewProblemBundle(source, rawText, bundle, hasAssets);
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
                ? workflowConfig.resolvePromptInputBudgetTokens()
                : ModelConfig.DEFAULT_MAX_INPUT_TOKENS;

        NodeConversationContext context = new NodeConversationContext(maxInputTokens);
        context.setSystemMessage(ProblemNormalizationPrompts.buildRulesPrompt());
        context.setFixedContextMessage(ProblemNormalizationPrompts.buildFixedContextPrompt(outputTarget));

        String userPrompt = ProblemNormalizationPrompts.buildUserPrompt(rawText, outputTarget);

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                log,
                rawText.length() > 60 ? rawText.substring(0, 60) : rawText,
                NodeSupport.buildAiRequest(context, userPrompt, ToolSchemas.PROBLEM_BUNDLE),
                AiRequestUtils.JsonRequestOptions.of(() -> apiCalls.incrementAndGet())
        ).join();
        return requireProblemBundlePayload(result, "ProblemBundle text normalization");
    }

    private JsonNode requestMultimodal(ProblemSource source, String rawText) {
        ImageAttachmentPayload imagePayload = buildImageAttachmentPayload(source);
        List<AiContentPart> userParts = new ArrayList<>();
        String textPrompt = ProblemNormalizationPrompts.buildMultimodalUserPrompt(
                rawText, outputTarget, imagePayload.getImageCount());
        userParts.add(AiContentPart.text(textPrompt));
        userParts.addAll(imagePayload.getParts());

        List<AiMessage> messages = List.of(
                AiMessage.system(ProblemNormalizationPrompts.buildRulesPrompt()),
                AiMessage.system(ProblemNormalizationPrompts.buildFixedContextPrompt(outputTarget)),
                AiMessage.user(userParts)
        );

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                log,
                "ProblemBundle multimodal normalization",
                AiRequest.withTools(messages, ToolSchemas.PROBLEM_BUNDLE),
                AiRequestUtils.JsonRequestOptions.of(() -> apiCalls.incrementAndGet())
        ).join();
        return requireProblemBundlePayload(result, "ProblemBundle multimodal normalization");
    }

    private ProblemBundle reviewProblemBundle(ProblemSource source,
                                              String rawText,
                                              ProblemBundle generatedBundle,
                                              boolean hasAssets) {
        if (generatedBundle == null) {
            throw new IllegalStateException("ProblemBundle normalization produced no bundle to review");
        }

        log.info("Reviewing normalized ProblemBundle against original source");
        JsonNode payload = hasAssets
                ? requestMultimodalReview(source, rawText, generatedBundle)
                : requestTextOnlyReview(rawText, generatedBundle);
        ProblemBundle reviewedBundle = parseProblemBundle(payload, source);
        log.info("ProblemBundle review complete: id={}, mode={}, diagram.present={}",
                reviewedBundle.getId(), reviewedBundle.getInputMode(),
                reviewedBundle.getDiagram() != null && reviewedBundle.getDiagram().isPresent());
        return reviewedBundle;
    }

    private JsonNode requestTextOnlyReview(String rawText, ProblemBundle generatedBundle) {
        int maxInputTokens = workflowConfig != null
                ? workflowConfig.resolvePromptInputBudgetTokens()
                : ModelConfig.DEFAULT_MAX_INPUT_TOKENS;

        NodeConversationContext context = new NodeConversationContext(maxInputTokens);
        context.setSystemMessage(ProblemNormalizationPrompts.buildReviewRulesPrompt());
        context.setFixedContextMessage(ProblemNormalizationPrompts.buildReviewFixedContextPrompt(outputTarget));

        String userPrompt = ProblemNormalizationPrompts.buildReviewUserPrompt(
                rawText,
                outputTarget,
                0,
                JsonUtils.toPrettyJson(generatedBundle));

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                log,
                "ProblemBundle review",
                NodeSupport.buildAiRequest(context, userPrompt, ToolSchemas.PROBLEM_BUNDLE),
                AiRequestUtils.JsonRequestOptions.of(() -> apiCalls.incrementAndGet())
        ).join();
        return requireProblemBundlePayload(result, "ProblemBundle review");
    }

    private JsonNode requestMultimodalReview(ProblemSource source, String rawText, ProblemBundle generatedBundle) {
        ImageAttachmentPayload imagePayload = buildImageAttachmentPayload(source);
        String textPrompt = ProblemNormalizationPrompts.buildReviewUserPrompt(
                rawText,
                outputTarget,
                imagePayload.getImageCount(),
                JsonUtils.toPrettyJson(generatedBundle));

        List<AiContentPart> userParts = new ArrayList<>();
        userParts.add(AiContentPart.text(textPrompt));
        userParts.addAll(imagePayload.getParts());

        List<AiMessage> messages = List.of(
                AiMessage.system(ProblemNormalizationPrompts.buildReviewRulesPrompt()),
                AiMessage.system(ProblemNormalizationPrompts.buildReviewFixedContextPrompt(outputTarget)),
                AiMessage.user(userParts)
        );

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                log,
                "ProblemBundle multimodal review",
                AiRequest.withTools(messages, ToolSchemas.PROBLEM_BUNDLE),
                AiRequestUtils.JsonRequestOptions.of(() -> apiCalls.incrementAndGet())
        ).join();
        return requireProblemBundlePayload(result, "ProblemBundle multimodal review");
    }

    private ImageAttachmentPayload buildImageAttachmentPayload(ProblemSource source) {
        List<AiContentPart> parts = new ArrayList<>();
        if (source == null || source.getAssets() == null) {
            return new ImageAttachmentPayload(parts, 0);
        }

        int imageCount = 0;
        for (SourceAsset asset : source.getAssets()) {
            if ("image".equals(asset.getType()) && asset.getPath() != null) {
                try {
                    byte[] bytes = Files.readAllBytes(Path.of(asset.getPath()));
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    String mime = asset.getMimeType() != null ? asset.getMimeType() : "image/png";
                    parts.add(AiContentPart.image(mime, base64));
                    imageCount++;
                } catch (Exception e) {
                    log.warn("Failed to read image asset {}: {}", asset.getPath(), e.getMessage());
                }
            }
        }
        return new ImageAttachmentPayload(parts, imageCount);
    }

    @Override
    public String post(Map<String, Object> ctx, ProblemSource source, ProblemBundle bundle) {
        bundle.setOutputTarget(outputTarget);
        bundle.setSource(source);
        ctx.put(WorkflowKeys.PROBLEM_BUNDLE, bundle);
        ctx.put(WorkflowKeys.PROBLEM_NORMALIZATION_API_CALLS, apiCalls.get());

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveProblemSource(outputDir, source);
            FileOutputService.saveProblemBundle(outputDir, bundle);
        }

        return null;
    }

    private ProblemBundle parseProblemBundle(JsonNode payload, ProblemSource source) {
        if (payload == null || payload.isEmpty()) {
            throw new IllegalStateException("ProblemBundle LLM response contained no usable payload");
        }
        if (!looksLikeProblemBundlePayload(payload)) {
            throw new IllegalStateException("ProblemBundle LLM response did not look like a ProblemBundle");
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
            migrateLegacyDiagramPayload(bundle, payload);
            return bundle;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse ProblemBundle from LLM response: " + e.getMessage(), e);
        }
    }

    private boolean looksLikeProblemBundlePayload(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return false;
        }
        return payload.has("statement")
                || payload.has("diagram")
                || payload.has("input_mode")
                || payload.has("scene_mode");
    }

    private JsonNode requireProblemBundlePayload(AiRequestUtils.JsonObjectResult result, String phase) {
        if (result != null && result.getPayload() != null) {
            return result.getPayload();
        }
        String reason = result != null ? result.getFailureReason() : "AI response was null";
        throw new IllegalStateException(phase + " did not return usable ProblemBundle JSON: " + reason);
    }

    private void migrateLegacyDiagramPayload(ProblemBundle bundle, JsonNode payload) {
        if (bundle == null || bundle.getDiagram() == null || payload == null) {
            return;
        }
        JsonNode diagramPayload = payload.path("diagram");
        if (!diagramPayload.isObject()) {
            return;
        }

        var diagram = bundle.getDiagram();
        if (!diagram.isPresent()) {
            diagram.setSourceObserved(false);
            return;
        }
        if (!diagram.isSourceObserved()) {
            diagram.setSourceObserved(true);
        }

        if (!diagram.hasDescriptionPayload()) {
            ObjectNode description = JsonUtils.mapper().createObjectNode();
            String legacyDescription = diagramPayload.path("description").asText("");
            if (!legacyDescription.isBlank()) {
                description.put("overall_shape", legacyDescription);
            }
            if (diagramPayload.has("objects")) {
                description.set("legacy_objects", diagramPayload.get("objects"));
            }
            if (diagramPayload.has("constraints")) {
                description.set("legacy_constraints", diagramPayload.get("constraints"));
            }
            if (description.size() > 0) {
                diagram.setDiagramDescription(description);
            }

            ArrayNode notes = JsonUtils.mapper().createArrayNode();
            JsonNode legacyNotes = diagramPayload.get("construction_notes");
            if (legacyNotes != null && legacyNotes.isArray()) {
                for (JsonNode note : legacyNotes) {
                    if (note != null && !note.asText("").isBlank()) {
                        notes.add(note.asText());
                    }
                }
            }
            if (notes.size() > 0) {
                List<String> normalizationNotes = new ArrayList<>();
                for (JsonNode note : notes) {
                    normalizationNotes.add(note.asText());
                }
                diagram.setNormalizationNotes(normalizationNotes);
            }
        }
    }

    private static final class ImageAttachmentPayload {
        private final List<AiContentPart> parts;
        private final int imageCount;

        private ImageAttachmentPayload(List<AiContentPart> parts, int imageCount) {
            this.parts = parts;
            this.imageCount = imageCount;
        }

        private List<AiContentPart> getParts() {
            return parts;
        }

        private int getImageCount() {
            return imageCount;
        }
    }
}
