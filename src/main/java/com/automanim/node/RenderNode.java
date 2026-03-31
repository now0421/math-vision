package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeEvaluationResult;
import com.automanim.model.CodeFixRequest;
import com.automanim.model.CodeFixResult;
import com.automanim.model.CodeFixSource;
import com.automanim.model.CodeResult;
import com.automanim.model.Narrative;
import com.automanim.model.RenderResult;
import com.automanim.model.WorkflowActions;
import com.automanim.model.WorkflowKeys;
import com.automanim.node.support.FixRetryState;
import com.automanim.prompt.StoryboardJsonBuilder;
import com.automanim.service.FileOutputService;
import com.automanim.service.GeoGebraRenderService;
import com.automanim.service.ManimRendererService;
import com.automanim.service.ManimRendererService.RenderAttemptResult;
import com.automanim.util.ErrorSummarizer;
import com.automanim.util.GeoGebraCodeUtils;
import com.automanim.util.ManimCodeUtils;
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
    private final GeoGebraRenderService geoGebraRenderer;

    public RenderNode() {
        this(new ManimRendererService(), new GeoGebraRenderService());
    }

    RenderNode(ManimRendererService renderer) {
        this(renderer, new GeoGebraRenderService());
    }

    RenderNode(ManimRendererService renderer, GeoGebraRenderService geoGebraRenderer) {
        super(1, 0);
        this.renderer = renderer;
        this.geoGebraRenderer = geoGebraRenderer;
    }

    public static class RenderInput {
        private final CodeResult codeResult;
        private final CodeEvaluationResult codeEvaluationResult;
        private final Narrative narrative;
        private final WorkflowConfig config;
        private final Path outputDir;
        private final CodeFixResult previousFixResult;
        private final RenderRetryState retryState;

        public RenderInput(CodeResult codeResult,
                           CodeEvaluationResult codeEvaluationResult,
                           Narrative narrative,
                           WorkflowConfig config,
                           Path outputDir,
                           CodeFixResult previousFixResult,
                           RenderRetryState retryState) {
            this.codeResult = codeResult;
            this.codeEvaluationResult = codeEvaluationResult;
            this.narrative = narrative;
            this.config = config;
            this.outputDir = outputDir;
            this.previousFixResult = previousFixResult;
            this.retryState = retryState;
        }

        public CodeResult codeResult() { return codeResult; }
        public CodeEvaluationResult codeEvaluationResult() { return codeEvaluationResult; }
        public Narrative narrative() { return narrative; }
        public WorkflowConfig config() { return config; }
        public Path outputDir() { return outputDir; }
        public CodeFixResult previousFixResult() { return previousFixResult; }
        public RenderRetryState retryState() { return retryState; }
    }

    @Override
    public RenderInput prep(Map<String, Object> ctx) {
        ctx.remove(WorkflowKeys.SCENE_EVALUATION_RESULT);

        RenderRetryState retryState = (RenderRetryState) ctx.get(WorkflowKeys.RENDER_RETRY_STATE);
        if (retryState == null) {
            retryState = new RenderRetryState();
            ctx.put(WorkflowKeys.RENDER_RETRY_STATE, retryState);
        }

        CodeFixResult previousFixResult = consumeRetryRenderFixResult(ctx);
        if (previousFixResult != null) {
            retryState.addFixToolCalls(previousFixResult.getToolCalls());
            String attemptSummary = summarizeFixAttempt(previousFixResult);
            if (attemptSummary != null && !attemptSummary.isBlank()) {
                retryState.fixHistory.add(attemptSummary);
            }
        }

        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        CodeEvaluationResult codeEvaluationResult =
                (CodeEvaluationResult) ctx.get(WorkflowKeys.CODE_EVALUATION_RESULT);
        Narrative narrative = (Narrative) ctx.get(WorkflowKeys.NARRATIVE);
        WorkflowConfig config = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        deleteStaleSceneEvaluationArtifact(outputDir);
        return new RenderInput(codeResult, codeEvaluationResult, narrative, config, outputDir, previousFixResult, retryState);
    }

    @Override
    public RenderResult exec(RenderInput input) {
        RenderRetryState retryState = input.retryState();
        retryState.setRequestFix(false);
        retryState.pendingFocusedError = null;

        log.info("=== Stage 4: Code Rendering ===");

        CodeResult codeResult = input.codeResult();
        WorkflowConfig config = input.config();
        if (config != null && !config.isRenderEnabled()) {
            log.info("Rendering disabled by config");
            retryState.reset();
            return skippedResult(codeResult, config, "Render disabled");
        }
        if (codeResult == null || !codeResult.hasCode()) {
            log.warn("No code to render");
            retryState.reset();
            return skippedResult(codeResult, config, "No code");
        }

        return isGeoGebraTarget(input)
                ? renderGeoGebra(input)
                : renderManim(input);
    }

    private RenderResult renderManim(RenderInput input) {
        CodeResult codeResult = input.codeResult();
        CodeEvaluationResult codeEvaluationResult = input.codeEvaluationResult();
        WorkflowConfig config = input.config();
        Path outputDir = input.outputDir();
        RenderRetryState retryState = input.retryState();

        if (codeEvaluationResult != null && !codeEvaluationResult.isApprovedForRender()) {
            String reason = codeEvaluationResult.getGateReason();
            log.warn("Code evaluation reported advisory issues; continuing to render anyway: {}",
                    reason != null && !reason.isBlank() ? reason : "No additional detail");
        }

        String currentCode = ManimCodeUtils.extractCode(codeResult.getCode());
        if (currentCode == null || currentCode.isBlank()) {
            currentCode = codeResult.getCode();
        }
        currentCode = ManimCodeUtils.enforceMainSceneName(currentCode);
        codeResult.setCode(currentCode);

        String sceneName = ManimCodeUtils.EXPECTED_SCENE_NAME;
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
            result.setArtifactPath(renderAttempt.videoPath());
            result.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_MANIM);
            result.setArtifactType("video");
            result.setGeometryPath(geometryPath);
            result.setAttempts(attemptNumber);
            result.setToolCalls(retryState.getFixToolCalls());
            return result;
        }

        retryState.setAttempts(attemptNumber);
        String lastError = ErrorSummarizer.combineErrorStreams(renderAttempt.stdout(), renderAttempt.stderr());
        log.warn("  Render failed (attempt {}): {}", attemptNumber, abbreviateError(lastError));

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
        retryState.setRequestFix(true);
        retryState.pendingFocusedError = focusedError;
        return failureResult(currentCode, sceneName, attemptNumber, lastError, geometryPath, retryState.getFixToolCalls());
    }

    private RenderResult renderGeoGebra(RenderInput input) {
        CodeResult codeResult = input.codeResult();
        WorkflowConfig config = input.config();
        RenderRetryState retryState = input.retryState();
        String preparedCode = GeoGebraCodeUtils.enrichWithSceneButtons(
                codeResult.getCode(),
                input.narrative() != null ? input.narrative().getStoryboard() : null);
        codeResult.setCode(preparedCode);
        String sceneName = codeResult.getSceneName() != null ? codeResult.getSceneName() : "GeoGebraFigure";
        codeResult.setSceneName(sceneName);

        if (input.previousFixResult() != null && !input.previousFixResult().isApplied()) {
            log.warn("Previous shared code-fix pass produced no meaningful GeoGebra change, stopping render retries");
            return geoGebraFailureResult(
                    preparedCode,
                    sceneName,
                    retryState.getAttempts(),
                    input.previousFixResult().getFailureReason(),
                    null,
                    retryState.getFixToolCalls()
            );
        }

        int maxRetries = config != null ? config.getRenderMaxRetries() : 4;
        int attemptNumber = retryState.getAttempts() + 1;
        log.info("  GeoGebra validation attempt {}/{}", attemptNumber, maxRetries + 1);

        GeoGebraRenderService.RenderAttemptResult renderAttempt = geoGebraRenderer.render(
                preparedCode,
                sceneName,
                input.outputDir());

        if (renderAttempt.success()) {
            retryState.reset();
            RenderResult result = new RenderResult();
            result.setSuccess(true);
            result.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
            result.setArtifactType("geogebra_preview_html");
            result.setFinalCode(preparedCode);
            result.setSceneName(sceneName);
            result.setArtifactPath(renderAttempt.previewPath());
            result.setAttempts(attemptNumber);
            result.setToolCalls(retryState.getFixToolCalls());
            return result;
        }

        retryState.setAttempts(attemptNumber);
        String error = renderAttempt.error();
        log.warn("  GeoGebra validation failed (attempt {}): {}", attemptNumber, abbreviateError(error));

        if (attemptNumber >= maxRetries + 1) {
            log.warn("GeoGebra validation failed after {} attempts", attemptNumber);
            return geoGebraFailureResult(
                    preparedCode,
                    sceneName,
                    attemptNumber,
                    error,
                    renderAttempt.previewPath(),
                    retryState.getFixToolCalls()
            );
        }

        if (isGeoGebraEnvironmentError(error)) {
            log.warn("  GeoGebra validation failure looks environmental, stopping retries");
            return geoGebraFailureResult(
                    preparedCode,
                    sceneName,
                    attemptNumber,
                    error,
                    renderAttempt.previewPath(),
                    retryState.getFixToolCalls()
            );
        }

        retryState.setRequestFix(true);
        retryState.pendingFocusedError = error;
        return geoGebraFailureResult(
                preparedCode,
                sceneName,
                attemptNumber,
                error,
                renderAttempt.previewPath(),
                retryState.getFixToolCalls()
        );
    }

    @Override
    public String post(Map<String, Object> ctx, RenderInput input, RenderResult result) {
        ctx.put(WorkflowKeys.RENDER_RESULT, result);

        Path outputDir = input.outputDir();
        if (outputDir != null) {
            FileOutputService.saveRenderResult(outputDir, result);

            CodeResult codeResult = input.codeResult();
            if (result.getFinalCode() != null
                    && !result.getFinalCode().equals(codeResult.getCode())) {
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
        request.setCode(codeResult.getCode());
        request.setErrorReason(retryState.pendingFocusedError);
        request.setTargetConcept(codeResult.getTargetConcept());
        request.setTargetDescription(codeResult.getTargetDescription());
        request.setSceneName(codeResult.getSceneName());
        request.setExpectedSceneName(codeResult.isGeoGebraTarget()
                ? (codeResult.getSceneName() != null ? codeResult.getSceneName() : "GeoGebraFigure")
                : ManimCodeUtils.EXPECTED_SCENE_NAME);
        request.setStoryboardJson(input.narrative() != null && input.narrative().hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(input.narrative().getStoryboard())
                : "{\"scenes\":[]}");
        request.setFixHistory(new ArrayList<>(retryState.fixHistory));
        return request;
    }

    private RenderResult skippedResult(CodeResult codeResult,
                                       WorkflowConfig config,
                                       String reason) {
        RenderResult skipped = RenderResult.skipped(
                codeResult != null ? codeResult.getSceneName() : ManimCodeUtils.EXPECTED_SCENE_NAME,
                reason);

        String outputTarget = config != null
                ? config.getOutputTarget()
                : codeResult != null ? codeResult.getOutputTarget() : WorkflowConfig.OUTPUT_TARGET_MANIM;
        skipped.setOutputTarget(outputTarget);
        skipped.setArtifactType(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equals(outputTarget)
                ? "geogebra_preview_html"
                : "video");
        return skipped;
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
        result.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_MANIM);
        result.setArtifactType("video");
        result.setGeometryPath(geometryPath);
        result.setAttempts(attempts);
        result.setLastError(error);
        result.setToolCalls(toolCalls);
        return result;
    }

    private RenderResult geoGebraFailureResult(String code,
                                               String sceneName,
                                               int attempts,
                                               String error,
                                               String previewPath,
                                               int toolCalls) {
        RenderResult result = new RenderResult();
        result.setSuccess(false);
        result.setFinalCode(code);
        result.setSceneName(sceneName);
        result.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        result.setArtifactType("geogebra_preview_html");
        result.setArtifactPath(previewPath);
        result.setAttempts(attempts);
        result.setLastError(error);
        result.setToolCalls(toolCalls);
        return result;
    }

    private boolean isGeoGebraEnvironmentError(String error) {
        if (error == null || error.isBlank()) {
            return true;
        }

        String normalized = error.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("failed to launch chromium")
                || normalized.contains("install chromium")
                || normalized.contains("configured browser executable does not exist")
                || normalized.contains("output directory is unavailable")
                || normalized.contains("no executable geogebra commands were found");
    }

    private boolean isGeoGebraTarget(RenderInput input) {
        if (input == null) {
            return false;
        }
        if (input.config() != null) {
            return input.config().isGeoGebraTarget();
        }
        return input.codeResult() != null && input.codeResult().isGeoGebraTarget();
    }

    private String abbreviateError(String error) {
        if (error == null || error.length() <= 200) {
            return error;
        }
        return error.substring(0, 200) + "...";
    }

    private String summarizeFixAttempt(CodeFixResult result) {
        if (result == null) {
            return "";
        }

        String errorSignature = ErrorSummarizer.summarizeSignature(result.getErrorReason());
        String outcome;
        if (result.isApplied()) {
            outcome = "applied code change";
        } else {
            String failureReason = result.getFailureReason();
            outcome = (failureReason == null || failureReason.isBlank())
                    ? "no code change applied"
                    : failureReason;
        }

        if (errorSignature == null || errorSignature.isBlank()) {
            return outcome;
        }
        return "Tried fixing " + errorSignature + " -> " + outcome;
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

