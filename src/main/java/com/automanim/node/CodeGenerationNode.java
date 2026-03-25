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
import com.automanim.prompt.ToolSchemas;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.CodeUtils;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.JsonUtils;
import com.automanim.util.NodeConversationContext;
import com.automanim.util.TargetDescriptionBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Stage 2: Code Generation - generates executable Manim Python code
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
                CodeGenerationPrompts.systemPrompt(targetConcept, targetDescription));

        String code;
        String sceneName = CodeUtils.EXPECTED_SCENE_NAME;
        if (input.previousFixResult() != null) {
            log.info("  Re-validating code returned from shared CodeFixNode");
            code = extractRetriedCode(input);
        } else {
            String userPrompt = buildGenerationPrompt(narrative, CodeUtils.EXPECTED_SCENE_NAME);
            if (userPrompt.isBlank()) {
                log.warn("Narrative prompt is empty, cannot generate code");
                return new CodeResult("", "", "Empty narrative", targetConcept, targetDescription);
            }

            try {
                CodeDraft draft = requestCodeAsync(userPrompt, CodeUtils.EXPECTED_SCENE_NAME).join();
                code = CodeUtils.enforceMainSceneName(draft.code);
                sceneName = draft.sceneName;
            } catch (CompletionException e) {
                Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
                log.error("  Code generation failed: {}", cause.getMessage());
                code = "";
            }
            sceneName = CodeUtils.EXPECTED_SCENE_NAME;
        }

        code = CodeUtils.enforceMainSceneName(code);
        List<String> violations = CodeUtils.validateFull(code);

        if (input.previousFixResult() != null) {
            if (!violations.isEmpty()) {
                if (fixState.getOriginalCodeBeforeFix() != null
                        && violations.size() >= fixState.getOriginalIssueCount()) {
                    log.warn("  Shared code fix did not improve validation violations ({} -> {}),"
                                    + " keeping original generated code",
                            fixState.getOriginalIssueCount(), violations.size());
                    code = fixState.getOriginalCodeBeforeFix();
                    violations = CodeUtils.validateFull(code);
                } else {
                    log.info("  Shared code fix reduced violations from {} to {}",
                            fixState.getOriginalIssueCount(), violations.size());
                }
            }
            fixState.clearPending();
        } else if (!violations.isEmpty() && fixState.getAttempts() < MAX_VALIDATION_FIX_ATTEMPTS) {
            log.warn("  Code validation found {} violations, routing to shared CodeFixNode",
                    violations.size());
            fixState.recordFixRequest(code, violations);
        }

        CodeResult result = new CodeResult(
                code,
                sceneName,
                "Manim animation for " + targetConcept,
                targetConcept,
                targetDescription
        );
        result.setToolCalls(toolCalls + fixState.totalToolCalls());

        log.info("Code generated: {} lines, scene={}", result.codeLineCount(), sceneName);
        return result;
    }

    private CompletableFuture<CodeDraft> requestCodeAsync(String userPrompt, String expectedSceneName) {
        conversationContext.addUserMessage(userPrompt);

        return aiClient.chatWithToolsRawAsync(conversationContext, ToolSchemas.MANIM_CODE)
                .thenApply(rawResponse -> {
                    toolCalls++;

                    JsonNode toolData = JsonUtils.extractToolCallPayload(rawResponse);
                    String code = null;
                    String sceneName = expectedSceneName;

                    if (toolData != null && toolData.has("code")) {
                        code = toolData.get("code").asText();
                    }

                    if (code == null || code.isBlank()) {
                        String textContent = JsonUtils.extractBestEffortTextFromResponse(rawResponse);
                        if (textContent != null) {
                            code = CodeUtils.extractCode(textContent);
                        }
                    }

                    CodeDraft draft = new CodeDraft(code, sceneName);
                    if (draft.hasCode()) {
                        String transcript = JsonUtils.buildToolCallTranscript(rawResponse);
                        conversationContext.addAssistantMessage(
                                transcript == null || transcript.isBlank()
                                        ? "```python\n" + draft.code + "\n```"
                                        : transcript);
                    }
                    return draft;
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.debug("  Tool calling failed, falling back to plain chat: {}", cause.getMessage());
                    return null;
                })
                .thenCompose(draft -> {
                    if (draft != null && draft.hasCode()) {
                        return CompletableFuture.completedFuture(draft);
                    }
                    return aiClient.chatAsync(conversationContext)
                            .thenApply(response -> {
                                toolCalls++;
                                conversationContext.addAssistantMessage(response);
                                return new CodeDraft(CodeUtils.extractCode(response), expectedSceneName);
                            });
                });
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
            ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildValidationFixRequest(codeResult, input.fixState()));
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
                    narrative.getStoryboard());
        } else {
            basePrompt = narrative.getVerbosePrompt();
        }

        if (basePrompt == null || basePrompt.isBlank()) {
            return "";
        }

        return basePrompt
                + "\n\nScene class name: " + expectedSceneName
                + "\nUse this exact scene class name verbatim in the generated code."
                + "\nAll class names, method names, and variable names must use ASCII identifiers only."
                + "\nDo not use Chinese, pinyin, mojibake, or any non-ASCII text in Python identifiers.";
    }

    private CodeFixRequest buildValidationFixRequest(CodeResult codeResult, FixRetryState fixState) {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.GENERATION_VALIDATION);
        request.setReturnAction(WorkflowActions.RETRY_CODE_GENERATION);
        request.setCode(codeResult.getManimCode());
        request.setErrorReason(String.join("\n", fixState.getCurrentIssues()));
        request.setTargetConcept(codeResult.getTargetConcept());
        request.setTargetDescription(codeResult.getTargetDescription());
        request.setSceneName(codeResult.getSceneName());
        request.setExpectedSceneName(CodeUtils.EXPECTED_SCENE_NAME);
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
                && input.existingCodeResult().getManimCode() != null
                && !input.existingCodeResult().getManimCode().isBlank()) {
            return input.existingCodeResult().getManimCode();
        }
        if (input.previousFixResult() != null) {
            return input.previousFixResult().getFixedCode();
        }
        return "";
    }

    private static final class CodeDraft {
        private final String code;
        private final String sceneName;

        private CodeDraft(String code, String sceneName) {
            this.code = code;
            this.sceneName = sceneName;
        }

        private boolean hasCode() {
            return code != null && !code.isBlank();
        }
    }
}
