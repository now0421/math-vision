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
import com.mathvision.prompt.RenderFixPrompts;
import com.mathvision.prompt.SceneEvaluationPrompts;
import com.mathvision.prompt.StoryboardJsonBuilder;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.node.support.NodeSupport;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.CodeValidationSupport;
import com.mathvision.util.GeoGebraCodeUtils;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.ManimCodeUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.ProblemBundleContextBuilder;
import com.mathvision.util.TextHealthDiagnostics;
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
    private CodeFixRequest currentRequest;

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
            this.currentRequest = null;
            result.setFailureReason("No code fix request available");
            result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
            return result;
        }

        this.currentRequest = request;
        result.setSource(request.getSource());
        result.setReturnAction(request.getReturnAction());
        result.setOriginalGeneratedCode(request.getGeneratedCode());
        result.setErrorReason(request.getErrorReason());

        if (request.getGeneratedCode() == null || request.getGeneratedCode().isBlank()) {
            result.setFailureReason("No code provided for code fix");
            result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
            return result;
        }

        String rulesPrompt = TextUtils.firstNonBlank(request.getRulesPrompt(), selectRulesPrompt(request));
        String fixedContextPrompt = TextUtils.firstNonBlank(
                request.getFixedContextPrompt(), selectFixedContextPrompt(request));
        NodeConversationContext conversationContext = resolveConversationContext(
                request, rulesPrompt, fixedContextPrompt);
        result.setRulesPrompt(rulesPrompt);
        result.setFixedContextPrompt(fixedContextPrompt);

        String currentRequestPrompt = selectCurrentRequestPrompt(request);
        result.setCurrentRequestPrompt(currentRequestPrompt);
        if (currentRequestPrompt == null || currentRequestPrompt.isBlank()) {
            result.setFailureReason("Code fix prompt was empty");
            result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
            return result;
        }

        try {
            log.info("=== Shared Code Fix: {} ===", request.getSource());
            String fixedCode = AiRequestUtils.requestExtractedTextAsync(
                            aiClient,
                            log,
                            "code-fix",
                            conversationContext,
                            currentRequestPrompt,
                            resolveToolSchema(),
                            () -> toolCalls++,
                            List.of(resolveGeneratedCodeFieldName()),
                            this::extractCodeFromText,
                            text -> text != null && !text.isBlank())
                    .join();
            if (fixedCode == null || fixedCode.isBlank()) {
                result.setFailureReason("Code fix returned no parseable "
                        + (isGeoGebraTarget(request) ? "GeoGebra code" : "Python code"));
                result.setOutcome(CodeFixResult.FixOutcome.UNCHANGED);
            } else if (!CodeValidationSupport.hasCodeChanged(request.getGeneratedCode(), fixedCode)) {
                result.setFailureReason("Code fix returned code identical to source code");
                result.setOutcome(CodeFixResult.FixOutcome.UNCHANGED);
            } else {
                result.setApplied(true);
                result.setFixedGeneratedCode(fixedCode);
            }
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            result.setFailureReason("Code fix request failed: " + cause.getMessage());
            result.setOutcome(CodeFixResult.FixOutcome.FAILED);
        } catch (RuntimeException e) {
            result.setFailureReason("Code fix request failed: " + e.getMessage());
            result.setOutcome(CodeFixResult.FixOutcome.FAILED);
        }

        // Check for input corruption
        if (request.getInputTextHealth() != null
                && TextHealthDiagnostics.hasSuspiciousEncoding(request.getInputTextHealth())) {
            result.setOutcome(CodeFixResult.FixOutcome.INPUT_CORRUPTED);
        }

        if (result.isApplied()) {
            List<String> postFixIssues = isGeoGebraTarget(request)
                    ? GeoGebraCodeUtils.validateFull(result.getFixedGeneratedCode())
                    : ManimCodeUtils.validateFull(result.getFixedGeneratedCode());
            result.setPostFixStaticAuditIssueCount(postFixIssues.size());
            result.setPostFixStaticAuditSummary(summarizeIssues(postFixIssues));
            if (postFixIssues.isEmpty()) {
                result.setOutcome(CodeFixResult.FixOutcome.FIXED);
            } else {
                result.setOutcome(CodeFixResult.FixOutcome.APPLIED_WITH_ISSUES);
            }
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
                if (request != null && request.getOutputTarget() != null && !request.getOutputTarget().isBlank()) {
                    codeResult.setOutputTarget(request.getOutputTarget());
                }
                ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
            }
        }

        return result != null ? result.getReturnAction() : null;
    }

    private NodeConversationContext resolveConversationContext(CodeFixRequest request,
                                                               String rulesPrompt,
                                                               String fixedContextPrompt) {
        NodeConversationContext conversationContext =
                request != null ? request.getConversationContext() : null;
        if (conversationContext == null) {
            int maxInputTokens = workflowConfig != null
                    ? workflowConfig.resolveMaxInputTokens()
                    : ModelConfig.DEFAULT_MAX_INPUT_TOKENS;
            conversationContext = new NodeConversationContext(maxInputTokens, fallbackContextRounds(request));
        }
        if (conversationContext.getPinnedMessages().isEmpty()) {
            conversationContext.setSystemMessage(rulesPrompt);
            conversationContext.setFixedContextMessage(fixedContextPrompt);
        }
        return conversationContext;
    }

    private int fallbackContextRounds(CodeFixRequest request) {
        if (request == null || request.getSource() == null) {
            return 4;
        }
        switch (request.getSource()) {
            case CODE_RENDER:
                return 6;
            case SCENE_LAYOUT_EVALUATION:
                return 5;
            case CODE_EVALUATION:
            default:
                return 4;
        }
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
            entry.setProblemTitle(ProblemBundleContextBuilder.displayTitle(request.getProblemBundle()));
            entry.setErrorReason(request.getErrorReason());
            entry.setErrorContextMode(request.getErrorContextMode());
            entry.setInputTextHealth(request.getInputTextHealth());
            entry.setStaticAuditIssueCount(request.getStaticAuditIssueCount());
            entry.setStaticAuditSummary(request.getStaticAuditSummary());
            entry.setFixHistory(request.getFixHistory());
        }
        if (result != null) {
            entry.setApplied(result.isApplied());
            entry.setFailureReason(result.getFailureReason());
            entry.setPostFixStaticAuditIssueCount(result.getPostFixStaticAuditIssueCount());
            entry.setPostFixStaticAuditSummary(result.getPostFixStaticAuditSummary());
            entry.setFixOutcome(result.getOutcome());
            entry.setToolCalls(result.getToolCalls());
            entry.setExecutionTimeSeconds(result.getExecutionTimeSeconds());
            entry.setRulesPrompt(result.getRulesPrompt());
            entry.setFixedContextPrompt(result.getFixedContextPrompt());
            entry.setCurrentRequestPrompt(result.getCurrentRequestPrompt());
        }

        entries.add(entry);

        if (outputDir != null) {
            CodeFixTraceReport report = new CodeFixTraceReport();
            report.setTotalFixEvents(entries.size());
            report.setEntries(entries);
            FileOutputService.saveCodeFixTrace(outputDir, report);
        }
    }

    private String selectRulesPrompt(CodeFixRequest request) {
        String outputTarget = resolveOutputTarget(request);
        if (request.getSource() == CodeFixSource.CODE_EVALUATION) {
            return CodeEvaluationPrompts.buildRevisionRulesPrompt(outputTarget);
        }
        if (request.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION) {
            return SceneEvaluationPrompts.buildLayoutFixRulesPrompt(outputTarget);
        }
        return RenderFixPrompts.buildRulesPrompt(outputTarget);
    }

    private String selectFixedContextPrompt(CodeFixRequest request) {
        String outputTarget = resolveOutputTarget(request);
        String targetDescription = TextUtils.firstNonBlank(request.getTargetDescription(), "");
        if (request.getSource() == CodeFixSource.CODE_EVALUATION) {
            return CodeEvaluationPrompts.buildRevisionFixedContextPrompt(
                    request.getProblemBundle(),
                    targetDescription,
                    outputTarget);
        }
        if (request.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION) {
            return SceneEvaluationPrompts.buildLayoutFixFixedContextPrompt(
                    request.getProblemBundle(),
                    targetDescription,
                    outputTarget);
        }
        return RenderFixPrompts.buildFixedContextPrompt(
                request.getProblemBundle(),
                targetDescription,
                outputTarget);
    }

    private String selectCurrentRequestPrompt(CodeFixRequest request) {
        String outputTarget = resolveOutputTarget(request);
        boolean geoGebraTarget = isGeoGebraTarget(request);
        if (request.getSource() == CodeFixSource.CODE_EVALUATION) {
            String artifactName = TextUtils.firstNonBlank(
                    request.getSceneName(),
                    request.getExpectedSceneName(),
                    geoGebraTarget ? GeoGebraCodeUtils.EXPECTED_FIGURE_NAME : "MainScene");
            return CodeEvaluationPrompts.revisionUserPrompt(
                    artifactName,
                    TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                    TextUtils.defaultIfBlank(request.getStaticAnalysisJson(), "{}"),
                    TextUtils.defaultIfBlank(request.getReviewJson(), "{}"),
                    request.getGeneratedCode(),
                    outputTarget
            );
        }
        if (request.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION) {
            if (geoGebraTarget) {
                return SceneEvaluationPrompts.geoGebraLayoutFixUserPrompt(
                        TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                        request.getGeneratedCode(),
                        TextUtils.firstNonBlank(request.getErrorReason(), "Unknown scene evaluation issue"),
                        TextUtils.defaultIfBlank(request.getSceneEvaluationJson(), "{}"),
                        request.getFixHistory() != null ? request.getFixHistory() : Collections.emptyList()
                );
            }
            return SceneEvaluationPrompts.manimLayoutFixUserPrompt(
                    TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                    request.getGeneratedCode(),
                    TextUtils.firstNonBlank(request.getErrorReason(), "Unknown scene evaluation issue"),
                    TextUtils.defaultIfBlank(request.getSceneEvaluationJson(), "{}"),
                    request.getFixHistory() != null ? request.getFixHistory() : Collections.emptyList()
            );
        }
        return geoGebraTarget
                ? RenderFixPrompts.geoGebraUserPrompt(
                request.getGeneratedCode(),
                TextUtils.firstNonBlank(request.getErrorReason(), "Unknown render failure"),
                TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                request.getFixHistory() != null ? request.getFixHistory() : Collections.emptyList()
        )
                : RenderFixPrompts.manimUserPrompt(
                request.getGeneratedCode(),
                TextUtils.firstNonBlank(request.getErrorReason(), "Unknown render failure"),
                TextUtils.defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                request.getFixHistory() != null ? request.getFixHistory() : Collections.emptyList(),
                TextUtils.firstNonBlank(request.getErrorContextMode(), ""),
                TextUtils.firstNonBlank(request.getStaticAuditSummary(), "")
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

    private String extractCodeFromText(String text) {
        return isGeoGebraTarget(currentRequest)
                ? GeoGebraCodeUtils.ensureDefaultViewCommand(GeoGebraCodeUtils.extractCode(text))
                : ManimCodeUtils.extractCode(text);
    }

    private String summarizeIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        String joined = String.join(" | ", issues);
        return joined.length() > 400 ? joined.substring(0, 400) + "..." : joined;
    }

    private String resolveToolSchema() {
        return isGeoGebraTarget(currentRequest)
                ? ToolSchemas.GEOGEBRA_CODE
                : ToolSchemas.MANIM_CODE;
    }

    private String resolveGeneratedCodeFieldName() {
        return isGeoGebraTarget(currentRequest) ? "geogebraCode" : "manimCode";
    }

    private String resolveOutputTarget(CodeFixRequest request) {
        if (request != null && request.getOutputTarget() != null && !request.getOutputTarget().isBlank()) {
            return WorkflowConfig.normalizeOutputTarget(request.getOutputTarget());
        }
        return NodeSupport.resolveOutputTarget(workflowConfig);
    }

    private boolean isGeoGebraTarget(CodeFixRequest request) {
        return WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equals(resolveOutputTarget(request));
    }

}
