package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeEvaluationResult;
import com.automanim.model.CodeFixRequest;
import com.automanim.model.CodeFixResult;
import com.automanim.model.CodeFixSource;
import com.automanim.model.CodeResult;
import com.automanim.model.RenderResult;
import com.automanim.model.WorkflowActions;
import com.automanim.model.WorkflowKeys;
import com.automanim.node.support.FixRetryState;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.service.ManimRendererService;
import com.automanim.service.ManimRendererService.RenderAttemptResult;
import com.automanim.util.CodeUtils;
import com.automanim.util.ErrorSummarizer;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/**
 * Stage 4: Code Rendering - renders Manim code to video and routes code fixes
 * through the shared CodeFixNode when needed.
 */
public class RenderNode extends PocketFlow.Node<RenderNode.RenderInput, RenderResult, String> {

    private static final Logger log = LoggerFactory.getLogger(RenderNode.class);

    private final ManimRendererService renderer;
    private AiClient aiClient;

    public RenderNode() {
        this(new ManimRendererService());
    }

    RenderNode(ManimRendererService renderer) {
        super(1, 0);
        this.renderer = renderer;
    }

    public static class RenderInput {
        private final CodeResult codeResult;
        private final CodeEvaluationResult codeEvaluationResult;
        private final WorkflowConfig config;
        private final Path outputDir;
        private final CodeFixResult previousFixResult;
        private final RenderRetryState retryState;

        public RenderInput(CodeResult codeResult,
                           CodeEvaluationResult codeEvaluationResult,
                           WorkflowConfig config,
                           Path outputDir,
                           CodeFixResult previousFixResult,
                           RenderRetryState retryState) {
            this.codeResult = codeResult;
            this.codeEvaluationResult = codeEvaluationResult;
            this.config = config;
            this.outputDir = outputDir;
            this.previousFixResult = previousFixResult;
            this.retryState = retryState;
        }

        public CodeResult codeResult() { return codeResult; }
        public CodeEvaluationResult codeEvaluationResult() { return codeEvaluationResult; }
        public WorkflowConfig config() { return config; }
        public Path outputDir() { return outputDir; }
        public CodeFixResult previousFixResult() { return previousFixResult; }
        public RenderRetryState retryState() { return retryState; }
    }

    @Override
    public RenderInput prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        ctx.remove(WorkflowKeys.SCENE_EVALUATION_RESULT);

        RenderRetryState retryState = (RenderRetryState) ctx.get(WorkflowKeys.RENDER_RETRY_STATE);
        if (retryState == null) {
            retryState = new RenderRetryState();
            ctx.put(WorkflowKeys.RENDER_RETRY_STATE, retryState);
        }

        CodeFixResult previousFixResult = consumeRetryRenderFixResult(ctx);
        if (previousFixResult != null) {
            retryState.addFixToolCalls(previousFixResult.getToolCalls());
        }

        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        CodeEvaluationResult codeEvaluationResult =
                (CodeEvaluationResult) ctx.get(WorkflowKeys.CODE_EVALUATION_RESULT);
        WorkflowConfig config = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        deleteStaleSceneEvaluationArtifact(outputDir);
        return new RenderInput(codeResult, codeEvaluationResult, config, outputDir, previousFixResult, retryState);
    }

    @Override
    public RenderResult exec(RenderInput input) {
        CodeResult codeResult = input.codeResult();
        CodeEvaluationResult codeEvaluationResult = input.codeEvaluationResult();
        WorkflowConfig config = input.config();
        Path outputDir = input.outputDir();
        RenderRetryState retryState = input.retryState();
        retryState.setRequestFix(false);
        retryState.pendingFocusedError = null;

        log.info("=== Stage 4: Code Rendering ===");

        if (config != null && !config.isRenderEnabled()) {
            log.info("Rendering disabled by config");
            retryState.reset();
            return RenderResult.skipped(codeResult != null ? codeResult.getSceneName() : CodeUtils.EXPECTED_SCENE_NAME, "Render disabled");
        }
        if (codeResult == null || !codeResult.hasCode()) {
            log.warn("No code to render");
            retryState.reset();
            return RenderResult.skipped(codeResult != null ? codeResult.getSceneName() : CodeUtils.EXPECTED_SCENE_NAME, "No code");
        }
        if (codeEvaluationResult != null && !codeEvaluationResult.isApprovedForRender()) {
            String reason = codeEvaluationResult.getGateReason();
            log.warn("Code evaluation reported advisory issues; continuing to render anyway: {}",
                    reason != null && !reason.isBlank() ? reason : "No additional detail");
        }

        String currentCode = CodeUtils.extractCode(codeResult.getManimCode());
        if (currentCode == null || currentCode.isBlank()) {
            currentCode = codeResult.getManimCode();
        }
        currentCode = CodeUtils.enforceMainSceneName(currentCode);
        codeResult.setManimCode(currentCode);

        String sceneName = CodeUtils.EXPECTED_SCENE_NAME;
        codeResult.setSceneName(sceneName);

        if (input.previousFixResult() != null && !input.previousFixResult().isApplied()) {
            log.warn("Previous shared code-fix pass produced no meaningful change, stopping render retries");
            return failureResult(
                    currentCode,
                    sceneName,
                    retryState.getAttempts(),
                    input.previousFixResult().getFailureReason(),
                    null,
                    retryState.getFixToolCalls()
            );
        }

        String quality = config != null ? config.getRenderQuality() : "low";
        int maxRetries = config != null ? config.getRenderMaxRetries() : 4;
        int attemptNumber = retryState.getAttempts() + 1;
        log.info("  Render attempt {}/{}", attemptNumber, maxRetries + 1);

        RenderAttemptResult renderAttempt = renderer.render(currentCode, sceneName, quality, outputDir);
        String geometryPath = renderAttempt.geometryPath();

        if (renderAttempt.success()) {
            log.info("  Render succeeded on attempt {}", attemptNumber);
            retryState.reset();
            RenderResult result = new RenderResult();
            result.setSuccess(true);
            result.setFinalCode(currentCode);
            result.setSceneName(sceneName);
            result.setVideoPath(renderAttempt.videoPath());
            result.setGeometryPath(geometryPath);
            result.setAttempts(attemptNumber);
            result.setToolCalls(retryState.getFixToolCalls());
            return result;
        }

        retryState.setAttempts(attemptNumber);
        String lastError = ErrorSummarizer.combineErrorStreams(renderAttempt.stdout(), renderAttempt.stderr());
        log.warn("  Render failed (attempt {}): {}", attemptNumber,
                lastError.length() > 200 ? lastError.substring(0, 200) + "..." : lastError);

        if (attemptNumber >= maxRetries + 1) {
            log.warn("Render failed after {} attempts", attemptNumber);
            return failureResult(currentCode, sceneName, attemptNumber, lastError, geometryPath, retryState.getFixToolCalls());
        }

        if (ErrorSummarizer.isEnvironmentError(lastError)) {
            log.warn("  Non-code error detected (environment issue), stopping retries");
            return failureResult(currentCode, sceneName, attemptNumber, lastError, geometryPath, retryState.getFixToolCalls());
        }

        String focusedError = ErrorSummarizer.extractFocusedError(renderAttempt.stdout(), renderAttempt.stderr());
        String errorSignature = ErrorSummarizer.summarizeSignature(focusedError);
        retryState.previousErrorSignature = errorSignature;
        retryState.fixHistory.add(errorSignature);
        retryState.setRequestFix(true);
        retryState.pendingFocusedError = focusedError;
        return failureResult(currentCode, sceneName, attemptNumber, lastError, geometryPath, retryState.getFixToolCalls());
    }

    @Override
    public String post(Map<String, Object> ctx, RenderInput input, RenderResult result) {
        ctx.put(WorkflowKeys.RENDER_RESULT, result);

        Path outputDir = input.outputDir();
        if (outputDir != null) {
            FileOutputService.saveRenderResult(outputDir, result);

            CodeResult codeResult = input.codeResult();
            if (result.getFinalCode() != null
                    && !result.getFinalCode().equals(codeResult.getManimCode())) {
                log.info("Final code differs from current workflow code");
            }
        }

        if (input.retryState().isRequestFix()) {
            ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildRenderFixRequest(input));
            return WorkflowActions.FIX_CODE;
        }

        input.retryState().reset();
        return null;
    }

    private CodeFixRequest buildRenderFixRequest(RenderInput input) {
        CodeResult codeResult = input.codeResult();
        RenderRetryState retryState = input.retryState();

        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.RENDER_FAILURE);
        request.setReturnAction(WorkflowActions.RETRY_RENDER);
        request.setCode(codeResult.getManimCode());
        request.setErrorReason(retryState.pendingFocusedError);
        request.setTargetConcept(codeResult.getTargetConcept());
        request.setTargetDescription(codeResult.getTargetDescription());
        request.setSceneName(codeResult.getSceneName());
        request.setExpectedSceneName(CodeUtils.EXPECTED_SCENE_NAME);
        request.setFixHistory(new ArrayList<>(retryState.fixHistory));
        return request;
    }

    private RenderResult failureResult(String code,
                                       String sceneName,
                                       int attempts,
                                       String error,
                                       String geometryPath,
                                       int toolCalls) {
        RenderResult result = new RenderResult();
        result.setSuccess(false);
        result.setFinalCode(code);
        result.setSceneName(sceneName);
        result.setGeometryPath(geometryPath);
        result.setAttempts(attempts);
        result.setLastError(error);
        result.setToolCalls(toolCalls);
        return result;
    }

    private CodeFixResult consumeRetryRenderFixResult(Map<String, Object> ctx) {
        CodeFixResult result = (CodeFixResult) ctx.get(WorkflowKeys.CODE_FIX_RESULT);
        if (result != null
                && WorkflowActions.RETRY_RENDER.equals(result.getReturnAction())
                && (result.getSource() == CodeFixSource.RENDER_FAILURE
                || result.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION)) {
            ctx.remove(WorkflowKeys.CODE_FIX_RESULT);
            return result;
        }
        return null;
    }

    private void deleteStaleSceneEvaluationArtifact(Path outputDir) {
        if (outputDir == null) {
            return;
        }
        try {
            Files.deleteIfExists(outputDir.resolve("6_scene_evaluation.json"));
        } catch (IOException e) {
            log.debug("Could not clear stale scene evaluation artifact: {}", e.getMessage());
        }
    }

    static final class RenderRetryState extends FixRetryState {
        String previousErrorSignature;
        final java.util.List<String> fixHistory = new ArrayList<>();
        String pendingFocusedError;

        @Override
        public void reset() {
            super.reset();
            previousErrorSignature = null;
            fixHistory.clear();
            pendingFocusedError = null;
        }
    }
}
