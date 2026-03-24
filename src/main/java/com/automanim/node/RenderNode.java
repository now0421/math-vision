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
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.service.ManimRendererService;
import com.automanim.service.ManimRendererService.RenderAttemptResult;
import com.automanim.util.JsonUtils;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 4: Code Rendering - renders Manim code to video and routes code fixes
 * through the shared CodeFixNode when needed.
 */
public class RenderNode extends PocketFlow.Node<RenderNode.RenderInput, RenderResult, String> {

    private static final Logger log = LoggerFactory.getLogger(RenderNode.class);
    private static final int MAX_TRACEBACK_LINES = 30;
    private static final int TRACEBACK_CONTEXT_RADIUS = 4;
    private static final int MAX_STDOUT_ERROR_LINES = 12;
    private static final int MAX_CONSECUTIVE_SAME_ERROR_ATTEMPTS = 3;
    private static final String TRACEBACK_MARKER = "Traceback (most recent call last)";
    private static final String GENERATED_SCENE_FILE = "scene_render.py";
    private static final Pattern ANY_SCENE_CLASS = Pattern.compile("class\\s+[^\\s(]+\\s*\\((.*?Scene.*?)\\)");
    private static final Pattern ERROR_SIGNATURE_PATTERN = Pattern.compile(
            "\\b(?:[A-Za-z_][A-Za-z0-9_]*Error|[A-Za-z_][A-Za-z0-9_]*Exception)\\s*:\\s*.+");

    // Error patterns that indicate non-code (environment) errors
    private static final List<Pattern> NON_CODE_ERROR_PATTERNS = Arrays.asList(
            Pattern.compile("(?i)no module named"),
            Pattern.compile("(?i)command not found"),
            Pattern.compile("(?i)permission denied"),
            Pattern.compile("(?i)out of memory"),
            Pattern.compile("(?i)disk quota"),
            Pattern.compile("(?i)segmentation fault"),
            Pattern.compile("(?i)killed"),
            Pattern.compile("(?i)cannot allocate memory"),
            Pattern.compile("(?i)ffmpeg.*not found"),
            Pattern.compile("(?i)latex.*not found"),
            Pattern.compile("(?i)dvisvgm.*not found")
    );

    private final ManimRendererService renderer;
    private AiClient aiClient;

    public RenderNode() {
        this(new ManimRendererService());
    }

    RenderNode(ManimRendererService renderer) {
        super(1, 0);
        this.renderer = renderer;
    }

    /**
     * Input bundle for the render stage.
     */
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
            retryState.fixToolCalls += previousFixResult.getToolCalls();
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
        retryState.requestFix = false;
        retryState.pendingFocusedError = null;

        log.info("=== Stage 4: Code Rendering ===");

        if (config != null && !config.isRenderEnabled()) {
            log.info("Rendering disabled by config");
            retryState.reset();
            return RenderResult.skipped(codeResult != null ? codeResult.getSceneName() : "MainScene", "Render disabled");
        }
        if (codeResult == null || !codeResult.hasCode()) {
            log.warn("No code to render");
            retryState.reset();
            return RenderResult.skipped(codeResult != null ? codeResult.getSceneName() : "MainScene", "No code");
        }
        if (codeEvaluationResult != null && !codeEvaluationResult.isApprovedForRender()) {
            String reason = codeEvaluationResult.getGateReason();
            log.warn("Code evaluation reported advisory issues; continuing to render anyway: {}",
                    reason != null && !reason.isBlank() ? reason : "No additional detail");
        }

        String currentCode = JsonUtils.extractCodeBlock(codeResult.getManimCode());
        if (currentCode == null || currentCode.isBlank()) {
            currentCode = codeResult.getManimCode();
        }
        currentCode = enforceMainSceneClassName(currentCode);
        codeResult.setManimCode(currentCode);

        String sceneName = "MainScene";
        codeResult.setSceneName(sceneName);

        if (input.previousFixResult() != null && !input.previousFixResult().isApplied()) {
            log.warn("Previous shared code-fix pass produced no meaningful change, stopping render retries");
            return failureResult(
                    currentCode,
                    sceneName,
                    retryState.attempts,
                    input.previousFixResult().getFailureReason(),
                    null,
                    retryState.fixToolCalls
            );
        }

        String quality = config != null ? config.getRenderQuality() : "low";
        int maxRetries = config != null ? config.getRenderMaxRetries() : 4;
        int attemptNumber = retryState.attempts + 1;
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
            result.setToolCalls(retryState.fixToolCalls);
            return result;
        }

        retryState.attempts = attemptNumber;
        String lastError = combineErrorStreams(renderAttempt.stdout(), renderAttempt.stderr());
        log.warn("  Render failed (attempt {}): {}", attemptNumber,
                lastError.length() > 200 ? lastError.substring(0, 200) + "..." : lastError);

        if (attemptNumber >= maxRetries + 1) {
            log.warn("Render failed after {} attempts", attemptNumber);
            return failureResult(currentCode, sceneName, attemptNumber, lastError, geometryPath, retryState.fixToolCalls);
        }

        if (!isCodeError(lastError)) {
            log.warn("  Non-code error detected (environment issue), stopping retries");
            return failureResult(currentCode, sceneName, attemptNumber, lastError, geometryPath, retryState.fixToolCalls);
        }

        String focusedError = extractFocusedError(renderAttempt);
        String errorSignature = summarizeErrorSignature(focusedError);
        if (errorSignature.equals(retryState.previousErrorSignature)) {
            retryState.consecutiveSameErrorCount++;
        } else {
            retryState.consecutiveSameErrorCount = 1;
        }

        if (retryState.consecutiveSameErrorCount >= MAX_CONSECUTIVE_SAME_ERROR_ATTEMPTS) {
            log.warn("  Same error repeated {} times in a row, stopping retries",
                    retryState.consecutiveSameErrorCount);
            return failureResult(currentCode, sceneName, attemptNumber, lastError, geometryPath, retryState.fixToolCalls);
        }

        retryState.previousErrorSignature = errorSignature;
        retryState.fixHistory.add(errorSignature);
        retryState.requestFix = true;
        retryState.pendingFocusedError = focusedError;
        return failureResult(currentCode, sceneName, attemptNumber, lastError, geometryPath, retryState.fixToolCalls);
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

        if (input.retryState().requestFix) {
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
        request.setExpectedSceneName("MainScene");
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

    private boolean isCodeError(String error) {
        if (error == null || error.isBlank()) return false;

        for (Pattern p : NON_CODE_ERROR_PATTERNS) {
            if (p.matcher(error).find()) {
                return false;
            }
        }
        return true;
    }

    private String extractFocusedError(RenderAttemptResult renderResult) {
        String stdoutSummary = extractStdoutErrors(renderResult.stdout());
        String tracebackSummary = extractTraceback(renderResult.stderr());

        List<String> sections = new ArrayList<>();
        if (!stdoutSummary.isBlank()) {
            sections.add("=== stdout highlights ===\n" + stdoutSummary);
        }
        if (!tracebackSummary.isBlank()) {
            sections.add("=== stderr traceback ===\n" + tracebackSummary);
        }

        if (!sections.isEmpty()) {
            return String.join("\n\n", sections);
        }

        return tailLines(combineErrorStreams(renderResult.stdout(), renderResult.stderr()), MAX_TRACEBACK_LINES);
    }

    private String combineErrorStreams(String stdout, String stderr) {
        List<String> sections = new ArrayList<>();
        if (stdout != null && !stdout.isBlank()) {
            sections.add("[stdout]\n" + stdout.strip());
        }
        if (stderr != null && !stderr.isBlank()) {
            sections.add("[stderr]\n" + stderr.strip());
        }
        return String.join("\n\n", sections);
    }

    private String extractTraceback(String stderr) {
        if (stderr == null || stderr.isBlank()) return "";

        int tbStart = stderr.lastIndexOf(TRACEBACK_MARKER);
        if (tbStart < 0) {
            return tailLines(stderr, MAX_TRACEBACK_LINES);
        }

        List<String> lines = Arrays.asList(stderr.substring(tbStart).split("\\R"));
        if (lines.size() <= MAX_TRACEBACK_LINES) {
            return String.join("\n", lines).strip();
        }

        TreeSet<Integer> selected = new TreeSet<>();
        selected.add(0);

        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(GENERATED_SCENE_FILE)) {
                addWindow(selected, i, lines.size(), TRACEBACK_CONTEXT_RADIUS, TRACEBACK_CONTEXT_RADIUS + 2);
            }
        }

        for (int i = Math.max(0, lines.size() - 4); i < lines.size(); i++) {
            selected.add(i);
        }

        if (selected.size() <= 1) {
            return tailLines(String.join("\n", lines), MAX_TRACEBACK_LINES);
        }

        return joinSelectedLines(lines, selected);
    }

    private String extractStdoutErrors(String stdout) {
        if (stdout == null || stdout.isBlank()) return "";

        List<String> lines = Arrays.asList(stdout.split("\\R"));
        TreeSet<Integer> selected = new TreeSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.matches(".*(?i)(\\bERROR\\b|exception|traceback|not in the script|latex compilation error|context of error).*")) {
                addWindow(selected, i, lines.size(), 1, 3);
            }
        }

        if (selected.isEmpty()) {
            return "";
        }

        List<String> picked = new ArrayList<>();
        for (Integer index : selected) {
            String line = lines.get(index);
            if (line.contains("%|") || line.trim().startsWith("Animation ")) {
                continue;
            }
            picked.add(line);
            if (picked.size() >= MAX_STDOUT_ERROR_LINES) {
                break;
            }
        }
        return String.join("\n", picked).strip();
    }

    private void addWindow(TreeSet<Integer> selected, int center, int lineCount, int before, int after) {
        int start = Math.max(0, center - before);
        int end = Math.min(lineCount - 1, center + after);
        for (int i = start; i <= end; i++) {
            selected.add(i);
        }
    }

    private String joinSelectedLines(List<String> lines, TreeSet<Integer> selected) {
        StringBuilder sb = new StringBuilder();
        int previous = -2;
        for (Integer index : selected) {
            if (previous >= 0 && index > previous + 1) {
                sb.append("...\n");
            }
            sb.append(lines.get(index)).append("\n");
            previous = index;
        }
        return sb.toString().strip();
    }

    private String tailLines(String text, int maxLines) {
        if (text == null || text.isBlank()) return "";

        String[] lines = text.split("\\R");
        if (lines.length <= maxLines) {
            return text.strip();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - maxLines; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString().strip();
    }

    private String summarizeErrorSignature(String focusedError) {
        if (focusedError == null || focusedError.isBlank()) {
            return "";
        }

        String[] lines = focusedError.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            Matcher matcher = ERROR_SIGNATURE_PATTERN.matcher(line);
            if (matcher.find()) {
                String signature = matcher.group().trim();
                return signature.length() > 200 ? signature.substring(0, 200) : signature;
            }
        }

        String normalized = focusedError.replaceAll("\\s+", " ").trim();
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
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

    private String enforceMainSceneClassName(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        return ANY_SCENE_CLASS.matcher(code)
                .replaceFirst("class MainScene($1)");
    }

    static final class RenderRetryState {
        private int attempts;
        private int fixToolCalls;
        private boolean requestFix;
        private String previousErrorSignature;
        private int consecutiveSameErrorCount;
        private final List<String> fixHistory = new ArrayList<>();
        private String pendingFocusedError;

        void reset() {
            attempts = 0;
            fixToolCalls = 0;
            requestFix = false;
            previousErrorSignature = null;
            consecutiveSameErrorCount = 0;
            fixHistory.clear();
            pendingFocusedError = null;
        }
    }
}
