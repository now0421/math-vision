package com.automanim.node;

import com.automanim.config.PipelineConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.PipelineKeys;
import com.automanim.model.RenderResult;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.service.ManimRendererService;
import com.automanim.service.ManimRendererService.RenderAttemptResult;
import com.automanim.util.JsonUtils;
import com.automanim.util.PromptTemplates;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Stage 3: Code Rendering — renders Manim code to video with automatic
 * error-driven retry.
 */
public class RenderNode extends PocketFlow.Node<RenderNode.RenderInput, RenderResult, String> {

    private static final Logger log = LoggerFactory.getLogger(RenderNode.class);
    private static final int MAX_TRACEBACK_LINES = 30;

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

    private final ManimRendererService renderer = new ManimRendererService();
    private AiClient aiClient;

    public RenderNode() {
        super(1, 0); // no PocketFlow retry — we handle retry internally
    }

    /**
     * Input bundle for the render stage.
     */
    public static class RenderInput {
        private final CodeResult codeResult;
        private final PipelineConfig config;
        private final Path outputDir;

        public RenderInput(CodeResult codeResult, PipelineConfig config, Path outputDir) {
            this.codeResult = codeResult;
            this.config = config;
            this.outputDir = outputDir;
        }

        public CodeResult codeResult() { return codeResult; }
        public PipelineConfig config() { return config; }
        public Path outputDir() { return outputDir; }
    }

    @Override
    public RenderInput prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(PipelineKeys.AI_CLIENT);
        CodeResult codeResult = (CodeResult) ctx.get(PipelineKeys.CODE_RESULT);
        PipelineConfig config = (PipelineConfig) ctx.get(PipelineKeys.CONFIG);
        Path outputDir = (Path) ctx.get(PipelineKeys.OUTPUT_DIR);
        return new RenderInput(codeResult, config, outputDir);
    }

    @Override
    public RenderResult exec(RenderInput input) {
        CodeResult codeResult = input.codeResult();
        PipelineConfig config = input.config();
        Path outputDir = input.outputDir();

        log.info("=== Stage 3: Code Rendering ===");

        // Skip if render disabled or no code
        if (config != null && !config.isRenderEnabled()) {
            log.info("Rendering disabled by config");
            return RenderResult.skipped(codeResult.getSceneName(), "Render disabled");
        }
        if (!codeResult.hasCode()) {
            log.warn("No code to render");
            return RenderResult.skipped(codeResult.getSceneName(), "No code");
        }

        String quality = config != null ? config.getRenderQuality() : "low";
        int maxRetries = config != null ? config.getRenderMaxRetries() : 4;

        String currentCode = codeResult.getManimCode();
        String sceneName = codeResult.getSceneName();
        String lastError = "";
        int attempts = 0;
        int toolCalls = 0;

        List<String> fixHistory = new ArrayList<>();

        // Render-fix loop
        for (int i = 0; i <= maxRetries; i++) {
            attempts++;
            log.info("  Render attempt {}/{}", attempts, maxRetries + 1);

            RenderAttemptResult renderResult = renderer.render(
                    currentCode, sceneName, quality, outputDir
            );

            if (renderResult.success()) {
                log.info("  Render succeeded on attempt {}", attempts);
                RenderResult result = new RenderResult();
                result.setSuccess(true);
                result.setFinalCode(currentCode);
                result.setSceneName(sceneName);
                result.setVideoPath(renderResult.videoPath());
                result.setAttempts(attempts);
                result.setToolCalls(toolCalls);
                return result;
            }

            lastError = renderResult.stderr();
            log.warn("  Render failed (attempt {}): {}", attempts,
                    lastError.length() > 200 ? lastError.substring(0, 200) + "..." : lastError);

            // Don't fix on last attempt
            if (i >= maxRetries) break;

            // Error classification: skip AI fix for non-code errors
            if (!isCodeError(lastError)) {
                log.warn("  Non-code error detected (environment issue), stopping retries");
                break;
            }

            String focusedError = extractTraceback(lastError);

            String errorSignature = focusedError.length() > 200
                    ? focusedError.substring(0, 200) : focusedError;
            if (fixHistory.contains(errorSignature)) {
                log.warn("  Same error seen before (fix history), stopping retries");
                break;
            }
            fixHistory.add(errorSignature);

            // AI fix
            try {
                String fixPrompt = PromptTemplates.renderFixUserPrompt(currentCode, focusedError);
                String fixResponse = aiClient.chat(fixPrompt, PromptTemplates.RENDER_FIX_SYSTEM);
                toolCalls++;

                String fixedCode = JsonUtils.extractCodeBlock(fixResponse);
                if (fixedCode.isBlank() || fixedCode.equals(currentCode)) {
                    log.warn("  AI fix produced empty or identical code, stopping retries");
                    break;
                }
                currentCode = fixedCode;
                log.info("  AI fix applied ({} lines)", fixedCode.split("\n").length);
            } catch (Exception e) {
                log.error("  AI fix failed: {}", e.getMessage());
                break;
            }
        }

        // All retries exhausted
        log.warn("Render failed after {} attempts", attempts);
        RenderResult result = new RenderResult();
        result.setSuccess(false);
        result.setFinalCode(currentCode);
        result.setSceneName(sceneName);
        result.setAttempts(attempts);
        result.setLastError(lastError);
        result.setToolCalls(toolCalls);
        return result;
    }

    @Override
    public String post(Map<String, Object> ctx, RenderInput input, RenderResult result) {
        ctx.put(PipelineKeys.RENDER_RESULT, result);

        Path outputDir = input.outputDir();
        if (outputDir != null) {
            FileOutputService.saveRenderResult(outputDir, result);

            CodeResult codeResult = input.codeResult();
            if (result.getFinalCode() != null
                    && !result.getFinalCode().equals(codeResult.getManimCode())) {
                log.info("Final code differs from original (AI fixes applied)");
            }
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

    private String extractTraceback(String stderr) {
        if (stderr == null || stderr.isBlank()) return "";

        // Find the last "Traceback" occurrence for the most relevant error
        int tbStart = stderr.lastIndexOf("Traceback (most recent call last)");
        if (tbStart < 0) {
            // No traceback found — return last MAX_TRACEBACK_LINES lines
            String[] lines = stderr.split("\n");
            if (lines.length <= MAX_TRACEBACK_LINES) return stderr;
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - MAX_TRACEBACK_LINES; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString();
        }

        String traceback = stderr.substring(tbStart);
        String[] lines = traceback.split("\n");
        if (lines.length <= MAX_TRACEBACK_LINES) return traceback;

        // Truncate: keep first few lines and last lines for context
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("  ... (").append(lines.length - MAX_TRACEBACK_LINES).append(" lines truncated) ...\n");
        for (int i = lines.length - (MAX_TRACEBACK_LINES - 5); i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }
}
