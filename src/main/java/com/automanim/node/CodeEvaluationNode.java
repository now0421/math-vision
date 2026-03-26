package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeFixRequest;
import com.automanim.model.CodeFixResult;
import com.automanim.model.CodeFixSource;
import com.automanim.model.CodeResult;
import com.automanim.model.Narrative;
import com.automanim.model.Narrative.Storyboard;
import com.automanim.model.Narrative.StoryboardObject;
import com.automanim.model.Narrative.StoryboardScene;
import com.automanim.model.CodeEvaluationResult;
import com.automanim.model.CodeEvaluationResult.ReviewSnapshot;
import com.automanim.model.CodeEvaluationResult.StaticAnalysis;
import com.automanim.model.CodeEvaluationResult.StaticFinding;
import com.automanim.model.WorkflowActions;
import com.automanim.model.WorkflowKeys;
import com.automanim.node.support.FixRetryState;
import com.automanim.prompt.CodeEvaluationPrompts;
import com.automanim.prompt.StoryboardJsonBuilder;
import com.automanim.prompt.ToolSchemas;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.AiRequestUtils;
import com.automanim.util.CodeUtils;
import com.automanim.util.ConcurrencyUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.automanim.util.JsonUtils;
import com.automanim.util.NodeConversationContext;
import com.automanim.util.TargetDescriptionBuilder;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 3: Code evaluation - checks storyboard/code alignment for likely
 * crowding, drift, continuity, and pacing problems before render.
 */
public class CodeEvaluationNode extends PocketFlow.Node<CodeEvaluationNode.CodeEvaluationInput,
        CodeEvaluationResult, String> {

    private static final Logger log = LoggerFactory.getLogger(CodeEvaluationNode.class);

    private static final int MIN_LAYOUT_SCORE = 7;
    private static final int MIN_CONTINUITY_SCORE = 6;
    private static final int MIN_PACING_SCORE = 6;
    private static final int MAX_CLUTTER_RISK = 4;
    private static final int MAX_OFFSCREEN_RISK = 4;
    private static final int MAX_REVISION_ATTEMPTS = 2;

    private static final Pattern TO_EDGE_PATTERN = Pattern.compile("\\.to_edge\\(");
    private static final Pattern SHIFT_PATTERN = Pattern.compile("\\.shift\\(");
    private static final Pattern HORIZONTAL_LARGE_SHIFT_PATTERN =
            Pattern.compile("(?:LEFT|RIGHT)\\s*\\*\\s*(?:4(?:\\.\\d+)?|[5-9](?:\\.\\d+)?|\\d{2,}(?:\\.\\d+)?)");
    private static final Pattern VERTICAL_LARGE_SHIFT_PATTERN =
            Pattern.compile("(?:UP|DOWN)\\s*\\*\\s*(?:3(?:\\.\\d+)?|[4-9](?:\\.\\d+)?|\\d{2,}(?:\\.\\d+)?)");
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

        CodeFixResult previousFixResult = consumeFixResult(ctx, CodeFixSource.EVALUATION_REVIEW);
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
            result.setExecutionTimeSeconds(toSeconds(start));
            return result;
        }

        String currentCode = codeResult.getManimCode();
        String sceneName = CodeUtils.extractSceneName(currentCode, codeResult.getSceneName());
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
        if (!approved && input.previousFixResult() == null && fixState.getAttempts() < MAX_REVISION_ATTEMPTS) {
            fixState.setRequestFix(true);
            fixState.setAttempts(fixState.getAttempts() + 1);
            log.warn("Code evaluation flagged scene {}, routing to shared CodeFixNode (attempt {}/{})",
                    sceneName, fixState.getAttempts(), MAX_REVISION_ATTEMPTS);
        } else if (!approved && input.previousFixResult() != null && !input.previousFixResult().isApplied()) {
            log.warn("Code evaluation re-run received no meaningful code change from CodeFixNode");
        }

        result.setApprovedForRender(approved);
        result.setRevisionAttempts(fixState.getAttempts());
        result.setRevisionTriggered(fixState.getAttempts() > 0 || fixState.isRequestFix() || fixState.revisedCodeApplied);
        result.setRevisedCodeApplied(fixState.revisedCodeApplied);
        result.setToolCalls(toolCalls + fixState.getFixToolCalls() + fixState.getCarryoverToolCalls());
        result.setGateReason(buildGateReason(approved, result.getFinalStaticAnalysis(), result.getFinalReview()));
        result.setExecutionTimeSeconds(toSeconds(start));

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
                                                String code) {
        StaticAnalysis analysis = new StaticAnalysis();
        analysis.setCodeLines(codeResult.codeLineCount());
        analysis.setToEdgeCount(countMatches(code, TO_EDGE_PATTERN));
        analysis.setShiftCount(countMatches(code, SHIFT_PATTERN));
        analysis.setLargeShiftCount(countLargeShiftMatches(code));
        analysis.setFadeInCount(countMatches(code, FADE_IN_PATTERN));
        analysis.setFadeOutCount(countMatches(code, FADE_OUT_PATTERN));
        analysis.setTransformCount(countMatches(code, TRANSFORM_PATTERN) + countMatches(code, ANIMATE_PATTERN));
        analysis.setReplacementTransformCount(countMatches(code, REPLACEMENT_TRANSFORM_PATTERN));
        analysis.setFadeTransformCount(countMatches(code, FADE_TRANSFORM_PATTERN));
        analysis.setArrangeCount(countMatches(code, ARRANGE_PATTERN));
        analysis.setNextToCount(countMatches(code, NEXT_TO_PATTERN));
        analysis.setMathTexCount(countMatches(code, MATH_TEX_PATTERN));
        analysis.setTextCount(countMatches(code, TEXT_PATTERN));
        analysis.setThreeDScene(THREE_D_SCENE_PATTERN.matcher(code).find());
        analysis.setThreeDObjectCount(countMatches(code, THREE_D_OBJECT_PATTERN));
        analysis.setCameraOrientationCount(countMatches(code, CAMERA_ORIENTATION_PATTERN));
        analysis.setCameraMotionCount(countMatches(code, CAMERA_MOTION_PATTERN));
        analysis.setFixedInFrameCount(countMatches(code, FIXED_IN_FRAME_PATTERN));
        analysis.setFixedOrientationCount(countMatches(code, FIXED_ORIENTATION_PATTERN));

        Storyboard storyboard = narrative != null ? narrative.getStoryboard() : null;
        if (storyboard != null && storyboard.getScenes() != null) {
            analysis.setSceneCount(storyboard.getScenes().size());
            analysis.setThreeDStoryboardSceneCount(countThreeDStoryboardScenes(storyboard));
            populateStoryboardMetrics(analysis, storyboard);
            addStoryboardDrivenFindings(analysis, storyboard);
        }

        addCodeDrivenFindings(analysis);
        return analysis;
    }

    private ReviewSnapshot requestCodeReview(Narrative narrative,
                                             CodeResult codeResult,
                                             String sceneName,
                                             String code,
                                             StaticAnalysis analysis) {
        String targetConcept = codeResult.getTargetConcept() != null
                ? codeResult.getTargetConcept()
                : sceneName;
        String storyboardJson = narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(narrative.getStoryboard())
                : "{\"scenes\":[]}";
        String staticAnalysisJson = JsonUtils.toPrettyJson(analysis);
        String userPrompt = CodeEvaluationPrompts.reviewUserPrompt(
                targetConcept, sceneName, storyboardJson, staticAnalysisJson, code);

        try {
            JsonNode payload = AiRequestUtils.requestJsonObjectAsync(
                    aiClient,
                    log,
                    sceneName,
                    reviewConversationContext,
                    userPrompt,
                    ToolSchemas.CODE_REVIEW,
                    () -> toolCalls++,
                    this::parseReviewTextResponse
            ).join();
            ReviewSnapshot parsed = parseReviewSnapshot(payload, null);
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
        int maxInputTokens = (workflowConfig != null && workflowConfig.getModelConfig() != null)
                ? workflowConfig.getModelConfig().getMaxInputTokens()
                : 131072;

        this.reviewConversationContext = new NodeConversationContext(maxInputTokens);
        this.reviewConversationContext.setSystemMessage(
                CodeEvaluationPrompts.reviewSystemPrompt(targetConcept, targetDescription));
    }

    private CodeFixRequest buildEvaluationFixRequest(CodeEvaluationInput input,
                                                     CodeEvaluationResult result) {
        CodeResult codeResult = input.codeResult();
        Narrative narrative = input.narrative();
        String sceneName = result.getSceneName() != null ? result.getSceneName() : codeResult.getSceneName();

        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.EVALUATION_REVIEW);
        request.setReturnAction(WorkflowActions.RETRY_CODE_EVALUATION);
        request.setCode(codeResult.getManimCode());
        request.setErrorReason(buildDetailedEvaluationFixReason(
                codeResult.getManimCode(),
                result.getFinalStaticAnalysis(),
                result.getFinalReview(),
                result.getGateReason()));
        request.setTargetConcept(codeResult.getTargetConcept());
        request.setTargetDescription(codeResult.getTargetDescription());
        request.setSceneName(sceneName);
        request.setExpectedSceneName(sceneName);
        request.setStoryboardJson(narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(narrative.getStoryboard())
                : "{\"scenes\":[]}");
        request.setStaticAnalysisJson(JsonUtils.toPrettyJson(result.getFinalStaticAnalysis()));
        request.setReviewJson(JsonUtils.toPrettyJson(result.getFinalReview()));
        return request;
    }

    private String buildDetailedEvaluationFixReason(String code,
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

                List<String> snippets = extractRelevantCodeEvidence(code, finding.getRuleId());
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

    private List<String> extractRelevantCodeEvidence(String code, String ruleId) {
        List<String> snippets = new ArrayList<>();
        if (code == null || code.isBlank() || ruleId == null || ruleId.isBlank()) {
            return snippets;
        }

        List<Pattern> patterns = patternsForRule(ruleId);
        if (patterns.isEmpty()) {
            return snippets;
        }

        String[] lines = code.split("\\R");
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
            case "scene_object_overload":
            case "visible_object_overload":
                patterns.add(FADE_IN_PATTERN);
                patterns.add(Pattern.compile("\\bCreate\\s*\\("));
                patterns.add(Pattern.compile("\\bWrite\\s*\\("));
                break;
            case "text_stack_clutter":
            case "spacing_strategy_missing":
                patterns.add(TEXT_PATTERN);
                patterns.add(MATH_TEX_PATTERN);
                patterns.add(ARRANGE_PATTERN);
                patterns.add(NEXT_TO_PATTERN);
                break;
            case "edge_push_abuse":
                patterns.add(TO_EDGE_PATTERN);
                patterns.add(SHIFT_PATTERN);
                break;
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

    private CodeFixResult consumeFixResult(Map<String, Object> ctx, CodeFixSource expectedSource) {
        CodeFixResult result = (CodeFixResult) ctx.get(WorkflowKeys.CODE_FIX_RESULT);
        if (result != null && result.getSource() == expectedSource) {
            ctx.remove(WorkflowKeys.CODE_FIX_RESULT);
            return result;
        }
        return null;
    }

    private boolean passesGate(ReviewSnapshot review, StaticAnalysis analysis) {
        if (review == null) {
            return false;
        }
        return review.getLayoutScore() >= MIN_LAYOUT_SCORE
                && review.getContinuityScore() >= MIN_CONTINUITY_SCORE
                && review.getPacingScore() >= MIN_PACING_SCORE;
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
            if (review != null
                    && (review.getClutterRisk() > MAX_CLUTTER_RISK
                    || review.getLikelyOffscreenRisk() > MAX_OFFSCREEN_RISK)) {
                return "Semantic review passed; layout-risk heuristics are advisory and deferred to Stage 5 scene evaluation.";
            }
            return "Advisory review suggests visual refinements before render.";
        }
        return String.join("; ", reasons.subList(0, Math.min(3, reasons.size())));
    }

    private int countTextualObjects(Set<String> visibleIds, Map<String, StoryboardObject> objectRegistry) {
        int count = 0;
        for (String id : visibleIds) {
            StoryboardObject object = objectRegistry.get(id);
            if (object != null && isTextualObject(object)) {
                count++;
            }
        }
        return count;
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

    private double narrationWordsPerSecond(StoryboardScene scene) {
        if (scene == null || scene.getNarration() == null || scene.getNarration().isBlank()) {
            return 0.0;
        }
        int duration = Math.max(1, scene.getDurationSeconds());
        String trimmed = scene.getNarration().trim();
        int wordCount = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        return wordCount / (double) duration;
    }

    private void addFinding(StaticAnalysis analysis,
                            String ruleId,
                            String severity,
                            String summary,
                            String evidence) {
        analysis.getFindings().add(new StaticFinding(ruleId, severity, summary, evidence));
    }

    private boolean hasRule(StaticAnalysis analysis, String ruleId) {
        if (analysis == null || analysis.getFindings() == null) {
            return false;
        }
        return analysis.getFindings().stream()
                .anyMatch(finding -> ruleId.equalsIgnoreCase(finding.getRuleId()));
    }

    private boolean hasRuleWithSeverity(StaticAnalysis analysis, String ruleId, String severity) {
        if (analysis == null || analysis.getFindings() == null) {
            return false;
        }
        return analysis.getFindings().stream()
                .anyMatch(finding -> ruleId.equalsIgnoreCase(finding.getRuleId())
                        && severity.equalsIgnoreCase(finding.getSeverity()));
    }

    private int countFindingsBySeverity(StaticAnalysis analysis, String severity) {
        if (analysis == null || analysis.getFindings() == null) {
            return 0;
        }
        return (int) analysis.getFindings().stream()
                .filter(finding -> severity.equalsIgnoreCase(finding.getSeverity()))
                .count();
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

    private int countLargeShiftMatches(String code) {
        if (code == null || code.isBlank()) {
            return 0;
        }

        int count = 0;
        for (String line : code.split("\\R")) {
            if (!line.contains(".shift(")) {
                continue;
            }
            if (HORIZONTAL_LARGE_SHIFT_PATTERN.matcher(line).find()
                    || VERTICAL_LARGE_SHIFT_PATTERN.matcher(line).find()) {
                count++;
            }
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

    private ReviewSnapshot parseReviewSnapshot(JsonNode toolData, String rawText) {
        ReviewSnapshot snapshot = parseReviewNode(toolData);
        if (snapshot != null) {
            return snapshot;
        }
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        try {
            String candidate = JsonUtils.extractJsonObject(rawText);
            if (candidate == null || candidate.isBlank()) {
                return null;
            }
            return parseReviewNode(JsonUtils.parseTree(candidate));
        } catch (RuntimeException e) {
            log.debug("Failed to parse review JSON from text response: {}", e.getMessage());
            return null;
        }
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
        snapshot.setClutterRisk(readInt(reviewNode, "clutter_risk", "clutterRisk"));
        snapshot.setLikelyOffscreenRisk(readInt(reviewNode, "likely_offscreen_risk", "likelyOffscreenRisk"));
        snapshot.setSummary(readText(reviewNode, "summary"));
        snapshot.setStrengths(readStringList(reviewNode, "strengths"));
        snapshot.setBlockingIssues(readStringList(reviewNode, "blocking_issues", "blockingIssues"));
        snapshot.setRevisionDirectives(readStringList(reviewNode, "revision_directives", "revisionDirectives"));

        if (snapshot.getLayoutScore() <= 0
                && snapshot.getContinuityScore() <= 0
                && snapshot.getPacingScore() <= 0
                && snapshot.getClutterRisk() <= 0
                && snapshot.getLikelyOffscreenRisk() <= 0) {
            return null;
        }
        return snapshot;
    }

    private JsonNode parseReviewTextResponse(String response) {
        return tryParseJsonObject(response);
    }

    private JsonNode tryParseJsonObject(String response) {
        if (response == null || !response.contains("{")) {
            return null;
        }
        try {
            String candidate = JsonUtils.extractJsonObject(response);
            if (candidate == null || candidate.isBlank()) {
                return null;
            }
            return JsonUtils.parseTree(candidate);
        } catch (RuntimeException e) {
            log.debug("Failed to parse code review JSON from text response: {}", e.getMessage());
            return null;
        }
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
        snapshot.setClutterRisk(clampScore(snapshot.getClutterRisk(), 4));
        snapshot.setLikelyOffscreenRisk(clampScore(snapshot.getLikelyOffscreenRisk(), 4));
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
            if (isStage3BlockingRule(finding.getRuleId())
                    && "fail".equalsIgnoreCase(finding.getSeverity())) {
                blockingIssues.add(finding.getSummary());
            }
            directives.add(finding.getSummary());
        }

        int semanticPenalty = 0;
        semanticPenalty += hasRuleWithSeverity(analysis, "three_d_scene_required", "fail") ? 3 : 0;
        semanticPenalty += hasRuleWithSeverity(analysis, "three_d_overlay_unfixed", "fail") ? 2 : 0;
        semanticPenalty += hasRuleWithSeverity(analysis, "three_d_camera_plan_missing", "warn") ? 1 : 0;
        semanticPenalty += hasRuleWithSeverity(analysis, "three_d_camera_motion_missing", "warn") ? 1 : 0;
        semanticPenalty += hasRuleWithSeverity(analysis, "three_d_overlay_missing", "warn") ? 1 : 0;
        review.setLayoutScore(Math.max(2, 8 - semanticPenalty));
        review.setContinuityScore(Math.max(2,
                8 - (hasRuleWithSeverity(analysis, "weak_transform_continuity", "fail") ? 2 : 0)
                        - (hasRuleWithSeverity(analysis, "weak_transform_continuity", "warn") ? 1 : 0)
                        - (hasRuleWithSeverity(analysis, "code_bloat", "warn") ? 1 : 0)));
        review.setPacingScore(Math.max(2,
                8 - (hasRuleWithSeverity(analysis, "pacing_mismatch_dense", "fail") ? 2 : 0)
                        - (hasRuleWithSeverity(analysis, "pacing_mismatch_dense", "warn") ? 1 : 0)
                        - (hasRuleWithSeverity(analysis, "pacing_mismatch_sparse", "warn") ? 1 : 0)));
        review.setClutterRisk(Math.min(10,
                2 + (hasRuleWithSeverity(analysis, "scene_object_overload", "fail") ? 2 : 0)
                        + (hasRuleWithSeverity(analysis, "scene_object_overload", "warn") ? 1 : 0)
                        + (hasRuleWithSeverity(analysis, "text_stack_clutter", "fail") ? 2 : 0)
                        + (hasRuleWithSeverity(analysis, "text_stack_clutter", "warn") ? 1 : 0)
                        + (hasRuleWithSeverity(analysis, "visible_object_overload", "fail") ? 2 : 0)
                        + (hasRuleWithSeverity(analysis, "visible_object_overload", "warn") ? 1 : 0)));
        review.setLikelyOffscreenRisk(Math.min(10,
                2 + (analysis.getToEdgeCount() / 3)
                        + Math.min(2, analysis.getLargeShiftCount())
                        + (hasRuleWithSeverity(analysis, "edge_push_abuse", "fail") ? 2 : 0)
                        + (hasRuleWithSeverity(analysis, "edge_push_abuse", "warn") ? 1 : 0)));
        review.setBlockingIssues(blockingIssues);
        review.setRevisionDirectives(directives);
        review.setSummary("Fallback Stage 3 review synthesized from static semantic, continuity, pacing, and 3D-readability heuristics.");
        review.setApprovedForRender(review.getLayoutScore() >= MIN_LAYOUT_SCORE
                && review.getContinuityScore() >= MIN_CONTINUITY_SCORE
                && review.getPacingScore() >= MIN_PACING_SCORE);
        return review;
    }

    private boolean isStage3BlockingRule(String ruleId) {
        return "weak_transform_continuity".equalsIgnoreCase(ruleId)
                || "pacing_mismatch_dense".equalsIgnoreCase(ruleId)
                || "three_d_scene_required".equalsIgnoreCase(ruleId)
                || "three_d_overlay_unfixed".equalsIgnoreCase(ruleId);
    }

    private void populateStoryboardMetrics(StaticAnalysis analysis, Storyboard storyboard) {
        Map<String, StoryboardObject> objectRegistry = new LinkedHashMap<>();
        Set<String> carryOver = new LinkedHashSet<>();
        double minWps = Double.MAX_VALUE;
        double maxWps = 0.0;
        boolean sawNarration = false;

        for (StoryboardScene scene : storyboard.getScenes()) {
            if (scene == null) {
                continue;
            }

            List<StoryboardObject> enteringObjects = scene.getEnteringObjects() != null
                    ? scene.getEnteringObjects()
                    : new ArrayList<>();
            analysis.setMaxEnteringObjects(Math.max(analysis.getMaxEnteringObjects(), enteringObjects.size()));

            for (StoryboardObject object : enteringObjects) {
                if (object != null && object.getId() != null && !object.getId().isBlank()) {
                    objectRegistry.put(object.getId(), object);
                }
            }

            Set<String> currentVisible = new LinkedHashSet<>(carryOver);
            for (StoryboardObject object : enteringObjects) {
                if (object != null && object.getId() != null && !object.getId().isBlank()) {
                    currentVisible.add(object.getId());
                }
            }

            analysis.setMaxVisibleObjects(Math.max(analysis.getMaxVisibleObjects(), currentVisible.size()));
            analysis.setMaxVisibleTextualObjects(Math.max(
                    analysis.getMaxVisibleTextualObjects(),
                    countTextualObjects(currentVisible, objectRegistry)));

            double wordsPerSecond = narrationWordsPerSecond(scene);
            if (wordsPerSecond > 0) {
                sawNarration = true;
                maxWps = Math.max(maxWps, wordsPerSecond);
                minWps = Math.min(minWps, wordsPerSecond);
            }

            Set<String> nextCarryOver = new LinkedHashSet<>();
            if (scene.getPersistentObjects() != null && !scene.getPersistentObjects().isEmpty()) {
                nextCarryOver.addAll(scene.getPersistentObjects());
            } else {
                nextCarryOver.addAll(currentVisible);
            }
            if (scene.getExitingObjects() != null && !scene.getExitingObjects().isEmpty()) {
                nextCarryOver.removeAll(scene.getExitingObjects());
            }
            carryOver = nextCarryOver;
        }

        analysis.setMaxNarrationWordsPerSecond(maxWps);
        analysis.setMinNarrationWordsPerSecond(sawNarration ? minWps : 0.0);
    }

    private void addStoryboardDrivenFindings(StaticAnalysis analysis, Storyboard storyboard) {
        if (analysis.getMaxEnteringObjects() >= 10) {
            addFinding(analysis, "scene_object_overload", "fail",
                    "A storyboard scene introduces too many new objects at once.",
                    "max_entering_objects=" + analysis.getMaxEnteringObjects());
        } else if (analysis.getMaxEnteringObjects() >= 8) {
            addFinding(analysis, "scene_object_overload", "warn",
                    "A storyboard scene may introduce more objects than the frame can comfortably absorb.",
                    "max_entering_objects=" + analysis.getMaxEnteringObjects());
        }

        if (analysis.getMaxVisibleObjects() >= 12) {
            addFinding(analysis, "visible_object_overload", "fail",
                    "The storyboard likely keeps too many simultaneous elements on screen.",
                    "max_visible_objects=" + analysis.getMaxVisibleObjects());
        } else if (analysis.getMaxVisibleObjects() >= 9) {
            addFinding(analysis, "visible_object_overload", "warn",
                    "The storyboard approaches a cluttered simultaneous-object count.",
                    "max_visible_objects=" + analysis.getMaxVisibleObjects());
        }

        if (analysis.getMaxVisibleTextualObjects() >= 6) {
            addFinding(analysis, "text_stack_clutter", "fail",
                    "The storyboard likely stacks too many textual or formula objects at once.",
                    "max_visible_textual_objects=" + analysis.getMaxVisibleTextualObjects());
        } else if (analysis.getMaxVisibleTextualObjects() >= 5) {
            addFinding(analysis, "text_stack_clutter", "warn",
                    "The storyboard may show too many text/formula objects at the same time.",
                    "max_visible_textual_objects=" + analysis.getMaxVisibleTextualObjects());
        }

        if (analysis.getMaxNarrationWordsPerSecond() > 4.5) {
            addFinding(analysis, "pacing_mismatch_dense", "fail",
                    "At least one scene has narration that is too dense for its duration.",
                    String.format(Locale.ROOT, "max_words_per_second=%.2f", analysis.getMaxNarrationWordsPerSecond()));
        } else if (analysis.getMaxNarrationWordsPerSecond() > 3.6) {
            addFinding(analysis, "pacing_mismatch_dense", "warn",
                    "A scene may have narration that feels rushed relative to the planned animation.",
                    String.format(Locale.ROOT, "max_words_per_second=%.2f", analysis.getMaxNarrationWordsPerSecond()));
        }

        if (analysis.getMinNarrationWordsPerSecond() > 0 && analysis.getMinNarrationWordsPerSecond() < 0.30) {
            addFinding(analysis, "pacing_mismatch_sparse", "warn",
                    "At least one scene may have very little narration relative to its duration.",
                    String.format(Locale.ROOT, "min_words_per_second=%.2f", analysis.getMinNarrationWordsPerSecond()));
        }

        boolean dynamicThreeDCameraRequested = false;
        boolean fixedOverlayRequested = false;
        for (StoryboardScene scene : storyboard.getScenes()) {
            dynamicThreeDCameraRequested |= requestsDynamicThreeDCamera(scene);
            fixedOverlayRequested |= requiresFixedOverlay(scene);
        }

        if (analysis.getThreeDStoryboardSceneCount() > 0 && !analysis.isThreeDScene()) {
            addFinding(analysis, "three_d_scene_required", "fail",
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
                    dynamicThreeDCameraRequested ? "fail" : "warn",
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
            addFinding(analysis, "weak_transform_continuity", "fail",
                    "The storyboard expects persistent visual continuity, but the code barely uses transforms.",
                    String.format(Locale.ROOT,
                            "continuity_scenes=%d, transform_like=%d, fade_in_out=%d",
                            continuityScenes, transformLike, fadeCycles));
        } else if (continuityScenes >= 2 && transformLike <= 1 && fadeCycles >= 4) {
            addFinding(analysis, "weak_transform_continuity", "warn",
                    "The storyboard expects persistent visual continuity, but the code uses few transforms.",
                    String.format(Locale.ROOT,
                            "continuity_scenes=%d, transform_like=%d, fade_in_out=%d",
                            continuityScenes, transformLike, fadeCycles));
        }
    }

    private void addCodeDrivenFindings(StaticAnalysis analysis) {
        if (analysis.getThreeDObjectCount() > 0 && !analysis.isThreeDScene()
                && !hasRule(analysis, "three_d_scene_required")) {
            addFinding(analysis, "three_d_scene_required", "fail",
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
            addFinding(analysis, "three_d_overlay_unfixed", "fail",
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

        if (analysis.getToEdgeCount() >= 7 || analysis.getLargeShiftCount() >= 5) {
            addFinding(analysis, "edge_push_abuse", "fail",
                    "The code heavily relies on edge pushes or large shifts, which raises drift/offscreen risk.",
                    String.format(Locale.ROOT,
                            "to_edge=%d, large_shift=%d",
                            analysis.getToEdgeCount(), analysis.getLargeShiftCount()));
        } else if (analysis.getToEdgeCount() >= 4 || analysis.getLargeShiftCount() >= 3) {
            addFinding(analysis, "edge_push_abuse", "warn",
                    "The code leans on `to_edge` or large shifts more than is ideal for stable layouts.",
                    String.format(Locale.ROOT,
                            "to_edge=%d, large_shift=%d",
                            analysis.getToEdgeCount(), analysis.getLargeShiftCount()));
        }

        if (analysis.getMathTexCount() + analysis.getTextCount() >= 8
                && analysis.getArrangeCount() == 0
                && analysis.getNextToCount() == 0) {
            addFinding(analysis, "spacing_strategy_missing", "fail",
                    "The code creates many text/formula objects without a clear spacing strategy.",
                    String.format(Locale.ROOT,
                            "mathtex=%d, text=%d, arrange=%d, next_to=%d",
                            analysis.getMathTexCount(), analysis.getTextCount(),
                            analysis.getArrangeCount(), analysis.getNextToCount()));
        } else if (analysis.getMathTexCount() + analysis.getTextCount() >= 5
                && analysis.getArrangeCount() == 0
                && analysis.getNextToCount() < 2) {
            addFinding(analysis, "spacing_strategy_missing", "warn",
                    "The code may lack a consistent arrangement/spacing pattern for textual content.",
                    String.format(Locale.ROOT,
                            "mathtex=%d, text=%d, arrange=%d, next_to=%d",
                            analysis.getMathTexCount(), analysis.getTextCount(),
                            analysis.getArrangeCount(), analysis.getNextToCount()));
        }

        if (analysis.getSceneCount() > 0 && analysis.getCodeLines() > Math.max(260, analysis.getSceneCount() * 120)) {
            addFinding(analysis, "code_bloat", "warn",
                    "The generated code looks long relative to the planned storyboard size.",
                    String.format(Locale.ROOT,
                            "code_lines=%d, scene_count=%d",
                            analysis.getCodeLines(), analysis.getSceneCount()));
        }
    }

    private double toSeconds(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() / 1000.0;
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
