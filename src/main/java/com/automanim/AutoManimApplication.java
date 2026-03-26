package com.automanim;

import com.automanim.config.ConfigLoader;
import com.automanim.config.ModelConfig;
import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.CodeFixTraceEntry;
import com.automanim.model.CodeFixTraceReport;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.RenderResult;
import com.automanim.model.CodeEvaluationResult;
import com.automanim.model.SceneEvaluationResult;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.service.GeminiAiClient;
import com.automanim.service.OpenAiCompatibleAiClient;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
            return;
        }

        // If first arg doesn't start with '-', treat it as the concept
        String concept = null;
        int startIndex = 0;
        if (!args[0].startsWith("-")) {
            concept = args[0];
            startIndex = 1;
        }

        String workflowConfigPath = null;
        String modelConfigPath = null;
        String outputDirOverride = null;
        String fromGraphPath = null;
        String fromCodePath = null;

        // Parse CLI flags
        for (int i = startIndex; i < args.length; i++) {
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
                case "--from-graph":
                    fromGraphPath = args[++i];
                    break;
                case "--from-code":
                    fromCodePath = args[++i];
                    break;
                default:
                    log.warn("Unknown option: {}", args[i]);
                    break;
            }
        }

        if (fromGraphPath != null && fromCodePath != null) {
            log.error("Use either --from-graph or --from-code, not both.");
            printUsage();
            System.exit(1);
            return;
        }

        // Load pre-built knowledge graph if --from-graph is specified
        KnowledgeGraph preloadedGraph = null;
        Path graphOutputDir = null;
        if (fromGraphPath != null) {
            Path graphFile = resolveGraphPath(fromGraphPath);
            if (!Files.exists(graphFile)) {
                log.error("Knowledge graph file not found: {}", graphFile);
                System.exit(1);
                return;
            }
            preloadedGraph = FileOutputService.loadKnowledgeGraph(graphFile);
            graphOutputDir = graphFile.toAbsolutePath().getParent();
            if (concept == null) {
                concept = preloadedGraph.getTargetConcept();
            }
        }

        CodeResult preloadedCodeResult = null;
        Path codeOutputDir = null;
        if (fromCodePath != null) {
            Path codeFile = resolveCodePath(fromCodePath);
            if (!Files.exists(codeFile)) {
                log.error("Manim code file not found: {}", codeFile);
                System.exit(1);
                return;
            }
            preloadedCodeResult = FileOutputService.loadCodeResult(codeFile);
            codeOutputDir = codeFile.toAbsolutePath().getParent();
            if (concept == null) {
                concept = firstNonBlank(
                        preloadedCodeResult.getTargetConcept(),
                        preloadedCodeResult.getSceneName());
            }
        }

        if (concept == null) {
            log.error("No concept provided. Specify a concept as the first argument or use --from-graph/--from-code.");
            printUsage();
            System.exit(1);
            return;
        }

        WorkflowConfig config = ConfigLoader.load(workflowConfigPath, modelConfigPath);

        // Create AI client
        AiClient aiClient = createAiClient(config);

        // Determine output directory
        Path outputDir;
        if (preloadedGraph != null) {
            // Always write outputs alongside the supplied graph
            outputDir = graphOutputDir;
        } else if (preloadedCodeResult != null) {
            // Always write outputs alongside the supplied code
            outputDir = codeOutputDir;
        } else if (outputDirOverride != null) {
            outputDir = Path.of(outputDirOverride);
        } else {
            outputDir = FileOutputService.createOutputDir(Path.of("output"), concept);
        }

        log.info("============================================================");
        log.info("  Auto-Manim Workflow");
        log.info("  Concept:  {}", concept);
        if (preloadedGraph != null) {
            log.info("  Stage 0:  [skipped – loaded from {}]", fromGraphPath);
        }
        if (preloadedCodeResult != null) {
            log.info("  Stage 0-2: [skipped - loaded from {}]", fromCodePath);
        }
        log.info("  Mode:     {}", config.getInputMode());
        log.info("  Target:   {}", config.getOutputTarget());
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

        if (preloadedGraph != null) {
            ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, preloadedGraph);
            ctx.put(WorkflowKeys.EXPLORATION_API_CALLS, 0);
        }
        if (preloadedCodeResult != null) {
            ctx.put(WorkflowKeys.CODE_RESULT, preloadedCodeResult);
        }

        // Create and run workflow
        PocketFlow.Flow<?> flow;
        if (preloadedGraph != null) {
            flow = config.isRenderEnabled()
                    ? WorkflowFlow.createFromGraph()
                    : WorkflowFlow.createFromGraphWithoutRender();
        } else if (preloadedCodeResult != null) {
            flow = config.isRenderEnabled()
                    ? WorkflowFlow.createFromCode()
                    : WorkflowFlow.createFromCodeWithoutRender();
        } else if (config.isRenderEnabled()) {
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
        FileOutputService.saveCodeFixTrace(outputDir, buildCodeFixTraceReport(ctx));

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
            case "zhipu":
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
        CodeEvaluationResult codeEvaluationResult =
                (CodeEvaluationResult) ctx.get(WorkflowKeys.CODE_EVALUATION_RESULT);
        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        SceneEvaluationResult sceneEvaluationResult =
                (SceneEvaluationResult) ctx.get(WorkflowKeys.SCENE_EVALUATION_RESULT);
        int apiCalls = (int) ctx.getOrDefault(WorkflowKeys.EXPLORATION_API_CALLS, 0);
        apiCalls += (int) ctx.getOrDefault(WorkflowKeys.ENRICHMENT_TOOL_CALLS, 0);

        WorkflowConfig workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        summary.put("concept", ctx.get(WorkflowKeys.CONCEPT));
        summary.put("input_mode", workflowConfig.getInputMode());
        summary.put("output_target", workflowConfig.getOutputTarget());
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

        if (codeEvaluationResult != null) {
            apiCalls += codeEvaluationResult.getToolCalls();
            summary.put("code_evaluation_approved", codeEvaluationResult.isApprovedForRender());
            summary.put("code_revision_triggered", codeEvaluationResult.isRevisionTriggered());
            summary.put("code_revision_attempts", codeEvaluationResult.getRevisionAttempts());
            summary.put("code_gate_reason", codeEvaluationResult.getGateReason());

            CodeEvaluationResult.ReviewSnapshot finalReview = codeEvaluationResult.getFinalReview();
            if (finalReview != null) {
                summary.put("layout_score", finalReview.getLayoutScore());
                summary.put("continuity_score", finalReview.getContinuityScore());
                summary.put("pacing_score", finalReview.getPacingScore());
                summary.put("clutter_risk", finalReview.getClutterRisk());
                summary.put("likely_offscreen_risk", finalReview.getLikelyOffscreenRisk());
            }
        }

        if (renderResult != null) {
            apiCalls += renderResult.getToolCalls();
            summary.put("render_success", renderResult.isSuccess());
            summary.put("render_attempts", renderResult.getAttempts());
            summary.put("video_path", renderResult.getVideoPath());
            summary.put("geometry_path", renderResult.getGeometryPath());
        }

        if (sceneEvaluationResult != null) {
            apiCalls += sceneEvaluationResult.getToolCalls();
            summary.put("scene_evaluation_evaluated", sceneEvaluationResult.isEvaluated());
            summary.put("scene_evaluation_approved", sceneEvaluationResult.isApproved());
            summary.put("scene_evaluation_revision_triggered", sceneEvaluationResult.isRevisionTriggered());
            summary.put("scene_evaluation_revision_attempts", sceneEvaluationResult.getRevisionAttempts());
            summary.put("scene_evaluation_gate_reason", sceneEvaluationResult.getGateReason());
            summary.put("scene_evaluation_sample_count", sceneEvaluationResult.getSampleCount());
            summary.put("scene_evaluation_issue_samples", sceneEvaluationResult.getIssueSampleCount());
            summary.put("scene_evaluation_total_issues", sceneEvaluationResult.getTotalIssueCount());
            summary.put("scene_evaluation_overlap_issues", sceneEvaluationResult.getOverlapIssueCount());
            summary.put("scene_evaluation_offscreen_issues", sceneEvaluationResult.getOffscreenIssueCount());
        }

        summary.put("code_fix_event_count", buildCodeFixTraceReport(ctx).getTotalFixEvents());

        summary.put("total_api_calls_estimate", apiCalls);
        summary.put("duration_human", formatDuration(elapsed));
        return summary;
    }

    @SuppressWarnings("unchecked")
    private static CodeFixTraceReport buildCodeFixTraceReport(Map<String, Object> ctx) {
        List<CodeFixTraceEntry> entries =
                (List<CodeFixTraceEntry>) ctx.getOrDefault(WorkflowKeys.CODE_FIX_TRACE, new ArrayList<>());
        CodeFixTraceReport report = new CodeFixTraceReport();
        report.setTotalFixEvents(entries.size());
        report.setEntries(entries);
        return report;
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
        if (summary.containsKey("code_evaluation_approved")) {
            log.info("  Code Evaluation: {} (revision_triggered={}, attempts={})",
                    Boolean.TRUE.equals(summary.get("code_evaluation_approved")) ? "APPROVED" : "BLOCKED",
                    summary.get("code_revision_triggered"),
                    summary.get("code_revision_attempts"));
            if (summary.containsKey("layout_score")) {
                log.info("  Scores: layout={}, continuity={}, pacing={}, clutter_risk={}, offscreen_risk={}",
                        summary.get("layout_score"),
                        summary.get("continuity_score"),
                        summary.get("pacing_score"),
                        summary.get("clutter_risk"),
                        summary.get("likely_offscreen_risk"));
            }
            if (summary.get("code_gate_reason") != null) {
                log.info("  Gate:   {}", summary.get("code_gate_reason"));
            }
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
            if (summary.get("geometry_path") != null) {
                log.info("  Geometry: {}", summary.get("geometry_path"));
            }
        }
        if (summary.containsKey("scene_evaluation_approved")) {
            log.info("  Scene Evaluation: {} (evaluated={}, revision_triggered={}, attempts={})",
                    Boolean.TRUE.equals(summary.get("scene_evaluation_approved")) ? "APPROVED" : "BLOCKED",
                    summary.get("scene_evaluation_evaluated"),
                    summary.get("scene_evaluation_revision_triggered"),
                    summary.get("scene_evaluation_revision_attempts"));
            log.info("  Scene Issues: samples={}, total={}, overlap={}, offscreen={}",
                    summary.get("scene_evaluation_issue_samples"),
                    summary.get("scene_evaluation_total_issues"),
                    summary.get("scene_evaluation_overlap_issues"),
                    summary.get("scene_evaluation_offscreen_issues"));
            if (summary.get("scene_evaluation_gate_reason") != null) {
                log.info("  Scene Gate: {}", summary.get("scene_evaluation_gate_reason"));
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

    private static Path resolveGraphPath(String fromGraphPath) {
        Path p = Path.of(fromGraphPath);
        if (Files.isDirectory(p)) {
            return p.resolve("1_knowledge_graph.json");
        }
        return p;
    }

    private static Path resolveCodePath(String fromCodePath) {
        Path p = Path.of(fromCodePath);
        if (Files.isDirectory(p)) {
            return p.resolve("4_manim_code.py");
        }
        return p;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void printUsage() {
        System.out.println(
                "Usage: auto-manim [concept] [options]\n"
                + "\n"
                + "Arguments:\n"
                + "  concept                    Concept or problem to animate"
                + " (required unless --from-graph/--from-code is used)\n"
                + "\n"
                + "Options:\n"
                + "  --from-graph FILE|DIR      Skip stage 0: load a pre-built knowledge graph\n"
                + "                             (accepts 1_knowledge_graph.json or its parent directory).\n"
                + "                             Outputs are written to the same directory as the graph.\n"
                + "  --from-code FILE|DIR       Skip stages 0-2: load pre-built Manim code\n"
                + "                             (accepts 4_manim_code.py or its parent directory).\n"
                + "                             Outputs are written to the same directory as the code.\n"
                + "  --workflow-config FILE     Workflow JSON config path\n"
                + "  --model-config FILE        Model JSON config path\n"
                + "  --output DIR               Output directory"
                + " (ignored when --from-graph/--from-code is used)\n"
                + "  -h, --help                 Show this help\n"
                + "\n"
                + "Environment variables:\n"
                + "  API keys are still read from env vars referenced by model-config.json\n"
        );
    }
}
