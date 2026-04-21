package com.mathvision.node;

import com.mathvision.config.ModelConfig;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeFixRequest;
import com.mathvision.model.CodeFixResult;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.CodeFixTraceEntry;
import com.mathvision.model.CodeFixTraceReport;
import com.mathvision.model.CodeResult;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.CodeEvaluationPrompts;
import com.mathvision.prompt.CodeGenerationPrompts;
import com.mathvision.prompt.RenderFixPrompts;
import com.mathvision.prompt.SceneEvaluationPrompts;
import com.mathvision.prompt.StoryboardJsonBuilder;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.node.support.NodeSupport;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.CodeValidationSupport;
import com.mathvision.util.GeoGebraCodeUtils;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.TextUtils;
import com.mathvision.util.TimeUtils;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Shared routed node that repairs backend-specific code and returns control to
 * the caller.
 */
public class CodeFixNode extends PocketFlow.Node<CodeFixRequest, CodeFixResult, String> {

    private static final Logger log = LoggerFactory.getLogger(CodeFixNode.class);

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private Path outputDir;
    private int toolCalls;

    public CodeFixNode() {
        super(1, 0);
    }

    @Override
    public CodeFixRequest prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        this.outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        this.toolCalls = 0;
        return (CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
    }

    @Override
    public CodeFixResult exec(CodeFixRequest request) {
        Instant start = Instant.now();
        CodeFixResult result = new CodeFixResult();

        if (request == null) {
            result.setFailureReason("No code fix request available");
            result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
            return result;
        }

        result.setSource(request.getSource());
        result.setReturnAction(request.getReturnAction());
        result.setOriginalGeneratedCode(request.getGeneratedCode());
        result.setErrorReason(request.getErrorReason());

        if (request.getGeneratedCode() == null || request.getGeneratedCode().isBlank()) {
            result.setFailureReason("No code provided for code fix");
            result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
            return result;
        }

        int maxInputTokens = workflowConfig != null
                ? workflowConfig.resolveMaxInputTokens()
                : ModelConfig.DEFAULT_MAX_INPUT_TOKENS;
        NodeConversationContext conversationContext = new NodeConversationContext(maxInputTokens);
        String systemPrompt = selectSystemPrompt(request);
        conversationContext.setSystemMessage(systemPrompt);
        result.setSystemPrompt(systemPrompt);

        String userPrompt = selectUserPrompt(request);
        result.setUserPrompt(userPrompt);
        if (userPrompt == null || userPrompt.isBlank()) {
            result.setFailureReason("Code fix prompt was empty");
            result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
            return result;
        }

        try {
            log.info("=== Shared Code Fix: {} ===", request.getSource());
            conversationContext.addUserMessage(userPrompt);
            String response = AiRequestUtils.requestChatAsync(
                            aiClient, log, "code-fix", conversationContext,
                            () -> toolCalls++)
                    .join();
            conversationContext.addAssistantMessage(response);

            String fixedCode = extractReturnedCode(response);
            if (fixedCode == null || fixedCode.isBlank()) {
                result.setFailureReason("Code fix returned no parseable "
                        + (NodeSupport.isGeoGebraTarget(workflowConfig) ? "GeoGebra code" : "Python code"));
            } else if (!CodeValidationSupport.hasCodeChanged(request.getGeneratedCode(), fixedCode)) {
                result.setFailureReason("Code fix returned code identical to source code");
            } else {
                result.setApplied(true);
                result.setFixedGeneratedCode(fixedCode);
            }
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            result.setFailureReason("Code fix request failed: " + cause.getMessage());
        } catch (RuntimeException e) {
            result.setFailureReason("Code fix request failed: " + e.getMessage());
        }

        result.setToolCalls(toolCalls);
        result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
        return result;
    }

    @Override
    public String post(Map<String, Object> ctx, CodeFixRequest request, CodeFixResult result) {
        ctx.remove(WorkflowKeys.CODE_FIX_REQUEST);
        ctx.put(WorkflowKeys.CODE_FIX_RESULT, result);
        appendTraceEntry(ctx, request, result);

        if (result != null && result.isApplied()) {
            CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
            if (codeResult != null) {
                codeResult.setGeneratedCode(result.getFixedGeneratedCode());
                String updatedSceneName = TextUtils.firstNonBlank(
                        request != null ? request.getExpectedSceneName() : null,
                        request != null ? request.getSceneName() : null,
                        codeResult.getSceneName()
                );
                if (updatedSceneName != null) {
                    codeResult.setSceneName(updatedSceneName);
                }
                ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
            }
        }

        return result != null ? result.getReturnAction() : null;
    }

    @SuppressWarnings("unchecked")
    private void appendTraceEntry(Map<String, Object> ctx,
                                  CodeFixRequest request,
                                  CodeFixResult result) {
        List<CodeFixTraceEntry> entries =
                (List<CodeFixTraceEntry>) ctx.get(WorkflowKeys.CODE_FIX_TRACE);
        if (entries == null) {
            entries = new java.util.ArrayList<>();
            ctx.put(WorkflowKeys.CODE_FIX_TRACE, entries);
        }

        CodeFixTraceEntry entry = new CodeFixTraceEntry();
        entry.setSequence(entries.size() + 1);
        if (request != null) {
            entry.setSource(request.getSource());
            entry.setReturnAction(request.getReturnAction());
            entry.setSceneName(request.getSceneName());
            entry.setExpectedSceneName(request.getExpectedSceneName());
            entry.setTargetConcept(request.getTargetConcept());
            entry.setErrorReason(request.getErrorReason());
            entry.setFixHistory(request.getFixHistory());
        }
        if (result != null) {
            entry.setApplied(result.isApplied());
            entry.setFailureReason(result.getFailureReason());
            entry.setToolCalls(result.getToolCalls());
            entry.setExecutionTimeSeconds(result.getExecutionTimeSeconds());
            entry.setSystemPrompt(result.getSystemPrompt());
            entry.setUserPrompt(result.getUserPrompt());
        }

        entries.add(entry);

        if (outputDir != null) {
            CodeFixTraceReport report = new CodeFixTraceReport();
            report.setTotalFixEvents(entries.size());
            report.setEntries(entries);
            FileOutputService.saveCodeFixTrace(outputDir, report);
        }
    }

    private String selectSystemPrompt(CodeFixRequest request) {
        String targetConcept = TextUtils.firstNonBlank(
                request.getTargetConcept(), request.getSceneName(), "Unknown target");
        String targetDescription = TextUtils.firstNonBlank(request.getTargetDescription(), "");
        String systemPrompt;

        if (request.getSource() == CodeFixSource.EVALUATION_REVIEW) {
            systemPrompt = CodeEvaluationPrompts.revisionSystemPrompt(
                    targetConcept,
                    targetDescription,
                    NodeSupport.resolveOutputTarget(workflowConfig));
        } else if (request.getSource() == CodeFixSource.GENERATION_VALIDATION) {
            systemPrompt = NodeSupport.isGeoGebraTarget(workflowConfig)
                    ? CodeGenerationPrompts.geoGebraValidationFixSystemPrompt(targetConcept, targetDescription)
                    : CodeGenerationPrompts.validationFixSystemPrompt(targetConcept, targetDescription);
        } else if (request.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION) {
            systemPrompt = NodeSupport.isGeoGebraTarget(workflowConfig)
                    ? SceneEvaluationPrompts.geoGebraLayoutFixSystemPrompt(targetConcept, targetDescription)
                    : SceneEvaluationPrompts.layoutFixSystemPrompt(targetConcept, targetDescription);
        } else {
            systemPrompt = NodeSupport.isGeoGebraTarget(workflowConfig)
                    ? RenderFixPrompts.geoGebraSystemPrompt(targetConcept, targetDescription)
                    : RenderFixPrompts.systemPrompt(targetConcept, targetDescription);
        }
        return systemPrompt;
    }

    private String selectUserPrompt(CodeFixRequest request) {
        if (request.getSource() == CodeFixSource.EVALUATION_REVIEW) {
            String artifactName = TextUtils.firstNonBlank(
                    request.getSceneName(),
                    request.getExpectedSceneName(),
                    NodeSupport.isGeoGebraTarget(workflowConfig) ? GeoGebraCodeUtils.EXPECTED_FIGURE_NAME : "MainScene");
            return CodeEvaluationPrompts.revisionUserPrompt(
                    artifactName,
                    TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                    TextUtils.defaultIfBlank(request.getStaticAnalysisJson(), "{}"),
                    TextUtils.defaultIfBlank(request.getReviewJson(), "{}"),
                    request.getGeneratedCode(),
                    NodeSupport.resolveOutputTarget(workflowConfig)
            );
        }
        if (request.getSource() == CodeFixSource.GENERATION_VALIDATION) {
            if (NodeSupport.isGeoGebraTarget(workflowConfig)) {
                return CodeGenerationPrompts.geoGebraValidationFixUserPrompt(
                        TextUtils.firstNonBlank(
                                request.getExpectedSceneName(), request.getSceneName(), GeoGebraCodeUtils.EXPECTED_FIGURE_NAME),
                        request.getGeneratedCode(),
                        splitValidationProblems(request.getErrorReason()),
                        TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON)
                );
            }
            return CodeGenerationPrompts.validationFixUserPrompt(
                    TextUtils.firstNonBlank(request.getExpectedSceneName(), request.getSceneName(), "MainScene"),
                    request.getGeneratedCode(),
                    splitValidationProblems(request.getErrorReason()),
                    TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON)
            );
        }
        if (request.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION) {
            if (NodeSupport.isGeoGebraTarget(workflowConfig)) {
                return SceneEvaluationPrompts.geoGebraLayoutFixUserPrompt(
                        TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                        request.getGeneratedCode(),
                        TextUtils.firstNonBlank(request.getErrorReason(), "Unknown scene evaluation issue"),
                        TextUtils.defaultIfBlank(request.getSceneEvaluationJson(), "{}"),
                        request.getFixHistory() != null ? request.getFixHistory() : Collections.emptyList()
                );
            }
            return SceneEvaluationPrompts.layoutFixUserPrompt(
                    TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                    request.getGeneratedCode(),
                    TextUtils.firstNonBlank(request.getErrorReason(), "Unknown scene evaluation issue"),
                    TextUtils.defaultIfBlank(request.getSceneEvaluationJson(), "{}"),
                    request.getFixHistory() != null ? request.getFixHistory() : Collections.emptyList()
            );
        }
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? RenderFixPrompts.geoGebraUserPrompt(
                request.getGeneratedCode(),
                TextUtils.firstNonBlank(request.getErrorReason(), "Unknown render failure"),
                TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                request.getFixHistory() != null ? request.getFixHistory() : Collections.emptyList()
        )
                : RenderFixPrompts.userPrompt(
                request.getGeneratedCode(),
                TextUtils.firstNonBlank(request.getErrorReason(), "Unknown render failure"),
                TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                request.getFixHistory() != null ? request.getFixHistory() : Collections.emptyList()
        );
    }

    private List<String> splitValidationProblems(String errorReason) {
        if (errorReason == null || errorReason.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(errorReason.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
    }

    private String extractReturnedCode(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String codeBlock = JsonUtils.extractCodeBlock(response);
        if (codeBlock != null && !codeBlock.isBlank()) {
            return codeBlock;
        }
        String trimmed = response.trim();
        if (NodeSupport.isGeoGebraTarget(workflowConfig)) {
            return GeoGebraCodeUtils.looksLikeCommandBlock(trimmed) ? trimmed : null;
        }
        if (trimmed.toLowerCase(Locale.ROOT).contains("from manim import")
                || trimmed.contains("class ")) {
            return trimmed;
        }
        return null;
    }

}
