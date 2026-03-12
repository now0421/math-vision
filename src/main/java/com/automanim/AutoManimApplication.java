package com.automanim;

import com.automanim.config.PipelineConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.PipelineKeys;
import com.automanim.model.RenderResult;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.service.GeminiAiClient;
import com.automanim.service.KimiAiClient;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * CLI entry point for the Auto-Manim pipeline.
 *
 * Usage:
 *   java -jar auto-manim.jar <concept> [options]
 *
 * Options:
 *   --provider kimi|gemini     AI provider (default: kimi)
 *   --quality low|medium|high  Render quality (default: low)
 *   --max-depth N              Exploration depth (default: 4)
 *   --output DIR               Output directory (default: ./output/<concept>)
 *   --no-render                Skip rendering stage
 *   --render-retries N         Max render retries (default: 4)
 */
public class AutoManimApplication {

    private static final Logger log = LoggerFactory.getLogger(AutoManimApplication.class);

    public static void main(String[] args) {
        if (args.length < 1 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            System.exit(args.length < 1 ? 1 : 0);
            return;
        }

        String concept = args[0];
        PipelineConfig.Builder configBuilder = PipelineConfig.builder();
        String outputDirOverride = null;

        // Parse CLI flags
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--provider":
                    configBuilder.aiProvider(args[++i]);
                    break;
                case "--quality":
                    configBuilder.renderQuality(args[++i]);
                    break;
                case "--max-depth":
                    configBuilder.maxDepth(Integer.parseInt(args[++i]));
                    break;
                case "--output":
                    outputDirOverride = args[++i];
                    break;
                case "--no-render":
                    configBuilder.renderEnabled(false);
                    break;
                case "--render-retries":
                    configBuilder.renderMaxRetries(Integer.parseInt(args[++i]));
                    break;
                default:
                    log.warn("Unknown option: {}", args[i]);
                    break;
            }
        }

        PipelineConfig config = configBuilder.build();

        // Create AI client
        AiClient aiClient = createAiClient(config.getAiProvider());

        // Create output directory
        Path outputDir;
        if (outputDirOverride != null) {
            outputDir = Path.of(outputDirOverride);
        } else {
            outputDir = FileOutputService.createOutputDir(Path.of("output"), concept);
        }

        log.info("============================================================");
        log.info("  Auto-Manim Pipeline");
        log.info("  Concept:  {}", concept);
        log.info("  Provider: {}", config.getAiProvider());
        log.info("  Quality:  {}", config.getRenderQuality());
        log.info("  Output:   {}", outputDir);
        log.info("============================================================");

        // Build shared context
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(PipelineKeys.CONCEPT, concept);
        ctx.put(PipelineKeys.CONFIG, config);
        ctx.put(PipelineKeys.AI_CLIENT, aiClient);
        ctx.put(PipelineKeys.OUTPUT_DIR, outputDir);

        // Create and run pipeline
        PocketFlow.Flow<?> flow;
        if (config.isRenderEnabled()) {
            flow = PipelineFlow.create();
        } else {
            flow = PipelineFlow.createWithoutRender();
        }

        Instant start = Instant.now();

        try {
            flow.run(ctx);
        } catch (Exception e) {
            log.error("Pipeline failed: {}", e.getMessage(), e);
            System.exit(2);
            return;
        }

        Duration elapsed = Duration.between(start, Instant.now());

        // Summary
        printSummary(ctx, elapsed);
        // Add timing to context for summary
        ctx.put("elapsed_seconds", elapsed.getSeconds());
        FileOutputService.savePipelineSummary(outputDir, ctx);

        log.info("Pipeline completed in {}", formatDuration(elapsed));
    }

    private static AiClient createAiClient(String provider) {
        switch (provider.toLowerCase()) {
            case "gemini":
                return new GeminiAiClient();
            case "kimi":
                return new KimiAiClient();
            default:
                log.warn("Unknown provider '{}', using kimi", provider);
                return new KimiAiClient();
        }
    }

    private static void printSummary(Map<String, Object> ctx, Duration elapsed) {
        log.info("");
        log.info("==================== PIPELINE SUMMARY ====================");

        CodeResult codeResult = (CodeResult) ctx.get(PipelineKeys.CODE_RESULT);
        RenderResult renderResult = (RenderResult) ctx.get(PipelineKeys.RENDER_RESULT);
        int apiCalls = (int) ctx.getOrDefault(PipelineKeys.EXPLORATION_API_CALLS, 0);
        apiCalls += (int) ctx.getOrDefault(PipelineKeys.ENRICHMENT_TOOL_CALLS, 0);

        if (codeResult != null) {
            log.info("  Code: {} lines, scene={}", codeResult.codeLineCount(), codeResult.getSceneName());
            apiCalls += codeResult.getToolCalls();
        }

        if (renderResult != null) {
            if (renderResult.isSuccess()) {
                log.info("  Render: SUCCESS ({} attempts)", renderResult.getAttempts());
                if (renderResult.getVideoPath() != null) {
                    log.info("  Video:  {}", renderResult.getVideoPath());
                }
            } else {
                log.info("  Render: FAILED after {} attempts", renderResult.getAttempts());
            }
            apiCalls += renderResult.getToolCalls();
        }

        log.info("  Total API calls: ~{}", apiCalls);
        log.info("  Duration: {}", formatDuration(elapsed));
        log.info("==========================================================");
    }

    private static String formatDuration(Duration d) {
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    private static void printUsage() {
        System.out.println(
                "Usage: auto-manim <concept> [options]\n"
                + "\n"
                + "Options:\n"
                + "  --provider kimi|gemini     AI provider (default: kimi)\n"
                + "  --quality low|medium|high  Render quality (default: low)\n"
                + "  --max-depth N              Exploration depth (default: 4)\n"
                + "  --output DIR               Output directory\n"
                + "  --no-render                Skip rendering stage\n"
                + "  --render-retries N         Max render retries (default: 4)\n"
                + "  -h, --help                 Show this help\n"
                + "\n"
                + "Environment variables:\n"
                + "  MOONSHOT_API_KEY   Required for kimi provider\n"
                + "  GEMINI_API_KEY     Required for gemini provider\n"
        );
    }
}
