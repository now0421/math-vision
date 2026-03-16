package com.automanim;

import com.automanim.config.ConfigLoader;
import com.automanim.config.ModelConfig;
import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.RenderResult;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.service.GeminiAiClient;
import com.automanim.service.OpenAiCompatibleAiClient;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI entry point for the Auto-Manim workflow.
 *
 * Usage:
 *   java -jar auto-manim.jar <concept> [options]
 *
 * Options:
 *   --workflow-config FILE     Workflow JSON config path
 *   --model-config FILE        Model JSON config path
 *   --output DIR               Output directory (default: ./output/<concept>)
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
        String workflowConfigPath = null;
        String modelConfigPath = null;
        String outputDirOverride = null;

        // Parse CLI flags
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--workflow-config":
                    workflowConfigPath = args[++i];
                    break;
                case "--model-config":
                    modelConfigPath = args[++i];
                    break;
                case "--output":
                    outputDirOverride = args[++i];
                    break;
                default:
                    log.warn("Unknown option: {}", args[i]);
                    break;
            }
        }

        WorkflowConfig config = ConfigLoader.load(workflowConfigPath, modelConfigPath);

        // Create AI client
        AiClient aiClient = createAiClient(config);

        // Create output directory
        Path outputDir;
        if (outputDirOverride != null) {
            outputDir = Path.of(outputDirOverride);
        } else {
            outputDir = FileOutputService.createOutputDir(Path.of("output"), concept);
        }

        log.info("============================================================");
        log.info("  Auto-Manim Workflow");
        log.info("  Concept:  {}", concept);
        log.info("  Mode:     {}", config.getInputMode());
        log.info("  Model:    {}", config.getModel());
        log.info("  Provider: {}", config.getModelConfig().resolveProvider());
        log.info("  Quality:  {}", config.getRenderQuality());
        log.info("  Output:   {}", outputDir);
        log.info("============================================================");

        // Build shared context
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(WorkflowKeys.CONCEPT, concept);
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.OUTPUT_DIR, outputDir);

        // Create and run workflow
        PocketFlow.Flow<?> flow;
        if (config.isRenderEnabled()) {
            flow = WorkflowFlow.create();
        } else {
            flow = WorkflowFlow.createWithoutRender();
        }

        Instant start = Instant.now();

        try {
            flow.run(ctx);
        } catch (Exception e) {
            log.error("Workflow failed: {}", e.getMessage(), e);
            System.exit(2);
            return;
        }

        Duration elapsed = Duration.between(start, Instant.now());

        Map<String, Object> summary = buildSummary(ctx, elapsed);
        printSummary(summary);
        FileOutputService.saveWorkflowSummary(outputDir, summary);

        log.info("Workflow completed in {}", formatDuration(elapsed));
    }

    private static AiClient createAiClient(WorkflowConfig config) {
        ModelConfig modelConfig = config.getModelConfig();
        String provider = modelConfig.resolveProvider();
        switch (provider) {
            case "gemini":
                return new GeminiAiClient(modelConfig);
            case "moonshot":
            case "deepseek":
            case "openai":
                return new OpenAiCompatibleAiClient(modelConfig);
            default:
                throw new IllegalStateException("Unsupported provider '" + provider
                        + "' for model '" + modelConfig.getModel() + "'");
        }
    }

    private static Map<String, Object> buildSummary(Map<String, Object> ctx, Duration elapsed) {
        Map<String, Object> summary = new LinkedHashMap<>();
        KnowledgeGraph graph = (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
        log.info("");
        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        int apiCalls = (int) ctx.getOrDefault(WorkflowKeys.EXPLORATION_API_CALLS, 0);
        apiCalls += (int) ctx.getOrDefault(WorkflowKeys.ENRICHMENT_TOOL_CALLS, 0);

        WorkflowConfig workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        summary.put("concept", ctx.get(WorkflowKeys.CONCEPT));
        summary.put("input_mode", workflowConfig.getInputMode());
        summary.put("model", workflowConfig.getModel());
        summary.put("provider", workflowConfig.getModelConfig().resolveProvider());
        summary.put("elapsed_seconds", elapsed.getSeconds());

        if (graph != null) {
            summary.put("graph_nodes", graph.countNodes());
            summary.put("graph_edges", graph.countEdges());
            summary.put("graph_max_depth", graph.getMaxDepth());
        }

        if (codeResult != null) {
            apiCalls += codeResult.getToolCalls();
            summary.put("scene_name", codeResult.getSceneName());
            summary.put("code_lines", codeResult.codeLineCount());
        }

        if (renderResult != null) {
            apiCalls += renderResult.getToolCalls();
            summary.put("render_success", renderResult.isSuccess());
            summary.put("render_attempts", renderResult.getAttempts());
            summary.put("video_path", renderResult.getVideoPath());
        }

        summary.put("total_api_calls_estimate", apiCalls);
        summary.put("duration_human", formatDuration(elapsed));
        return summary;
    }

    private static void printSummary(Map<String, Object> summary) {
        log.info("==================== WORKFLOW SUMMARY ====================");
        if (summary.containsKey("graph_nodes")) {
            log.info("  Graph: {} nodes, {} edges, max depth {}",
                    summary.get("graph_nodes"), summary.get("graph_edges"), summary.get("graph_max_depth"));
        }
        if (summary.containsKey("code_lines")) {
            log.info("  Code: {} lines, scene={}",
                    summary.get("code_lines"), summary.get("scene_name"));
        }
        if (summary.containsKey("render_success")) {
            if (Boolean.TRUE.equals(summary.get("render_success"))) {
                log.info("  Render: SUCCESS ({} attempts)", summary.get("render_attempts"));
                if (summary.get("video_path") != null) {
                    log.info("  Video:  {}", summary.get("video_path"));
                }
            } else {
                log.info("  Render: FAILED after {} attempts", summary.get("render_attempts"));
            }
        }
        log.info("  Total API calls: ~{}", summary.get("total_api_calls_estimate"));
        log.info("  Duration: {}", summary.get("duration_human"));
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
                + "  --workflow-config FILE     Workflow JSON config path\n"
                + "  --model-config FILE        Model JSON config path\n"
                + "  --output DIR               Output directory\n"
                + "  -h, --help                 Show this help\n"
                + "\n"
                + "Environment variables:\n"
                + "  API keys are still read from env vars referenced by model-config.json\n"
        );
    }
}
