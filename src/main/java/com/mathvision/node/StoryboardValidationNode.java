package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardPlacement;
import com.mathvision.model.Narrative.StoryboardPlacementAxis;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.StoryboardValidationReport;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.NarrativePrompts;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.StoryboardNormalizer;
import com.mathvision.util.StoryboardPatchResolver;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Stage 1c: Storyboard Validation — static checks on the assembled storyboard
 * followed by an optional LLM fix pass when issues are detected.
 *
 * Replaces NarrativeNode in the pipeline. Receives the Narrative already
 * assembled by VisualDesignNode and validates object lifecycle consistency,
 * scene ordering, and field completeness.
 */
public class StoryboardValidationNode extends PocketFlow.Node<Narrative, Narrative, String> {

    private static final Logger log = LoggerFactory.getLogger(StoryboardValidationNode.class);
    private static final double FRAME_MIN_X = -7.111111;
    private static final double FRAME_MAX_X = 7.111111;
    private static final double FRAME_MIN_Y = -4.0;
    private static final double FRAME_MAX_Y = 4.0;
    private static final double OFFSCREEN_TOLERANCE = 0.03;
    private static final double MIN_OVERLAP_AREA = 0.015;
    private static final double MIN_OVERLAP_RATIO = 0.08;
    private static final double SPATIAL_BUCKET_SIZE = 1.25;

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private int toolCalls = 0;
    private StoryboardValidationReport storyboardValidationReport;

    public StoryboardValidationNode() {
        super(1, 0);
    }

    @Override
    public Narrative prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        if (workflowConfig != null) {
            this.outputTarget = workflowConfig.getOutputTarget();
        }
        this.toolCalls = 0;
        this.storyboardValidationReport = null;
        return (Narrative) ctx.get(WorkflowKeys.NARRATIVE);
    }

    @Override
    public Narrative exec(Narrative narrative) {
        log.info("=== Stage 1c: Storyboard Validation ===");

        if (narrative == null || narrative.getStoryboard() == null) {
            log.warn("No narrative/storyboard to validate");
            this.storyboardValidationReport = buildSkippedReport("No narrative/storyboard to validate");
            return narrative;
        }

        Storyboard storyboard = narrative.getStoryboard();
        List<String> issues = validate(storyboard);
        this.storyboardValidationReport = baseReport(storyboard, issues);

        if (issues.isEmpty()) {
            log.info("Storyboard validation passed (no issues)");
            finalizeReport(storyboardValidationReport, true, false, false, List.of(),
                    "Storyboard validation passed (no issues)");
            return narrative;
        }

        log.warn("Storyboard validation found {} issues:", issues.size());
        for (String issue : issues) {
            log.warn("  - {}", issue);
        }

        // Attempt LLM fix
        Narrative fixed = attemptLlmFix(narrative, issues);
        if (fixed != null) {
            // Re-validate after fix
            List<String> remainingIssues = validate(fixed.getStoryboard());
            if (remainingIssues.isEmpty()) {
                log.info("LLM fix resolved all {} issues", issues.size());
                finalizeReport(storyboardValidationReport, true, true, true, remainingIssues,
                        "Storyboard validation issues were fixed successfully");
                return fixed;
            }
            log.warn("LLM fix resolved {}/{} issues, {} remain",
                    issues.size() - remainingIssues.size(), issues.size(), remainingIssues.size());
            finalizeReport(storyboardValidationReport, false, true, true, remainingIssues,
                    "Storyboard validation fix was applied, but some issues remain");
            return fixed;
        }

        log.warn("LLM fix failed, proceeding with original storyboard");
        finalizeReport(storyboardValidationReport, false, true, false, issues,
                "Storyboard validation found issues and the automatic fix did not succeed");
        return narrative;
    }

    @Override
    public String post(Map<String, Object> ctx, Narrative prepRes, Narrative narrative) {
        ctx.put(WorkflowKeys.NARRATIVE, narrative);

        int prevCalls = (int) ctx.getOrDefault(WorkflowKeys.ENRICHMENT_TOOL_CALLS, 0);
        ctx.put(WorkflowKeys.ENRICHMENT_TOOL_CALLS, prevCalls + toolCalls);

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveNarrative(outputDir, narrative);
            StoryboardValidationReport reportToSave = storyboardValidationReport != null
                    ? storyboardValidationReport
                    : buildSkippedReport("Storyboard validation report was not produced");
            FileOutputService.saveStoryboardValidation(outputDir, reportToSave);
        }

        return null;
    }

    // ---- Static validation ----

    List<String> validate(Storyboard storyboard) {
        List<String> issues = new ArrayList<>();
        if (storyboard.getScenes() == null || storyboard.getScenes().isEmpty()) {
            issues.add("Storyboard has no scenes");
            return issues;
        }

        Map<String, StoryboardObject> registryById = new LinkedHashMap<>();
        Set<String> registryIds = new HashSet<>();
        if (storyboard.getObjectRegistry() != null) {
            for (StoryboardObject obj : storyboard.getObjectRegistry()) {
                String id = StoryboardPatchResolver.objectId(obj);
                if (id != null) {
                    registryIds.add(id);
                    registryById.put(id, obj);
                }
                if (obj != null && obj.getPlacement() != null && obj.getPlacement().hasData()) {
                    issues.add("Registry object '" + idOrUnknown(obj)
                            + "' should not define placement");
                }
                if (hasPatchFields(obj)) {
                    issues.add("Registry object '" + idOrUnknown(obj)
                            + "' is missing canonical fields or was reduced to a scene patch");
                }
            }
        }

        Set<String> introducedIds = new HashSet<>();
        Set<String> activeIds = new HashSet<>();
        Storyboard mergedStoryboard = StoryboardPatchResolver.buildMergedStoryboard(storyboard);

        for (int i = 0; i < storyboard.getScenes().size(); i++) {
            StoryboardScene scene = storyboard.getScenes().get(i);
            String label = "scene " + (i + 1) + " (" + scene.getSceneId() + ")";
            StoryboardScene mergedScene = mergedStoryboard != null
                    && mergedStoryboard.getScenes() != null
                    && i < mergedStoryboard.getScenes().size()
                    ? mergedStoryboard.getScenes().get(i)
                    : null;

            // Check entering_objects
            if (scene.getEnteringObjects() != null) {
                for (StoryboardObject obj : scene.getEnteringObjects()) {
                    String id = StoryboardPatchResolver.objectId(obj);
                    if (id == null) {
                        issues.add(label + ": entering object has no id");
                        continue;
                    }
                    if (!registryIds.contains(id)) {
                        issues.add(label + ": entering_objects references id '" + id
                                + "' that is missing from object_registry");
                    }
                    if (introducedIds.contains(id)) {
                        issues.add(label + ": object '" + id
                                + "' re-entered but was already introduced in an earlier scene");
                    }
                    if (hasNonPatchFields(obj)) {
                        issues.add(label + ": entering_objects object '" + id
                                + "' should only contain id plus optional placement/style");
                    }
                    validateRawPlacement(label + ": entering_objects object '" + id + "'", obj, issues);
                    introducedIds.add(id);
                }
            }

            // Check persistent_objects reference valid ids
            if (scene.getPersistentObjects() != null) {
                for (StoryboardObject obj : scene.getPersistentObjects()) {
                    String id = StoryboardPatchResolver.objectId(obj);
                    if (id == null) {
                        issues.add(label + ": persistent_objects object has no id");
                        continue;
                    }
                    if (!activeIds.contains(id)) {
                        issues.add(label + ": persistent_objects references inactive id '" + id + "'");
                    }
                    if (hasNonPatchFields(obj)) {
                        issues.add(label + ": persistent_objects object '" + id
                                + "' should only contain id plus optional placement/style");
                    }
                    validateRawPlacement(label + ": persistent_objects object '" + id + "'", obj, issues);
                }
            }

            Set<String> visibleThisScene = new HashSet<>(StoryboardPatchResolver.idSetOf(scene.getPersistentObjects()));
            visibleThisScene.addAll(StoryboardPatchResolver.idSetOf(scene.getEnteringObjects()));

            // Check exiting_objects reference valid ids
            if (scene.getExitingObjects() != null) {
                for (StoryboardObject obj : scene.getExitingObjects()) {
                    String id = StoryboardPatchResolver.objectId(obj);
                    if (id == null) {
                        issues.add(label + ": exiting_objects object has no id");
                        continue;
                    }
                    if (!visibleThisScene.contains(id)) {
                        issues.add(label + ": exiting_objects references non-visible id '" + id + "'");
                    }
                    if (hasAnyFieldBeyondId(obj)) {
                        issues.add(label + ": exiting_objects object '" + id + "' must contain only id");
                    }
                }
            }

            validateMergedPlacementObjects(label, "entering_objects",
                    mergedScene != null ? mergedScene.getEnteringObjects() : List.of(),
                    registryById,
                    issues);
            validateMergedPlacementObjects(label, "persistent_objects",
                    mergedScene != null ? mergedScene.getPersistentObjects() : List.of(),
                    registryById,
                    issues);
            validateSceneLayout(label, mergedScene, issues);

            // Check required fields
            if (scene.getTitle() == null || scene.getTitle().isBlank()) {
                issues.add(label + ": missing title");
            }
            if (scene.getGoal() == null || scene.getGoal().isBlank()) {
                issues.add(label + ": missing goal");
            }

            activeIds = new HashSet<>(visibleThisScene);
            if (scene.getExitingObjects() != null) {
                activeIds.removeAll(StoryboardPatchResolver.idSetOf(scene.getExitingObjects()));
            }
        }

        // Check registry objects match introduced objects
        for (String regId : registryIds) {
            if (!introducedIds.contains(regId)) {
                issues.add("Registry object '" + regId + "' never enters any scene");
            }
        }

        return issues;
    }

    private void validateMergedPlacementObjects(String sceneLabel,
                                                String fieldName,
                                                List<StoryboardObject> objects,
                                                Map<String, StoryboardObject> registryById,
                                                List<String> issues) {
        if (objects == null) {
            return;
        }
        for (StoryboardObject object : objects) {
            String id = StoryboardPatchResolver.objectId(object);
            if (id == null) {
                continue;
            }
            StoryboardObject registryObject = registryById.get(id);
            validateResolvedPlacement(sceneLabel + ": " + fieldName + " object '" + id + "'",
                    object,
                    registryObject,
                    issues);
        }
    }

    private void validateSceneLayout(String sceneLabel,
                                     StoryboardScene mergedScene,
                                     List<String> issues) {
        if (mergedScene == null) {
            return;
        }

        List<StoryboardLayoutElement> elements = resolveSceneLayoutElements(sceneLabel, mergedScene, issues);
        if (elements.isEmpty()) {
            return;
        }

        if (!isGeoGebraTarget()) {
            for (StoryboardLayoutElement element : elements) {
                String overflowSummary = summarizeOverflow(element.bounds);
                if (overflowSummary != null) {
                    issues.add(sceneLabel + ": object '" + element.objectId
                            + "' extends outside the frame bounds (" + overflowSummary + ")");
                }
            }
        }

        issues.addAll(evaluateLayoutOverlapIssues(sceneLabel, elements));
    }

    private List<StoryboardLayoutElement> resolveSceneLayoutElements(String sceneLabel,
                                                                     StoryboardScene mergedScene,
                                                                     List<String> issues) {
        Map<String, StoryboardObject> visibleObjects = new LinkedHashMap<>();
        addVisibleObjects(visibleObjects, mergedScene.getPersistentObjects());
        addVisibleObjects(visibleObjects, mergedScene.getEnteringObjects());

        List<StoryboardLayoutElement> elements = new ArrayList<>();
        Map<String, StoryboardLayoutElement> cache = new LinkedHashMap<>();
        Set<String> resolvingIds = new HashSet<>();
        for (StoryboardObject object : visibleObjects.values()) {
            StoryboardLayoutElement element = resolveLayoutElement(
                    sceneLabel, object, visibleObjects, cache, resolvingIds, issues);
            if (element != null) {
                elements.add(element);
            }
        }
        return elements;
    }

    private void addVisibleObjects(Map<String, StoryboardObject> visibleObjects,
                                   List<StoryboardObject> objects) {
        if (objects == null) {
            return;
        }
        for (StoryboardObject object : objects) {
            String objectId = StoryboardPatchResolver.objectId(object);
            if (objectId != null) {
                visibleObjects.put(objectId, object);
            }
        }
    }

    private StoryboardLayoutElement resolveLayoutElement(String sceneLabel,
                                                         StoryboardObject object,
                                                         Map<String, StoryboardObject> visibleObjects,
                                                         Map<String, StoryboardLayoutElement> cache,
                                                         Set<String> resolvingIds,
                                                         List<String> issues) {
        String objectId = StoryboardPatchResolver.objectId(object);
        if (objectId == null) {
            return null;
        }
        if (cache.containsKey(objectId)) {
            return cache.get(objectId);
        }

        StoryboardPlacement placement = object != null ? object.getPlacement() : null;
        if (placement == null || !placement.hasData()) {
            cache.put(objectId, null);
            return null;
        }

        if (!resolvingIds.add(objectId)) {
            issues.add(sceneLabel + ": anchor placement cycle detected for object '" + objectId + "'");
            cache.put(objectId, null);
            return null;
        }

        try {
            StoryboardLayoutBounds bounds = resolveLayoutBounds(
                    sceneLabel, object, visibleObjects, cache, resolvingIds, issues);
            if (bounds == null) {
                cache.put(objectId, null);
                return null;
            }

            StoryboardLayoutElement element = new StoryboardLayoutElement(objectId, object, bounds);
            cache.put(objectId, element);
            return element;
        } finally {
            resolvingIds.remove(objectId);
        }
    }

    private StoryboardLayoutBounds resolveLayoutBounds(String sceneLabel,
                                                       StoryboardObject object,
                                                       Map<String, StoryboardObject> visibleObjects,
                                                       Map<String, StoryboardLayoutElement> cache,
                                                       Set<String> resolvingIds,
                                                       List<String> issues) {
        if (object == null || object.getPlacement() == null || !object.getPlacement().hasData()) {
            return null;
        }

        StoryboardPlacement placement = object.getPlacement();
        String coordinateSpace = placement.getCoordinateSpace();
        if (isBlank(coordinateSpace)) {
            return null;
        }

        AxisBounds xBounds;
        AxisBounds yBounds;
        if (Narrative.StoryboardPlacement.COORDINATE_SPACE_ANCHOR.equalsIgnoreCase(coordinateSpace)) {
            String rawAnchorId = !isBlank(object.getAnchorId()) ? object.getAnchorId().trim() : null;
            String anchorId = StoryboardPatchResolver.objectId(visibleObjects.get(rawAnchorId));
            if (anchorId == null) {
                issues.add(sceneLabel + ": object '" + idOrUnknown(object)
                        + "' references anchor_id '" + rawAnchorId
                        + "' that is not visible in the scene");
                return null;
            }
            StoryboardLayoutElement anchorElement = resolveLayoutElement(
                    sceneLabel,
                    visibleObjects.get(anchorId),
                    visibleObjects,
                    cache,
                    resolvingIds,
                    issues);
            if (anchorElement == null) {
                return null;
            }
            xBounds = resolveAxisBounds(placement.getX(), anchorElement.bounds.centerX(), true);
            yBounds = resolveAxisBounds(placement.getY(), anchorElement.bounds.centerY(), true);
        } else if (Narrative.StoryboardPlacement.COORDINATE_SPACE_WORLD.equalsIgnoreCase(coordinateSpace)
                || Narrative.StoryboardPlacement.COORDINATE_SPACE_SCREEN.equalsIgnoreCase(coordinateSpace)) {
            if (placement.getX() == null && placement.getY() == null) {
                return null;
            }
            xBounds = resolveAxisBounds(placement.getX(), 0.0, false);
            yBounds = resolveAxisBounds(placement.getY(), 0.0, false);
        } else {
            return null;
        }

        return new StoryboardLayoutBounds(xBounds.min, xBounds.max, yBounds.min, yBounds.max);
    }

    private AxisBounds resolveAxisBounds(StoryboardPlacementAxis axis,
                                         double fallbackCenter,
                                         boolean relativeToBase) {
        if (axis == null || !axis.hasData()) {
            return new AxisBounds(fallbackCenter, fallbackCenter);
        }

        Double rawMin = axis.getMin() != null
                ? axis.getMin()
                : axis.getValue() != null ? axis.getValue() : axis.getMax();
        Double rawMax = axis.getMax() != null
                ? axis.getMax()
                : axis.getValue() != null ? axis.getValue() : axis.getMin();
        if (rawMin == null || rawMax == null) {
            return new AxisBounds(fallbackCenter, fallbackCenter);
        }

        double resolvedMin = relativeToBase ? fallbackCenter + rawMin : rawMin;
        double resolvedMax = relativeToBase ? fallbackCenter + rawMax : rawMax;
        return new AxisBounds(
                round(Math.min(resolvedMin, resolvedMax)),
                round(Math.max(resolvedMin, resolvedMax)));
    }

    private String summarizeOverflow(StoryboardLayoutBounds bounds) {
        double left = Math.max(FRAME_MIN_X - bounds.minX, 0.0);
        double right = Math.max(bounds.maxX - FRAME_MAX_X, 0.0);
        double bottom = Math.max(FRAME_MIN_Y - bounds.minY, 0.0);
        double top = Math.max(bounds.maxY - FRAME_MAX_Y, 0.0);
        if (Math.max(Math.max(left, right), Math.max(bottom, top)) <= OFFSCREEN_TOLERANCE) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        if (left > OFFSCREEN_TOLERANCE) {
            parts.add("left=" + round(left));
        }
        if (right > OFFSCREEN_TOLERANCE) {
            parts.add("right=" + round(right));
        }
        if (bottom > OFFSCREEN_TOLERANCE) {
            parts.add("bottom=" + round(bottom));
        }
        if (top > OFFSCREEN_TOLERANCE) {
            parts.add("top=" + round(top));
        }
        return String.join(", ", parts);
    }

    private List<String> evaluateLayoutOverlapIssues(String sceneLabel,
                                                     List<StoryboardLayoutElement> elements) {
        List<String> issues = new ArrayList<>();
        if (elements.size() < 2) {
            return issues;
        }

        Map<Long, List<Integer>> buckets = buildSpatialBuckets(elements);
        for (int index = 0; index < elements.size(); index++) {
            StoryboardLayoutElement left = elements.get(index);
            LayoutBucketRange range = bucketRange(left.bounds);
            Set<Integer> seenCandidates = new LinkedHashSet<>();

            for (int bucketX = range.minX; bucketX <= range.maxX; bucketX++) {
                for (int bucketY = range.minY; bucketY <= range.maxY; bucketY++) {
                    List<Integer> bucketElements = buckets.get(bucketKey(bucketX, bucketY));
                    if (bucketElements == null || bucketElements.isEmpty()) {
                        continue;
                    }

                    for (Integer candidateIndex : bucketElements) {
                        if (candidateIndex == null
                                || candidateIndex <= index
                                || !seenCandidates.add(candidateIndex)) {
                            continue;
                        }

                        StoryboardLayoutElement right = elements.get(candidateIndex);
                        if (!shouldCheckLayoutOverlap(left, right)) {
                            continue;
                        }

                        if (!overlapsSignificantly(left.bounds, right.bounds)) {
                            continue;
                        }

                        String blockingIssue = classifyLayoutOverlap(sceneLabel, left, right);
                        if (blockingIssue != null) {
                            issues.add(blockingIssue);
                        }
                    }
                }
            }
        }
        return issues;
    }

    private Map<Long, List<Integer>> buildSpatialBuckets(List<StoryboardLayoutElement> elements) {
        Map<Long, List<Integer>> buckets = new LinkedHashMap<>();
        for (int index = 0; index < elements.size(); index++) {
            LayoutBucketRange range = bucketRange(elements.get(index).bounds);
            for (int bucketX = range.minX; bucketX <= range.maxX; bucketX++) {
                for (int bucketY = range.minY; bucketY <= range.maxY; bucketY++) {
                    long key = bucketKey(bucketX, bucketY);
                    buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
                }
            }
        }
        return buckets;
    }

    private LayoutBucketRange bucketRange(StoryboardLayoutBounds bounds) {
        return new LayoutBucketRange(
                bucketIndex(bounds.minX),
                bucketIndex(bounds.maxX),
                bucketIndex(bounds.minY),
                bucketIndex(bounds.maxY));
    }

    private int bucketIndex(double value) {
        return (int) Math.floor(value / SPATIAL_BUCKET_SIZE);
    }

    private long bucketKey(int bucketX, int bucketY) {
        return (((long) bucketX) << 32) ^ (bucketY & 0xffffffffL);
    }

    private boolean shouldCheckLayoutOverlap(StoryboardLayoutElement left,
                                             StoryboardLayoutElement right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.objectId.equals(right.objectId)) {
            return false;
        }
        return isTextual(left.object) || isTextual(right.object);
    }

    private boolean overlapsSignificantly(StoryboardLayoutBounds left,
                                          StoryboardLayoutBounds right) {
        double overlapWidth = Math.min(left.maxX, right.maxX) - Math.max(left.minX, right.minX);
        double overlapHeight = Math.min(left.maxY, right.maxY) - Math.max(left.minY, right.minY);
        if (overlapWidth <= 1e-9 || overlapHeight <= 1e-9) {
            return false;
        }
        double area = overlapWidth * overlapHeight;
        double leftArea = Math.max(left.area(), 1e-9);
        double rightArea = Math.max(right.area(), 1e-9);
        double minAreaRatio = area / Math.min(leftArea, rightArea);
        return area >= MIN_OVERLAP_AREA && minAreaRatio >= MIN_OVERLAP_RATIO;
    }

    private String classifyLayoutOverlap(String sceneLabel,
                                         StoryboardLayoutElement left,
                                         StoryboardLayoutElement right) {
        boolean leftText = isTextual(left.object);
        boolean rightText = isTextual(right.object);
        if (leftText && rightText) {
            return sceneLabel + ": text objects '" + left.objectId
                    + "' and '" + right.objectId + "' overlap";
        }

        StoryboardLayoutElement textElement = leftText ? left : right;
        StoryboardLayoutElement otherElement = leftText ? right : left;
        if (isAttachedLabelPair(textElement.object, otherElement.object)) {
            return null;
        }

        return sceneLabel + ": text object '" + textElement.objectId
                + "' overlaps object '" + otherElement.objectId + "'";
    }

    private boolean isTextual(StoryboardObject object) {
        if (object == null) {
            return false;
        }
        String kind = object.getKind();
        if ("text".equalsIgnoreCase(kind) || "label".equalsIgnoreCase(kind)) {
            return true;
        }
        if (object.getStyle() == null) {
            return false;
        }
        return object.getStyle().stream()
                .anyMatch(style -> style != null
                        && !isBlank(style.getType())
                        && style.getType().toLowerCase(Locale.ROOT).contains("text"));
    }

    private boolean isAttachedLabelPair(StoryboardObject textObject,
                                        StoryboardObject otherObject) {
        if (textObject == null || otherObject == null) {
            return false;
        }
        String otherId = StoryboardPatchResolver.objectId(otherObject);
        if (!isBlank(textObject.getAnchorId()) && textObject.getAnchorId().trim().equals(otherId)) {
            return true;
        }
        if (!isLikelyLabel(textObject)) {
            return false;
        }
        String textStem = semanticStem(StoryboardPatchResolver.objectId(textObject));
        String otherStem = semanticStem(otherId);
        return textStem != null && textStem.equals(otherStem);
    }

    private boolean isLikelyLabel(StoryboardObject object) {
        if (object == null) {
            return false;
        }
        if ("label".equalsIgnoreCase(object.getKind())) {
            return true;
        }
        String objectId = StoryboardPatchResolver.objectId(object);
        if (objectId == null) {
            return false;
        }
        String normalized = objectId.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("_label")
                || normalized.endsWith("label")
                || normalized.endsWith("_text")
                || normalized.endsWith("text")
                || normalized.startsWith("label_")
                || normalized.startsWith("text_");
    }

    private String semanticStem(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return null;
        }
        String normalized = objectId.trim().toLowerCase(Locale.ROOT);
        // Strip common label/text suffixes first
        String[] suffixes = {"_label", "_text", "label", "text"};
        for (String suffix : suffixes) {
            if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                normalized = normalized.substring(0, normalized.length() - suffix.length());
                break;
            }
        }
        // Strip common type prefixes (label, text, and geometry types) to align with
        // SceneEvaluationNode.semanticFamilyKey behaviour so that e.g. "label_a" and
        // "point_a" resolve to the same stem "a".
        String[] prefixes = {"label_", "text_", "point_", "seg_", "line_", "brace_", "bar_"};
        for (String prefix : prefixes) {
            if (normalized.startsWith(prefix) && normalized.length() > prefix.length()) {
                normalized = normalized.substring(prefix.length());
                break;
            }
        }
        return normalized;
    }

    private void validateRawPlacement(String objectLabel,
                                      StoryboardObject object,
                                      List<String> issues) {
        if (object == null || object.getPlacement() == null) {
            return;
        }
        validatePlacementShape(objectLabel, object.getPlacement(), issues);
    }

    private void validateResolvedPlacement(String objectLabel,
                                           StoryboardObject object,
                                           StoryboardObject registryObject,
                                           List<String> issues) {
        if (object == null || object.getPlacement() == null) {
            return;
        }
        validatePlacementShape(objectLabel, object.getPlacement(), issues);
        if (Narrative.StoryboardPlacement.COORDINATE_SPACE_ANCHOR
                .equalsIgnoreCase(object.getPlacement().getCoordinateSpace())) {
            String effectiveAnchorId = firstNonBlank(
                    object.getAnchorId(),
                    registryObject != null ? registryObject.getAnchorId() : null);
            if (effectiveAnchorId == null) {
                issues.add(objectLabel + ": placement uses coordinate_space=anchor but anchor_id is missing");
            }
        }
    }

    private void validatePlacementShape(String objectLabel,
                                        StoryboardPlacement placement,
                                        List<String> issues) {
        if (placement == null) {
            return;
        }
        String coordinateSpace = placement.getCoordinateSpace();
        if (coordinateSpace == null || coordinateSpace.isBlank()) {
            issues.add(objectLabel + ": placement.coordinate_space is required");
        } else if (!Narrative.StoryboardPlacement.COORDINATE_SPACE_WORLD.equalsIgnoreCase(coordinateSpace)
                && !Narrative.StoryboardPlacement.COORDINATE_SPACE_SCREEN.equalsIgnoreCase(coordinateSpace)
                && !Narrative.StoryboardPlacement.COORDINATE_SPACE_ANCHOR.equalsIgnoreCase(coordinateSpace)) {
            issues.add(objectLabel + ": placement.coordinate_space must be one of world, screen, or anchor");
        }

        boolean hasAnyAxis = validatePlacementAxis(objectLabel, "x", placement.getX(), issues)
                | validatePlacementAxis(objectLabel, "y", placement.getY(), issues)
                | validatePlacementAxis(objectLabel, "z", placement.getZ(), issues);
        if (!hasAnyAxis) {
            issues.add(objectLabel + ": placement must define at least one of x/y/z");
        }
        if ((Narrative.StoryboardPlacement.COORDINATE_SPACE_WORLD.equalsIgnoreCase(coordinateSpace)
                || Narrative.StoryboardPlacement.COORDINATE_SPACE_SCREEN.equalsIgnoreCase(coordinateSpace))
                && placement.getX() == null
                && placement.getY() == null) {
            issues.add(objectLabel + ": world/screen placement must define x or y");
        }
    }

    private boolean validatePlacementAxis(String objectLabel,
                                          String axisName,
                                          StoryboardPlacementAxis axis,
                                          List<String> issues) {
        if (axis == null) {
            return false;
        }
        boolean hasData = axis.hasData();
        if (!hasData) {
            issues.add(objectLabel + ": placement." + axisName + " must include value or min/max");
            return false;
        }
        if (axis.getMin() != null && axis.getMax() != null && axis.getMin() > axis.getMax()) {
            issues.add(objectLabel + ": placement." + axisName + " has min greater than max");
        }
        if (axis.getValue() != null && axis.getMin() != null && axis.getValue() < axis.getMin()) {
            issues.add(objectLabel + ": placement." + axisName + ".value is below min");
        }
        if (axis.getValue() != null && axis.getMax() != null && axis.getValue() > axis.getMax()) {
            issues.add(objectLabel + ": placement." + axisName + ".value is above max");
        }
        return true;
    }

    private boolean hasPatchFields(StoryboardObject object) {
        return object == null
                || isBlank(object.getKind())
                || isBlank(object.getContent());
    }

    private boolean hasNonPatchFields(StoryboardObject object) {
        if (object == null) {
            return false;
        }
        return !isBlank(object.getKind())
                || !isBlank(object.getContent())
                || !isBlank(object.getSourceNode())
                || !isBlank(object.getBehavior())
                || !isBlank(object.getAnchorId())
                || !isBlank(object.getDependencyNote())
                || !isBlank(object.getConstraintNote());
    }

    private boolean hasAnyFieldBeyondId(StoryboardObject object) {
        if (object == null) {
            return false;
        }
        return hasNonPatchFields(object)
                || (object.getPlacement() != null && object.getPlacement().hasData())
                || (object.getStyle() != null && !object.getStyle().isEmpty());
    }

    private String idOrUnknown(StoryboardObject object) {
        String id = StoryboardPatchResolver.objectId(object);
        return id != null ? id : "<unknown>";
    }

    private String firstNonBlank(String first, String second) {
        if (!isBlank(first)) {
            return first.trim();
        }
        if (!isBlank(second)) {
            return second.trim();
        }
        return null;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private boolean isGeoGebraTarget() {
        return workflowConfig != null
                ? workflowConfig.isGeoGebraTarget()
                : WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equalsIgnoreCase(outputTarget);
    }

    private double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private StoryboardValidationReport buildSkippedReport(String message) {
        StoryboardValidationReport report = new StoryboardValidationReport();
        report.setValidated(false);
        report.setPassed(true);
        report.setOutputTarget(outputTarget);
        report.setSceneCount(0);
        report.setInitialIssueCount(0);
        report.setInitialIssues(new ArrayList<>());
        report.setFixAttempted(false);
        report.setFixApplied(false);
        report.setResolvedIssueCount(0);
        report.setFinalIssueCount(0);
        report.setFinalIssues(new ArrayList<>());
        report.setMessage(message);
        return report;
    }

    private StoryboardValidationReport baseReport(Storyboard storyboard,
                                                  List<String> initialIssues) {
        StoryboardValidationReport report = new StoryboardValidationReport();
        report.setValidated(true);
        report.setPassed(initialIssues == null || initialIssues.isEmpty());
        report.setOutputTarget(outputTarget);
        report.setSceneCount(storyboard != null && storyboard.getScenes() != null
                ? storyboard.getScenes().size()
                : 0);
        report.setInitialIssueCount(initialIssues != null ? initialIssues.size() : 0);
        report.setInitialIssues(initialIssues != null ? new ArrayList<>(initialIssues) : new ArrayList<>());
        report.setFixAttempted(false);
        report.setFixApplied(false);
        report.setResolvedIssueCount(0);
        report.setFinalIssueCount(initialIssues != null ? initialIssues.size() : 0);
        report.setFinalIssues(initialIssues != null ? new ArrayList<>(initialIssues) : new ArrayList<>());
        return report;
    }

    private void finalizeReport(StoryboardValidationReport report,
                                boolean passed,
                                boolean fixAttempted,
                                boolean fixApplied,
                                List<String> finalIssues,
                                String message) {
        if (report == null) {
            return;
        }
        List<String> resolvedFinalIssues = finalIssues != null ? new ArrayList<>(finalIssues) : new ArrayList<>();
        report.setPassed(passed);
        report.setFixAttempted(fixAttempted);
        report.setFixApplied(fixApplied);
        report.setFinalIssueCount(resolvedFinalIssues.size());
        report.setFinalIssues(resolvedFinalIssues);
        report.setResolvedIssueCount(Math.max(report.getInitialIssueCount() - resolvedFinalIssues.size(), 0));
        report.setMessage(message);
    }

    // ---- LLM fix pass ----

    private Narrative attemptLlmFix(Narrative narrative, List<String> issues) {
        try {
            String storyboardJson = JsonUtils.mapper().writeValueAsString(narrative.getStoryboard());
            StringBuilder fixPrompt = new StringBuilder();
            fixPrompt.append("The following storyboard has validation issues. Fix them and return the corrected storyboard.\n\n");
            fixPrompt.append("Issues found:\n");
            for (String issue : issues) {
                fixPrompt.append("- ").append(issue).append("\n");
            }
            fixPrompt.append("\nCurrent storyboard:\n```json\n").append(storyboardJson).append("\n```\n\n");
            fixPrompt.append("Return the full corrected storyboard JSON with all scenes, object_registry, and metadata.");

            String systemPrompt = NarrativePrompts.systemPrompt(
                    narrative.getTargetConcept(),
                    narrative.getTargetDescription(),
                    outputTarget);

            JsonNode fixedData = AiRequestUtils.requestJsonObjectAsync(
                            aiClient,
                            log,
                            "storyboard-fix",
                            fixPrompt.toString(),
                            systemPrompt,
                            ToolSchemas.STORYBOARD,
                            () -> toolCalls++)
                    .join();

            if (fixedData == null) {
                return null;
            }

            JsonNode storyboardNode = fixedData.has("storyboard")
                    ? fixedData.get("storyboard") : fixedData;
            if (storyboardNode == null || !storyboardNode.has("scenes")) {
                return null;
            }

            Storyboard fixedStoryboard = JsonUtils.mapper().treeToValue(storyboardNode, Storyboard.class);
            fixedStoryboard = StoryboardNormalizer.normalize(fixedStoryboard);

            return new Narrative(
                    narrative.getTargetConcept(),
                    narrative.getTargetDescription(),
                    fixedStoryboard
            );
        } catch (CompletionException e) {
            log.warn("LLM fix call failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("LLM fix failed: {}", e.getMessage());
            return null;
        }
    }

    private static final class StoryboardLayoutElement {
        private final String objectId;
        private final StoryboardObject object;
        private final StoryboardLayoutBounds bounds;

        private StoryboardLayoutElement(String objectId,
                                        StoryboardObject object,
                                        StoryboardLayoutBounds bounds) {
            this.objectId = objectId;
            this.object = object;
            this.bounds = bounds;
        }
    }

    private static final class StoryboardLayoutBounds {
        private final double minX;
        private final double maxX;
        private final double minY;
        private final double maxY;

        private StoryboardLayoutBounds(double minX,
                                       double maxX,
                                       double minY,
                                       double maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }

        private double centerX() {
            return (minX + maxX) / 2.0;
        }

        private double centerY() {
            return (minY + maxY) / 2.0;
        }

        private double area() {
            return Math.max(maxX - minX, 0.0) * Math.max(maxY - minY, 0.0);
        }
    }

    private static final class AxisBounds {
        private final double min;
        private final double max;

        private AxisBounds(double min, double max) {
            this.min = min;
            this.max = max;
        }
    }

    private static final class LayoutBucketRange {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;

        private LayoutBucketRange(int minX, int maxX, int minY, int maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

}
