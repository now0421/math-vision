package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.Narrative;
import com.automanim.model.Narrative.Storyboard;
import com.automanim.model.Narrative.StoryboardObject;
import com.automanim.model.Narrative.StoryboardScene;
import com.automanim.model.CodeEvaluationResult;
import com.automanim.model.CodeEvaluationResult.ReviewSnapshot;
import com.automanim.model.CodeEvaluationResult.StaticAnalysis;
import com.automanim.model.CodeEvaluationResult.StaticFinding;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.fasterxml.jackson.databind.JsonNode;
import com.automanim.util.JsonUtils;
import com.automanim.util.PromptTemplates;
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
    private static final int MAX_REVISION_ATTEMPTS = 3;

    private static final Pattern SCENE_CLASS = Pattern.compile("class\\s+(\\w+)\\s*\\(.*?Scene.*?\\)");
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

    private static final String CODE_REVIEW_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"review_code_quality\","
            + "    \"description\": \"Return a structured Manim code quality review before render.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"approved_for_render\": { \"type\": \"boolean\" },"
            + "        \"layout_score\": { \"type\": \"integer\" },"
            + "        \"continuity_score\": { \"type\": \"integer\" },"
            + "        \"pacing_score\": { \"type\": \"integer\" },"
            + "        \"clutter_risk\": { \"type\": \"integer\" },"
            + "        \"likely_offscreen_risk\": { \"type\": \"integer\" },"
            + "        \"summary\": { \"type\": \"string\" },"
            + "        \"strengths\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "        \"blocking_issues\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } },"
            + "        \"revision_directives\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } }"
            + "      },"
            + "      \"required\": ["
            + "        \"approved_for_render\","
            + "        \"layout_score\","
            + "        \"continuity_score\","
            + "        \"pacing_score\","
            + "        \"clutter_risk\","
            + "        \"likely_offscreen_risk\""
            + "      ]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private AiClient aiClient;
    private int toolCalls;

    public CodeEvaluationNode() {
        super(1, 1000);
    }

    public static class CodeEvaluationInput {
        private final CodeResult codeResult;
        private final Narrative narrative;
        private final WorkflowConfig config;
        private final Path outputDir;

        public CodeEvaluationInput(CodeResult codeResult,
                                   Narrative narrative,
                                   WorkflowConfig config,
                                   Path outputDir) {
            this.codeResult = codeResult;
            this.narrative = narrative;
            this.config = config;
            this.outputDir = outputDir;
        }

        public CodeResult codeResult() { return codeResult; }
        public Narrative narrative() { return narrative; }
        public WorkflowConfig config() { return config; }
        public Path outputDir() { return outputDir; }
    }

    @Override
    public CodeEvaluationInput prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.toolCalls = 0;
        return new CodeEvaluationInput(
                (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT),
                (Narrative) ctx.get(WorkflowKeys.NARRATIVE),
                (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG),
                (Path) ctx.get(WorkflowKeys.OUTPUT_DIR)
        );
    }

    @Override
    public CodeEvaluationResult exec(CodeEvaluationInput input) {
        Instant start = Instant.now();
        log.info("=== Stage 3: Code Evaluation ===");

        CodeResult codeResult = input.codeResult();
        Narrative narrative = input.narrative();

        CodeEvaluationResult result = new CodeEvaluationResult();
        if (codeResult == null || !codeResult.hasCode()) {
            result.setApprovedForRender(false);
            result.setGateReason("No code available for code evaluation");
            result.setExecutionTimeSeconds(toSeconds(start));
            return result;
        }

        String currentCode = codeResult.getManimCode();
        String sceneName = extractSceneName(currentCode, codeResult.getSceneName());
        codeResult.setSceneName(sceneName);
        result.setSceneName(sceneName);

        StaticAnalysis initialStatic = analyzeStaticQuality(narrative, codeResult, currentCode);
        ReviewSnapshot initialReview = requestCodeReview(narrative, codeResult, sceneName, currentCode, initialStatic);

        result.setInitialStaticAnalysis(initialStatic);
        result.setInitialReview(initialReview);
        result.setFinalStaticAnalysis(initialStatic);
        result.setFinalReview(initialReview);

        boolean approved = passesGate(initialReview, initialStatic);
        if (!approved) {
            result.setRevisionTriggered(true);
            log.warn("Code evaluation flagged scene {}, triggering advisory code revision passes (max={})",
                    sceneName, MAX_REVISION_ATTEMPTS);
        }

        for (int attempt = 0; !approved && attempt < MAX_REVISION_ATTEMPTS; attempt++) {
            result.setRevisionAttempts(attempt + 1);
            String revisedCode = requestCodeRevision(
                    narrative, codeResult, sceneName, currentCode, initialStatic, initialReview);

            if (revisedCode == null || revisedCode.isBlank()
                    || normalizeCode(revisedCode).equals(normalizeCode(currentCode))) {
                log.warn("Visual revision attempt {} produced no meaningful code change", attempt + 1);
                break;
            }

            currentCode = revisedCode;
            sceneName = extractSceneName(currentCode, sceneName);
            codeResult.setManimCode(currentCode);
            codeResult.setSceneName(sceneName);
            result.setSceneName(sceneName);
            result.setRevisedCodeApplied(true);

            StaticAnalysis revisedStatic = analyzeStaticQuality(narrative, codeResult, currentCode);
            ReviewSnapshot revisedReview = requestCodeReview(
                    narrative, codeResult, sceneName, currentCode, revisedStatic);

            result.setFinalStaticAnalysis(revisedStatic);
            result.setFinalReview(revisedReview);
            approved = passesGate(revisedReview, revisedStatic);
        }

        result.setApprovedForRender(approved);
        result.setToolCalls(toolCalls);
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
        ctx.put(WorkflowKeys.CODE_RESULT, input.codeResult());
        ctx.put(WorkflowKeys.CODE_EVALUATION_RESULT, result);

        Path outputDir = input.outputDir();
        if (outputDir != null) {
            FileOutputService.saveCodeEvaluation(outputDir, result, input.codeResult());
        }

        return null;
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

        Storyboard storyboard = narrative != null ? narrative.getStoryboard() : null;
        if (storyboard != null && storyboard.getScenes() != null) {
            analysis.setSceneCount(storyboard.getScenes().size());
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
                ? JsonUtils.toPrettyJson(narrative.getStoryboard())
                : "{\"scenes\":[]}";
        String staticAnalysisJson = JsonUtils.toPrettyJson(analysis);

        try {
            JsonNode rawResponse = aiClient.chatWithToolsRaw(
                    PromptTemplates.codeReviewUserPrompt(
                            targetConcept, sceneName, storyboardJson, staticAnalysisJson, code),
                    PromptTemplates.codeEvaluationSystemPrompt(
                            targetConcept, codeResult.getTargetDescription()),
                    CODE_REVIEW_TOOL);
            toolCalls++;
            ReviewSnapshot parsed = parseReviewSnapshot(
                    JsonUtils.extractToolCallPayload(rawResponse),
                    JsonUtils.extractBestEffortTextFromResponse(rawResponse));
            if (parsed != null) {
                return normalizeReview(parsed);
            }
            log.warn("Code reviewer returned no parseable structure, falling back to static synthesis");
        } catch (Exception e) {
            log.warn("Code reviewer request failed, using static fallback: {}", e.getMessage());
        }
        return fallbackReviewFromStaticAnalysis(analysis);
    }

    private String requestCodeRevision(Narrative narrative,
                                       CodeResult codeResult,
                                       String sceneName,
                                       String code,
                                       StaticAnalysis analysis,
                                       ReviewSnapshot review) {
        String targetConcept = codeResult.getTargetConcept() != null
                ? codeResult.getTargetConcept()
                : sceneName;
        String storyboardJson = narrative != null && narrative.hasStoryboard()
                ? JsonUtils.toPrettyJson(narrative.getStoryboard())
                : "{\"scenes\":[]}";
        String staticAnalysisJson = JsonUtils.toPrettyJson(analysis);
        String reviewJson = JsonUtils.toPrettyJson(review);

        try {
            String response = aiClient.chat(
                    PromptTemplates.codeRevisionUserPrompt(
                            targetConcept,
                            sceneName,
                            storyboardJson,
                            staticAnalysisJson,
                            reviewJson,
                            code),
                    PromptTemplates.codeRevisionSystemPrompt(
                            targetConcept,
                            codeResult.getTargetDescription()));
            toolCalls++;
            return JsonUtils.extractCodeBlock(response);
        } catch (Exception e) {
            log.warn("Code revision request failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean passesGate(ReviewSnapshot review, StaticAnalysis analysis) {
        if (review == null) {
            return false;
        }
        return review.getLayoutScore() >= MIN_LAYOUT_SCORE
                && review.getContinuityScore() >= MIN_CONTINUITY_SCORE
                && review.getPacingScore() >= MIN_PACING_SCORE
                && review.getClutterRisk() <= MAX_CLUTTER_RISK
                && review.getLikelyOffscreenRisk() <= MAX_OFFSCREEN_RISK;
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
            if (review.getClutterRisk() > MAX_CLUTTER_RISK) {
                reasons.add("clutter_risk=" + safeScore(review.getClutterRisk()) + " > " + MAX_CLUTTER_RISK);
            }
            if (review.getLikelyOffscreenRisk() > MAX_OFFSCREEN_RISK) {
                reasons.add("likely_offscreen_risk=" + safeScore(review.getLikelyOffscreenRisk())
                        + " > " + MAX_OFFSCREEN_RISK);
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
            return parseReviewNode(JsonUtils.parseTree(JsonUtils.extractJsonObject(rawText)));
        } catch (RuntimeException e) {
            log.debug("Failed to parse review JSON from text response: {}", e.getMessage());
            return null;
        }
    }

    private ReviewSnapshot parseReviewNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        ReviewSnapshot snapshot = new ReviewSnapshot();
        snapshot.setApprovedForRender(readBoolean(node, "approved_for_render", "approvedForRender"));
        snapshot.setLayoutScore(readInt(node, "layout_score", "layoutScore"));
        snapshot.setContinuityScore(readInt(node, "continuity_score", "continuityScore"));
        snapshot.setPacingScore(readInt(node, "pacing_score", "pacingScore"));
        snapshot.setClutterRisk(readInt(node, "clutter_risk", "clutterRisk"));
        snapshot.setLikelyOffscreenRisk(readInt(node, "likely_offscreen_risk", "likelyOffscreenRisk"));
        snapshot.setSummary(readText(node, "summary"));
        snapshot.setStrengths(readStringList(node, "strengths"));
        snapshot.setBlockingIssues(readStringList(node, "blocking_issues", "blockingIssues"));
        snapshot.setRevisionDirectives(readStringList(node, "revision_directives", "revisionDirectives"));

        if (snapshot.getLayoutScore() <= 0
                && snapshot.getContinuityScore() <= 0
                && snapshot.getPacingScore() <= 0
                && snapshot.getClutterRisk() <= 0
                && snapshot.getLikelyOffscreenRisk() <= 0) {
            return null;
        }
        return snapshot;
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
        int failCount = countFindingsBySeverity(analysis, "fail");
        int warningCount = countFindingsBySeverity(analysis, "warn");
        List<String> blockingIssues = new ArrayList<>();
        List<String> directives = new ArrayList<>();

        for (StaticFinding finding : analysis.getFindings()) {
            if ("fail".equalsIgnoreCase(finding.getSeverity())) {
                blockingIssues.add(finding.getSummary());
            }
            directives.add(finding.getSummary());
        }

        review.setLayoutScore(Math.max(2, 8 - failCount - (warningCount / 2)));
        review.setContinuityScore(Math.max(2,
                8 - (hasRuleWithSeverity(analysis, "weak_transform_continuity", "fail") ? 2 : 0)
                        - (hasRuleWithSeverity(analysis, "weak_transform_continuity", "warn") ? 1 : 0)
                        - (warningCount / 2)));
        review.setPacingScore(Math.max(2,
                8 - (hasRuleWithSeverity(analysis, "pacing_mismatch_dense", "fail") ? 2 : 0)
                        - (hasRuleWithSeverity(analysis, "pacing_mismatch_dense", "warn") ? 1 : 0)
                        - (hasRuleWithSeverity(analysis, "pacing_mismatch_sparse", "warn") ? 1 : 0)));
        review.setClutterRisk(Math.min(10,
                2 + failCount
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
        review.setSummary("Fallback review synthesized from static visual analysis.");
        review.setApprovedForRender(review.getLayoutScore() >= MIN_LAYOUT_SCORE
                && review.getContinuityScore() >= MIN_CONTINUITY_SCORE
                && review.getPacingScore() >= MIN_PACING_SCORE
                && review.getClutterRisk() <= MAX_CLUTTER_RISK
                && review.getLikelyOffscreenRisk() <= MAX_OFFSCREEN_RISK);
        return review;
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

    private String extractSceneName(String code, String fallback) {
        if (code != null) {
            Matcher matcher = SCENE_CLASS.matcher(code);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return fallback != null && !fallback.isBlank() ? fallback : "MainScene";
    }

    private String normalizeCode(String code) {
        return code == null ? "" : code.trim().replace("\r\n", "\n");
    }

    private double toSeconds(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() / 1000.0;
    }
}
