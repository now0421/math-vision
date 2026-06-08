package com.mathvision;

import com.mathvision.config.ConfigLoader;
import com.mathvision.config.ModelConfig;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeResult;
import com.mathvision.model.CodeFixTraceEntry;
import com.mathvision.model.CodeFixTraceReport;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.ProblemBundle;
import com.mathvision.model.ProblemDiagram;
import com.mathvision.model.ProblemSource;
import com.mathvision.model.RenderResult;
import com.mathvision.model.SourceAsset;
import com.mathvision.model.CodeEvaluationResult;
import com.mathvision.model.SceneEvaluationResult;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.mathvision.service.AnthropicAiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.service.GeminiAiClient;
import com.mathvision.service.OpenAiCompatibleAiClient;
import com.mathvision.util.SceneModeUtils;
import com.mathvision.util.TextUtils;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.file.InvalidPathException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI entry point for the MathVision workflow.
 *
 * Usage:
 *   java -jar mathvision.jar <target-input> [options]
 *   java -jar mathvision.jar --problem-file <file> [options]
 *
 * Options:
 *   --workflow-config FILE     Workflow JSON config path
 *   --model-config FILE        Model JSON config path
 *   --output DIR               Output directory (default: ./output/<target>/<target_input>)
 */
public class MathVisionApplication {

    private static final Logger log = LoggerFactory.getLogger(MathVisionApplication.class);
    private static final Pattern MARKDOWN_IMAGE_PATTERN =
            Pattern.compile("!\\[[^\\]]*]\\(([^)]+)\\)");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"
    );

    public static void main(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
            return;
        }

        // If first arg doesn't start with '-', treat it as raw source text.
        String rawInput = null;
        String problemFilePath = null;
        int startIndex = 0;
        if (!args[0].startsWith("-")) {
            if (isExistingMarkdownFile(args[0])) {
                problemFilePath = args[0];
            } else {
                rawInput = args[0];
            }
            startIndex = 1;
        }

        String workflowConfigPath = null;
        String modelConfigPath = null;
        String outputDirOverride = null;
        String fromGraphPath = null;
        String fromCodePath = null;
        ProblemInput problemInput = null;
        boolean normalizeOnly = false;
        boolean explorationOnly = false;
        boolean toStoryboardValidation = false;
        boolean toVisualDesign = false;
        List<String> imageAssetPaths = new ArrayList<>();

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
                case "--normalize-only":
                case "--normalization-only":
                case "--problem-normalization-only":
                    normalizeOnly = true;
                    break;
                case "--exploration-only":
                    explorationOnly = true;
                    break;
                case "--to-storyboard-validation":
                    toStoryboardValidation = true;
                    break;
                case "--to-visual-design":
                    toVisualDesign = true;
                    break;
                case "--image":
                case "--asset":
                    imageAssetPaths.add(requireOptionValue(args, ++i, args[i - 1]));
                    break;
                default:
                    log.warn("Unknown option: {}", args[i]);
                    break;
            }
        }

        if (rawInput != null && problemFilePath != null) {
            log.error("Provide either a target input argument or --problem-file, not both.");
            printUsage();
            System.exit(1);
            return;
        }

        int partialRunFlagCount = (normalizeOnly ? 1 : 0)
                + (explorationOnly ? 1 : 0)
                + (toVisualDesign ? 1 : 0)
                + (toStoryboardValidation ? 1 : 0);
        if (partialRunFlagCount > 1) {
            log.error("Use only one partial-run option: --normalize-only, --exploration-only, --to-visual-design, or --to-storyboard-validation.");
            printUsage();
            System.exit(1);
            return;
        }

        if (normalizeOnly && (fromGraphPath != null || fromCodePath != null)) {
            log.error("--normalize-only cannot be combined with --from-graph or --from-code.");
            printUsage();
            System.exit(1);
            return;
        }

        if (problemFilePath != null) {
            problemInput = loadProblemInputFromFile(problemFilePath);
            rawInput = problemInput.rawText;
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
        ProblemBundle preloadedProblemBundle = null;
        if (fromGraphPath != null) {
            Path graphFile = resolveGraphPath(fromGraphPath);
            if (!Files.exists(graphFile)) {
                log.error("Knowledge graph file not found: {}", graphFile);
                System.exit(1);
                return;
            }
            preloadedGraph = FileOutputService.loadKnowledgeGraph(graphFile);
            graphOutputDir = graphFile.toAbsolutePath().getParent();
            preloadedProblemBundle = FileOutputService.loadProblemBundle(graphOutputDir);
            if (rawInput == null && preloadedProblemBundle != null) {
                rawInput = TextUtils.firstNonBlank(
                        preloadedProblemBundle.getStatement(),
                        preloadedProblemBundle.getTitle(),
                        preloadedProblemBundle.getId());
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
            preloadedProblemBundle = FileOutputService.loadProblemBundle(codeOutputDir);
            if (rawInput == null) {
                rawInput = preloadedProblemBundle != null
                        ? TextUtils.firstNonBlank(
                                preloadedProblemBundle.getStatement(),
                                preloadedProblemBundle.getTitle(),
                                preloadedProblemBundle.getId())
                        : preloadedCodeResult.getSceneName();
            }
        }

        if (rawInput == null) {
            log.error("No target input provided. Specify a target input, use --problem-file, or use --from-graph/--from-code.");
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
                    problemInput != null && problemInput.sourcePath != null
                            ? fileStem(problemInput.sourcePath)
                            : rawInput,
                    config.getOutputTarget());
        }

        log.info("============================================================");
        log.info("  MathVision Workflow");
        log.info("  Input:    {}", summarizeRawInputForLog(rawInput));
        if (problemFilePath != null) {
            log.info("  Source:   {}", Path.of(problemFilePath).toAbsolutePath().normalize());
        }
        if (problemInput != null && !problemInput.markdownAssets.isEmpty()) {
            log.info("  Images:   {} from Markdown", problemInput.markdownAssets.size());
        }
        if (preloadedGraph != null) {
            log.info("  Stage 0-1: [skipped - loaded from {}]", fromGraphPath);
        }
        if (preloadedCodeResult != null) {
            log.info("  Stage 0-5: [skipped - loaded from {}]", fromCodePath);
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
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.OUTPUT_DIR, outputDir);

        // Build ProblemSource for normalization node
        ProblemSource problemSource = new ProblemSource();
        problemSource.setRawText(rawInput);

        List<SourceAsset> assets = new ArrayList<>();
        Set<String> seenAssetPaths = new LinkedHashSet<>();
        if (problemInput != null) {
            addImageAssets(assets, seenAssetPaths, problemInput.markdownAssets);
        }
        for (String imageAssetPath : imageAssetPaths) {
            Path assetPath = Path.of(imageAssetPath).toAbsolutePath().normalize();
            if (!Files.exists(assetPath)) {
                log.error("Image file not found: {}", assetPath);
                System.exit(1);
                return;
            }
            if (!Files.isRegularFile(assetPath)) {
                log.error("Image asset is not a regular file: {}", assetPath);
                System.exit(1);
                return;
            }
            addImageAsset(assets, seenAssetPaths, assetPath);
        }
        problemSource.setAssets(assets);
        problemSource.setSourceType(resolveSourceType(rawInput, assets));
        ctx.put(WorkflowKeys.PROBLEM_SOURCE, problemSource);

        if (preloadedGraph != null) {
            ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, preloadedGraph);
            ctx.put(WorkflowKeys.EXPLORATION_API_CALLS, 0);
            ctx.put(WorkflowKeys.PROBLEM_BUNDLE, preloadedProblemBundle != null
                    ? preloadedProblemBundle
                    : buildDegradedBundle(rawInput, config, preloadedGraph.isProblemMode()));
        }
        if (preloadedCodeResult != null) {
            ctx.put(WorkflowKeys.CODE_RESULT, preloadedCodeResult);
            ctx.put(WorkflowKeys.PROBLEM_BUNDLE, preloadedProblemBundle != null
                    ? preloadedProblemBundle
                    : buildDegradedBundle(rawInput, config, true));
        }

        // Create and run workflow
        PocketFlow.Flow<?> flow;
        if (normalizeOnly) {
            flow = WorkflowFlow.createProblemNormalizationOnly();
        } else if (explorationOnly) {
            flow = WorkflowFlow.createExplorationOnly();
        } else if (toStoryboardValidation) {
            flow = WorkflowFlow.createToStoryboardValidation();
        } else if (toVisualDesign) {
            flow = WorkflowFlow.createToVisualDesign();
        } else if (preloadedGraph != null) {
            flow = config.isRenderEnabled()
                    ? WorkflowFlow.createFromGraph(config)
                    : WorkflowFlow.createFromGraphWithoutRender(config);
        } else if (preloadedCodeResult != null) {
            flow = config.isRenderEnabled()
                    ? WorkflowFlow.createFromCode()
                    : WorkflowFlow.createFromCodeWithoutRender();
        } else if (config.isRenderEnabled()) {
            flow = WorkflowFlow.create(config);
        } else {
            flow = WorkflowFlow.createWithoutRender(config);
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
            case "anthropic":
                return new AnthropicAiClient(modelConfig);
            case "moonshot":
            case "deepseek":
            case "zhipu":
            case "aliyun":
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
        int problemNormalizationCalls = (int) ctx.getOrDefault(WorkflowKeys.PROBLEM_NORMALIZATION_API_CALLS, 0);
        int explorationCalls = (int) ctx.getOrDefault(WorkflowKeys.EXPLORATION_API_CALLS, 0);
        int enrichmentCalls = (int) ctx.getOrDefault(WorkflowKeys.ENRICHMENT_TOOL_CALLS, 0);
        int codeGenerationCalls = codeResult != null ? codeResult.getToolCalls() : 0;
        int codeEvaluationCalls = codeEvaluationResult != null ? codeEvaluationResult.getToolCalls() : 0;
        int renderStageCalls = renderResult != null ? renderResult.getToolCalls() : 0;
        int sceneEvaluationCalls = sceneEvaluationResult != null ? sceneEvaluationResult.getToolCalls() : 0;
        int codeFixCalls = sumCodeFixToolCalls(codeFixTraceReport);
        int totalLlmCalls = problemNormalizationCalls
                + explorationCalls
                + enrichmentCalls
                + codeGenerationCalls
                + codeEvaluationCalls
                + renderStageCalls
                + sceneEvaluationCalls;

        WorkflowConfig workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        summary.put("target_input", summarizeWorkflowTarget(ctx, graph, codeResult));
        summary.put("input_mode_configured", workflowConfig.getInputMode());
        summary.put("input_mode_resolved", resolveSummaryInputMode(ctx, workflowConfig, graph));
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
                problemNormalizationCalls,
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

    private static String summarizeWorkflowTarget(Map<String, Object> ctx,
                                                  KnowledgeGraph graph,
                                                  CodeResult codeResult) {
        ProblemBundle problemBundle = (ProblemBundle) ctx.get(WorkflowKeys.PROBLEM_BUNDLE);
        return TextUtils.firstNonBlank(
                problemBundle != null ? problemBundle.getStatement() : null,
                problemBundle != null ? problemBundle.getTitle() : null,
                problemBundle != null ? problemBundle.getId() : null,
                "");
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

    private static Map<String, Integer> buildLlmCallBreakdown(int problemNormalizationCalls,
                                                              int explorationCalls,
                                                              int enrichmentCalls,
                                                              int codeGenerationCalls,
                                                              int codeEvaluationCalls,
                                                              int renderStageCalls,
                                                              int sceneEvaluationCalls) {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("problem_normalization", problemNormalizationCalls);
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
            return firstExistingPath(p,
                    FileOutputService.KNOWLEDGE_GRAPH_FILE,
                    FileOutputService.LEGACY_KNOWLEDGE_GRAPH_FILE);
        }
        return p;
    }

    private static Path resolveCodePath(String fromCodePath) {
        Path p = Path.of(fromCodePath);
        if (Files.isDirectory(p)) {
            return firstExistingPath(p,
                    FileOutputService.GEOGEBRA_FINAL_COMMANDS_FILE,
                    FileOutputService.MANIM_FINAL_CODE_FILE,
                    FileOutputService.LEGACY_GEOGEBRA_FINAL_COMMANDS_FILE,
                    FileOutputService.LEGACY_MANIM_FINAL_CODE_FILE,
                    FileOutputService.GEOGEBRA_COMMANDS_FILE,
                    FileOutputService.MANIM_CODE_FILE,
                    FileOutputService.LEGACY_GEOGEBRA_COMMANDS_FILE,
                    FileOutputService.LEGACY_MANIM_CODE_FILE);
        }
        return p;
    }

    private static Path firstExistingPath(Path parentDir, String... fileNames) {
        for (String fileName : fileNames) {
            Path candidate = parentDir.resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return parentDir.resolve(fileNames[fileNames.length - 1]);
    }

    private static String requireOptionValue(String[] args, int index, String optionName) {
        if (index >= args.length) {
            log.error("Missing value for option {}", optionName);
            printUsage();
            System.exit(1);
        }
        return args[index];
    }

    private static ProblemInput loadProblemInputFromFile(String problemFilePath) {
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
            List<Path> markdownAssets = extractMarkdownImageAssets(content, path);
            String rawText = markdownAssets.isEmpty()
                    ? content
                    : stripMarkdownImageReferences(content);
            return new ProblemInput(rawText, path, markdownAssets);
        } catch (IOException e) {
            log.error("Failed to read problem file {}: {}", path, e.getMessage(), e);
            System.exit(1);
            return null;
        }
    }

    private static boolean isExistingMarkdownFile(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Path path = Path.of(value);
            String fileName = path.getFileName() != null ? path.getFileName().toString().toLowerCase() : "";
            return (fileName.endsWith(".md") || fileName.endsWith(".markdown"))
                    && Files.isRegularFile(path);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static List<Path> extractMarkdownImageAssets(String markdown, Path markdownPath) {
        List<Path> assets = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return assets;
        }

        Path baseDir = markdownPath != null && markdownPath.getParent() != null
                ? markdownPath.getParent()
                : Path.of(".").toAbsolutePath().normalize();
        Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(markdown);
        Set<String> seenPaths = new LinkedHashSet<>();
        while (matcher.find()) {
            String rawDestination = matcher.group(1);
            Path imagePath = resolveMarkdownImagePath(rawDestination, baseDir);
            if (imagePath == null) {
                continue;
            }
            if (!Files.exists(imagePath)) {
                log.error("Markdown image file not found: {}", imagePath);
                System.exit(1);
            }
            if (!Files.isRegularFile(imagePath)) {
                log.error("Markdown image is not a regular file: {}", imagePath);
                System.exit(1);
            }
            if (!isSupportedImagePath(imagePath)) {
                log.warn("Skipping unsupported Markdown image type: {}", imagePath);
                continue;
            }
            if (seenPaths.add(imagePath.toString())) {
                assets.add(imagePath);
            }
        }
        return assets;
    }

    private static String stripMarkdownImageReferences(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String withoutImages = MARKDOWN_IMAGE_PATTERN.matcher(markdown).replaceAll("");
        return withoutImages
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("\\R{3,}", "\n\n")
                .trim();
    }

    private static Path resolveMarkdownImagePath(String rawDestination, Path baseDir) {
        String destination = normalizeMarkdownImageDestination(rawDestination);
        if (destination == null || destination.isBlank()) {
            return null;
        }
        String lower = destination.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")) {
            log.warn("Skipping non-local Markdown image: {}", destination);
            return null;
        }
        if (lower.startsWith("file:")) {
            try {
                return Path.of(new URI(destination)).toAbsolutePath().normalize();
            } catch (IllegalArgumentException | URISyntaxException e) {
                log.error("Invalid file URI in Markdown image: {}", destination);
                System.exit(1);
                return null;
            }
        }

        try {
            String decoded = URLDecoder.decode(destination, StandardCharsets.UTF_8.name());
            Path path = Path.of(decoded);
            if (!path.isAbsolute()) {
                path = baseDir.resolve(path);
            }
            return path.toAbsolutePath().normalize();
        } catch (IllegalArgumentException | IOException e) {
            log.error("Invalid Markdown image path '{}': {}", destination, e.getMessage());
            System.exit(1);
            return null;
        }
    }

    private static String normalizeMarkdownImageDestination(String rawDestination) {
        if (rawDestination == null) {
            return "";
        }
        String destination = rawDestination.trim();
        if (destination.startsWith("<")) {
            int closingIndex = destination.lastIndexOf('>');
            return closingIndex > 0 ? destination.substring(1, closingIndex).trim() : destination;
        }

        Matcher titleMatcher = Pattern.compile("^(.+?)\\s+(\"[^\"]*\"|'[^']*'|\\([^)]*\\))$").matcher(destination);
        if (titleMatcher.matches()) {
            return titleMatcher.group(1).trim();
        }
        return destination;
    }

    private static boolean isSupportedImagePath(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString().toLowerCase() : "";
        for (String extension : IMAGE_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static void addImageAssets(List<SourceAsset> assets, Set<String> seenAssetPaths, List<Path> assetPaths) {
        if (assetPaths == null) {
            return;
        }
        for (Path assetPath : assetPaths) {
            addImageAsset(assets, seenAssetPaths, assetPath);
        }
    }

    private static void addImageAsset(List<SourceAsset> assets, Set<String> seenAssetPaths, Path assetPath) {
        String normalizedPath = assetPath.toAbsolutePath().normalize().toString();
        if (!seenAssetPaths.add(normalizedPath)) {
            return;
        }
        SourceAsset asset = new SourceAsset();
        asset.setId("image_" + (assets.size() + 1));
        asset.setType("image");
        asset.setPath(normalizedPath);
        asset.setMimeType(detectMimeType(assetPath));
        assets.add(asset);
    }

    private static String fileStem(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private static String resolveSummaryInputMode(Map<String, Object> ctx,
                                                  WorkflowConfig workflowConfig,
                                                  KnowledgeGraph graph) {
        Object resolvedMode = ctx.get(WorkflowKeys.RESOLVED_INPUT_MODE);
        if (resolvedMode instanceof String && !((String) resolvedMode).isBlank()) {
            return WorkflowConfig.normalizeInputMode((String) resolvedMode);
        }
        if (graph != null) {
            return graph.isProblemMode()
                    ? WorkflowConfig.INPUT_MODE_PROBLEM
                    : WorkflowConfig.INPUT_MODE_CONCEPT;
        }
        return WorkflowConfig.normalizeInputMode(workflowConfig.getInputMode());
    }

    private static String summarizeRawInputForLog(String rawInput) {
        if (rawInput == null) {
            return "";
        }
        String normalized = rawInput.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    private static ProblemBundle buildDegradedBundle(String rawInput, WorkflowConfig config,
                                                     boolean isProblemMode) {
        ProblemBundle bundle = new ProblemBundle();
        bundle.setId("preloaded");
        bundle.setTitle(rawInput != null && rawInput.length() > 50
                ? rawInput.substring(0, 50) : rawInput);
        bundle.setStatement(rawInput);
        bundle.setInputMode(isProblemMode
                ? WorkflowConfig.INPUT_MODE_PROBLEM : WorkflowConfig.INPUT_MODE_CONCEPT);
        bundle.setOutputTarget(config.getOutputTarget());
        bundle.setSceneMode(SceneModeUtils.MODE_2D);
        ProblemDiagram diagram = new ProblemDiagram();
        diagram.setPresent(false);
        bundle.setDiagram(diagram);
        return bundle;
    }

    private static String resolveSourceType(String rawText, List<SourceAsset> assets) {
        boolean hasText = rawText != null && !rawText.isBlank();
        boolean hasAssets = assets != null && !assets.isEmpty();
        if (hasText && hasAssets) return "mixed";
        if (hasAssets) return "image";
        return "text";
    }

    private static String detectMimeType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    private static void printUsage() {
        System.out.println(
                "Usage: mathvision PROBLEM.md [options]\n"
                + "   or: mathvision --problem-file PROBLEM.md [options]\n"
                + "   or: mathvision [target-input] [options]\n"
                + "\n"
                + "Arguments:\n"
                + "  PROBLEM.md                 Markdown problem file; local Markdown images are attached automatically\n"
                + "  target-input               Concept or problem to animate"
                + " (required unless --problem-file/--from-graph/--from-code is used)\n"
                + "\n"
                + "Options:\n"
                + "  --problem-file FILE        Read the full problem statement from a Markdown file\n"
                + "                             Markdown image refs like ![](diagram.png) are attached automatically\n"
                + "  --image FILE               Add an input image (can be repeated for multiple images)\n"
                + "  --asset FILE               Alias for --image\n"
                + "  --from-graph FILE|DIR      Skip stages 0-1: load a pre-built knowledge graph\n"
                + "                             (accepts 01_knowledge_graph.json or its parent directory).\n"
                + "                             Outputs are written to the same directory as the graph.\n"
                + "  --from-code FILE|DIR       Skip stages 0-5: load pre-built generated code\n"
                + "                             (accepts 05_manim_code.py, 05_geogebra_commands.txt,\n"
                + "                             or their parent directory).\n"
                + "                             Outputs are written to the same directory as the code.\n"
                + "  --normalize-only           Run only stage 0 (ProblemNormalization), stop after saving the problem bundle\n"
                + "  --exploration-only         Run stages 0-1, stop after generating knowledge graph\n"
                + "  --to-visual-design          Run stages 0-3, stop after visual design\n"
                + "  --to-storyboard-validation Run stages 0-4, stop after storyboard validation\n"
                + "  --workflow-config FILE     Workflow JSON config path\n"
                + "  --model-config FILE        Model JSON config path\n"
                + "  --output DIR               Output directory"
                + " (ignored when --from-graph/--from-code is used)\n"
                + "                             default: ./output/<target>/<target_input_timestamp>\n"
                + "  -h, --help                 Show this help\n"
                + "\n"
                + "Environment variables:\n"
                + "  API keys are still read from env vars referenced by model-config.json\n"
        );
    }

    private static final class ProblemInput {
        private final String rawText;
        private final Path sourcePath;
        private final List<Path> markdownAssets;

        private ProblemInput(String rawText, Path sourcePath, List<Path> markdownAssets) {
            this.rawText = rawText;
            this.sourcePath = sourcePath;
            this.markdownAssets = markdownAssets != null ? markdownAssets : List.of();
        }
    }
}
