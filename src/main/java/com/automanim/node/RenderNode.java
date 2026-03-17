package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.RenderResult;
import com.automanim.model.CodeEvaluationResult;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.service.ManimRendererService;
import com.automanim.service.ManimRendererService.RenderAttemptResult;
import com.automanim.util.JsonUtils;
import com.automanim.util.NodeConversationContext;
import com.automanim.util.PromptTemplates;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Stage 4: Code Rendering - renders Manim code to video with automatic
 * error-driven retry.
 */
public class RenderNode extends PocketFlow.Node<RenderNode.RenderInput, RenderResult, String> {

    private static final Logger log = LoggerFactory.getLogger(RenderNode.class);
    private static final int MAX_TRACEBACK_LINES = 30;
    private static final int TRACEBACK_CONTEXT_RADIUS = 4;
    private static final int MAX_STDOUT_ERROR_LINES = 12;
    private static final int MAX_CONSECUTIVE_SAME_ERROR_ATTEMPTS = 3;
    private static final String TRACEBACK_MARKER = "Traceback (most recent call last)";
    private static final String GENERATED_SCENE_FILE = "scene_render.py";

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
        super(1, 0); // no PocketFlow retry - we handle retry internally
    }

    /**
     * Input bundle for the render stage.
     */
    public static class RenderInput {
        private final CodeResult codeResult;
        private final CodeEvaluationResult codeEvaluationResult;
        private final WorkflowConfig config;
        private final Path outputDir;

        public RenderInput(CodeResult codeResult,
                           CodeEvaluationResult codeEvaluationResult,
                           WorkflowConfig config,
                           Path outputDir) {
            this.codeResult = codeResult;
            this.codeEvaluationResult = codeEvaluationResult;
            this.config = config;
            this.outputDir = outputDir;
        }

        public CodeResult codeResult() { return codeResult; }
        public CodeEvaluationResult codeEvaluationResult() { return codeEvaluationResult; }
        public WorkflowConfig config() { return config; }
        public Path outputDir() { return outputDir; }
    }

    @Override
    public RenderInput prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        CodeEvaluationResult codeEvaluationResult =
                (CodeEvaluationResult) ctx.get(WorkflowKeys.CODE_EVALUATION_RESULT);
        WorkflowConfig config = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        return new RenderInput(codeResult, codeEvaluationResult, config, outputDir);
    }

    @Override
    public RenderResult exec(RenderInput input) {
        CodeResult codeResult = input.codeResult();
        CodeEvaluationResult codeEvaluationResult = input.codeEvaluationResult();
        WorkflowConfig config = input.config();
        Path outputDir = input.outputDir();

        log.info("=== Stage 4: Code Rendering ===");

        // Skip if render disabled or no code
        if (config != null && !config.isRenderEnabled()) {
            log.info("Rendering disabled by config");
            return RenderResult.skipped(codeResult.getSceneName(), "Render disabled");
        }
        if (!codeResult.hasCode()) {
            log.warn("No code to render");
            return RenderResult.skipped(codeResult.getSceneName(), "No code");
        }
        if (codeEvaluationResult != null && !codeEvaluationResult.isApprovedForRender()) {
            String reason = codeEvaluationResult.getGateReason();
            log.warn("Code evaluation reported advisory issues; continuing to render anyway: {}",
                    reason != null && !reason.isBlank() ? reason : "No additional detail");
        }

        String quality = config != null ? config.getRenderQuality() : "low";
        int maxRetries = config != null ? config.getRenderMaxRetries() : 4;

        int maxInputTokens = (config != null && config.getModelConfig() != null)
                ? config.getModelConfig().getMaxInputTokens()
                : 131072;
        NodeConversationContext conversationContext = new NodeConversationContext(maxInputTokens);
        conversationContext.setSystemMessage(PromptTemplates.renderFixSystemPrompt(
                codeResult.getTargetConcept(),
                codeResult.getTargetDescription()));

        String currentCode = codeResult.getManimCode();
        String sceneName = codeResult.getSceneName();
        String lastError = "";
        int attempts = 0;
        int toolCalls = 0;

        List<String> fixHistory = new ArrayList<>();
        String previousErrorSignature = null;
        int consecutiveSameErrorCount = 0;

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

            lastError = combineErrorStreams(renderResult.stdout(), renderResult.stderr());
            log.warn("  Render failed (attempt {}): {}", attempts,
                    lastError.length() > 200 ? lastError.substring(0, 200) + "..." : lastError);

            // Don't fix on last attempt
            if (i >= maxRetries) break;

            // Error classification: skip AI fix for non-code errors
            if (!isCodeError(lastError)) {
                log.warn("  Non-code error detected (environment issue), stopping retries");
                break;
            }

            String focusedError = extractFocusedError(renderResult);

            String errorSignature = summarizeErrorSignature(focusedError);
            if (errorSignature.equals(previousErrorSignature)) {
                consecutiveSameErrorCount++;
            } else {
                consecutiveSameErrorCount = 1;
            }

            if (consecutiveSameErrorCount >= MAX_CONSECUTIVE_SAME_ERROR_ATTEMPTS) {
                log.warn("  Same error repeated {} times in a row, stopping retries",
                        consecutiveSameErrorCount);
                break;
            }
            fixHistory.add(errorSignature);
            previousErrorSignature = errorSignature;

            // AI fix
            try {
                log.info("  Error output sent to LLM:\n{}", focusedError);
                String fixPrompt = PromptTemplates.renderFixUserPrompt(currentCode, focusedError, fixHistory);
                conversationContext.addUserMessage(fixPrompt);
                String fixResponse = aiClient.chat(conversationContext);
                conversationContext.addAssistantMessage(fixResponse);
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
        ctx.put(WorkflowKeys.RENDER_RESULT, result);

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
        String normalized = focusedError.replaceAll("\\s+", " ").trim();
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }
}
