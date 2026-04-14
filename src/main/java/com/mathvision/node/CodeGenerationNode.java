package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeFixRequest;
import com.mathvision.model.CodeFixResult;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.CodeResult;
import com.mathvision.model.Narrative;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.node.support.FixRetryState;
import com.mathvision.node.support.NodeSupport;
import com.mathvision.prompt.CodeGenerationPrompts;
import com.mathvision.prompt.NarrativePrompts;
import com.mathvision.prompt.StoryboardJsonBuilder;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.GeoGebraCodeUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.TimeUtils;
import com.mathvision.util.ManimCodeUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.TargetDescriptionBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Stage 2: Code Generation - generates backend-specific code
 * from the narrative storyboard prompt.
 */
public class CodeGenerationNode extends PocketFlow.Node<CodeGenerationNode.CodeGenerationInput, CodeResult, String> {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationNode.class);
    private static final int MAX_VALIDATION_FIX_ATTEMPTS = 1;

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private NodeConversationContext conversationContext;
    private int toolCalls = 0;

    public CodeGenerationNode() {
        super(2, 2000);
    }

    public static class CodeGenerationInput {
        private final Narrative narrative;
        private final CodeResult existingCodeResult;
        private final CodeFixResult previousFixResult;
        private final FixRetryState fixState;

        public CodeGenerationInput(Narrative narrative,
                                   CodeResult existingCodeResult,
                                   CodeFixResult previousFixResult,
                                   FixRetryState fixState) {
            this.narrative = narrative;
            this.existingCodeResult = existingCodeResult;
            this.previousFixResult = previousFixResult;
            this.fixState = fixState;
        }

        public Narrative narrative() { return narrative; }
        public CodeResult existingCodeResult() { return existingCodeResult; }
        public CodeFixResult previousFixResult() { return previousFixResult; }
        public FixRetryState fixState() { return fixState; }
    }

    private static final class CodeDraft {
        private final String generatedCode;
        private final String artifactName;

        private CodeDraft(String generatedCode, String artifactName) {
            this.generatedCode = generatedCode;
            this.artifactName = artifactName;
        }
    }

    @Override
    public CodeGenerationInput prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);

        FixRetryState fixState = (FixRetryState) ctx.get(WorkflowKeys.CODE_GENERATION_FIX_STATE);
        if (fixState == null) {
            fixState = new FixRetryState();
            ctx.put(WorkflowKeys.CODE_GENERATION_FIX_STATE, fixState);
        }

        CodeFixResult previousFixResult = NodeSupport.consumeFixResult(ctx, CodeFixSource.GENERATION_VALIDATION);
        if (previousFixResult != null) {
            fixState.addFixToolCalls(previousFixResult.getToolCalls());
        }

        return new CodeGenerationInput(
                (Narrative) ctx.get(WorkflowKeys.NARRATIVE),
                (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT),
                previousFixResult,
                fixState
        );
    }

    @Override
    public CodeResult exec(CodeGenerationInput input) {
        Instant start = Instant.now();
        log.info("=== Stage 2: Code Generation ===");
        toolCalls = 0;

        if (this.conversationContext == null) {
            int maxInputTokens = TargetDescriptionBuilder.resolveMaxInputTokens(workflowConfig);
            this.conversationContext = new NodeConversationContext(maxInputTokens);
        }

        Narrative narrative = input.narrative();
        FixRetryState fixState = input.fixState();
        fixState.setRequestFix(false);

        if (narrative == null && input.existingCodeResult() == null) {
            log.warn("Narrative is empty, cannot generate code");
            CodeResult emptyResult = new CodeResult("", "", "Empty narrative", "", "");
            emptyResult.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
            return emptyResult;
        }

        String targetConcept = narrative != null ? narrative.getTargetConcept()
                : input.existingCodeResult().getTargetConcept();
        String targetDescription = narrative != null ? narrative.getTargetDescription()
                : input.existingCodeResult().getTargetDescription();
        this.conversationContext.setSystemMessage(
                CodeGenerationPrompts.systemPrompt(
                        targetConcept, targetDescription, NodeSupport.resolveOutputTarget(workflowConfig)));

        String generatedCode;
        String artifactName = defaultArtifactName();
        if (input.previousFixResult() != null) {
            log.info("  Re-validating code returned from shared CodeFixNode");
            generatedCode = extractRetriedCode(input);
        } else {
            String userPrompt = buildGenerationPrompt(narrative, artifactName);
            if (userPrompt.isBlank()) {
                log.warn("Narrative prompt is empty, cannot generate code");
                CodeResult emptyResult = new CodeResult(
                        "",
                        "",
                        "Empty narrative",
                        targetConcept,
                        targetDescription
                );
                emptyResult.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
                return emptyResult;
            }

            try {
                CodeDraft draft = requestCodeAsync(userPrompt, artifactName).join();
                generatedCode = normalizeGeneratedCode(draft.generatedCode);
                artifactName = draft.artifactName;
            } catch (CompletionException e) {
                Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
                log.error("  Code generation failed: {}", cause.getMessage());
                generatedCode = "";
            }
        }

        generatedCode = normalizeGeneratedCode(generatedCode);
        if (NodeSupport.isGeoGebraTarget(workflowConfig)) {
            generatedCode = GeoGebraCodeUtils.enrichWithSceneButtons(
                    generatedCode,
                    narrative != null ? narrative.getStoryboard() : null);
        }
        List<String> violations = validateGeneratedCode(generatedCode);

        if (input.previousFixResult() != null) {
            if (!violations.isEmpty()) {
                if (fixState.getOriginalGeneratedCodeBeforeFix() != null
                        && violations.size() >= fixState.getOriginalIssueCount()) {
                    log.warn("  Shared code fix did not improve validation violations ({} -> {}),"
                                    + " keeping original generated code",
                            fixState.getOriginalIssueCount(), violations.size());
                    generatedCode = fixState.getOriginalGeneratedCodeBeforeFix();
                    violations = validateGeneratedCode(generatedCode);
                } else {
                    log.info("  Shared code fix reduced violations from {} to {}",
                            fixState.getOriginalIssueCount(), violations.size());
                }
            }
            fixState.clearPending();
        } else if (shouldRouteValidationFix()
                && !violations.isEmpty()
                && fixState.getAttempts() < MAX_VALIDATION_FIX_ATTEMPTS) {
            log.warn("  Code validation found {} violations, routing to shared CodeFixNode",
                    violations.size());
            fixState.recordFixRequest(generatedCode, violations);
        }

        CodeResult result = new CodeResult(
                generatedCode,
                artifactName,
                buildResultDescription(targetConcept),
                targetConcept,
                targetDescription
        );
        result.setOutputTarget(NodeSupport.resolveOutputTarget(workflowConfig));
        result.setArtifactFormat(resolveArtifactFormat());
        result.setToolCalls(toolCalls + fixState.totalToolCalls());
        result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));

        log.info("Code generated: {} lines, artifact={}", result.codeLineCount(), artifactName);
        return result;
    }

    private CompletableFuture<CodeDraft> requestCodeAsync(String userPrompt, String expectedArtifactName) {
        return AiRequestUtils.requestJsonObjectAsync(
                        aiClient,
                        log,
                        expectedArtifactName,
                        conversationContext,
                        userPrompt,
                        resolveToolSchema(),
                        () -> toolCalls++,
                        this::parseCodeTextResponse,
                        this::hasCodePayload
                )
                .thenApply(payload -> toCodeDraft(payload, expectedArtifactName));
    }

    @Override
    public String post(Map<String, Object> ctx, CodeGenerationInput input, CodeResult codeResult) {
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveCodeResult(outputDir, codeResult);
        }

        if (input.fixState().isRequestFix()) {
            input.fixState().addCarryoverToolCalls(toolCalls);
            ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildValidationFixRequest(input.narrative(), codeResult, input.fixState()));
            return WorkflowActions.FIX_CODE;
        }

        input.fixState().reset();
        return null;
    }

    private String buildGenerationPrompt(Narrative narrative, String expectedSceneName) {
        String basePrompt;
        if (narrative.hasStoryboard()) {
            basePrompt = NarrativePrompts.storyboardCodegenPrompt(
                    narrative.getTargetConcept(),
                    narrative.getStoryboard(),
                    NodeSupport.resolveOutputTarget(workflowConfig));
        } else {
            basePrompt = narrative.getVerbosePrompt();
        }

        if (basePrompt == null || basePrompt.isBlank()) {
            return "";
        }

        if (workflowConfig != null && workflowConfig.isGeoGebraTarget()) {
            return basePrompt
                    + "\n\nFigure name: " + expectedSceneName
                    + "\nUse this as the primary GeoGebra figure name when naming the construction.";
        }

        return basePrompt
                + "\n\nScene class name: " + expectedSceneName
                + "\nUse this exact scene class name verbatim in the generated code.";
    }

    private CodeFixRequest buildValidationFixRequest(Narrative narrative,
                                                     CodeResult codeResult,
                                                     FixRetryState fixState) {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.GENERATION_VALIDATION);
        request.setReturnAction(WorkflowActions.RETRY_CODE_GENERATION);
        request.setGeneratedCode(codeResult.getGeneratedCode());
        request.setErrorReason(String.join("\n", fixState.getCurrentIssues()));
        request.setTargetConcept(codeResult.getTargetConcept());
        request.setTargetDescription(codeResult.getTargetDescription());
        request.setSceneName(codeResult.getSceneName());
        request.setExpectedSceneName(resolveExpectedArtifactName(codeResult));
        request.setStoryboardJson(narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(narrative.getStoryboard())
                : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON);
        return request;
    }

    private String extractRetriedCode(CodeGenerationInput input) {
        if (input.existingCodeResult() != null
                && input.existingCodeResult().getGeneratedCode() != null
                && !input.existingCodeResult().getGeneratedCode().isBlank()) {
            return input.existingCodeResult().getGeneratedCode();
        }
        if (input.previousFixResult() != null) {
            return input.previousFixResult().getFixedGeneratedCode();
        }
        return "";
    }

    private JsonNode parseCodeTextResponse(String response) {
        String generatedCode = extractCodeFromText(response);
        if (generatedCode == null || generatedCode.isBlank()) {
            return null;
        }

        return JsonUtils.mapper().createObjectNode()
                .put(NodeSupport.isGeoGebraTarget(workflowConfig) ? "geogebraCode" : "manimCode", generatedCode);
    }

    private boolean hasCodePayload(JsonNode payload) {
        if (payload == null) return false;
        if (payload.has("manimCode") && !payload.get("manimCode").asText("").isBlank()) return true;
        if (payload.has("geogebraCode") && !payload.get("geogebraCode").asText("").isBlank()) return true;
        return false;
    }

    private CodeDraft toCodeDraft(JsonNode payload, String expectedArtifactName) {
        String generatedCode = null;
        String artifactName = expectedArtifactName;

        if (payload != null) {
            if (payload.has("manimCode")) {
                generatedCode = payload.get("manimCode").asText();
            } else if (payload.has("geogebraCode")) {
                generatedCode = payload.get("geogebraCode").asText();
            }
        }
        if (payload != null && payload.has("scene_name")) {
            artifactName = payload.get("scene_name").asText(expectedArtifactName);
        } else if (payload != null && payload.has("figure_name")) {
            artifactName = payload.get("figure_name").asText(expectedArtifactName);
        }

        return new CodeDraft(generatedCode, artifactName);
    }

    private String normalizeGeneratedCode(String generatedCode) {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? GeoGebraCodeUtils.extractCode(generatedCode)
                : ManimCodeUtils.enforceMainSceneName(generatedCode);
    }

    private List<String> validateGeneratedCode(String generatedCode) {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? GeoGebraCodeUtils.validateFull(generatedCode)
                : ManimCodeUtils.validateFull(generatedCode);
    }

    private boolean shouldRouteValidationFix() {
        return workflowConfig == null
                || workflowConfig.isManimTarget()
                || workflowConfig.isGeoGebraTarget();
    }

    private String resolveToolSchema() {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? ToolSchemas.GEOGEBRA_CODE
                : ToolSchemas.MANIM_CODE;
    }

    private String extractCodeFromText(String text) {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? GeoGebraCodeUtils.extractCode(text)
                : ManimCodeUtils.extractCode(text);
    }

    private String defaultArtifactName() {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? GeoGebraCodeUtils.EXPECTED_FIGURE_NAME
                : ManimCodeUtils.EXPECTED_SCENE_NAME;
    }

    private String resolveArtifactFormat() {
        return NodeSupport.isGeoGebraTarget(workflowConfig) ? "commands" : "python";
    }

    private String buildResultDescription(String targetConcept) {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? "GeoGebra construction for " + targetConcept
                : "Manim animation for " + targetConcept;
    }

    private String resolveExpectedArtifactName(CodeResult codeResult) {
        if (codeResult != null && codeResult.isGeoGebraTarget()) {
            if (codeResult.getSceneName() != null && !codeResult.getSceneName().isBlank()) {
                return codeResult.getSceneName();
            }
            return GeoGebraCodeUtils.EXPECTED_FIGURE_NAME;
        }
        return ManimCodeUtils.EXPECTED_SCENE_NAME;
    }

}
