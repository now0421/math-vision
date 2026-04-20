package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardPlacement;
import com.mathvision.model.Narrative.StoryboardPlacementAxis;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.NarrativePrompts;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
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
import java.util.HashSet;
import java.util.List;
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

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private int toolCalls = 0;

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
        return (Narrative) ctx.get(WorkflowKeys.NARRATIVE);
    }

    @Override
    public Narrative exec(Narrative narrative) {
        log.info("=== Stage 1c: Storyboard Validation ===");

        if (narrative == null || narrative.getStoryboard() == null) {
            log.warn("No narrative/storyboard to validate");
            return narrative;
        }

        Storyboard storyboard = narrative.getStoryboard();
        List<String> issues = validate(storyboard);

        if (issues.isEmpty()) {
            log.info("Storyboard validation passed (no issues)");
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
                return fixed;
            }
            log.warn("LLM fix resolved {}/{} issues, {} remain",
                    issues.size() - remainingIssues.size(), issues.size(), remainingIssues.size());
            return fixed;
        }

        log.warn("LLM fix failed, proceeding with original storyboard");
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
        }

        return null;
    }

    // ---- Static validation ----

    private List<String> validate(Storyboard storyboard) {
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

            JsonNode fixedData = aiClient.chatWithToolsRawAsync(
                    fixPrompt.toString(), systemPrompt, ToolSchemas.STORYBOARD)
                    .thenApply(raw -> {
                        toolCalls++;
                        JsonNode payload = JsonUtils.extractToolCallPayload(raw);
                        if (payload == null) {
                            String text = JsonUtils.extractBestEffortTextFromResponse(raw);
                            if (text != null && !text.isBlank()) {
                                String json = JsonUtils.extractJsonObject(text);
                                if (json != null) {
                                    return JsonUtils.parseTreeBestEffort(json);
                                }
                            }
                        }
                        return payload;
                    }).join();

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

            int totalDuration = StoryboardNormalizer.calculateStoryboardDuration(
                    fixedStoryboard, narrative.getTotalDuration());

            return new Narrative(
                    narrative.getTargetConcept(),
                    narrative.getTargetDescription(),
                    fixedStoryboard,
                    narrative.getStepOrder(),
                    totalDuration,
                    fixedStoryboard.getScenes().size()
            );
        } catch (CompletionException e) {
            log.warn("LLM fix call failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("LLM fix failed: {}", e.getMessage());
            return null;
        }
    }
}
