package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeFixRequest;
import com.automanim.model.CodeFixResult;
import com.automanim.model.CodeFixSource;
import com.automanim.model.CodeResult;
import com.automanim.model.Narrative;
import com.automanim.model.WorkflowActions;
import com.automanim.model.WorkflowKeys;
import com.automanim.node.support.FixRetryState;
import com.automanim.prompt.CodeGenerationPrompts;
import com.automanim.prompt.NarrativePrompts;
import com.automanim.prompt.StoryboardJsonBuilder;
import com.automanim.prompt.ToolSchemas;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.AiRequestUtils;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.GeoGebraCodeUtils;
import com.automanim.util.JsonUtils;
import com.automanim.util.ManimCodeUtils;
import com.automanim.util.NodeConversationContext;
import com.automanim.util.TargetDescriptionBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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

    @Override
    public CodeGenerationInput prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);

        FixRetryState fixState = (FixRetryState) ctx.get(WorkflowKeys.CODE_GENERATION_FIX_STATE);
        if (fixState == null) {
            fixState = new FixRetryState();
            ctx.put(WorkflowKeys.CODE_GENERATION_FIX_STATE, fixState);
        }

        CodeFixResult previousFixResult = consumeFixResult(ctx, CodeFixSource.GENERATION_VALIDATION);
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
            return new CodeResult("", "", "Empty narrative", "", "");
        }

        String targetConcept = narrative != null ? narrative.getTargetConcept()
                : input.existingCodeResult().getTargetConcept();
        String targetDescription = narrative != null ? narrative.getTargetDescription()
                : input.existingCodeResult().getTargetDescription();
        this.conversationContext.setSystemMessage(
                CodeGenerationPrompts.systemPrompt(
                        targetConcept, targetDescription, resolveOutputTarget()));

        String code;
        String artifactName = defaultArtifactName();
        if (input.previousFixResult() != null) {
            log.info("  Re-validating code returned from shared CodeFixNode");
            code = extractRetriedCode(input);
        } else {
            String userPrompt = buildGenerationPrompt(narrative, artifactName);
            if (userPrompt.isBlank()) {
                log.warn("Narrative prompt is empty, cannot generate code");
                return new CodeResult("", "", "Empty narrative", targetConcept, targetDescription);
            }

            try {
                CodeDraft draft = requestCodeAsync(userPrompt, artifactName).join();
                code = normalizeGeneratedCode(draft.code);
                artifactName = draft.artifactName;
            } catch (CompletionException e) {
                Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
                log.error("  Code generation failed: {}", cause.getMessage());
                code = "";
            }
        }

        code = normalizeGeneratedCode(code);
        if (isGeoGebraTarget()) {
            code = GeoGebraCodeUtils.enrichWithSceneButtons(
                    code,
                    narrative != null ? narrative.getStoryboard() : null);
        }
        List<String> violations = validateGeneratedCode(code);

        if (input.previousFixResult() != null) {
            if (!violations.isEmpty()) {
                if (fixState.getOriginalCodeBeforeFix() != null
                        && violations.size() >= fixState.getOriginalIssueCount()) {
                    log.warn("  Shared code fix did not improve validation violations ({} -> {}),"
                                    + " keeping original generated code",
                            fixState.getOriginalIssueCount(), violations.size());
                    code = fixState.getOriginalCodeBeforeFix();
                    violations = validateGeneratedCode(code);
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
            fixState.recordFixRequest(code, violations);
        }

        CodeResult result = new CodeResult(
                code,
                artifactName,
                buildResultDescription(targetConcept),
                targetConcept,
                targetDescription
        );
        result.setOutputTarget(resolveOutputTarget());
        result.setArtifactFormat(resolveArtifactFormat());
        result.setToolCalls(toolCalls + fixState.totalToolCalls());

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
                    resolveOutputTarget());
        } else {
            basePrompt = narrative.getVerbosePrompt();
        }

        if (basePrompt == null || basePrompt.isBlank()) {
            return "";
        }

        if (workflowConfig != null && workflowConfig.isGeoGebraTarget()) {
            return basePrompt
                    + "\n\nFigure name: " + expectedSceneName
                    + "\nUse this as the primary GeoGebra figure name when naming the construction."
                    + "\nAll object names and identifiers must use ASCII only."
                    + "\nKeep autogenerated text labels and comments ASCII-safe too."
                    + "\nPreserve scene-order grouping so per-scene visibility buttons can be synthesized downstream."
                    + "\nReturn GeoGebra commands only, not Python or JavaScript.";
        }

        return basePrompt
                + "\n\nScene class name: " + expectedSceneName
                + "\nUse this exact scene class name verbatim in the generated code."
                + "\nAll class names, method names, and variable names must use ASCII identifiers only."
                + "\nDo not use Chinese, pinyin, mojibake, or any non-ASCII text in Python identifiers.";
    }

    private CodeFixRequest buildValidationFixRequest(Narrative narrative,
                                                     CodeResult codeResult,
                                                     FixRetryState fixState) {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.GENERATION_VALIDATION);
        request.setReturnAction(WorkflowActions.RETRY_CODE_GENERATION);
        request.setCode(codeResult.getCode());
        request.setErrorReason(String.join("\n", fixState.getCurrentIssues()));
        request.setTargetConcept(codeResult.getTargetConcept());
        request.setTargetDescription(codeResult.getTargetDescription());
        request.setSceneName(codeResult.getSceneName());
        request.setExpectedSceneName(resolveExpectedArtifactName(codeResult));
        request.setStoryboardJson(narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(narrative.getStoryboard())
                : "{\"scenes\":[]}");
        return request;
    }

    private CodeFixResult consumeFixResult(Map<String, Object> ctx, CodeFixSource expectedSource) {
        CodeFixResult result = (CodeFixResult) ctx.get(WorkflowKeys.CODE_FIX_RESULT);
        if (result != null && result.getSource() == expectedSource) {
            ctx.remove(WorkflowKeys.CODE_FIX_RESULT);
            return result;
        }
        return null;
    }

    private String extractRetriedCode(CodeGenerationInput input) {
        if (input.existingCodeResult() != null
                && input.existingCodeResult().getCode() != null
                && !input.existingCodeResult().getCode().isBlank()) {
            return input.existingCodeResult().getCode();
        }
        if (input.previousFixResult() != null) {
            return input.previousFixResult().getFixedCode();
        }
        return "";
    }

    private static final class CodeDraft {
        private final String code;
        private final String artifactName;

        private CodeDraft(String code, String artifactName) {
            this.code = code;
            this.artifactName = artifactName;
        }
    }

    private JsonNode parseCodeTextResponse(String response) {
        String code = extractCodeFromText(response);
        if (code == null || code.isBlank()) {
            return null;
        }

        return JsonUtils.mapper().createObjectNode()
                .put("code", code);
    }

    private boolean hasCodePayload(JsonNode payload) {
        return payload != null
                && payload.has("code")
                && !payload.get("code").asText("").isBlank();
    }

    private CodeDraft toCodeDraft(JsonNode payload, String expectedArtifactName) {
        String code = null;
        String artifactName = expectedArtifactName;

        if (payload != null && payload.has("code")) {
            code = payload.get("code").asText();
        }
        if (payload != null && payload.has("scene_name")) {
            artifactName = payload.get("scene_name").asText(expectedArtifactName);
        } else if (payload != null && payload.has("figure_name")) {
            artifactName = payload.get("figure_name").asText(expectedArtifactName);
        }

        return new CodeDraft(code, artifactName);
    }

    private String normalizeGeneratedCode(String code) {
        return isGeoGebraTarget()
                ? GeoGebraCodeUtils.extractCode(code)
                : ManimCodeUtils.enforceMainSceneName(code);
    }

    private List<String> validateGeneratedCode(String code) {
        return isGeoGebraTarget()
                ? GeoGebraCodeUtils.validateFull(code)
                : ManimCodeUtils.validateFull(code);
    }

    private boolean shouldRouteValidationFix() {
        return workflowConfig == null
                || workflowConfig.isManimTarget()
                || workflowConfig.isGeoGebraTarget();
    }

    private String resolveToolSchema() {
        return isGeoGebraTarget()
                ? ToolSchemas.GEOGEBRA_CODE
                : ToolSchemas.MANIM_CODE;
    }

    private String extractCodeFromText(String text) {
        return isGeoGebraTarget()
                ? GeoGebraCodeUtils.extractCode(text)
                : ManimCodeUtils.extractCode(text);
    }

    private String defaultArtifactName() {
        return isGeoGebraTarget()
                ? "GeoGebraFigure"
                : ManimCodeUtils.EXPECTED_SCENE_NAME;
    }

    private String resolveArtifactFormat() {
        return isGeoGebraTarget() ? "commands" : "python";
    }

    private String buildResultDescription(String targetConcept) {
        return isGeoGebraTarget()
                ? "GeoGebra construction for " + targetConcept
                : "Manim animation for " + targetConcept;
    }

    private String resolveOutputTarget() {
        return workflowConfig != null ? workflowConfig.getOutputTarget() : WorkflowConfig.OUTPUT_TARGET_MANIM;
    }

    private String resolveExpectedArtifactName(CodeResult codeResult) {
        if (codeResult != null && codeResult.isGeoGebraTarget()) {
            if (codeResult.getSceneName() != null && !codeResult.getSceneName().isBlank()) {
                return codeResult.getSceneName();
            }
            return "GeoGebraFigure";
        }
        return ManimCodeUtils.EXPECTED_SCENE_NAME;
    }

    private boolean isGeoGebraTarget() {
        return workflowConfig != null && workflowConfig.isGeoGebraTarget();
    }
}

