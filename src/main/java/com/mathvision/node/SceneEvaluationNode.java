package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeFixRequest;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.CodeResult;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.RenderResult;
import com.mathvision.model.SceneEvaluationResult;
import com.mathvision.model.SceneEvaluationResult.Bounds;
import com.mathvision.model.SceneEvaluationResult.ElementRef;
import com.mathvision.model.SceneEvaluationResult.LayoutIssue;
import com.mathvision.model.SceneEvaluationResult.Overflow;
import com.mathvision.model.SceneEvaluationResult.SampleEvaluation;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.node.support.FixRetryState;
import com.mathvision.prompt.StoryboardJsonBuilder;
import com.mathvision.util.ErrorSummarizer;
import com.mathvision.util.GeoGebraCodeUtils;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.StoryboardConstraintUtils;
import com.mathvision.util.StoryboardPatchResolver;
import com.mathvision.util.TextUtils;
import com.mathvision.util.TimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stage 5: Scene evaluation - inspects sampled geometry output after render and
 * routes code fixes when layout issues are detected.
 */
public class SceneEvaluationNode extends PocketFlow.Node<SceneEvaluationNode.SceneEvaluationInput,
        SceneEvaluationResult, String> {

    private static final Logger log = LoggerFactory.getLogger(SceneEvaluationNode.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final double OFFSCREEN_TOLERANCE = 0.03;
    private static final double MIN_OVERLAP_AREA = 0.015;
    private static final double MIN_OVERLAP_RATIO = 0.08;
    private static final double SPATIAL_BUCKET_SIZE = 1.25;
    private static final double GEOMETRIC_TOUCH_TOLERANCE = 0.12;
    private static final double GEOMETRIC_ENDPOINT_TOLERANCE = 0.18;
    private static final double LINE_TEXT_INTERSECTION_TOLERANCE = 0.03;
    private static final double GEOGEBRA_MIN_VIEW_WIDTH_UTILIZATION = 0.25;
    private static final double GEOGEBRA_MIN_VIEW_HEIGHT_UTILIZATION = 0.20;
    private static final int DEFAULT_MAX_FIX_ATTEMPTS = 2;
    private static final int MAX_FIX_REPORT_SAMPLES = 12;
    private static final int MAX_ISSUES_PER_SAMPLE_IN_FIX_REPORT = 6;

    public SceneEvaluationNode() {
        super(1, 0);
    }

    public static class SceneEvaluationInput {
        private final CodeResult codeResult;
        private final Narrative narrative;
        private final RenderResult renderResult;
        private final WorkflowConfig config;
        private final Path outputDir;
        private final SceneEvaluationRetryState retryState;

        public SceneEvaluationInput(CodeResult codeResult,
                                    Narrative narrative,
                                    RenderResult renderResult,
                                    WorkflowConfig config,
                                    Path outputDir,
                                    SceneEvaluationRetryState retryState) {
            this.codeResult = codeResult;
            this.narrative = narrative;
            this.renderResult = renderResult;
            this.config = config;
            this.outputDir = outputDir;
            this.retryState = retryState;
        }

        public CodeResult codeResult() { return codeResult; }
        public Narrative narrative() { return narrative; }
        public RenderResult renderResult() { return renderResult; }
        public WorkflowConfig config() { return config; }
        public Path outputDir() { return outputDir; }
        public SceneEvaluationRetryState retryState() { return retryState; }
    }

    @Override
    public SceneEvaluationInput prep(Map<String, Object> ctx) {
        SceneEvaluationRetryState retryState =
                (SceneEvaluationRetryState) ctx.get(WorkflowKeys.SCENE_EVALUATION_RETRY_STATE);
        if (retryState == null) {
            retryState = new SceneEvaluationRetryState();
            ctx.put(WorkflowKeys.SCENE_EVALUATION_RETRY_STATE, retryState);
        }

        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        Narrative narrative = (Narrative) ctx.get(WorkflowKeys.NARRATIVE);
        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        WorkflowConfig config = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        return new SceneEvaluationInput(codeResult, narrative, renderResult, config, outputDir, retryState);
    }

    @Override
    public SceneEvaluationResult exec(SceneEvaluationInput input) {
        Instant start = Instant.now();
        SceneEvaluationRetryState retryState = input.retryState();
        retryState.setRequestFix(false);
        retryState.pendingIssueSummary = null;
        retryState.pendingSceneEvaluationJson = null;

        SceneEvaluationResult result = new SceneEvaluationResult();
        result.setToolCalls(0);

        CodeResult codeResult = input.codeResult();
        RenderResult renderResult = input.renderResult();
        result.setSceneName(codeResult != null ? codeResult.getSceneName() : null);
        result.setRenderSuccess(renderResult != null && renderResult.isSuccess());
        result.setGeometryPath(renderResult != null ? renderResult.getGeometryPath() : null);

        log.info("=== Stage 5: Scene Evaluation ===");

        if (renderResult == null) {
            return skipEvaluation(result, retryState, start, "Scene evaluation skipped: render result unavailable");
        }

        if (!renderResult.isSuccess()) {
            return skipEvaluation(result, retryState, start, "Scene evaluation skipped: render was not successful");
        }

        String geometryPath = renderResult.getGeometryPath();
        if (geometryPath == null || geometryPath.isBlank()) {
            return skipEvaluation(result, retryState, start, "Scene evaluation skipped: geometry report unavailable");
        }

        Path geometryFile = Path.of(geometryPath);
        if (!Files.exists(geometryFile)) {
            return skipEvaluation(result, retryState, start, "Scene evaluation skipped: geometry report not found");
        }

        try {
            JsonNode root = MAPPER.readTree(geometryFile.toFile());
            result.setSceneName(readText(root, "scene_name", result.getSceneName()));
            double[] frameMin = readPoint(root.path("frame_bounds").path("min"), new double[] {-7.111111, -4.0, 0.0});
            double[] frameMax = readPoint(root.path("frame_bounds").path("max"), new double[] {7.111111, 4.0, 0.0});

            List<SampleEvaluation> sampleEvaluations = new ArrayList<>();
            int overlapIssues = 0;
            int offscreenIssues = 0;
            int blockingIssues = 0;
            int totalIssues = 0;
            int issueSamples = 0;

            boolean skipOffscreen = false;

            JsonNode samplesNode = root.path("samples");
            if (samplesNode.isArray()) {
                for (JsonNode sampleNode : samplesNode) {
                    SampleEvaluation sampleEvaluation = evaluateSample(
                            sampleNode, frameMin, frameMax, skipOffscreen);
                    sampleEvaluations.add(sampleEvaluation);
                    if (sampleEvaluation.isHasIssues()) {
                        issueSamples++;
                    }
                    overlapIssues += sampleEvaluation.getOverlapIssueCount();
                    offscreenIssues += sampleEvaluation.getOffscreenIssueCount();
                    blockingIssues += sampleEvaluation.getBlockingIssueCount();
                    totalIssues += sampleEvaluation.getIssueCount();
                }
            }

            result.setSamples(sampleEvaluations);
            result.setSampleCount(sampleEvaluations.size());
            result.setIssueSampleCount(issueSamples);
            result.setOverlapIssueCount(overlapIssues);
            result.setOffscreenIssueCount(offscreenIssues);
            result.setBlockingIssueCount(blockingIssues);
            result.setTotalIssueCount(totalIssues);
            result.setEvaluated(true);
            result.setApproved(blockingIssues == 0);

            int attemptsSoFar = retryState.getAttempts();
            if (result.isApproved()) {
                if (totalIssues == 0) {
                    result.setGateReason("Scene evaluation passed: no overlap or offscreen issues detected");
                } else {
                    result.setGateReason(String.format(
                            "Scene evaluation passed: %d non-blocking layout observations were ignored",
                            totalIssues));
                }
                result.setRevisionTriggered(attemptsSoFar > 0);
                result.setRevisionAttempts(attemptsSoFar);
                finalizeResult(result, retryState, start, true);
                return result;
            }

            int maxFixAttempts = resolveMaxFixAttempts(input.config());
            String issueSummary = buildIssueSummary(result);

            if (attemptsSoFar < maxFixAttempts) {
                retryState.setAttempts(attemptsSoFar + 1);
                retryState.setRequestFix(true);
                retryState.pendingIssueSummary = issueSummary;
                retryState.pendingSceneEvaluationJson = buildFixReportJson(result, storyboardFrom(input.narrative()));
                result.setRevisionTriggered(true);
                result.setRevisionAttempts(retryState.getAttempts());
                result.setGateReason(String.format(
                        "Detected %d blocking layout issues across %d samples (%d total observations); routing to code fix (attempt %d/%d)",
                        blockingIssues,
                        issueSamples,
                        totalIssues,
                        retryState.getAttempts(),
                        maxFixAttempts));
                finalizeResult(result, retryState, start, false);
                return result;
            }

            result.setRevisionTriggered(attemptsSoFar > 0);
            result.setRevisionAttempts(attemptsSoFar);
            result.setGateReason(String.format(
                    "Layout issues remain after %d fix attempts; stopping retries", attemptsSoFar));
            finalizeResult(result, retryState, start, true);
            return result;

        } catch (IOException e) {
            log.warn("Scene evaluation could not read geometry report {}: {}", geometryPath, e.getMessage());
            return skipEvaluation(result, retryState, start, "Scene evaluation skipped: failed to parse geometry report");
        }
    }

    @Override
    public String post(Map<String, Object> ctx, SceneEvaluationInput input, SceneEvaluationResult result) {
        ctx.put(WorkflowKeys.SCENE_EVALUATION_RESULT, result);

        if (input.outputDir() != null) {
            FileOutputService.saveSceneEvaluation(input.outputDir(), result);
        }

        if (input.retryState().isRequestFix()) {
            ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildSceneEvaluationFixRequest(input, result));
            appendPendingIssueToFixHistory(input.retryState(), result);
            return WorkflowActions.FIX_CODE;
        }

        return null;
    }

    private CodeFixRequest buildSceneEvaluationFixRequest(SceneEvaluationInput input,
                                                          SceneEvaluationResult result) {
        CodeResult codeResult = input.codeResult();
        Narrative narrative = input.narrative();
        Storyboard storyboard = storyboardFrom(narrative);
        RenderResult renderResult = input.renderResult();
        SceneEvaluationRetryState retryState = input.retryState();

        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.SCENE_LAYOUT_EVALUATION);
        request.setReturnAction(WorkflowActions.RETRY_RENDER);
        request.setGeneratedCode(codeResult.getGeneratedCode());
        request.setErrorReason(retryState.pendingIssueSummary != null
                ? retryState.pendingIssueSummary
                : buildIssueSummary(result));
        request.setSceneEvaluationJson(retryState.pendingSceneEvaluationJson != null
                ? retryState.pendingSceneEvaluationJson
                : buildFixReportJson(result, storyboard));
        request.setTargetConcept(codeResult.getTargetConcept());
        request.setTargetDescription(codeResult.getTargetDescription());
        request.setSceneName(codeResult.getSceneName());
        // For GeoGebra, the expected scene name is typically GeoGebraCodeUtils.EXPECTED_FIGURE_NAME
        boolean isGeoGebra = renderResult != null && renderResult.isGeoGebraTarget();
        request.setOutputTarget(isGeoGebra
                ? WorkflowConfig.OUTPUT_TARGET_GEOGEBRA
                : WorkflowConfig.OUTPUT_TARGET_MANIM);
        request.setExpectedSceneName(isGeoGebra ? GeoGebraCodeUtils.EXPECTED_FIGURE_NAME : "MainScene");
        String outputTarget = isGeoGebra ? WorkflowConfig.OUTPUT_TARGET_GEOGEBRA : WorkflowConfig.OUTPUT_TARGET_MANIM;
        request.setStoryboardJson(storyboard != null
                ? StoryboardJsonBuilder.buildForSceneEvaluationFix(storyboard, outputTarget)
                : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON);
        request.setFixHistory(new ArrayList<>(retryState.fixHistory));
        return request;
    }

    private void appendPendingIssueToFixHistory(SceneEvaluationRetryState retryState,
                                                SceneEvaluationResult result) {
        if (retryState == null) {
            return;
        }
        String issueSummary = retryState.pendingIssueSummary != null
                ? retryState.pendingIssueSummary
                : buildIssueSummary(result);
        String historyEntry = summarizeFixHistory(issueSummary);
        if (historyEntry != null && !historyEntry.isBlank()) {
            retryState.fixHistory.add(historyEntry);
        }
    }

    private SampleEvaluation evaluateSample(JsonNode sampleNode, double[] frameMin, double[] frameMax,
                                            boolean skipOffscreen) {
        SampleEvaluation sample = new SampleEvaluation();
        sample.setSampleId(readText(sampleNode, "sample_id", ""));
        sample.setPlayIndex(sampleNode.hasNonNull("play_index") ? sampleNode.get("play_index").asInt() : null);
        sample.setSampleRole(readText(sampleNode, "sample_role", ""));
        sample.setTrigger(readText(sampleNode, "trigger", ""));
        sample.setSceneMethod(readText(sampleNode, "scene_method", ""));
        sample.setSourceCode(readText(sampleNode, "source_code", ""));

        List<ElementGeometry> elements = readElements(sampleNode.path("elements"), sample.getSampleRole());
        sample.setElementCount(elements.size());

        List<LayoutIssue> issues = new ArrayList<>();
        int offscreenCount = 0;
        int overlapCount = 0;
        int blockingCount = 0;

        // Initial-view bounds are part of the layout contract for every target,
        // including GeoGebra.
        if (!skipOffscreen) {
            for (ElementGeometry element : elements) {
                OverflowMetrics overflow = frameOverflow(element, frameMin, frameMax);
                if (overflow == null) {
                    continue;
                }
                LayoutIssue issue = new LayoutIssue();
                issue.setType("offscreen");
                issue.setMessage(String.format(
                        "Element %s extends outside the frame bounds",
                        element.displayName()));
                issue.setSeverity("blocking");
                issue.setReasonCode("OFFSCREEN");
                issue.setLikelyFalsePositive(false);
                issue.setRecommendedAction("move_or_scale_into_safe_frame");
                issue.setPrimaryElement(toElementRef(element, sample.getSampleRole()));
                issue.setOverflow(toOverflow(overflow));
                issues.add(issue);
                offscreenCount++;
                blockingCount++;
            }
        }

        LayoutIssue underfilledIssue = evaluateUnderfilledView(elements, frameMin, frameMax, sample.getSampleRole());
        if (underfilledIssue != null) {
            issues.add(underfilledIssue);
            blockingCount++;
        }

        List<LayoutIssue> overlapIssues = evaluateOverlapIssues(elements, sample.getSampleRole());
        issues.addAll(overlapIssues);
        overlapCount = overlapIssues.size();
        blockingCount += countBlockingIssues(overlapIssues);

        sample.setIssues(issues);
        sample.setOffscreenIssueCount(offscreenCount);
        sample.setOverlapIssueCount(overlapCount);
        sample.setBlockingIssueCount(blockingCount);
        sample.setIssueCount(issues.size());
        sample.setHasIssues(!issues.isEmpty());
        return sample;
    }

    private LayoutIssue evaluateUnderfilledView(List<ElementGeometry> elements,
                                                double[] frameMin,
                                                double[] frameMax,
                                                String sampleRole) {
        if (elements == null || elements.size() < 3 || frameMin == null || frameMax == null) {
            return null;
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        int boundedCount = 0;
        for (ElementGeometry element : elements) {
            BoundsPair bounds = evaluationBounds(element);
            if (element == null || !element.visible || bounds == null) {
                continue;
            }
            minX = Math.min(minX, bounds.min[0]);
            minY = Math.min(minY, bounds.min[1]);
            maxX = Math.max(maxX, bounds.max[0]);
            maxY = Math.max(maxY, bounds.max[1]);
            boundedCount++;
        }
        if (boundedCount < 3 || !Double.isFinite(minX) || !Double.isFinite(maxX)
                || !Double.isFinite(minY) || !Double.isFinite(maxY)) {
            return null;
        }

        double frameWidth = frameMax[0] - frameMin[0];
        double frameHeight = frameMax[1] - frameMin[1];
        if (frameWidth <= 0 || frameHeight <= 0) {
            return null;
        }
        double utilizationX = (maxX - minX) / frameWidth;
        double utilizationY = (maxY - minY) / frameHeight;
        if (utilizationX >= GEOGEBRA_MIN_VIEW_WIDTH_UTILIZATION
                || utilizationY >= GEOGEBRA_MIN_VIEW_HEIGHT_UTILIZATION) {
            return null;
        }

        LayoutIssue issue = new LayoutIssue();
        issue.setType("underfilled_view");
        issue.setMessage(String.format(
                "Visible objects occupy only %.0f%% of frame width and %.0f%% of frame height",
                utilizationX * 100.0,
                utilizationY * 100.0));
        issue.setSeverity("blocking");
        issue.setReasonCode("UNDERFILLED_VIEW");
        issue.setLikelyFalsePositive(false);
        issue.setRecommendedAction("scale_or_spread_construction_inside_frame");
        ElementGeometry aggregate = new ElementGeometry();
        aggregate.stableId = "visible-bounds";
        aggregate.semanticName = "visible construction";
        aggregate.className = "aggregate";
        aggregate.visible = true;
        aggregate.min = new double[] {minX, minY, 0};
        aggregate.max = new double[] {maxX, maxY, 0};
        issue.setPrimaryElement(toElementRef(aggregate, sampleRole));
        return issue;
    }

    private List<ElementGeometry> readElements(JsonNode elementsNode, String sampleRole) {
        List<ElementGeometry> elements = new ArrayList<>();
        if (!elementsNode.isArray()) {
            return elements;
        }

        for (JsonNode elementNode : elementsNode) {
            boolean visible = elementNode.path("visible").asBoolean(true);
            if (!visible) {
                continue;
            }
            JsonNode boundsNode = preferredBoundsNode(elementNode);
            double[] min = readPoint(boundsNode.path("min"), null);
            double[] max = readPoint(boundsNode.path("max"), null);
            JsonNode shapeHints = elementNode.path("shape_hints");
            JsonNode evaluationShape = elementNode.path("evaluation_shape");
            String evaluationShapeType = readNullableText(evaluationShape, "type");
            List<double[]> geometryPoints = readPointList(elementNode.path("geometry_points"));
            List<double[]> evaluationPoints = readEvaluationShapePoints(evaluationShape);
            double[] start = readPoint(shapeHints.path("start"), null);
            double[] end = readPoint(shapeHints.path("end"), null);
            List<double[]> pathPoints = readPointList(shapeHints.path("path_points"));
            if (pathPoints.isEmpty() && !evaluationPoints.isEmpty()) {
                pathPoints = evaluationPoints;
            }
            if (pathPoints.isEmpty() && !geometryPoints.isEmpty()) {
                pathPoints = geometryPoints;
            }
            if ((start == null || end == null) && pathPoints.size() >= 2) {
                start = pathPoints.get(0);
                end = pathPoints.get(pathPoints.size() - 1);
            }
            Double radius = readDouble(shapeHints.path("radius"), null);
            if (radius == null) {
                radius = readDouble(evaluationShape.path("radius"), null);
            }
            double[] arcCenter = readPoint(shapeHints.path("arc_center"), null);
            if (arcCenter == null) {
                arcCenter = readPoint(evaluationShape.path("center"), null);
            }
            if ((min == null || max == null) && !evaluationShape.isMissingNode()) {
                BoundsPair shapeBounds = boundsFromEvaluationShape(evaluationShape, pathPoints, radius);
                if (shapeBounds != null) {
                    min = shapeBounds.min;
                    max = shapeBounds.max;
                }
            }
            if ((min == null || max == null) && !pathPoints.isEmpty()) {
                BoundsPair pointBounds = boundsFromPoints(pathPoints, shapePadding(evaluationShapeType));
                if (pointBounds != null) {
                    min = pointBounds.min;
                    max = pointBounds.max;
                }
            }
            if (min == null || max == null) {
                continue;
            }

            ElementGeometry element = new ElementGeometry();
            element.stableId = readNullableText(elementNode, "stable_id");
            element.semanticName = TextUtils.firstNonBlank(
                    readNullableText(elementNode, "semantic_name"),
                    readNullableText(elementNode, "name"));
            element.className = readText(elementNode, "class_name", "Unknown");
            element.semanticClass = readNullableText(elementNode, "semantic_class");
            element.displayText = readNullableText(elementNode, "display_text");
            element.topLevelStableId = readNullableText(elementNode, "top_level_stable_id");
            element.visible = visible;
            element.center = readPoint(preferredCenterNode(elementNode), centerFromBounds(min, max));
            element.width = round(Math.max(max[0] - min[0], 0.0));
            element.height = round(Math.max(max[1] - min[1], 0.0));
            element.start = start;
            element.end = end;
            element.arcCenter = arcCenter;
            element.radius = radius;
            element.pathPoints = pathPoints;
            element.geometryType = TextUtils.firstNonBlank(evaluationShapeType, readNullableText(elementNode, "geometry_type"));
            element.min = min;
            element.max = max;
            elements.add(element);
        }

        return elements;
    }

    private JsonNode preferredBoundsNode(JsonNode elementNode) {
        if (elementNode == null || elementNode.isMissingNode()) {
            return MAPPER.createObjectNode();
        }
        JsonNode screenBounds = elementNode.path("screen_bounds");
        if (screenBounds.isObject()
                && screenBounds.has("min")
                && screenBounds.has("max")) {
            return screenBounds;
        }
        return elementNode.path("bounds");
    }

    private JsonNode preferredCenterNode(JsonNode elementNode) {
        if (elementNode == null || elementNode.isMissingNode()) {
            return MAPPER.createArrayNode();
        }
        JsonNode screenCenter = elementNode.path("screen_center");
        if (screenCenter.isArray() && screenCenter.size() >= 2) {
            return screenCenter;
        }
        JsonNode center = elementNode.path("center");
        if (center.isArray() && center.size() >= 2) {
            return center;
        }
        return MAPPER.createArrayNode();
    }

    private List<double[]> readEvaluationShapePoints(JsonNode evaluationShape) {
        if (evaluationShape == null || evaluationShape.isMissingNode() || evaluationShape.isNull()) {
            return new ArrayList<>();
        }
        List<double[]> points = readPointList(evaluationShape.path("points"));
        if (points.isEmpty()) {
            points = readPointList(evaluationShape.path("sample_points"));
        }
        double[] center = readPoint(evaluationShape.path("center"), null);
        if (center != null && points.isEmpty()) {
            points.add(center);
        }
        return points;
    }

    private BoundsPair boundsFromEvaluationShape(JsonNode evaluationShape,
                                                 List<double[]> points,
                                                 Double radius) {
        if (evaluationShape == null || evaluationShape.isMissingNode() || evaluationShape.isNull()) {
            return null;
        }
        double[] min = readPoint(evaluationShape.path("min"), null);
        double[] max = readPoint(evaluationShape.path("max"), null);
        if (min != null && max != null) {
            return new BoundsPair(min, max);
        }
        double[] center = readPoint(evaluationShape.path("center"), null);
        if (center != null && radius != null && Double.isFinite(radius) && radius > 0) {
            return new BoundsPair(
                    new double[] {round(center[0] - radius), round(center[1] - radius), 0.0},
                    new double[] {round(center[0] + radius), round(center[1] + radius), 0.0});
        }
        return boundsFromPoints(points, shapePadding(readNullableText(evaluationShape, "type")));
    }

    private BoundsPair boundsFromPoints(List<double[]> points, double padding) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (double[] point : points) {
            if (point == null || point.length < 2) {
                continue;
            }
            minX = Math.min(minX, point[0]);
            minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxY = Math.max(maxY, point[1]);
            count++;
        }
        if (count == 0 || !Double.isFinite(minX) || !Double.isFinite(maxX)
                || !Double.isFinite(minY) || !Double.isFinite(maxY)) {
            return null;
        }
        return new BoundsPair(
                new double[] {round(minX - padding), round(minY - padding), 0.0},
                new double[] {round(maxX + padding), round(maxY + padding), 0.0});
    }

    private double shapePadding(String shapeType) {
        if (shapeType == null || shapeType.isBlank()) {
            return 0.05;
        }
        String type = shapeType.toLowerCase(Locale.ROOT);
        if (type.contains("point")) {
            return 0.15;
        }
        if (type.contains("segment") || type.contains("line") || type.contains("ray")) {
            return 0.05;
        }
        return 0.0;
    }

    private Double readDouble(JsonNode node, Double fallback) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return fallback;
        }
        return round(node.asDouble());
    }

    private OverflowMetrics frameOverflow(ElementGeometry element, double[] frameMin, double[] frameMax) {
        BoundsPair evaluationBounds = evaluationBounds(element);
        if (evaluationBounds == null) {
            return null;
        }
        double left = Math.max(frameMin[0] - evaluationBounds.min[0], 0.0);
        double right = Math.max(evaluationBounds.max[0] - frameMax[0], 0.0);
        double bottom = Math.max(frameMin[1] - evaluationBounds.min[1], 0.0);
        double top = Math.max(evaluationBounds.max[1] - frameMax[1], 0.0);
        if (Math.max(Math.max(left, right), Math.max(bottom, top)) <= OFFSCREEN_TOLERANCE) {
            return null;
        }
        return new OverflowMetrics(left, right, top, bottom);
    }

    private BoundsPair evaluationBounds(ElementGeometry element) {
        if (element == null) {
            return null;
        }
        if (element.radius != null && element.radius > 0 && element.arcCenter != null
                && (isCircleLike(element) || "circle".equalsIgnoreCase(element.geometryType))) {
            return new BoundsPair(
                    new double[] {round(element.arcCenter[0] - element.radius), round(element.arcCenter[1] - element.radius), 0.0},
                    new double[] {round(element.arcCenter[0] + element.radius), round(element.arcCenter[1] + element.radius), 0.0});
        }
        if (element.hasPathPoints()) {
            return boundsFromPoints(element.pathPoints, shapePadding(element.geometryType));
        }
        if (element.min == null || element.max == null) {
            return null;
        }
        return new BoundsPair(element.min, element.max);
    }

    private BoundsPair bucketBounds(ElementGeometry element) {
        BoundsPair bounds = evaluationBounds(element);
        if (bounds != null) {
            return bounds;
        }
        return new BoundsPair(element.min, element.max);
    }

    private List<LayoutIssue> evaluateOverlapIssues(List<ElementGeometry> elements, String sampleRole) {
        List<LayoutIssue> issues = new ArrayList<>();
        if (elements.size() < 2) {
            return issues;
        }

        Map<Long, List<Integer>> buckets = buildSpatialBuckets(elements);
        for (int i = 0; i < elements.size(); i++) {
            ElementGeometry left = elements.get(i);
            BucketRange range = bucketRange(left);
            Set<Integer> seenCandidates = new LinkedHashSet<>();

            for (int bucketX = range.minX; bucketX <= range.maxX; bucketX++) {
                for (int bucketY = range.minY; bucketY <= range.maxY; bucketY++) {
                    List<Integer> bucketElements = buckets.get(bucketKey(bucketX, bucketY));
                    if (bucketElements == null || bucketElements.isEmpty()) {
                        continue;
                    }
                    for (Integer candidateIndex : bucketElements) {
                        if (candidateIndex == null
                                || candidateIndex <= i
                                || !seenCandidates.add(candidateIndex)) {
                            continue;
                        }

                        ElementGeometry right = elements.get(candidateIndex);
                        if (!shouldCheckOverlap(left, right)) {
                            continue;
                        }

                        OverlapMetrics overlap = overlap(left, right);
                        if (overlap == null) {
                            continue;
                        }

                        OverlapDisposition disposition = classifyOverlap(left, right, overlap);
                        if (disposition == null || disposition.ignore) {
                            continue;
                        }

                        LayoutIssue issue = new LayoutIssue();
                        issue.setType("overlap");
                        issue.setMessage(disposition.message != null
                                ? disposition.message
                                : String.format(
                                        "Elements %s and %s overlap in sampled frame",
                                        left.displayName(),
                                        right.displayName()));
                        issue.setSeverity(disposition.severity);
                        issue.setReasonCode(disposition.reasonCode);
                        issue.setLikelyFalsePositive(disposition.likelyFalsePositive);
                        issue.setRecommendedAction(disposition.recommendedAction);
                        issue.setPrimaryElement(toElementRef(left, sampleRole));
                        issue.setSecondaryElement(toElementRef(right, sampleRole));
                        issue.setIntersectionArea(overlap.area);
                        issue.setIntersectionBounds(toBounds(overlap.min, overlap.max));
                        issues.add(issue);
                    }
                }
            }
        }
        return issues;
    }

    private Map<Long, List<Integer>> buildSpatialBuckets(List<ElementGeometry> elements) {
        Map<Long, List<Integer>> buckets = new LinkedHashMap<>();
        for (int index = 0; index < elements.size(); index++) {
            BucketRange range = bucketRange(elements.get(index));
            for (int bucketX = range.minX; bucketX <= range.maxX; bucketX++) {
                for (int bucketY = range.minY; bucketY <= range.maxY; bucketY++) {
                    long key = bucketKey(bucketX, bucketY);
                    buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
                }
            }
        }
        return buckets;
    }

    private BucketRange bucketRange(ElementGeometry element) {
        BoundsPair bounds = bucketBounds(element);
        return new BucketRange(
                bucketIndex(bounds.min[0]),
                bucketIndex(bounds.max[0]),
                bucketIndex(bounds.min[1]),
                bucketIndex(bounds.max[1]));
    }

    private int bucketIndex(double value) {
        return (int) Math.floor(value / SPATIAL_BUCKET_SIZE);
    }

    private long bucketKey(int bucketX, int bucketY) {
        return (((long) bucketX) << 32) ^ (bucketY & 0xffffffffL);
    }

    private boolean shouldCheckOverlap(ElementGeometry left, ElementGeometry right) {
        if (left == null || right == null) {
            return false;
        }
        if (!left.visible || !right.visible) {
            return false;
        }
        if (left.stableId != null && left.stableId.equals(right.stableId)) {
            return false;
        }
        if (left.topLevelStableId != null
                && right.topLevelStableId != null
                && left.topLevelStableId.equals(right.topLevelStableId)) {
            return false;
        }
        if (left.className.equals(right.className)
                && isZeroArea(left)
                && isZeroArea(right)) {
            return false;
        }
        if (!left.isTextual() && !right.isTextual()) {
            return false;
        }
        return true;
    }

    private OverlapMetrics overlap(ElementGeometry left, ElementGeometry right) {
        BoundsPair leftBounds = evaluationBounds(left);
        BoundsPair rightBounds = evaluationBounds(right);
        if (leftBounds == null || rightBounds == null) {
            return null;
        }
        double overlapMinX = Math.max(leftBounds.min[0], rightBounds.min[0]);
        double overlapMaxX = Math.min(leftBounds.max[0], rightBounds.max[0]);
        double overlapMinY = Math.max(leftBounds.min[1], rightBounds.min[1]);
        double overlapMaxY = Math.min(leftBounds.max[1], rightBounds.max[1]);
        double overlapWidth = overlapMaxX - overlapMinX;
        double overlapHeight = overlapMaxY - overlapMinY;
        if (overlapWidth <= 1e-9 || overlapHeight <= 1e-9) {
            return null;
        }

        double area = overlapWidth * overlapHeight;
        double leftArea = Math.max((leftBounds.max[0] - leftBounds.min[0]) * (leftBounds.max[1] - leftBounds.min[1]), 1e-9);
        double rightArea = Math.max((rightBounds.max[0] - rightBounds.min[0]) * (rightBounds.max[1] - rightBounds.min[1]), 1e-9);
        double minAreaRatio = area / Math.min(leftArea, rightArea);
        if (area < MIN_OVERLAP_AREA || minAreaRatio < MIN_OVERLAP_RATIO) {
            return null;
        }

        return new OverlapMetrics(
                round(area),
                new double[] {round(overlapMinX), round(overlapMinY), 0.0},
                new double[] {round(overlapMaxX), round(overlapMaxY), 0.0}
        );
    }

    private OverlapDisposition classifyOverlap(ElementGeometry left,
                                               ElementGeometry right,
                                               OverlapMetrics overlap) {
        boolean leftText = left.isTextual();
        boolean rightText = right.isTextual();

        if (leftText && rightText) {
            return blockingDisposition(
                    "TEXT_TEXT_OVERLAP",
                    String.format("Text elements %s and %s overlap in sampled frame",
                            left.displayName(), right.displayName()),
                    "separate_text_blocks");
        }

        if (leftText || rightText) {
            ElementGeometry text = leftText ? left : right;
            ElementGeometry other = leftText ? right : left;
            if (sameSemanticFamily(text, other)) {
                return warningDisposition(
                        "TEXT_ATTACHED_TO_OWN_OBJECT",
                        String.format("Label %s overlaps its attached object %s",
                                text.displayName(), other.displayName()),
                        "ignore_or_adjust_label_buffer");
            }
            if (!geometryIntersectsText(other, text.min, text.max, LINE_TEXT_INTERSECTION_TOLERANCE)) {
                return ignoredDisposition();
            }
            return blockingDisposition(
                    "TEXT_GEOMETRY_OVERLAP",
                    String.format("Text element %s overlaps geometry element %s",
                            text.displayName(), other.displayName()),
                    "move_text_away_from_geometry");
        }

        if (isOptimalPointAlignment(left, right)) {
            return infoDisposition(
                    "OPTIMAL_POINT_ALIGNMENT",
                    String.format("Elements %s and %s are intentionally aligned at the optimal point",
                            left.displayName(), right.displayName()),
                    "keep_alignment");
        }

        if (isPointLineContact(left, right)) {
            return ignoredDisposition();
        }

        if (isPointLinePair(left, right)) {
            return ignoredDisposition();
        }

        if (left.isLineLike() && right.isLineLike()) {
            if (!linesActuallyInteract(left, right)) {
                return ignoredDisposition();
            }
            if (isLineLineSharedEndpoint(left, right) || isLineLineNearCollinear(left, right, overlap)) {
                return infoDisposition(
                        "GEOMETRIC_LINE_CONTACT",
                        String.format("Lines %s and %s make geometric contact that is acceptable in a proof scene",
                                left.displayName(), right.displayName()),
                        "ignore_unless_textual_occlusion");
            }
            return infoDisposition(
                    "GEOMETRIC_LINE_INTERSECTION",
                    String.format("Lines %s and %s intersect as part of the construction",
                            left.displayName(), right.displayName()),
                    "keep_if_readable");
        }

        if (sameSemanticFamily(left, right)) {
            return infoDisposition(
                    "SAME_FAMILY_CONTACT",
                    String.format("Elements %s and %s belong to the same semantic family and overlap",
                            left.displayName(), right.displayName()),
                    "ignore_unless_readability_suffers");
        }

        return blockingDisposition(
                "GEOMETRY_GEOMETRY_OVERLAP",
                String.format("Geometry elements %s and %s overlap in sampled frame",
                        left.displayName(), right.displayName()),
                "review_if_overlap_obscures_teaching_focus");
    }

    private int countBlockingIssues(List<LayoutIssue> issues) {
        int count = 0;
        if (issues == null) {
            return 0;
        }
        for (LayoutIssue issue : issues) {
            if (issue != null && "blocking".equalsIgnoreCase(issue.getSeverity())) {
                count++;
            }
        }
        return count;
    }

    private OverlapDisposition blockingDisposition(String reasonCode, String message, String action) {
        return new OverlapDisposition(false, "blocking", reasonCode, false, action, message);
    }

    private OverlapDisposition warningDisposition(String reasonCode, String message, String action) {
        return new OverlapDisposition(false, "warning", reasonCode, false, action, message);
    }

    private OverlapDisposition infoDisposition(String reasonCode, String message, String action) {
        return new OverlapDisposition(false, "info", reasonCode, true, action, message);
    }

    private OverlapDisposition ignoredDisposition() {
        return new OverlapDisposition(true, "info", "IGNORED_GEOMETRIC_CONTACT", true, "ignore", null);
    }

    private boolean isPointLinePair(ElementGeometry left, ElementGeometry right) {
        return (left.isPointLike() && right.isLineLike()) || (right.isPointLike() && left.isLineLike());
    }

    private boolean isPointLineContact(ElementGeometry left, ElementGeometry right) {
        if (left.isPointLike() && right.isLineLike()) {
            return pointTouchesLine(left, right);
        }
        if (right.isPointLike() && left.isLineLike()) {
            return pointTouchesLine(right, left);
        }
        return false;
    }

    private boolean pointTouchesLine(ElementGeometry point, ElementGeometry line) {
        double[] pointCenter = bestPointCenter(point);
        if (pointCenter == null || line.start == null || line.end == null) {
            return false;
        }
        double distance = distancePointToSegment(pointCenter, line.start, line.end);
        if (distance > GEOMETRIC_TOUCH_TOLERANCE) {
            return false;
        }
        return distance(pointCenter, line.start) <= GEOMETRIC_ENDPOINT_TOLERANCE
                || distance(pointCenter, line.end) <= GEOMETRIC_ENDPOINT_TOLERANCE
                || isPointOnSegment(pointCenter, line.start, line.end, GEOMETRIC_TOUCH_TOLERANCE);
    }

    private boolean isLineLineSharedEndpoint(ElementGeometry left, ElementGeometry right) {
        if (!left.isLineLike() || !right.isLineLike() || left.start == null || left.end == null
                || right.start == null || right.end == null) {
            return false;
        }
        return anyPointClose(left.start, right.start, GEOMETRIC_ENDPOINT_TOLERANCE)
                || anyPointClose(left.start, right.end, GEOMETRIC_ENDPOINT_TOLERANCE)
                || anyPointClose(left.end, right.start, GEOMETRIC_ENDPOINT_TOLERANCE)
                || anyPointClose(left.end, right.end, GEOMETRIC_ENDPOINT_TOLERANCE);
    }

    private boolean isLineLineNearCollinear(ElementGeometry left,
                                            ElementGeometry right,
                                            OverlapMetrics overlap) {
        if (!left.isLineLike() || !right.isLineLike() || left.start == null || left.end == null
                || right.start == null || right.end == null) {
            return false;
        }
        double angle = angleBetweenSegments(left.start, left.end, right.start, right.end);
        if (Double.isNaN(angle)) {
            return false;
        }
        boolean nearlyParallel = angle <= 8.0 || Math.abs(angle - 180.0) <= 8.0;
        boolean largeThinOverlap = overlap.area >= 0.15 && (left.height <= 0.25 || left.width <= 0.25
                || right.height <= 0.25 || right.width <= 0.25);
        return nearlyParallel && largeThinOverlap;
    }

    private boolean linesActuallyInteract(ElementGeometry left, ElementGeometry right) {
        if (!left.isLineLike() || !right.isLineLike() || left.start == null || left.end == null
                || right.start == null || right.end == null) {
            return false;
        }
        if (segmentsIntersect(left.start, left.end, right.start, right.end, GEOMETRIC_TOUCH_TOLERANCE)) {
            return true;
        }
        return distancePointToSegment(left.start, right.start, right.end) <= GEOMETRIC_TOUCH_TOLERANCE
                || distancePointToSegment(left.end, right.start, right.end) <= GEOMETRIC_TOUCH_TOLERANCE
                || distancePointToSegment(right.start, left.start, left.end) <= GEOMETRIC_TOUCH_TOLERANCE
                || distancePointToSegment(right.end, left.start, left.end) <= GEOMETRIC_TOUCH_TOLERANCE;
    }

    private boolean isOptimalPointAlignment(ElementGeometry left, ElementGeometry right) {
        if (!left.isPointLike() || !right.isPointLike()) {
            return false;
        }
        String leftFamily = semanticFamilyKey(left.semanticName);
        String rightFamily = semanticFamilyKey(right.semanticName);
        boolean pFamily = ("p".equals(leftFamily) && "pstar".equals(rightFamily))
                || ("pstar".equals(leftFamily) && "p".equals(rightFamily));
        if (!pFamily) {
            return false;
        }
        double[] leftCenter = bestPointCenter(left);
        double[] rightCenter = bestPointCenter(right);
        return leftCenter != null && rightCenter != null
                && distance(leftCenter, rightCenter) <= GEOMETRIC_ENDPOINT_TOLERANCE;
    }

    private boolean sameSemanticFamily(ElementGeometry left, ElementGeometry right) {
        String leftKey = semanticFamilyKey(left.semanticName);
        String rightKey = semanticFamilyKey(right.semanticName);
        return leftKey != null && leftKey.equals(rightKey);
    }

    private String semanticFamilyKey(String semanticName) {
        String value = TextUtils.safeLower(semanticName);
        if (value.isBlank()) {
            return null;
        }
        value = value.replace("label_", "")
                .replace("point_", "")
                .replace("seg_", "")
                .replace("line_", "")
                .replace("brace_", "")
                .replace("bar_", "");
        value = value.replace("label", "")
                .replace("point", "")
                .replace("segment", "")
                .replace("line", "");
        value = value.replaceAll("[\\s\\-]+", "_");
        int underscore = value.indexOf('_');
        value = underscore > 0 ? value.substring(0, underscore) : value;
        value = value.replaceAll("[^a-z0-9]", "");
        return value.isBlank() ? null : value;
    }

    private boolean isZeroArea(ElementGeometry element) {
        return element != null && element.width <= 1e-9 && element.height <= 1e-9;
    }

    private boolean isCircleLike(ElementGeometry element) {
        if (element == null) {
            return false;
        }
        return "circle".equalsIgnoreCase(element.geometryType)
                || "Circle".equals(element.className)
                || "Arc".equals(element.className)
                || "AnnularSector".equals(element.className);
    }

    private double[] bestPointCenter(ElementGeometry element) {
        if (element == null) {
            return null;
        }
        if (element.arcCenter != null && element.center != null
                && distance(element.arcCenter, element.center) <= GEOMETRIC_ENDPOINT_TOLERANCE) {
            return element.arcCenter;
        }
        if (element.center != null) {
            return element.center;
        }
        return element.arcCenter;
    }

    private double[] centerFromBounds(double[] min, double[] max) {
        if (min == null || max == null) {
            return null;
        }
        return new double[] {
                round((min[0] + max[0]) / 2.0),
                round((min[1] + max[1]) / 2.0),
                0.0
        };
    }

    private boolean isPointOnSegment(double[] point, double[] start, double[] end, double tolerance) {
        return distancePointToSegment(point, start, end) <= tolerance;
    }

    private boolean geometryIntersectsText(ElementGeometry geometry,
                                           double[] boundsMin,
                                           double[] boundsMax,
                                           double tolerance) {
        if (geometry == null) {
            return false;
        }
        if (geometry.hasPathPoints()) {
            return pathIntersectsBounds(geometry.pathPoints, boundsMin, boundsMax, tolerance);
        }
        if (isCircleLike(geometry) && geometry.arcCenter != null && geometry.radius != null) {
            return circleIntersectsBounds(geometry.arcCenter, geometry.radius, boundsMin, boundsMax, tolerance);
        }
        if (geometry.isLineLike()) {
            return lineIntersectsBounds(geometry, boundsMin, boundsMax, tolerance);
        }
        return true;
    }

    private boolean lineIntersectsBounds(ElementGeometry line,
                                         double[] boundsMin,
                                         double[] boundsMax,
                                         double tolerance) {
        if (line == null || line.start == null || line.end == null || boundsMin == null || boundsMax == null) {
            return false;
        }
        if (pointInsideBounds(line.start, boundsMin, boundsMax, tolerance)
                || pointInsideBounds(line.end, boundsMin, boundsMax, tolerance)) {
            return true;
        }
        double[] bottomLeft = new double[] {boundsMin[0], boundsMin[1], 0.0};
        double[] bottomRight = new double[] {boundsMax[0], boundsMin[1], 0.0};
        double[] topLeft = new double[] {boundsMin[0], boundsMax[1], 0.0};
        double[] topRight = new double[] {boundsMax[0], boundsMax[1], 0.0};
        return segmentsIntersect(line.start, line.end, bottomLeft, bottomRight, tolerance)
                || segmentsIntersect(line.start, line.end, bottomRight, topRight, tolerance)
                || segmentsIntersect(line.start, line.end, topRight, topLeft, tolerance)
                || segmentsIntersect(line.start, line.end, topLeft, bottomLeft, tolerance);
    }

    private boolean circleIntersectsBounds(double[] center,
                                           double radius,
                                           double[] boundsMin,
                                           double[] boundsMax,
                                           double tolerance) {
        if (center == null || boundsMin == null || boundsMax == null || radius <= 0) {
            return false;
        }
        double closestX = Math.max(boundsMin[0], Math.min(center[0], boundsMax[0]));
        double closestY = Math.max(boundsMin[1], Math.min(center[1], boundsMax[1]));
        double distance = Math.hypot(center[0] - closestX, center[1] - closestY);
        if (distance <= radius + tolerance && distance >= Math.max(radius - tolerance, 0.0)) {
            return true;
        }
        return pointInsideBounds(new double[] {center[0] + radius, center[1], 0.0}, boundsMin, boundsMax, tolerance)
                || pointInsideBounds(new double[] {center[0] - radius, center[1], 0.0}, boundsMin, boundsMax, tolerance)
                || pointInsideBounds(new double[] {center[0], center[1] + radius, 0.0}, boundsMin, boundsMax, tolerance)
                || pointInsideBounds(new double[] {center[0], center[1] - radius, 0.0}, boundsMin, boundsMax, tolerance);
    }

    private boolean pathIntersectsBounds(List<double[]> pathPoints,
                                         double[] boundsMin,
                                         double[] boundsMax,
                                         double tolerance) {
        if (pathPoints == null || pathPoints.isEmpty() || boundsMin == null || boundsMax == null) {
            return false;
        }

        double[] previous = null;
        for (double[] point : pathPoints) {
            if (point == null) {
                continue;
            }
            if (pointInsideBounds(point, boundsMin, boundsMax, tolerance)) {
                return true;
            }
            if (previous != null && segmentIntersectsBounds(previous, point, boundsMin, boundsMax, tolerance)) {
                return true;
            }
            previous = point;
        }
        return false;
    }

    private boolean segmentIntersectsBounds(double[] start,
                                            double[] end,
                                            double[] boundsMin,
                                            double[] boundsMax,
                                            double tolerance) {
        if (start == null || end == null || boundsMin == null || boundsMax == null) {
            return false;
        }
        if (pointInsideBounds(start, boundsMin, boundsMax, tolerance)
                || pointInsideBounds(end, boundsMin, boundsMax, tolerance)) {
            return true;
        }
        double[] bottomLeft = new double[] {boundsMin[0], boundsMin[1], 0.0};
        double[] bottomRight = new double[] {boundsMax[0], boundsMin[1], 0.0};
        double[] topLeft = new double[] {boundsMin[0], boundsMax[1], 0.0};
        double[] topRight = new double[] {boundsMax[0], boundsMax[1], 0.0};
        return segmentsIntersect(start, end, bottomLeft, bottomRight, tolerance)
                || segmentsIntersect(start, end, bottomRight, topRight, tolerance)
                || segmentsIntersect(start, end, topRight, topLeft, tolerance)
                || segmentsIntersect(start, end, topLeft, bottomLeft, tolerance);
    }

    private boolean pointInsideBounds(double[] point, double[] boundsMin, double[] boundsMax, double tolerance) {
        if (point == null || boundsMin == null || boundsMax == null) {
            return false;
        }
        return point[0] >= boundsMin[0] - tolerance && point[0] <= boundsMax[0] + tolerance
                && point[1] >= boundsMin[1] - tolerance && point[1] <= boundsMax[1] + tolerance;
    }

    private boolean segmentsIntersect(double[] a1,
                                      double[] a2,
                                      double[] b1,
                                      double[] b2,
                                      double tolerance) {
        if (a1 == null || a2 == null || b1 == null || b2 == null) {
            return false;
        }
        double o1 = orientation(a1, a2, b1);
        double o2 = orientation(a1, a2, b2);
        double o3 = orientation(b1, b2, a1);
        double o4 = orientation(b1, b2, a2);

        if ((o1 > tolerance && o2 < -tolerance || o1 < -tolerance && o2 > tolerance)
                && (o3 > tolerance && o4 < -tolerance || o3 < -tolerance && o4 > tolerance)) {
            return true;
        }

        return isPointOnSegmentWithOrientation(b1, a1, a2, o1, tolerance)
                || isPointOnSegmentWithOrientation(b2, a1, a2, o2, tolerance)
                || isPointOnSegmentWithOrientation(a1, b1, b2, o3, tolerance)
                || isPointOnSegmentWithOrientation(a2, b1, b2, o4, tolerance);
    }

    private boolean isPointOnSegmentWithOrientation(double[] point,
                                                    double[] start,
                                                    double[] end,
                                                    double orientation,
                                                    double tolerance) {
        return Math.abs(orientation) <= tolerance && pointInsideBounds(point,
                new double[] {Math.min(start[0], end[0]), Math.min(start[1], end[1]), 0.0},
                new double[] {Math.max(start[0], end[0]), Math.max(start[1], end[1]), 0.0},
                tolerance);
    }

    private double orientation(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private double distancePointToSegment(double[] point, double[] start, double[] end) {
        if (point == null || start == null || end == null) {
            return Double.POSITIVE_INFINITY;
        }
        double vx = end[0] - start[0];
        double vy = end[1] - start[1];
        double wx = point[0] - start[0];
        double wy = point[1] - start[1];
        double lengthSquared = vx * vx + vy * vy;
        if (lengthSquared <= 1e-9) {
            return distance(point, start);
        }
        double t = Math.max(0.0, Math.min(1.0, (wx * vx + wy * vy) / lengthSquared));
        double projX = start[0] + t * vx;
        double projY = start[1] + t * vy;
        return Math.hypot(point[0] - projX, point[1] - projY);
    }

    private double distance(double[] a, double[] b) {
        if (a == null || b == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private boolean anyPointClose(double[] a, double[] b, double tolerance) {
        return a != null && b != null && distance(a, b) <= tolerance;
    }

    private double angleBetweenSegments(double[] start1, double[] end1, double[] start2, double[] end2) {
        double v1x = end1[0] - start1[0];
        double v1y = end1[1] - start1[1];
        double v2x = end2[0] - start2[0];
        double v2y = end2[1] - start2[1];
        double len1 = Math.hypot(v1x, v1y);
        double len2 = Math.hypot(v2x, v2y);
        if (len1 <= 1e-9 || len2 <= 1e-9) {
            return Double.NaN;
        }
        double cosine = (v1x * v2x + v1y * v2y) / (len1 * len2);
        cosine = Math.max(-1.0, Math.min(1.0, cosine));
        return Math.toDegrees(Math.acos(cosine));
    }

    private ElementRef toElementRef(ElementGeometry element, String sampleRole) {
        ElementRef ref = new ElementRef();
        ref.setStableId(element.stableId);
        ref.setSemanticName(element.displayName());
        ref.setClassName(element.className);
        ref.setSampleRole(sampleRole);
        ref.setBounds(toBounds(element.min, element.max));
        return ref;
    }

    private Overflow toOverflow(OverflowMetrics metrics) {
        Overflow overflow = new Overflow();
        overflow.setLeft(round(metrics.left));
        overflow.setRight(round(metrics.right));
        overflow.setTop(round(metrics.top));
        overflow.setBottom(round(metrics.bottom));
        return overflow;
    }

    private Bounds toBounds(double[] min, double[] max) {
        Bounds bounds = new Bounds();
        bounds.setMin(min);
        bounds.setMax(max);
        return bounds;
    }

    private int resolveMaxFixAttempts(WorkflowConfig config) {
        if (config == null) {
            return DEFAULT_MAX_FIX_ATTEMPTS;
        }
        return Math.max(config.getSceneEvaluationMaxRetries(), 0);
    }

    private void finalizeResult(SceneEvaluationResult result,
                                SceneEvaluationRetryState retryState,
                                Instant start,
                                boolean resetState) {
        result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
        if (resetState) {
            retryState.reset();
        }
    }

    private SceneEvaluationResult skipEvaluation(SceneEvaluationResult result,
                                                 SceneEvaluationRetryState retryState,
                                                 Instant start,
                                                 String reason) {
        result.setEvaluated(false);
        result.setApproved(false);
        result.setGateReason(reason);
        finalizeResult(result, retryState, start, true);
        return result;
    }

    private String buildIssueSummary(SceneEvaluationResult result) {
        StringBuilder sb = new StringBuilder();
        int advisoryIssues = Math.max(result.getTotalIssueCount() - result.getBlockingIssueCount(), 0);
        sb.append(String.format(
                "Scene evaluation found %d blocking issues across %d/%d samples (%d overlap, %d offscreen, %d advisory observations).",
                result.getBlockingIssueCount(),
                result.getIssueSampleCount(),
                result.getSampleCount(),
                result.getOverlapIssueCount(),
                result.getOffscreenIssueCount(),
                advisoryIssues));

        int listedSamples = 0;
        for (SampleEvaluation sample : result.getSamples()) {
            if (!sample.isHasIssues()) {
                continue;
            }
            listedSamples++;
            sb.append("\n- Sample ").append(sample.getSampleId())
                    .append(" [").append(sample.getSampleRole()).append("]");
            if (sample.getSceneMethod() != null && !sample.getSceneMethod().isBlank()) {
                sb.append(" in ").append(sample.getSceneMethod());
            }
            if (sample.getSourceCode() != null && !sample.getSourceCode().isBlank()) {
                sb.append(": ").append(sample.getSourceCode());
            }
            int listedIssues = 0;
            for (int i = 0; i < sample.getIssues().size(); i++) {
                LayoutIssue issue = sample.getIssues().get(i);
                if (!"blocking".equalsIgnoreCase(issue.getSeverity())) {
                    continue;
                }
                sb.append("\n  * [").append(issue.getSeverity()).append("] ").append(issue.getMessage());
                listedIssues++;
                if (listedIssues >= 3) {
                    break;
                }
            }
            if (listedIssues == 0) {
                for (int i = 0; i < Math.min(sample.getIssues().size(), 1); i++) {
                    LayoutIssue issue = sample.getIssues().get(i);
                    sb.append("\n  * [").append(issue.getSeverity()).append("] ").append(issue.getMessage());
                }
            }
            if (listedSamples >= MAX_FIX_REPORT_SAMPLES) {
                break;
            }
        }
        return sb.toString();
    }

    private String summarizeFixHistory(String issueSummary) {
        if (issueSummary == null || issueSummary.isBlank()) {
            return "";
        }
        return ErrorSummarizer.compactSummary(issueSummary, 200);
    }

    private String buildFixReportJson(SceneEvaluationResult result, Storyboard storyboard) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scene_name", result.getSceneName());
        payload.put("geometry_path", result.getGeometryPath());
        payload.put("sample_count", result.getSampleCount());
        payload.put("issue_sample_count", result.getIssueSampleCount());
        payload.put("total_issue_count", result.getTotalIssueCount());
        payload.put("blocking_issue_count", result.getBlockingIssueCount());
        payload.put("overlap_issue_count", result.getOverlapIssueCount());
        payload.put("offscreen_issue_count", result.getOffscreenIssueCount());
        Map<String, StoryboardObject> storyboardObjects = buildStoryboardObjectMap(storyboard);

        List<Map<String, Object>> samples = new ArrayList<>();
        int addedSamples = 0;
        for (SampleEvaluation sample : result.getSamples()) {
            if (!sample.isHasIssues()) {
                continue;
            }
            Map<String, Object> sampleMap = new LinkedHashMap<>();
            sampleMap.put("sample_id", sample.getSampleId());
            sampleMap.put("play_index", sample.getPlayIndex());
            sampleMap.put("sample_role", sample.getSampleRole());
            sampleMap.put("trigger", sample.getTrigger());
            sampleMap.put("scene_method", sample.getSceneMethod());
            sampleMap.put("source_code", sample.getSourceCode());
            sampleMap.put("issue_count", sample.getIssueCount());
            sampleMap.put("blocking_issue_count", sample.getBlockingIssueCount());

            List<Map<String, Object>> issues = new ArrayList<>();
            for (int i = 0; i < Math.min(sample.getIssues().size(), MAX_ISSUES_PER_SAMPLE_IN_FIX_REPORT); i++) {
                LayoutIssue issue = sample.getIssues().get(i);
                Map<String, Object> issueMap = new LinkedHashMap<>();
                issueMap.put("type", issue.getType());
                issueMap.put("message", issue.getMessage());
                issueMap.put("severity", issue.getSeverity());
                issueMap.put("reason_code", issue.getReasonCode());
                issueMap.put("likely_false_positive", issue.getLikelyFalsePositive());
                issueMap.put("recommended_action", issue.getRecommendedAction());
                issueMap.put("primary_element", elementRefMap(issue.getPrimaryElement()));
                if (issue.getSecondaryElement() != null) {
                    issueMap.put("secondary_element", elementRefMap(issue.getSecondaryElement()));
                }
                Map<String, Object> dependencyContext = dependencyContextMap(issue, storyboardObjects);
                if (!dependencyContext.isEmpty()) {
                    issueMap.put("storyboard_dependency_context", dependencyContext);
                }
                if (issue.getOverflow() != null) {
                    issueMap.put("overflow", overflowMap(issue.getOverflow()));
                }
                if (issue.getIntersectionArea() != null) {
                    issueMap.put("intersection_area", issue.getIntersectionArea());
                }
                if (issue.getIntersectionBounds() != null) {
                    issueMap.put("intersection_bounds", boundsMap(issue.getIntersectionBounds()));
                }
                issues.add(issueMap);
            }
            sampleMap.put("issues", issues);
            samples.add(sampleMap);
            addedSamples++;
            if (addedSamples >= MAX_FIX_REPORT_SAMPLES) {
                break;
            }
        }
        payload.put("issue_samples", samples);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return buildFallbackFixJson(result);
        }
    }

    private Storyboard storyboardFrom(Narrative narrative) {
        return narrative != null && narrative.hasStoryboard() ? narrative.getStoryboard() : null;
    }

    private Map<String, StoryboardObject> buildStoryboardObjectMap(Storyboard storyboard) {
        Map<String, StoryboardObject> objects = new LinkedHashMap<>();
        if (storyboard == null) {
            return objects;
        }
        Storyboard mergedStoryboard = StoryboardPatchResolver.buildMergedStoryboard(storyboard);
        Storyboard source = mergedStoryboard != null ? mergedStoryboard : storyboard;

        if (source.getObjectRegistry() != null) {
            for (StoryboardObject object : source.getObjectRegistry()) {
                putStoryboardObject(objects, object, false);
            }
        }
        if (source.getScenes() != null) {
            for (Narrative.StoryboardScene scene : source.getScenes()) {
                if (scene == null) {
                    continue;
                }
                putStoryboardObjects(objects, scene.getEnteringObjects());
                putStoryboardObjects(objects, scene.getPersistentObjects());
            }
        }
        return objects;
    }

    private void putStoryboardObjects(Map<String, StoryboardObject> objects, List<StoryboardObject> values) {
        if (values == null) {
            return;
        }
        for (StoryboardObject object : values) {
            putStoryboardObject(objects, object, true);
        }
    }

    private void putStoryboardObject(Map<String, StoryboardObject> objects, StoryboardObject object, boolean overwrite) {
        String objectId = StoryboardPatchResolver.objectId(object);
        if (objectId != null && !objectId.isBlank() && (overwrite || !objects.containsKey(objectId))) {
            objects.put(objectId, object);
        }
    }

    private Map<String, Object> dependencyContextMap(LayoutIssue issue,
                                                     Map<String, StoryboardObject> storyboardObjects) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (issue == null || storyboardObjects == null || storyboardObjects.isEmpty()) {
            return context;
        }
        Map<String, Object> primary = dependencyContextForElement(
                "primary", issue.getPrimaryElement(), storyboardObjects);
        if (!primary.isEmpty()) {
            context.put("primary", primary);
        }
        Map<String, Object> secondary = dependencyContextForElement(
                "secondary", issue.getSecondaryElement(), storyboardObjects);
        if (!secondary.isEmpty()) {
            context.put("secondary", secondary);
        }
        if (!context.isEmpty()) {
            context.put("repair_rule",
                    "For constrained or derived objects, keep structured constraints true; fix layout by moving/scaling upstream source objects, the whole constrained group, camera/view, or attachment offset instead of directly assigning coordinates to the derived object.");
        }
        return context;
    }

    private Map<String, Object> dependencyContextForElement(String role,
                                                            ElementRef element,
                                                            Map<String, StoryboardObject> storyboardObjects) {
        Map<String, Object> context = new LinkedHashMap<>();
        String objectId = resolveStoryboardObjectId(element, storyboardObjects);
        if (objectId == null) {
            return context;
        }
        StoryboardObject object = storyboardObjects.get(objectId);
        if (object == null) {
            return context;
        }
        context.put("reported_role", role);
        context.put("object_id", objectId);
        putNonBlank(context, "kind", object.getKind());
        if (object.getConstraints() != null && !object.getConstraints().isEmpty()) {
            context.put("constraints", object.getConstraints());
        }
        List<String> dependencies = constraintDependencyIds(object);
        if (!dependencies.isEmpty()) {
            context.put("constraint_dependency_ids", dependencies);
        }
        List<Map<String, Object>> chain = new ArrayList<>();
        appendDependencyChain(objectId, storyboardObjects, new LinkedHashSet<>(), chain);
        if (!chain.isEmpty()) {
            context.put("full_dependency_chain", chain);
        }
        return context;
    }

    private void appendDependencyChain(String objectId,
                                       Map<String, StoryboardObject> storyboardObjects,
                                       LinkedHashSet<String> visited,
                                       List<Map<String, Object>> chain) {
        if (objectId == null || !visited.add(objectId)) {
            return;
        }
        StoryboardObject object = storyboardObjects.get(objectId);
        if (object == null) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("object_id", objectId);
            missing.put("available", false);
            chain.add(missing);
            return;
        }
        chain.add(storyboardObjectDependencyMap(objectId, object));
        for (String dependencyId : constraintDependencyIds(object)) {
            appendDependencyChain(dependencyId, storyboardObjects, visited, chain);
        }
    }

    private Map<String, Object> storyboardObjectDependencyMap(String objectId, StoryboardObject object) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("object_id", objectId);
        putNonBlank(map, "kind", object.getKind());
        List<String> dependencies = constraintDependencyIds(object);
        if (!dependencies.isEmpty()) {
            map.put("constraint_dependency_ids", dependencies);
        }
        if (object.getConstraints() != null && !object.getConstraints().isEmpty()) {
            map.put("constraints", object.getConstraints());
        }
        return map;
    }

    private List<String> constraintDependencyIds(StoryboardObject object) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (object == null || object.getConstraints() == null) {
            return new ArrayList<>();
        }
        String objectId = StoryboardPatchResolver.objectId(object);
        for (var constraint : object.getConstraints()) {
            ids.addAll(StoryboardConstraintUtils.dependencyIds(constraint));
        }
        ids.remove(objectId);
        return new ArrayList<>(ids);
    }

    private String resolveStoryboardObjectId(ElementRef element,
                                             Map<String, StoryboardObject> storyboardObjects) {
        if (element == null || storyboardObjects == null || storyboardObjects.isEmpty()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        addObjectIdCandidates(candidates, element.getSemanticName());
        addObjectIdCandidates(candidates, element.getStableId());
        for (String candidate : candidates) {
            if (storyboardObjects.containsKey(candidate)) {
                return candidate;
            }
        }
        Map<String, String> normalizedIds = new LinkedHashMap<>();
        for (String objectId : storyboardObjects.keySet()) {
            normalizedIds.putIfAbsent(normalizeIdForMatch(objectId), objectId);
        }
        for (String candidate : candidates) {
            String match = normalizedIds.get(normalizeIdForMatch(candidate));
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private void addObjectIdCandidates(List<String> candidates, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return;
        }
        String value = rawValue.trim();
        addCandidate(candidates, value);
        if (value.startsWith("self.") && value.length() > "self.".length()) {
            addCandidate(candidates, value.substring("self.".length()));
        }
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot < value.length() - 1) {
            addCandidate(candidates, value.substring(dot + 1));
        }
        for (String token : value.split("[^A-Za-z0-9_']+")) {
            addCandidate(candidates, token);
        }
    }

    private void addCandidate(List<String> candidates, String candidate) {
        if (candidate != null && !candidate.isBlank() && !candidates.contains(candidate.trim())) {
            candidates.add(candidate.trim());
        }
    }

    private String normalizeIdForMatch(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("ggb-")) {
            normalized = normalized.substring(4);
        }
        if (normalized.startsWith("self.")) {
            normalized = normalized.substring(5);
        }
        return normalized.replaceAll("[^a-z0-9]", "");
    }

    private void putNonBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private String buildFallbackFixJson(SceneEvaluationResult result) {
        return String.format(
                "{\"scene_name\":\"%s\",\"issue_sample_count\":%d,\"total_issue_count\":%d}",
                result.getSceneName() != null ? result.getSceneName() : "MainScene",
                result.getIssueSampleCount(),
                result.getTotalIssueCount());
    }

    private Map<String, Object> elementRefMap(ElementRef ref) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (ref == null) {
            return map;
        }
        map.put("stable_id", ref.getStableId());
        map.put("semantic_name", ref.getSemanticName());
        map.put("class_name", ref.getClassName());
        map.put("sample_role", ref.getSampleRole());
        map.put("bounds", boundsMap(ref.getBounds()));
        return map;
    }

    private Map<String, Object> overflowMap(Overflow overflow) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("left", overflow.getLeft());
        map.put("right", overflow.getRight());
        map.put("top", overflow.getTop());
        map.put("bottom", overflow.getBottom());
        return map;
    }

    private Map<String, Object> boundsMap(Bounds bounds) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (bounds == null) {
            return map;
        }
        map.put("min", bounds.getMin());
        map.put("max", bounds.getMax());
        return map;
    }

    private String readText(JsonNode node, String fieldName, String fallback) {
        String value = readNullableText(node, fieldName);
        return value != null ? value : fallback;
    }

    private String readNullableText(JsonNode node, String fieldName) {
        if (node == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text != null && !text.isBlank() ? text.trim() : null;
    }

    private List<double[]> readPointList(JsonNode node) {
        List<double[]> points = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return points;
        }
        for (JsonNode pointNode : node) {
            double[] point = readPoint(pointNode, null);
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }

    private double[] readPoint(JsonNode node, double[] fallback) {
        if (node == null || !node.isArray() || node.size() < 2) {
            return fallback;
        }
        double x = round(node.get(0).asDouble());
        double y = round(node.get(1).asDouble());
        double z = node.size() > 2 ? round(node.get(2).asDouble()) : 0.0;
        return new double[] {x, y, z};
    }

    private double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    static final class SceneEvaluationRetryState extends FixRetryState {
        final List<String> fixHistory = new ArrayList<>();
        String pendingIssueSummary;
        String pendingSceneEvaluationJson;

        @Override
        public void reset() {
            super.reset();
            fixHistory.clear();
            pendingIssueSummary = null;
            pendingSceneEvaluationJson = null;
        }
    }

    private static final class ElementGeometry {
        private String stableId;
        private String semanticName;
        private String className;
        private String semanticClass;
        private String displayText;
        private String topLevelStableId;
        private boolean visible;
        private double[] center;
        private double width;
        private double height;
        private double[] start;
        private double[] end;
        private double[] arcCenter;
        private Double radius;
        private String geometryType;
        private List<double[]> pathPoints;
        private double[] min;
        private double[] max;

        private String displayName() {
            return TextUtils.firstNonBlank(semanticName, displayText, className, stableId, "element");
        }

        private boolean isTextual() {
            return "text".equalsIgnoreCase(semanticClass)
                    || "Text".equals(className)
                    || "MathTex".equals(className)
                    || "MarkupText".equals(className)
                    || displayText != null;
        }

        private boolean isPointLike() {
            return "point".equalsIgnoreCase(semanticClass)
                    || "Dot".equals(className)
                    || "Dot3D".equals(className);
        }

        private boolean isLineLike() {
            return "line".equalsIgnoreCase(semanticClass)
                    || "Line".equals(className)
                    || "DashedLine".equals(className)
                    || "Arrow".equals(className);
        }

        private boolean hasPathPoints() {
            return pathPoints != null && pathPoints.size() >= 2;
        }
    }

    private static final class BoundsPair {
        private final double[] min;
        private final double[] max;

        private BoundsPair(double[] min, double[] max) {
            this.min = min;
            this.max = max;
        }
    }

    private static final class OverflowMetrics {
        private final double left;
        private final double right;
        private final double top;
        private final double bottom;

        private OverflowMetrics(double left, double right, double top, double bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }
    }

    private static final class OverlapMetrics {
        private final double area;
        private final double[] min;
        private final double[] max;

        private OverlapMetrics(double area, double[] min, double[] max) {
            this.area = area;
            this.min = min;
            this.max = max;
        }
    }

    private static final class BucketRange {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;

        private BucketRange(int minX, int maxX, int minY, int maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    private static final class OverlapDisposition {
        private final boolean ignore;
        private final String severity;
        private final String reasonCode;
        private final boolean likelyFalsePositive;
        private final String recommendedAction;
        private final String message;

        private OverlapDisposition(boolean ignore,
                                   String severity,
                                   String reasonCode,
                                   boolean likelyFalsePositive,
                                   String recommendedAction,
                                   String message) {
            this.ignore = ignore;
            this.severity = severity;
            this.reasonCode = reasonCode;
            this.likelyFalsePositive = likelyFalsePositive;
            this.recommendedAction = recommendedAction;
            this.message = message;
        }
    }
}
