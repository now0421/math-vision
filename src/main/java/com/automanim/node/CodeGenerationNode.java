package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeFixRequest;
import com.automanim.model.CodeFixResult;
import com.automanim.model.CodeFixSource;
import com.automanim.model.CodeResult;
import com.automanim.model.Narrative;
import com.automanim.model.WorkflowActions;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.JsonUtils;
import com.automanim.util.NodeConversationContext;
import com.automanim.util.PromptTemplates;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 2: Code Generation - generates executable Manim Python code
 * from the narrative storyboard prompt.
 */
public class CodeGenerationNode extends PocketFlow.Node<CodeGenerationNode.CodeGenerationInput, CodeResult, String> {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationNode.class);
    private static final Pattern MAIN_SCENE_CLASS = Pattern.compile("class\\s+MainScene\\s*\\(.*?Scene.*?\\)");
    private static final Pattern ANY_SCENE_CLASS = Pattern.compile("class\\s+[^\\s(]+\\s*\\((.*?Scene.*?)\\)");
    private static final Pattern NON_ASCII_IDENTIFIER = Pattern.compile(
            "(?:class|def)\\s+[^\\x00-\\x7F]+|self\\.[^\\x00-\\x7F]+|\\b[^\\x00-\\x7F_][^\\s(=:,]*");
    private static final int MAX_VALIDATION_FIX_ATTEMPTS = 1;
    private static final String EXPECTED_SCENE_NAME = "MainScene";

    private static final String MANIM_CODE_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_manim_code\","
            + "    \"description\": \"Return complete Manim Community Python animation code.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"code\": { \"type\": \"string\", \"description\": \"Complete Manim Python source code\" },"
            + "        \"scene_name\": { \"type\": \"string\", \"description\": \"Primary scene class name in ASCII\" },"
            + "        \"description\": { \"type\": \"string\", \"description\": \"Short summary of the animation\" }"
            + "      },"
            + "      \"required\": [\"code\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // Validation patterns for mandatory rules
    private static final Pattern RULE1_VIOLATION = Pattern.compile(
            "self\\.\\w+\\s*=\\s*(?:MathTex|Text|VGroup|Circle|Square|Line|Dot|Arrow|Axes|NumberPlane)");
    private static final Pattern RULE3_VIOLATION = Pattern.compile(
            "\\w+\\[\\d+\\]\\[\\d+:\\d+\\]");

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private NodeConversationContext conversationContext;
    private int toolCalls = 0;

    public CodeGenerationNode() {
        super(2, 2000); // 2 retries with 2-second wait
    }

    public static class CodeGenerationInput {
        private final Narrative narrative;
        private final CodeResult existingCodeResult;
        private final CodeFixResult previousFixResult;
        private final GenerationFixState fixState;

        public CodeGenerationInput(Narrative narrative,
                                   CodeResult existingCodeResult,
                                   CodeFixResult previousFixResult,
                                   GenerationFixState fixState) {
            this.narrative = narrative;
            this.existingCodeResult = existingCodeResult;
            this.previousFixResult = previousFixResult;
            this.fixState = fixState;
        }

        public Narrative narrative() { return narrative; }
        public CodeResult existingCodeResult() { return existingCodeResult; }
        public CodeFixResult previousFixResult() { return previousFixResult; }
        public GenerationFixState fixState() { return fixState; }
    }

    @Override
    public CodeGenerationInput prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);

        GenerationFixState fixState = (GenerationFixState) ctx.get(WorkflowKeys.CODE_GENERATION_FIX_STATE);
        if (fixState == null) {
            fixState = new GenerationFixState();
            ctx.put(WorkflowKeys.CODE_GENERATION_FIX_STATE, fixState);
        }

        CodeFixResult previousFixResult = consumeFixResult(ctx, CodeFixSource.GENERATION_VALIDATION);
        if (previousFixResult != null) {
            fixState.fixToolCalls += previousFixResult.getToolCalls();
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
            int maxInputTokens = (workflowConfig != null && workflowConfig.getModelConfig() != null)
                    ? workflowConfig.getModelConfig().getMaxInputTokens()
                    : 131072;
            this.conversationContext = new NodeConversationContext(maxInputTokens);
        }

        Narrative narrative = input.narrative();
        GenerationFixState fixState = input.fixState();
        fixState.requestFix = false;

        if (narrative == null && input.existingCodeResult() == null) {
            log.warn("Narrative is empty, cannot generate code");
            return new CodeResult("", "", "Empty narrative", "", "");
        }

        String targetConcept = narrative != null ? narrative.getTargetConcept()
                : input.existingCodeResult().getTargetConcept();
        String targetDescription = narrative != null ? narrative.getTargetDescription()
                : input.existingCodeResult().getTargetDescription();
        this.conversationContext.setSystemMessage(
                PromptTemplates.codeGenerationSystemPrompt(targetConcept, targetDescription));

        String code;
        String sceneName = EXPECTED_SCENE_NAME;
        if (input.previousFixResult() != null) {
            log.info("  Re-validating code returned from shared CodeFixNode");
            code = extractRetriedCode(input);
        } else {
            String userPrompt = buildGenerationPrompt(narrative, EXPECTED_SCENE_NAME);
            if (userPrompt.isBlank()) {
                log.warn("Narrative prompt is empty, cannot generate code");
                return new CodeResult("", "", "Empty narrative", targetConcept, targetDescription);
            }

            try {
                CodeDraft draft = requestCodeAsync(userPrompt, EXPECTED_SCENE_NAME).join();
                code = enforceMainSceneClassName(draft.code);
                sceneName = draft.sceneName;
            } catch (CompletionException e) {
                Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
                log.error("  Code generation failed: {}", cause.getMessage());
                code = "";
            }
            sceneName = EXPECTED_SCENE_NAME;
        }

        code = enforceMainSceneClassName(code);
        List<String> violations = validateCode(code);

        if (input.previousFixResult() != null) {
            if (!violations.isEmpty()) {
                if (fixState.originalCodeBeforeFix != null
                        && violations.size() >= fixState.originalViolationCount) {
                    log.warn("  Shared code fix did not improve validation violations ({} -> {}),"
                                    + " keeping original generated code",
                            fixState.originalViolationCount, violations.size());
                    code = fixState.originalCodeBeforeFix;
                    violations = validateCode(code);
                } else {
                    log.info("  Shared code fix reduced violations from {} to {}",
                            fixState.originalViolationCount, violations.size());
                }
            }
            fixState.clearPending();
        } else if (!violations.isEmpty() && fixState.attempts < MAX_VALIDATION_FIX_ATTEMPTS) {
            log.warn("  Code validation found {} violations, routing to shared CodeFixNode",
                    violations.size());
            fixState.requestFix = true;
            fixState.attempts++;
            fixState.originalCodeBeforeFix = code;
            fixState.originalViolationCount = violations.size();
            fixState.validationIssues = new ArrayList<>(violations);
        }

        CodeResult result = new CodeResult(
                code,
                sceneName,
                "Manim animation for " + targetConcept,
                targetConcept,
                targetDescription
        );
        result.setToolCalls(toolCalls + fixState.fixToolCalls + fixState.generationToolCallsCarryover);

        log.info("Code generated: {} lines, scene={}", result.codeLineCount(), sceneName);
        return result;
    }

    private CompletableFuture<CodeDraft> requestCodeAsync(String userPrompt, String expectedSceneName) {
        conversationContext.addUserMessage(userPrompt);

        return aiClient.chatWithToolsRawAsync(conversationContext, MANIM_CODE_TOOL)
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
                            code = JsonUtils.extractCodeBlock(textContent);
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
                                return new CodeDraft(JsonUtils.extractCodeBlock(response), expectedSceneName);
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

        if (input.fixState().requestFix) {
            input.fixState().generationToolCallsCarryover += toolCalls;
            ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildValidationFixRequest(codeResult, input.fixState()));
            return WorkflowActions.FIX_CODE;
        }

        input.fixState().reset();
        return null;
    }

    private String buildGenerationPrompt(Narrative narrative, String expectedSceneName) {
        String basePrompt;
        if (narrative.hasStoryboard()) {
            basePrompt = PromptTemplates.storyboardCodegenPrompt(
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

    private List<String> validateCode(String code) {
        List<String> violations = new ArrayList<>();
        if (code == null || code.isBlank()) {
            violations.add("Code is empty");
            return violations;
        }

        if (!code.contains("from manim import")) {
            violations.add("Missing 'from manim import' statement");
        }
        if (!MAIN_SCENE_CLASS.matcher(code).find()) {
            violations.add("Scene class must be named MainScene");
        }
        if (!code.contains("def construct(")) {
            violations.add("Missing construct() method");
        }
        String nonAsciiEvidence = findFirstMatchEvidence(code, NON_ASCII_IDENTIFIER);
        if (nonAsciiEvidence != null) {
            violations.add("Contains non-ASCII class, method, or variable identifiers"
                    + " (" + nonAsciiEvidence + ")");
        }

        String rule1Evidence = findFirstMatchEvidence(code, RULE1_VIOLATION);
        if (rule1Evidence != null) {
            violations.add("Rule 1 violation: stores mobjects on instance fields across scenes"
                    + " (" + rule1Evidence + ")");
        }

        String rule3Evidence = findFirstMatchEvidence(code, RULE3_VIOLATION);
        if (rule3Evidence != null) {
            violations.add("Rule 3 violation: hardcoded MathTex subobject indexing"
                    + " (" + rule3Evidence + ")");
        }

        return violations;
    }

    private String findFirstMatchEvidence(String code, Pattern pattern) {
        if (code == null || code.isBlank() || pattern == null) {
            return null;
        }

        String[] lines = code.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = pattern.matcher(lines[i]);
            if (matcher.find()) {
                String fragment = matcher.group();
                if (fragment == null || fragment.isBlank()) {
                    fragment = lines[i].trim();
                }
                fragment = fragment.replace("\t", " ").trim();
                if (fragment.length() > 120) {
                    fragment = fragment.substring(0, 120) + "...";
                }
                return "line " + (i + 1) + ": " + fragment;
            }
        }

        return null;
    }

    private CodeFixRequest buildValidationFixRequest(CodeResult codeResult, GenerationFixState fixState) {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.GENERATION_VALIDATION);
        request.setReturnAction(WorkflowActions.RETRY_CODE_GENERATION);
        request.setCode(codeResult.getManimCode());
        request.setErrorReason(String.join("\n", fixState.validationIssues));
        request.setTargetConcept(codeResult.getTargetConcept());
        request.setTargetDescription(codeResult.getTargetDescription());
        request.setSceneName(codeResult.getSceneName());
        request.setExpectedSceneName(EXPECTED_SCENE_NAME);
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

    private String enforceMainSceneClassName(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        return ANY_SCENE_CLASS.matcher(code)
                .replaceFirst("class MainScene($1)");
    }

    static final class GenerationFixState {
        private int attempts;
        private int fixToolCalls;
        private int generationToolCallsCarryover;
        private boolean requestFix;
        private String originalCodeBeforeFix;
        private int originalViolationCount;
        private List<String> validationIssues = new ArrayList<>();

        void clearPending() {
            requestFix = false;
            originalCodeBeforeFix = null;
            originalViolationCount = 0;
            validationIssues = new ArrayList<>();
        }

        void reset() {
            attempts = 0;
            fixToolCalls = 0;
            generationToolCallsCarryover = 0;
            clearPending();
        }
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
