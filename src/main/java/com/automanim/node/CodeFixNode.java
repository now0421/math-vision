package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeFixRequest;
import com.automanim.model.CodeFixResult;
import com.automanim.model.CodeFixSource;
import com.automanim.model.CodeFixTraceEntry;
import com.automanim.model.CodeFixTraceReport;
import com.automanim.model.CodeResult;
import com.automanim.model.WorkflowKeys;
import com.automanim.prompt.CodeEvaluationPrompts;
import com.automanim.prompt.CodeGenerationPrompts;
import com.automanim.prompt.RenderFixPrompts;
import com.automanim.prompt.SceneEvaluationPrompts;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.CodeValidationSupport;
import com.automanim.util.GeoGebraCodeUtils;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.JsonUtils;
import com.automanim.util.NodeConversationContext;
import com.automanim.util.TextUtils;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
            result.setExecutionTimeSeconds(toSeconds(start));
            return result;
        }

        result.setSource(request.getSource());
        result.setReturnAction(request.getReturnAction());
        result.setOriginalCode(request.getCode());
        result.setErrorReason(request.getErrorReason());

        if (request.getCode() == null || request.getCode().isBlank()) {
            result.setFailureReason("No code provided for code fix");
            result.setExecutionTimeSeconds(toSeconds(start));
            return result;
        }

        int maxInputTokens = (workflowConfig != null && workflowConfig.getModelConfig() != null)
                ? workflowConfig.getModelConfig().getMaxInputTokens()
                : 131072;
        NodeConversationContext conversationContext = new NodeConversationContext(maxInputTokens);
        String systemPrompt = selectSystemPrompt(request);
        conversationContext.setSystemMessage(systemPrompt);
        result.setSystemPrompt(systemPrompt);

        String userPrompt = selectUserPrompt(request);
        result.setUserPrompt(userPrompt);
        if (userPrompt == null || userPrompt.isBlank()) {
            result.setFailureReason("Code fix prompt was empty");
            result.setExecutionTimeSeconds(toSeconds(start));
            return result;
        }

        try {
            log.info("=== Shared Code Fix: {} ===", request.getSource());
            conversationContext.addUserMessage(userPrompt);
            String response = aiClient.chatAsync(conversationContext).join();
            conversationContext.addAssistantMessage(response);
            toolCalls++;

            String fixedCode = extractReturnedCode(response);
            if (fixedCode == null || fixedCode.isBlank()) {
                result.setFailureReason("Code fix returned no parseable "
                        + (isGeoGebraTarget() ? "GeoGebra code" : "Python code"));
            } else if (CodeValidationSupport.normalizeForComparison(fixedCode)
                    .equals(CodeValidationSupport.normalizeForComparison(request.getCode()))) {
                result.setFailureReason("Code fix produced no meaningful code change");
            } else {
                result.setApplied(true);
                result.setFixedCode(fixedCode);
            }
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            result.setFailureReason("Code fix request failed: " + cause.getMessage());
        } catch (RuntimeException e) {
            result.setFailureReason("Code fix request failed: " + e.getMessage());
        }

        result.setToolCalls(toolCalls);
        result.setExecutionTimeSeconds(toSeconds(start));
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
                codeResult.setCode(result.getFixedCode());
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
            systemPrompt = CodeEvaluationPrompts.revisionSystemPrompt(targetConcept, targetDescription);
        } else if (request.getSource() == CodeFixSource.GENERATION_VALIDATION) {
            systemPrompt = isGeoGebraTarget()
                    ? CodeGenerationPrompts.geoGebraValidationFixSystemPrompt(targetConcept, targetDescription)
                    : CodeGenerationPrompts.validationFixSystemPrompt(targetConcept, targetDescription);
        } else if (request.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION) {
            systemPrompt = SceneEvaluationPrompts.layoutFixSystemPrompt(targetConcept, targetDescription);
        } else {
            systemPrompt = isGeoGebraTarget()
                    ? RenderFixPrompts.geoGebraSystemPrompt(targetConcept, targetDescription)
                    : RenderFixPrompts.systemPrompt(targetConcept, targetDescription);
        }
        return systemPrompt;
    }

    private String selectUserPrompt(CodeFixRequest request) {
        if (request.getSource() == CodeFixSource.EVALUATION_REVIEW) {
            return CodeEvaluationPrompts.revisionUserPrompt(
                    TextUtils.firstNonBlank(request.getTargetConcept(), request.getSceneName(), "Unknown target"),
                    TextUtils.firstNonBlank(request.getSceneName(), request.getExpectedSceneName(), "MainScene"),
                    TextUtils.defaultIfBlank(request.getStoryboardJson(), "{\"scenes\":[]}"),
                    TextUtils.defaultIfBlank(request.getStaticAnalysisJson(), "{}"),
                    TextUtils.defaultIfBlank(request.getReviewJson(), "{}"),
                    request.getCode()
            );
        }
        if (request.getSource() == CodeFixSource.GENERATION_VALIDATION) {
            if (isGeoGebraTarget()) {
                return CodeGenerationPrompts.geoGebraValidationFixUserPrompt(
                        TextUtils.firstNonBlank(
                                request.getExpectedSceneName(), request.getSceneName(), "GeoGebraFigure"),
                        request.getCode(),
                        splitValidationProblems(request.getErrorReason()),
                        TextUtils.defaultIfBlank(request.getStoryboardJson(), "{\"scenes\":[]}")
                );
            }
            return CodeGenerationPrompts.validationFixUserPrompt(
                    TextUtils.firstNonBlank(request.getExpectedSceneName(), request.getSceneName(), "MainScene"),
                    request.getCode(),
                    splitValidationProblems(request.getErrorReason()),
                    TextUtils.defaultIfBlank(request.getStoryboardJson(), "{\"scenes\":[]}")
            );
        }
        if (request.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION) {
            return SceneEvaluationPrompts.layoutFixUserPrompt(
                    TextUtils.defaultIfBlank(request.getStoryboardJson(), "{\"scenes\":[]}"),
                    request.getCode(),
                    TextUtils.firstNonBlank(request.getErrorReason(), "Unknown scene evaluation issue"),
                    TextUtils.defaultIfBlank(request.getSceneEvaluationJson(), "{}"),
                    request.getFixHistory() != null ? request.getFixHistory() : Collections.emptyList()
            );
        }
        return isGeoGebraTarget()
                ? RenderFixPrompts.geoGebraUserPrompt(
                request.getCode(),
                TextUtils.firstNonBlank(request.getErrorReason(), "Unknown render failure"),
                TextUtils.defaultIfBlank(request.getStoryboardJson(), "{\"scenes\":[]}"),
                request.getFixHistory() != null ? request.getFixHistory() : Collections.emptyList()
        )
                : RenderFixPrompts.userPrompt(
                request.getCode(),
                TextUtils.firstNonBlank(request.getErrorReason(), "Unknown render failure"),
                TextUtils.defaultIfBlank(request.getStoryboardJson(), "{\"scenes\":[]}"),
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
        if (isGeoGebraTarget()) {
            return GeoGebraCodeUtils.looksLikeCommandBlock(trimmed) ? trimmed : null;
        }
        if (trimmed.toLowerCase(Locale.ROOT).contains("from manim import")
                || trimmed.contains("class ")) {
            return trimmed;
        }
        return null;
    }

    private boolean isGeoGebraTarget() {
        return workflowConfig != null && workflowConfig.isGeoGebraTarget();
    }

    private double toSeconds(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() / 1000.0;
    }
}

