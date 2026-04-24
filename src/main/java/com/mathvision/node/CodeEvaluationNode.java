package com.mathvision.node;

import com.mathvision.config.ModelConfig;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeFixRequest;
import com.mathvision.model.CodeFixResult;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.CodeResult;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.CodeEvaluationResult;
import com.mathvision.model.CodeEvaluationResult.ReviewSnapshot;
import com.mathvision.model.CodeEvaluationResult.StaticAnalysis;
import com.mathvision.model.CodeEvaluationResult.StaticFinding;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.node.support.FixRetryState;
import com.mathvision.node.support.NodeSupport;
import com.mathvision.prompt.CodeEvaluationPrompts;
import com.mathvision.prompt.StoryboardJsonBuilder;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.ConcurrencyUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.mathvision.util.GeoGebraCodeUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.ManimCodeUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.StoryboardPatchResolver;
import com.mathvision.util.TimeUtils;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 3: Code evaluation - checks generated code for static validity,
 * storyboard/code alignment, semantic continuity, and Manim-specific 3D
 * readability issues before render.
 */
public class CodeEvaluationNode extends PocketFlow.Node<CodeEvaluationNode.CodeEvaluationInput,
        CodeEvaluationResult, String> {

    private static final Logger log = LoggerFactory.getLogger(CodeEvaluationNode.class);

    private static final int MIN_LAYOUT_SCORE = 7;
    private static final int MIN_CONTINUITY_SCORE = 6;
    private static final int MIN_PACING_SCORE = 6;
    private static final int MAX_REVISION_ATTEMPTS = 2;

    private static final Pattern FADE_IN_PATTERN = Pattern.compile("\\bFadeIn\\s*\\(");
    private static final Pattern FADE_OUT_PATTERN = Pattern.compile("\\bFadeOut\\s*\\(");
    private static final Pattern TRANSFORM_PATTERN =
            Pattern.compile("\\b(?:Transform|TransformMatchingTex|TransformMatchingShapes)\\s*\\(");
    private static final Pattern REPLACEMENT_TRANSFORM_PATTERN =
            Pattern.compile("\\bReplacementTransform\\s*\\(");
    private static final Pattern FADE_TRANSFORM_PATTERN = Pattern.compile("\\bFadeTransform\\s*\\(");
    private static final Pattern ANIMATE_PATTERN = Pattern.compile("\\.animate\\.");
    private static final Pattern ARRANGE_PATTERN = Pattern.compile("\\.arrange(?:_in_grid)?\\(");
    private static final Pattern NEXT_TO_PATTERN = Pattern.compile("\\.next_to\\(");
    private static final Pattern MATH_TEX_PATTERN = Pattern.compile("\\bMathTex\\s*\\(");
    private static final Pattern TEXT_PATTERN = Pattern.compile("\\bText\\s*\\(");
    private static final Pattern THREE_D_SCENE_PATTERN =
            Pattern.compile("class\\s+\\w+\\s*\\(.*?ThreeDScene.*?\\)");
    private static final Pattern THREE_D_OBJECT_PATTERN = Pattern.compile(
            "\\b(?:ThreeDAxes|Dot3D|Surface|Sphere|Cube|Prism|Cone|Cylinder|Arrow3D|Line3D|Torus|ParametricSurface|OpenGLSurface|OpenGLSurfaceMesh)\\s*\\(");
    private static final Pattern CAMERA_ORIENTATION_PATTERN =
            Pattern.compile("\\b(?:set_camera_orientation|move_camera)\\s*\\(");
    private static final Pattern CAMERA_MOTION_PATTERN =
            Pattern.compile("\\b(?:begin_ambient_camera_rotation|begin_3dillusion_camera_rotation|move_camera)\\s*\\(");
    private static final Pattern FIXED_IN_FRAME_PATTERN =
            Pattern.compile("\\badd_fixed_in_frame_mobjects\\s*\\(");
    private static final Pattern FIXED_ORIENTATION_PATTERN =
            Pattern.compile("\\badd_fixed_orientation_mobjects\\s*\\(");

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private NodeConversationContext reviewConversationContext;
    private int toolCalls;

    public CodeEvaluationNode() {
        super(1, 1000);
    }

    public static class CodeEvaluationInput {
        private final CodeResult codeResult;
        private final Narrative narrative;
        private final WorkflowConfig config;
        private final Path outputDir;
        private final CodeFixResult previousFixResult;
        private final EvaluationFixState fixState;

        public CodeEvaluationInput(CodeResult codeResult,
                                   Narrative narrative,
                                   WorkflowConfig config,
                                   Path outputDir,
                                   CodeFixResult previousFixResult,
                                   EvaluationFixState fixState) {
            this.codeResult = codeResult;
            this.narrative = narrative;
            this.config = config;
            this.outputDir = outputDir;
            this.previousFixResult = previousFixResult;
            this.fixState = fixState;
        }

        public CodeResult codeResult() { return codeResult; }
        public Narrative narrative() { return narrative; }
        public WorkflowConfig config() { return config; }
        public Path outputDir() { return outputDir; }
        public CodeFixResult previousFixResult() { return previousFixResult; }
        public EvaluationFixState fixState() { return fixState; }
    }

    @Override
    public CodeEvaluationInput prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        this.toolCalls = 0;

        EvaluationFixState fixState = (EvaluationFixState) ctx.get(WorkflowKeys.CODE_EVALUATION_FIX_STATE);
        if (fixState == null) {
            fixState = new EvaluationFixState();
            ctx.put(WorkflowKeys.CODE_EVALUATION_FIX_STATE, fixState);
        }

        CodeFixResult previousFixResult = NodeSupport.consumeFixResult(ctx, CodeFixSource.EVALUATION_REVIEW);
        if (previousFixResult != null) {
            fixState.addFixToolCalls(previousFixResult.getToolCalls());
            if (previousFixResult.isApplied()) {
                fixState.revisedCodeApplied = true;
            }
        }

        return new CodeEvaluationInput(
                (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT),
                (Narrative) ctx.get(WorkflowKeys.NARRATIVE),
                (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG),
                (Path) ctx.get(WorkflowKeys.OUTPUT_DIR),
                previousFixResult,
                fixState
        );
    }

    @Override
    public CodeEvaluationResult exec(CodeEvaluationInput input) {
        Instant start = Instant.now();
        log.info("=== Stage 3: Code Evaluation ===");

        CodeResult codeResult = input.codeResult();
        Narrative narrative = input.narrative();
        EvaluationFixState fixState = input.fixState();
        fixState.setRequestFix(false);

        CodeEvaluationResult result = new CodeEvaluationResult();
        if (codeResult == null || !codeResult.hasCode()) {
            result.setApprovedForRender(false);
            result.setGateReason("No code available for code evaluation");
            result.setRevisionAttempts(fixState.getAttempts());
            result.setRevisionTriggered(fixState.getAttempts() > 0);
            result.setRevisedCodeApplied(fixState.revisedCodeApplied);
            result.setToolCalls(toolCalls + fixState.totalToolCalls());
            result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
            return result;
        }

        String currentCode = codeResult.getGeneratedCode();
        String sceneName = resolveEvaluationArtifactName(currentCode, codeResult.getSceneName());
        codeResult.setSceneName(sceneName);
        result.setSceneName(sceneName);
        initializeConversationContexts(codeResult, sceneName);

        StaticAnalysis initialStatic = analyzeStaticQuality(narrative, codeResult, currentCode);
        ReviewSnapshot initialReview = requestCodeReview(narrative, codeResult, sceneName, currentCode, initialStatic);

        result.setInitialStaticAnalysis(initialStatic);
        result.setInitialReview(initialReview);
        result.setFinalStaticAnalysis(initialStatic);
        result.setFinalReview(initialReview);

        boolean approved = passesGate(initialReview, initialStatic);
        if (!approved && fixState.getAttempts() < MAX_REVISION_ATTEMPTS) {
            fixState.setRequestFix(true);
            fixState.setAttempts(fixState.getAttempts() + 1);
            log.warn("Code evaluation advisory review still failed for scene {}, routing to shared CodeFixNode (attempt {}/{})",
                    sceneName, fixState.getAttempts(), MAX_REVISION_ATTEMPTS);
        } else if (!approved) {
            log.warn("Code evaluation advisory review still failed for scene {}, max revision attempts reached ({})",
                    sceneName, MAX_REVISION_ATTEMPTS);
        }

        result.setApprovedForRender(approved);
        result.setRevisionAttempts(fixState.getAttempts());
        result.setRevisionTriggered(fixState.getAttempts() > 0 || fixState.isRequestFix() || fixState.revisedCodeApplied);
        result.setRevisedCodeApplied(fixState.revisedCodeApplied);
        result.setToolCalls(toolCalls + fixState.getFixToolCalls() + fixState.getCarryoverToolCalls());
        result.setGateReason(buildGateReason(approved, result.getFinalStaticAnalysis(), result.getFinalReview()));
        result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));

        if (approved) {
            log.info("Code evaluation advisory passed for scene {}", sceneName);
        } else {
            log.warn("Code evaluation still recommends revisions for scene {}: {}",
                    sceneName, result.getGateReason());
        }

        return result;
    }

    @Override
    public String post(Map<String, Object> ctx,
                       CodeEvaluationInput input,
                       CodeEvaluationResult result) {
        appendEvaluationAttempt(ctx, result);
        ctx.put(WorkflowKeys.CODE_RESULT, input.codeResult());
        ctx.put(WorkflowKeys.CODE_EVALUATION_RESULT, result);

        Path outputDir = input.outputDir();
        if (outputDir != null) {
            FileOutputService.saveCodeEvaluation(outputDir, result, input.codeResult());
        }

        if (input.fixState().isRequestFix()) {
            input.fixState().addCarryoverToolCalls(toolCalls);
            ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildEvaluationFixRequest(input, result));
            return WorkflowActions.FIX_CODE;
        }

        input.fixState().reset();
        return null;
    }

    @SuppressWarnings("unchecked")
    private void appendEvaluationAttempt(Map<String, Object> ctx, CodeEvaluationResult result) {
        if (result == null) {
            return;
        }

        List<CodeEvaluationResult.EvaluationAttempt> history =
                (List<CodeEvaluationResult.EvaluationAttempt>) ctx.get(WorkflowKeys.CODE_EVALUATION_HISTORY);
        if (history == null) {
            history = new ArrayList<>();
            ctx.put(WorkflowKeys.CODE_EVALUATION_HISTORY, history);
        }

        history.add(CodeEvaluationResult.fromResult(result, history.size() + 1));
        result.setAttempts(new ArrayList<>(history));
        result.setTotalEvaluations(history.size());
    }

    private StaticAnalysis analyzeStaticQuality(Narrative narrative,
                                                CodeResult codeResult,
                                                String generatedCode) {
        StaticAnalysis analysis = new StaticAnalysis();
        boolean geoGebraTarget = NodeSupport.isGeoGebraTarget(workflowConfig);
        analysis.setCodeLines(codeResult.codeLineCount());
        if (!geoGebraTarget) {
            analysis.setFadeInCount(countMatches(generatedCode, FADE_IN_PATTERN));
            analysis.setFadeOutCount(countMatches(generatedCode, FADE_OUT_PATTERN));
            analysis.setTransformCount(countMatches(generatedCode, TRANSFORM_PATTERN) + countMatches(generatedCode, ANIMATE_PATTERN));
            analysis.setReplacementTransformCount(countMatches(generatedCode, REPLACEMENT_TRANSFORM_PATTERN));
            analysis.setFadeTransformCount(countMatches(generatedCode, FADE_TRANSFORM_PATTERN));
            analysis.setArrangeCount(countMatches(generatedCode, ARRANGE_PATTERN));
            analysis.setNextToCount(countMatches(generatedCode, NEXT_TO_PATTERN));
            analysis.setMathTexCount(countMatches(generatedCode, MATH_TEX_PATTERN));
            analysis.setTextCount(countMatches(generatedCode, TEXT_PATTERN));
            analysis.setThreeDScene(THREE_D_SCENE_PATTERN.matcher(generatedCode).find());
            analysis.setThreeDObjectCount(countMatches(generatedCode, THREE_D_OBJECT_PATTERN));
            analysis.setCameraOrientationCount(countMatches(generatedCode, CAMERA_ORIENTATION_PATTERN));
            analysis.setCameraMotionCount(countMatches(generatedCode, CAMERA_MOTION_PATTERN));
            analysis.setFixedInFrameCount(countMatches(generatedCode, FIXED_IN_FRAME_PATTERN));
            analysis.setFixedOrientationCount(countMatches(generatedCode, FIXED_ORIENTATION_PATTERN));
        }

        Storyboard storyboard = narrative != null
                ? StoryboardPatchResolver.buildMergedStoryboard(narrative.getStoryboard())
                : null;
        if (storyboard != null && storyboard.getScenes() != null) {
            analysis.setSceneCount(storyboard.getScenes().size());
            if (!geoGebraTarget) {
                analysis.setThreeDStoryboardSceneCount(countThreeDStoryboardScenes(storyboard));
                addStoryboardDrivenFindings(analysis, storyboard);
            }
        }

        if (!geoGebraTarget) {
            addCodeDrivenFindings(analysis);
        }
        addStaticValidationFindings(analysis, generatedCode);
        return analysis;
    }

    private ReviewSnapshot requestCodeReview(Narrative narrative,
                                             CodeResult codeResult,
                                             String sceneName,
                                             String generatedCode,
                                             StaticAnalysis analysis) {
        String storyboardJson = narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(narrative.getStoryboard())
                : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON;
        String staticAnalysisJson = JsonUtils.toPrettyJson(analysis);
        String userPrompt = CodeEvaluationPrompts.reviewUserPrompt(
                sceneName, storyboardJson, staticAnalysisJson, generatedCode, NodeSupport.resolveOutputTarget(workflowConfig));

        try {
            JsonNode payload = AiRequestUtils.requestJsonObjectAsync(
                    aiClient,
                    log,
                    sceneName,
                    reviewConversationContext,
                    userPrompt,
                    ToolSchemas.CODE_REVIEW,
                    () -> toolCalls++
            ).join();
            ReviewSnapshot parsed = parseReviewSnapshot(payload);
            if (parsed != null) {
                return normalizeReview(parsed);
            }
            log.warn("Code reviewer returned no parseable structure, falling back to static synthesis");
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            log.warn("Code reviewer request failed, using static fallback: {}", cause.getMessage());
        } catch (Exception e) {
            log.warn("Code reviewer request failed, using static fallback: {}", e.getMessage());
        }
        return fallbackReviewFromStaticAnalysis(analysis);
    }

    private void initializeConversationContexts(CodeResult codeResult, String fallbackSceneName) {
        String targetConcept = codeResult != null && codeResult.getTargetConcept() != null
                ? codeResult.getTargetConcept()
                : fallbackSceneName;
        String targetDescription = codeResult != null ? codeResult.getTargetDescription() : "";
        int maxInputTokens = workflowConfig != null
                ? workflowConfig.resolveMaxInputTokens()
                : ModelConfig.DEFAULT_MAX_INPUT_TOKENS;

        this.reviewConversationContext = new NodeConversationContext(maxInputTokens);
        this.reviewConversationContext.setSystemMessage(
                CodeEvaluationPrompts.buildReviewRulesPrompt(
                        NodeSupport.resolveOutputTarget(workflowConfig)));
        this.reviewConversationContext.setFixedContextMessage(
                CodeEvaluationPrompts.buildReviewFixedContextPrompt(
                        targetConcept,
                        targetDescription,
                        NodeSupport.resolveOutputTarget(workflowConfig)));
    }

    private String resolveEvaluationArtifactName(String generatedCode, String fallbackName) {
        if (NodeSupport.isGeoGebraTarget(workflowConfig)) {
            if (fallbackName != null && !fallbackName.isBlank()) {
                return fallbackName;
            }
            return GeoGebraCodeUtils.EXPECTED_FIGURE_NAME;
        }
        return ManimCodeUtils.extractSceneName(generatedCode, fallbackName);
    }

    private CodeFixRequest buildEvaluationFixRequest(CodeEvaluationInput input,
                                                     CodeEvaluationResult result) {
        CodeResult codeResult = input.codeResult();
        Narrative narrative = input.narrative();
        String sceneName = result.getSceneName() != null ? result.getSceneName() : codeResult.getSceneName();

        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.EVALUATION_REVIEW);
        request.setReturnAction(WorkflowActions.RETRY_CODE_EVALUATION);
        request.setGeneratedCode(codeResult.getGeneratedCode());
        request.setErrorReason(buildDetailedEvaluationFixReason(
                codeResult.getGeneratedCode(),
                result.getFinalStaticAnalysis(),
                result.getFinalReview(),
                result.getGateReason()));
        request.setTargetConcept(codeResult.getTargetConcept());
        request.setTargetDescription(codeResult.getTargetDescription());
        request.setSceneName(sceneName);
        request.setExpectedSceneName(sceneName);
        request.setStoryboardJson(narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(narrative.getStoryboard())
                : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON);
        request.setStaticAnalysisJson(JsonUtils.toPrettyJson(result.getFinalStaticAnalysis()));
        request.setReviewJson(JsonUtils.toPrettyJson(result.getFinalReview()));
        return request;
    }

    private String buildDetailedEvaluationFixReason(String generatedCode,
                                                    StaticAnalysis analysis,
                                                    ReviewSnapshot review,
                                                    String gateReason) {
        List<String> reasons = new ArrayList<>();
        if (gateReason != null && !gateReason.isBlank()) {
            reasons.add("Gate summary: " + gateReason.trim());
        }

        if (analysis != null && analysis.getFindings() != null) {
            for (StaticFinding finding : analysis.getFindings()) {
                if (!"fail".equalsIgnoreCase(finding.getSeverity())
                        && !"warn".equalsIgnoreCase(finding.getSeverity())) {
                    continue;
                }

                StringBuilder item = new StringBuilder();
                item.append(finding.getSummary());
                if (finding.getEvidence() != null && !finding.getEvidence().isBlank()) {
                    item.append(" [evidence: ").append(finding.getEvidence().trim()).append("]");
                }

                List<String> snippets = extractRelevantCodeEvidence(generatedCode, finding.getRuleId());
                if (!snippets.isEmpty()) {
                    item.append(" [code: ").append(String.join(" | ", snippets)).append("]");
                }
                reasons.add(item.toString());
            }
        }

        if (review != null && review.getBlockingIssues() != null) {
            for (String issue : review.getBlockingIssues()) {
                if (issue == null || issue.isBlank()) {
                    continue;
                }
                reasons.add("Review blocking issue: " + issue.trim());
            }
        }

        return String.join("\n", reasons);
    }

    private List<String> extractRelevantCodeEvidence(String generatedCode, String ruleId) {
        List<String> snippets = new ArrayList<>();
        if (generatedCode == null || generatedCode.isBlank() || ruleId == null || ruleId.isBlank()) {
            return snippets;
        }

        List<Pattern> patterns = patternsForRule(ruleId);
        if (patterns.isEmpty()) {
            return snippets;
        }

        String[] lines = generatedCode.split("\\R");
        for (int i = 0; i < lines.length && snippets.size() < 3; i++) {
            String line = lines[i];
            for (Pattern pattern : patterns) {
                if (pattern.matcher(line).find()) {
                    String snippet = line.trim();
                    if (snippet.length() > 140) {
                        snippet = snippet.substring(0, 140) + "...";
                    }
                    snippets.add("line " + (i + 1) + ": " + snippet);
                    break;
                }
            }
        }

        return snippets;
    }

    private List<Pattern> patternsForRule(String ruleId) {
        List<Pattern> patterns = new ArrayList<>();
        switch (ruleId) {
            case "weak_transform_continuity":
                patterns.add(FADE_IN_PATTERN);
                patterns.add(FADE_OUT_PATTERN);
                patterns.add(TRANSFORM_PATTERN);
                patterns.add(ANIMATE_PATTERN);
                break;
            case "three_d_scene_required":
            case "three_d_camera_plan_missing":
            case "three_d_camera_motion_missing":
                patterns.add(THREE_D_OBJECT_PATTERN);
                patterns.add(CAMERA_ORIENTATION_PATTERN);
                patterns.add(CAMERA_MOTION_PATTERN);
                break;
            case "three_d_overlay_missing":
            case "three_d_overlay_unfixed":
                patterns.add(TEXT_PATTERN);
                patterns.add(MATH_TEX_PATTERN);
                patterns.add(FIXED_IN_FRAME_PATTERN);
                patterns.add(FIXED_ORIENTATION_PATTERN);
                break;
            default:
                break;
        }
        return patterns;
    }

    private boolean passesGate(ReviewSnapshot review, StaticAnalysis analysis) {
        if (review == null) {
            return false;
        }
        if (review.getContinuityScore() < MIN_CONTINUITY_SCORE
                || review.getPacingScore() < MIN_PACING_SCORE) {
            return false;
        }
        return review.getLayoutScore() >= MIN_LAYOUT_SCORE;
    }

    private String buildGateReason(boolean approved,
                                   StaticAnalysis analysis,
                                   ReviewSnapshot review) {
        if (approved) {
            return "Advisory review passed";
        }

        List<String> reasons = new ArrayList<>();
        if (analysis != null) {
            for (StaticFinding finding : analysis.getFindings()) {
                if ("fail".equalsIgnoreCase(finding.getSeverity())
                        || "warn".equalsIgnoreCase(finding.getSeverity())) {
                    reasons.add(finding.getSummary());
                }
            }
        }

        if (review != null) {
            if (review.getLayoutScore() < MIN_LAYOUT_SCORE) {
                reasons.add("layout_score=" + safeScore(review.getLayoutScore()) + " < " + MIN_LAYOUT_SCORE);
            }
            if (review.getContinuityScore() < MIN_CONTINUITY_SCORE) {
                reasons.add("continuity_score=" + safeScore(review.getContinuityScore()) + " < " + MIN_CONTINUITY_SCORE);
            }
            if (review.getPacingScore() < MIN_PACING_SCORE) {
                reasons.add("pacing_score=" + safeScore(review.getPacingScore()) + " < " + MIN_PACING_SCORE);
            }
            if (review.getBlockingIssues() != null) {
                for (String issue : review.getBlockingIssues()) {
                    if (issue != null && !issue.isBlank()) {
                        reasons.add(issue.trim());
                    }
                }
            }
        }

        if (reasons.isEmpty()) {
            return "Advisory review suggests visual refinements before render.";
        }
        return String.join("; ", reasons.subList(0, Math.min(3, reasons.size())));
    }

    private boolean isTextualObject(StoryboardObject object) {
        String joined = ((object.getKind() != null ? object.getKind() : "") + " "
                + (object.getContent() != null ? object.getContent() : "")).toLowerCase(Locale.ROOT);
        return joined.contains("text")
                || joined.contains("math")
                || joined.contains("formula")
                || joined.contains("equation")
                || joined.contains("label")
                || joined.contains("caption")
                || joined.contains("title");
    }

    private int countContinuityScenes(Storyboard storyboard) {
        int count = 0;
        if (storyboard == null || storyboard.getScenes() == null) {
            return count;
        }
        for (StoryboardScene scene : storyboard.getScenes()) {
            if (scene != null
                    && scene.getPersistentObjects() != null
                    && !scene.getPersistentObjects().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int countThreeDStoryboardScenes(Storyboard storyboard) {
        if (storyboard == null || storyboard.getScenes() == null) {
            return 0;
        }
        int count = 0;
        for (StoryboardScene scene : storyboard.getScenes()) {
            if (isThreeDStoryboardScene(scene)) {
                count++;
            }
        }
        return count;
    }

    private boolean isThreeDStoryboardScene(StoryboardScene scene) {
        return scene != null && "3d".equalsIgnoreCase(scene.getSceneMode());
    }

    private boolean requestsDynamicThreeDCamera(StoryboardScene scene) {
        if (!isThreeDStoryboardScene(scene)) {
            return false;
        }
        String cameraPlan = scene.getCameraPlan();
        if (cameraPlan == null || cameraPlan.isBlank()) {
            return false;
        }
        String normalized = cameraPlan.toLowerCase(Locale.ROOT);
        return normalized.contains("rotate")
                || normalized.contains("orbit")
                || normalized.contains("move")
                || normalized.contains("phi")
                || normalized.contains("theta")
                || normalized.contains("gamma")
                || normalized.contains("zoom")
                || normalized.contains("ambient");
    }

    private boolean requiresFixedOverlay(StoryboardScene scene) {
        if (!isThreeDStoryboardScene(scene)) {
            return false;
        }
        String overlayPlan = scene.getScreenOverlayPlan();
        if (overlayPlan != null && !overlayPlan.isBlank()) {
            String normalized = overlayPlan.toLowerCase(Locale.ROOT);
            if (normalized.contains("none") || normalized.contains("no fixed")) {
                return false;
            }
            return true;
        }
        List<StoryboardObject> enteringObjects = scene.getEnteringObjects() != null
                ? scene.getEnteringObjects()
                : new ArrayList<>();
        for (StoryboardObject object : enteringObjects) {
            if (object != null && isTextualObject(object)) {
                return true;
            }
        }
        return false;
    }

    private void addFinding(StaticAnalysis analysis,
                            String ruleId,
                            String severity,
                            String summary,
                            String evidence) {
        analysis.getFindings().add(new StaticFinding(ruleId, severity, summary, evidence));
    }

    private void addStaticValidationFindings(StaticAnalysis analysis, String generatedCode) {
        List<String> violations = NodeSupport.isGeoGebraTarget(workflowConfig)
                ? GeoGebraCodeUtils.validateFull(generatedCode)
                : ManimCodeUtils.validateFull(generatedCode);

        for (String violation : violations) {
            if (violation == null || violation.isBlank()) {
                continue;
            }
            String trimmed = violation.trim();
            addFinding(analysis, "static_validation", "warn", trimmed, trimmed);
        }
    }

    private boolean hasRule(StaticAnalysis analysis, String ruleId) {
        if (analysis == null || analysis.getFindings() == null) {
            return false;
        }
        return analysis.getFindings().stream()
                .anyMatch(finding -> ruleId.equalsIgnoreCase(finding.getRuleId()));
    }

    private int countMatches(String text, Pattern pattern) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean readBoolean(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value.asBoolean(false);
            }
        }
        return false;
    }

    private int readInt(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value.asInt(0);
            }
        }
        return 0;
    }

    private String readText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value.asText("");
            }
        }
        return "";
    }

    private List<String> readStringList(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isArray()) {
                List<String> items = new ArrayList<>();
                for (JsonNode item : value) {
                    String text = item.asText("").trim();
                    if (!text.isEmpty()) {
                        items.add(text);
                    }
                }
                return items;
            }
        }
        return new ArrayList<>();
    }

    private int clampScore(int value, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        if (value > 10) {
            return 10;
        }
        return value;
    }

    private int safeScore(int value) {
        return Math.max(0, value);
    }

    private ReviewSnapshot parseReviewSnapshot(JsonNode toolData) {
        return parseReviewNode(toolData);
    }

    private ReviewSnapshot parseReviewNode(JsonNode node) {
        JsonNode reviewNode = unwrapReviewPayload(node);
        if (reviewNode == null || reviewNode.isNull()) {
            return null;
        }

        ReviewSnapshot snapshot = new ReviewSnapshot();
        snapshot.setApprovedForRender(readBoolean(reviewNode, "approved_for_render", "approvedForRender"));
        snapshot.setLayoutScore(readInt(reviewNode, "layout_score", "layoutScore"));
        snapshot.setContinuityScore(readInt(reviewNode, "continuity_score", "continuityScore"));
        snapshot.setPacingScore(readInt(reviewNode, "pacing_score", "pacingScore"));
        snapshot.setSummary(readText(reviewNode, "summary"));
        snapshot.setStrengths(readStringList(reviewNode, "strengths"));
        snapshot.setBlockingIssues(readStringList(reviewNode, "blocking_issues", "blockingIssues"));
        snapshot.setRevisionDirectives(readStringList(reviewNode, "revision_directives", "revisionDirectives"));

        if (snapshot.getLayoutScore() <= 0
                && snapshot.getContinuityScore() <= 0
                && snapshot.getPacingScore() <= 0) {
            return null;
        }
        return snapshot;
    }

    private JsonNode unwrapReviewPayload(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String[] wrapperFields = {
                "review",
                "code_review",
                "codeReview",
                "result",
                "payload"
        };
        for (String field : wrapperFields) {
            JsonNode wrapped = node.get(field);
            if (wrapped != null && wrapped.isObject()) {
                return wrapped;
            }
        }
        return node;
    }

    private ReviewSnapshot normalizeReview(ReviewSnapshot snapshot) {
        snapshot.setLayoutScore(clampScore(snapshot.getLayoutScore(), 7));
        snapshot.setContinuityScore(clampScore(snapshot.getContinuityScore(), 6));
        snapshot.setPacingScore(clampScore(snapshot.getPacingScore(), 6));
        if (snapshot.getSummary() == null || snapshot.getSummary().isBlank()) {
            snapshot.setSummary("Structured code review completed.");
        }
        if (snapshot.getStrengths() == null) {
            snapshot.setStrengths(new ArrayList<>());
        }
        if (snapshot.getBlockingIssues() == null) {
            snapshot.setBlockingIssues(new ArrayList<>());
        }
        if (snapshot.getRevisionDirectives() == null) {
            snapshot.setRevisionDirectives(new ArrayList<>());
        }
        return snapshot;
    }

    private ReviewSnapshot fallbackReviewFromStaticAnalysis(StaticAnalysis analysis) {
        ReviewSnapshot review = new ReviewSnapshot();
        List<String> blockingIssues = new ArrayList<>();
        List<String> directives = new ArrayList<>();

        for (StaticFinding finding : analysis.getFindings()) {
            if ("three_d_scene_required".equalsIgnoreCase(finding.getRuleId())
                    || "three_d_overlay_unfixed".equalsIgnoreCase(finding.getRuleId())) {
                blockingIssues.add(finding.getSummary());
            }
            directives.add(finding.getSummary());
        }

        int semanticPenalty = 0;
        semanticPenalty += hasRule(analysis, "three_d_scene_required") ? 3 : 0;
        semanticPenalty += hasRule(analysis, "three_d_overlay_unfixed") ? 2 : 0;
        semanticPenalty += hasRule(analysis, "three_d_camera_plan_missing") ? 1 : 0;
        semanticPenalty += hasRule(analysis, "three_d_camera_motion_missing") ? 1 : 0;
        semanticPenalty += hasRule(analysis, "three_d_overlay_missing") ? 1 : 0;
        review.setLayoutScore(Math.max(2, 8 - semanticPenalty));
        review.setContinuityScore(8);
        review.setPacingScore(8);
        review.setBlockingIssues(blockingIssues);
        review.setRevisionDirectives(directives);
        review.setSummary("Fallback Stage 3 review synthesized from static validation and Manim 3D-readability heuristics.");
        review.setApprovedForRender(review.getLayoutScore() >= MIN_LAYOUT_SCORE
                && review.getContinuityScore() >= MIN_CONTINUITY_SCORE
                && review.getPacingScore() >= MIN_PACING_SCORE);
        return review;
    }

    private void addStoryboardDrivenFindings(StaticAnalysis analysis, Storyboard storyboard) {
        if (NodeSupport.isGeoGebraTarget(workflowConfig)) {
            return;
        }

        boolean dynamicThreeDCameraRequested = false;
        boolean fixedOverlayRequested = false;
        for (StoryboardScene scene : storyboard.getScenes()) {
            dynamicThreeDCameraRequested |= requestsDynamicThreeDCamera(scene);
            fixedOverlayRequested |= requiresFixedOverlay(scene);
        }

        if (analysis.getThreeDStoryboardSceneCount() > 0 && !analysis.isThreeDScene()) {
            addFinding(analysis, "three_d_scene_required", "warn",
                    "The storyboard requests 3D staging, but the code does not use `ThreeDScene`.",
                    String.format(Locale.ROOT,
                            "storyboard_3d_scenes=%d, code_uses_threedscene=%s",
                            analysis.getThreeDStoryboardSceneCount(), analysis.isThreeDScene()));
        } else if (analysis.getThreeDStoryboardSceneCount() > 0
                && analysis.getCameraOrientationCount() == 0) {
            addFinding(analysis, "three_d_camera_plan_missing", "warn",
                    "The storyboard includes 3D scenes, but the code never sets an explicit camera view.",
                    String.format(Locale.ROOT,
                            "storyboard_3d_scenes=%d, camera_orientation_calls=%d",
                            analysis.getThreeDStoryboardSceneCount(), analysis.getCameraOrientationCount()));
        }

        if (dynamicThreeDCameraRequested
                && analysis.getCameraOrientationCount() == 0
                && analysis.getCameraMotionCount() == 0) {
            addFinding(analysis, "three_d_camera_motion_missing", "warn",
                    "The storyboard requests 3D camera control, but the code shows no matching camera calls.",
                    String.format(Locale.ROOT,
                            "camera_orientation_calls=%d, camera_motion_calls=%d",
                            analysis.getCameraOrientationCount(), analysis.getCameraMotionCount()));
        }

        if (fixedOverlayRequested
                && analysis.getMathTexCount() + analysis.getTextCount() > 0
                && analysis.getFixedInFrameCount() == 0
                && analysis.getFixedOrientationCount() == 0) {
            addFinding(analysis, dynamicThreeDCameraRequested ? "three_d_overlay_unfixed" : "three_d_overlay_missing",
                    "warn",
                    dynamicThreeDCameraRequested
                            ? "3D scenes with camera motion should keep explanatory text fixed in frame."
                            : "3D storyboard scenes likely need fixed-in-frame overlays for readable text.",
                    String.format(Locale.ROOT,
                            "fixed_in_frame=%d, fixed_orientation=%d, text_like=%d",
                            analysis.getFixedInFrameCount(),
                            analysis.getFixedOrientationCount(),
                            analysis.getMathTexCount() + analysis.getTextCount()));
        }

        int continuityScenes = countContinuityScenes(storyboard);
        int transformLike = analysis.getTransformCount()
                + analysis.getReplacementTransformCount()
                + analysis.getFadeTransformCount();
        int fadeCycles = analysis.getFadeInCount() + analysis.getFadeOutCount();
        if (continuityScenes >= 3 && transformLike == 0 && fadeCycles >= 6) {
            addFinding(analysis, "weak_transform_continuity", "info",
                    "The storyboard expects persistent visual continuity, but the code barely uses transforms.",
                    String.format(Locale.ROOT,
                            "continuity_scenes=%d, transform_like=%d, fade_in_out=%d",
                            continuityScenes, transformLike, fadeCycles));
        } else if (continuityScenes >= 2 && transformLike <= 1 && fadeCycles >= 4) {
            addFinding(analysis, "weak_transform_continuity", "info",
                    "The storyboard expects persistent visual continuity, but the code uses few transforms.",
                    String.format(Locale.ROOT,
                            "continuity_scenes=%d, transform_like=%d, fade_in_out=%d",
                            continuityScenes, transformLike, fadeCycles));
        }
    }

    private void addCodeDrivenFindings(StaticAnalysis analysis) {
        if (NodeSupport.isGeoGebraTarget(workflowConfig)) {
            return;
        }

        if (analysis.getThreeDObjectCount() > 0 && !analysis.isThreeDScene()
                && !hasRule(analysis, "three_d_scene_required")) {
            addFinding(analysis, "three_d_scene_required", "warn",
                    "The code creates 3D objects but does not declare a `ThreeDScene`.",
                    String.format(Locale.ROOT,
                            "three_d_objects=%d, code_uses_threedscene=%s",
                            analysis.getThreeDObjectCount(), analysis.isThreeDScene()));
        } else if (analysis.isThreeDScene()
                && analysis.getThreeDObjectCount() > 0
                && analysis.getCameraOrientationCount() == 0
                && !hasRule(analysis, "three_d_camera_plan_missing")) {
            addFinding(analysis, "three_d_camera_plan_missing", "warn",
                    "The code uses 3D objects but never sets an explicit camera orientation.",
                    String.format(Locale.ROOT,
                            "three_d_objects=%d, camera_orientation_calls=%d",
                            analysis.getThreeDObjectCount(), analysis.getCameraOrientationCount()));
        }

        if (analysis.isThreeDScene()
                && analysis.getMathTexCount() + analysis.getTextCount() > 0
                && analysis.getCameraMotionCount() > 0
                && analysis.getFixedInFrameCount() == 0
                && analysis.getFixedOrientationCount() == 0
                && !hasRule(analysis, "three_d_overlay_unfixed")) {
            addFinding(analysis, "three_d_overlay_unfixed", "warn",
                    "Camera motion in a 3D scene should not leave explanatory text rotating with the world.",
                    String.format(Locale.ROOT,
                            "camera_motion_calls=%d, fixed_in_frame=%d, fixed_orientation=%d, text_like=%d",
                            analysis.getCameraMotionCount(),
                            analysis.getFixedInFrameCount(),
                            analysis.getFixedOrientationCount(),
                            analysis.getMathTexCount() + analysis.getTextCount()));
        } else if (analysis.isThreeDScene()
                && analysis.getMathTexCount() + analysis.getTextCount() > 0
                && analysis.getFixedInFrameCount() == 0
                && analysis.getFixedOrientationCount() == 0
                && !hasRule(analysis, "three_d_overlay_missing")
                && !hasRule(analysis, "three_d_overlay_unfixed")) {
            addFinding(analysis, "three_d_overlay_missing", "warn",
                    "3D explanatory text is easier to read when it is fixed in frame or fixed in orientation.",
                    String.format(Locale.ROOT,
                            "fixed_in_frame=%d, fixed_orientation=%d, text_like=%d",
                            analysis.getFixedInFrameCount(),
                            analysis.getFixedOrientationCount(),
                            analysis.getMathTexCount() + analysis.getTextCount()));
        }
    }

    static final class EvaluationFixState extends FixRetryState {
        boolean revisedCodeApplied;

        @Override
        public void reset() {
            super.reset();
            revisedCodeApplied = false;
        }
    }
}
