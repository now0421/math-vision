package com.mathvision;

import com.mathvision.config.ConfigLoader;
import com.mathvision.config.ModelConfig;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeResult;
import com.mathvision.model.CodeFixTraceEntry;
import com.mathvision.model.CodeFixTraceReport;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.RenderResult;
import com.mathvision.model.CodeEvaluationResult;
import com.mathvision.model.SceneEvaluationResult;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.service.GeminiAiClient;
import com.mathvision.service.OpenAiCompatibleAiClient;
import com.mathvision.util.TextUtils;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * CLI entry point for the MathVision workflow.
 *
 * Usage:
 *   java -jar mathvision.jar <concept> [options]
 *   java -jar mathvision.jar --problem-file <file> [options]
 *
 * Options:
 *   --workflow-config FILE     Workflow JSON config path
 *   --model-config FILE        Model JSON config path
 *   --output DIR               Output directory (default: ./output/<target>/<concept>)
 */
public class MathVisionApplication {

    private static final Logger log = LoggerFactory.getLogger(MathVisionApplication.class);

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
        String problemFilePath = null;

        // Parse CLI flags
        for (int i = startIndex; i < args.length; i++) {
            switch (args[i]) {
                case "--workflow-config":
                    workflowConfigPath = requireOptionValue(args, ++i, "--workflow-config");
                    break;
                case "--model-config":
                    modelConfigPath = requireOptionValue(args, ++i, "--model-config");
                    break;
                case "--output":
                    outputDirOverride = requireOptionValue(args, ++i, "--output");
                    break;
                case "--from-graph":
                    fromGraphPath = requireOptionValue(args, ++i, "--from-graph");
                    break;
                case "--from-code":
                    fromCodePath = requireOptionValue(args, ++i, "--from-code");
                    break;
                case "--problem-file":
                    problemFilePath = requireOptionValue(args, ++i, "--problem-file");
                    break;
                default:
                    log.warn("Unknown option: {}", args[i]);
                    break;
            }
        }

        if (concept != null && problemFilePath != null) {
            log.error("Provide either a concept argument or --problem-file, not both.");
            printUsage();
            System.exit(1);
            return;
        }

        if (problemFilePath != null) {
            concept = loadProblemFromFile(problemFilePath);
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
                log.error("Code file not found: {}", codeFile);
                System.exit(1);
                return;
            }
            preloadedCodeResult = FileOutputService.loadCodeResult(codeFile);
            codeOutputDir = codeFile.toAbsolutePath().getParent();
            if (concept == null) {
                concept = TextUtils.firstNonBlank(
                        preloadedCodeResult.getTargetConcept(),
                        preloadedCodeResult.getSceneName());
            }
        }

        if (concept == null) {
            log.error("No concept provided. Specify a concept, use --problem-file, or use --from-graph/--from-code.");
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
            outputDir = FileOutputService.createOutputDir(
                    Path.of("output"),
                    concept,
                    config.getOutputTarget());
        }

        log.info("============================================================");
        log.info("  MathVision Workflow");
        log.info("  Concept:  {}", summarizeConceptForLog(concept));
        if (problemFilePath != null) {
            log.info("  Source:   {}", Path.of(problemFilePath).toAbsolutePath().normalize());
        }
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

        CodeFixTraceReport codeFixTraceReport = buildCodeFixTraceReport(ctx);
        Map<String, Object> summary = buildSummary(ctx, elapsed, codeFixTraceReport);
        printSummary(summary);
        FileOutputService.saveWorkflowSummary(outputDir, summary);
        FileOutputService.saveCodeFixTrace(outputDir, codeFixTraceReport);

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

    private static Map<String, Object> buildSummary(Map<String, Object> ctx,
                                                    Duration elapsed,
                                                    CodeFixTraceReport codeFixTraceReport) {
        Map<String, Object> summary = new LinkedHashMap<>();
        KnowledgeGraph graph = (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        CodeEvaluationResult codeEvaluationResult =
                (CodeEvaluationResult) ctx.get(WorkflowKeys.CODE_EVALUATION_RESULT);
        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        SceneEvaluationResult sceneEvaluationResult =
                (SceneEvaluationResult) ctx.get(WorkflowKeys.SCENE_EVALUATION_RESULT);
        int explorationCalls = (int) ctx.getOrDefault(WorkflowKeys.EXPLORATION_API_CALLS, 0);
        int enrichmentCalls = (int) ctx.getOrDefault(WorkflowKeys.ENRICHMENT_TOOL_CALLS, 0);
        int codeGenerationCalls = codeResult != null ? codeResult.getToolCalls() : 0;
        int codeEvaluationCalls = codeEvaluationResult != null ? codeEvaluationResult.getToolCalls() : 0;
        int renderStageCalls = renderResult != null ? renderResult.getToolCalls() : 0;
        int sceneEvaluationCalls = sceneEvaluationResult != null ? sceneEvaluationResult.getToolCalls() : 0;
        int codeFixCalls = sumCodeFixToolCalls(codeFixTraceReport);
        int totalLlmCalls = explorationCalls
                + enrichmentCalls
                + codeGenerationCalls
                + codeEvaluationCalls
                + renderStageCalls
                + sceneEvaluationCalls;

        WorkflowConfig workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        summary.put("concept", ctx.get(WorkflowKeys.CONCEPT));
        summary.put("input_mode", workflowConfig.getInputMode());
        summary.put("output_target", workflowConfig.getOutputTarget());
        summary.put("model", workflowConfig.getModel());
        summary.put("provider", workflowConfig.getModelConfig().resolveProvider());
        summary.put("elapsed_millis", elapsed.toMillis());
        summary.put("elapsed_seconds", toSeconds(elapsed));

        if (graph != null) {
            summary.put("graph_nodes", graph.countNodes());
            summary.put("graph_edges", graph.countEdges());
            summary.put("graph_max_depth", graph.getMaxDepth());
        }

        if (codeResult != null) {
            summary.put("scene_name", codeResult.getSceneName());
            summary.put("code_lines", codeResult.codeLineCount());
            summary.put("code_generation_seconds", codeResult.getExecutionTimeSeconds());
        }

        if (codeEvaluationResult != null) {
            summary.put("code_evaluation_approved", codeEvaluationResult.isApprovedForRender());
            summary.put("code_revision_triggered", codeEvaluationResult.isRevisionTriggered());
            summary.put("code_revision_attempts", codeEvaluationResult.getRevisionAttempts());
            summary.put("code_gate_reason", codeEvaluationResult.getGateReason());
            summary.put("code_evaluation_seconds", codeEvaluationResult.getExecutionTimeSeconds());

            CodeEvaluationResult.ReviewSnapshot finalReview = codeEvaluationResult.getFinalReview();
            if (finalReview != null) {
                long failedRuleCount = finalReview.getRuleChecks().stream()
                        .filter(check -> "fail".equalsIgnoreCase(check.getStatus()))
                        .count();
                long warnedRuleCount = finalReview.getRuleChecks().stream()
                        .filter(check -> "warn".equalsIgnoreCase(check.getStatus()))
                        .count();
                summary.put("code_review_rule_checks", finalReview.getRuleChecks().size());
                summary.put("code_review_failed_rules", failedRuleCount);
                summary.put("code_review_warned_rules", warnedRuleCount);
            }
        }

        if (renderResult != null) {
            summary.put("render_success", renderResult.isSuccess());
            summary.put("render_attempts", renderResult.getAttempts());
            summary.put("video_path", renderResult.getVideoPath());
            summary.put("artifact_path", renderResult.getArtifactPath());
            summary.put("artifact_type", renderResult.getArtifactType());
            summary.put("geometry_path", renderResult.getGeometryPath());
            summary.put("render_seconds", renderResult.getExecutionTimeSeconds());
        }

        if (sceneEvaluationResult != null) {
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
            summary.put("scene_evaluation_seconds", sceneEvaluationResult.getExecutionTimeSeconds());
        }

        summary.put("code_fix_event_count", codeFixTraceReport.getTotalFixEvents());
        summary.put("code_fix_llm_calls", codeFixCalls);
        summary.put("llm_calls_breakdown", buildLlmCallBreakdown(
                explorationCalls,
                enrichmentCalls,
                codeGenerationCalls,
                codeEvaluationCalls,
                renderStageCalls,
                sceneEvaluationCalls
        ));
        summary.put("total_llm_calls", totalLlmCalls);
        summary.put("total_api_calls_estimate", totalLlmCalls);
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
            if (summary.containsKey("code_review_rule_checks")) {
                log.info("  Rule Checks: total={}, failed={}, warned={}",
                        summary.get("code_review_rule_checks"),
                        summary.get("code_review_failed_rules"),
                        summary.get("code_review_warned_rules"));
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
                if (summary.get("artifact_path") != null
                        && !summary.get("artifact_path").equals(summary.get("video_path"))) {
                    log.info("  Artifact: {}", summary.get("artifact_path"));
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
        log.info("  Total LLM calls: {}", summary.get("total_llm_calls"));
        log.info("  Duration: {}", summary.get("duration_human"));
        log.info("==========================================================");
    }

    private static String formatDuration(Duration d) {
        long s = d.toMillis() / 1000;
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    private static double toSeconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }

    private static int sumCodeFixToolCalls(CodeFixTraceReport report) {
        if (report == null || report.getEntries() == null) {
            return 0;
        }
        return report.getEntries().stream()
                .mapToInt(CodeFixTraceEntry::getToolCalls)
                .sum();
    }

    private static Map<String, Integer> buildLlmCallBreakdown(int explorationCalls,
                                                              int enrichmentCalls,
                                                              int codeGenerationCalls,
                                                              int codeEvaluationCalls,
                                                              int renderStageCalls,
                                                              int sceneEvaluationCalls) {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("exploration", explorationCalls);
        breakdown.put("enrichment_and_narrative", enrichmentCalls);
        breakdown.put("code_generation", codeGenerationCalls);
        breakdown.put("code_evaluation", codeEvaluationCalls);
        breakdown.put("render_related_code_fix", renderStageCalls);
        breakdown.put("scene_evaluation", sceneEvaluationCalls);
        return breakdown;
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
            Path manim = p.resolve("4_manim_code.py");
            if (Files.exists(manim)) {
                return manim;
            }
            Path geogebra = p.resolve("4_geogebra_commands.txt");
            if (Files.exists(geogebra)) {
                return geogebra;
            }
            Path manimFinal = p.resolve("5_manim_code_final.py");
            if (Files.exists(manimFinal)) {
                return manimFinal;
            }
            Path geogebraFinal = p.resolve("5_geogebra_commands_final.txt");
            if (Files.exists(geogebraFinal)) {
                return geogebraFinal;
            }
            return manim;
        }
        return p;
    }

    private static String requireOptionValue(String[] args, int index, String optionName) {
        if (index >= args.length) {
            log.error("Missing value for option {}", optionName);
            printUsage();
            System.exit(1);
        }
        return args[index];
    }

    private static String loadProblemFromFile(String problemFilePath) {
        Path path = Path.of(problemFilePath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            log.error("Problem file not found: {}", path);
            System.exit(1);
        }
        if (!Files.isRegularFile(path)) {
            log.error("Problem file is not a regular file: {}", path);
            System.exit(1);
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                log.error("Problem file is empty: {}", path);
                System.exit(1);
            }
            return content;
        } catch (IOException e) {
            log.error("Failed to read problem file {}: {}", path, e.getMessage(), e);
            System.exit(1);
            return null;
        }
    }

    private static String summarizeConceptForLog(String concept) {
        if (concept == null) {
            return "";
        }
        String normalized = concept.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    private static void printUsage() {
        System.out.println(
                "Usage: mathvision [concept] [options]\n"
                + "   or: mathvision --problem-file FILE [options]\n"
                + "\n"
                + "Arguments:\n"
                + "  concept                    Concept or problem to animate"
                + " (required unless --problem-file/--from-graph/--from-code is used)\n"
                + "\n"
                + "Options:\n"
                + "  --problem-file FILE        Read the full problem statement from a text/Markdown file\n"
                + "  --from-graph FILE|DIR      Skip stage 0: load a pre-built knowledge graph\n"
                + "                             (accepts 1_knowledge_graph.json or its parent directory).\n"
                + "                             Outputs are written to the same directory as the graph.\n"
                + "  --from-code FILE|DIR       Skip stages 0-2: load pre-built generated code\n"
                + "                             (accepts 4_manim_code.py, 4_geogebra_commands.txt,\n"
                + "                             or their parent directory).\n"
                + "                             Outputs are written to the same directory as the code.\n"
                + "  --workflow-config FILE     Workflow JSON config path\n"
                + "  --model-config FILE        Model JSON config path\n"
                + "  --output DIR               Output directory"
                + " (ignored when --from-graph/--from-code is used)\n"
                + "                             default: ./output/<target>/<concept_timestamp>\n"
                + "  -h, --help                 Show this help\n"
                + "\n"
                + "Environment variables:\n"
                + "  API keys are still read from env vars referenced by model-config.json\n"
        );
    }
}
