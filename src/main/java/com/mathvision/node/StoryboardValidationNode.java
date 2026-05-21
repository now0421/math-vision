package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardConstraint;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardPlacement;
import com.mathvision.model.Narrative.StoryboardPlacementAxis;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.StoryboardValidationReport;
import com.mathvision.model.StoryboardValidationTraceEntry;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.NarrativePrompts;
import com.mathvision.prompt.SystemPrompts;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.StoryboardConstraintCatalog;
import com.mathvision.util.StoryboardConstraintUtils;
import com.mathvision.util.StoryboardConstraintCatalog.RelationSpec;
import com.mathvision.util.StoryboardConstraintCatalog.Scope;
import com.mathvision.util.StoryboardGeometricMarkerValidator;
import com.mathvision.util.StoryboardNormalizer;
import com.mathvision.util.StoryboardPatchResolver;
import com.mathvision.util.TargetDescriptionBuilder;
import com.mathvision.util.TimeUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
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
 * Stage 1c: Storyboard Validation - static checks on the assembled storyboard
 * followed by an optional LLM cleanup/fix pass.
 *
 * Replaces NarrativeNode in the pipeline. Receives the Narrative already
 * assembled by VisualDesignNode and validates object lifecycle consistency,
 * scene ordering, and field completeness.
 */
public class StoryboardValidationNode extends PocketFlow.Node<Narrative, Narrative, String> {

    private static final Logger log = LoggerFactory.getLogger(StoryboardValidationNode.class);
    private static final String DEFAULT_STORYBOARD_BACKGROUND_DARK = "#000000";
    private static final String DEFAULT_STORYBOARD_BACKGROUND_LIGHT = "#FFFFFF";

    private String defaultStoryboardBackground() {
        return WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equalsIgnoreCase(outputTarget)
                ? DEFAULT_STORYBOARD_BACKGROUND_LIGHT
                : DEFAULT_STORYBOARD_BACKGROUND_DARK;
    }
    private static final double NON_TEXT_CONTRAST_THRESHOLD = 3.0;
    private static final double TEXT_CONTRAST_THRESHOLD = 4.5;
    private static final double FRAME_MIN_X = -7.111111;
    private static final double FRAME_MAX_X = 7.111111;
    private static final double FRAME_MIN_Y = -4.0;
    private static final double FRAME_MAX_Y = 4.0;
    private static final double OFFSCREEN_TOLERANCE = 0.03;
    private static final double MIN_OVERLAP_AREA = 0.015;
    private static final double MIN_OVERLAP_RATIO = 0.08;
    private static final double SPATIAL_BUCKET_SIZE = 1.25;
    private static final int DEFAULT_VALIDATION_FIX_ATTEMPTS = 3;

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private KnowledgeGraph knowledgeGraph;
    private String outputTarget = WorkflowConfig.OUTPUT_TARGET_MANIM;
    private int toolCalls = 0;
    private StoryboardValidationReport storyboardValidationReport;
    private NodeConversationContext fixConversationContext;

    public StoryboardValidationNode() {
        super(1, 0);
    }

    @Override
    public Narrative prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);
        this.knowledgeGraph = (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
        if (workflowConfig != null) {
            this.outputTarget = workflowConfig.getOutputTarget();
        }
        this.toolCalls = 0;
        this.storyboardValidationReport = null;
        this.fixConversationContext = null;
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

        Narrative current = narrative;
        Instant initialValidationStart = Instant.now();
        List<String> issues = validate(current.getStoryboard());
        this.storyboardValidationReport = baseReport(current.getStoryboard(), issues);
        appendValidationTraceEntry(
                current.getStoryboard(),
                "initial_validation",
                0,
                false,
                false,
                issues,
                0,
                TimeUtils.secondsSince(initialValidationStart),
                "Initial storyboard validation");
        logValidationIssues(issues);

        boolean fixApplied = false;
        int attempts = 0;
        int maxValidationFixAttempts = resolveMaxValidationFixAttempts();
        if (issues.isEmpty()) {
            if (aiClient == null || maxValidationFixAttempts == 0) {
                log.info("Storyboard validation passed (no issues)");
                finalizeReport(storyboardValidationReport, true, false, false, List.of(),
                        "Storyboard validation passed");
                return current;
            }

            attempts++;
            log.info("Storyboard validation passed; attempting LLM storyboard cleanup pass {}/{}",
                    attempts, maxValidationFixAttempts);
            Instant cleanupStart = Instant.now();
            int toolCallsBefore = toolCalls;
            Narrative fixed = attemptLlmFix(current, issues);
            int cleanupToolCalls = toolCalls - toolCallsBefore;
            if (fixed == null || fixed.getStoryboard() == null) {
                log.warn("LLM storyboard cleanup pass {}/{} did not return a usable storyboard",
                        attempts, maxValidationFixAttempts);
                appendValidationTraceEntry(
                        current.getStoryboard(),
                        "cleanup_failed",
                        attempts,
                        true,
                        false,
                        issues,
                        cleanupToolCalls,
                        TimeUtils.secondsSince(cleanupStart),
                        "Storyboard validation passed, but optional LLM cleanup did not return a usable storyboard");
                finalizeReport(storyboardValidationReport, true, true, false, List.of(),
                        "Storyboard validation passed; optional LLM cleanup was skipped after an unusable response");
                return current;
            }

            current = fixed;
            fixApplied = true;
            issues = validate(current.getStoryboard());
            appendValidationTraceEntry(
                    current.getStoryboard(),
                    "post_cleanup_validation",
                    attempts,
                    true,
                    true,
                    issues,
                    cleanupToolCalls,
                    TimeUtils.secondsSince(cleanupStart),
                    issues.isEmpty()
                            ? "Optional cleanup pass preserved a valid storyboard"
                            : "Optional cleanup pass introduced " + issues.size()
                                    + " storyboard validation issue(s)");
            if (issues.isEmpty()) {
                log.info("LLM storyboard cleanup completed successfully after clean validation");
                finalizeReport(storyboardValidationReport, true, true, true, issues,
                        "Storyboard validation passed and optional LLM cleanup completed successfully");
                return current;
            }

            log.warn("LLM storyboard cleanup pass {}/{} left {} issues",
                    attempts, maxValidationFixAttempts, issues.size());
            logValidationIssues(issues);
        }

        while (!issues.isEmpty() && attempts < maxValidationFixAttempts) {
            attempts++;
            log.warn("Attempting LLM storyboard cleanup pass {}/{}",
                    attempts, maxValidationFixAttempts);
            Instant cleanupStart = Instant.now();
            int toolCallsBefore = toolCalls;
            Narrative fixed = attemptLlmFix(current, issues);
            int cleanupToolCalls = toolCalls - toolCallsBefore;
            if (fixed == null || fixed.getStoryboard() == null) {
                log.warn("LLM storyboard cleanup pass {}/{} did not return a usable storyboard",
                        attempts, maxValidationFixAttempts);
                appendValidationTraceEntry(
                        current.getStoryboard(),
                        "cleanup_failed",
                        attempts,
                        true,
                        false,
                        issues,
                        cleanupToolCalls,
                        TimeUtils.secondsSince(cleanupStart),
                        "LLM storyboard cleanup did not return a usable storyboard");
                finalizeReport(storyboardValidationReport, false, true, fixApplied, issues,
                        "Storyboard validation found issues and the automatic LLM cleanup did not succeed");
                return current;
            }

            current = fixed;
            fixApplied = true;
            issues = validate(current.getStoryboard());
            appendValidationTraceEntry(
                    current.getStoryboard(),
                    "post_cleanup_validation",
                    attempts,
                    true,
                    true,
                    issues,
                    cleanupToolCalls,
                    TimeUtils.secondsSince(cleanupStart),
                    issues.isEmpty()
                            ? "Cleanup pass " + attempts + " resolved all storyboard validation issues"
                            : "Cleanup pass " + attempts + " left " + issues.size() + " storyboard validation issue(s)");
            if (issues.isEmpty()) {
                log.info("LLM storyboard cleanup completed successfully after {} pass(es)", attempts);
                finalizeReport(storyboardValidationReport, true, true, true, issues,
                        "Storyboard validation issues were fixed successfully after " + attempts + " pass(es)");
                return current;
            }

            log.warn("LLM storyboard cleanup pass {}/{} left {} issues",
                    attempts, maxValidationFixAttempts, issues.size());
            logValidationIssues(issues);
        }

        log.warn("Storyboard validation still has {} issues after {} cleanup pass(es); proceeding to next node",
                issues.size(), attempts);
        finalizeReport(storyboardValidationReport, false, attempts > 0, fixApplied, issues,
                "Storyboard validation reached the maximum of " + maxValidationFixAttempts
                        + " cleanup pass(es); proceeding with remaining issues");
        return current;
    }

    private int resolveMaxValidationFixAttempts() {
        if (workflowConfig == null) {
            return DEFAULT_VALIDATION_FIX_ATTEMPTS;
        }
        return Math.max(workflowConfig.getStoryboardValidationMaxRetries(), 0);
    }

    private void logValidationIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return;
        }
        log.warn("Storyboard validation found {} issues:", issues.size());
        for (String issue : issues) {
            log.warn("  - {}", issue);
        }
    }

    @Override
    public String post(Map<String, Object> ctx, Narrative prepRes, Narrative narrative) {
        ctx.put(WorkflowKeys.NARRATIVE, narrative);

        int prevCalls = (int) ctx.getOrDefault(WorkflowKeys.ENRICHMENT_TOOL_CALLS, 0);
        ctx.put(WorkflowKeys.ENRICHMENT_TOOL_CALLS, prevCalls + toolCalls);

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveValidatedStoryboard(outputDir,
                    narrative != null ? narrative.getStoryboard() : null);
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

        // Enrich placements via LLM for layout validation only.
        // Objects without placement (derived objects) are otherwise invisible
        // to offscreen / overlap checks. The enriched storyboard is used
        // exclusively for validateSceneLayout and is discarded afterward.
        Storyboard placementEnrichedStoryboard = resolvePlacementEnrichedStoryboard(storyboard);

        List<StoryboardScene> layoutScenes = buildValidationLayoutScenes(
                placementEnrichedStoryboard != null ? placementEnrichedStoryboard : storyboard);

        for (int i = 0; i < storyboard.getScenes().size(); i++) {
            StoryboardScene scene = storyboard.getScenes().get(i);
            String label = "scene " + (i + 1) + " (" + scene.getSceneId() + ")";

            StoryboardScene layoutScene = i < layoutScenes.size() ? layoutScenes.get(i) : null;
            validateSceneLayout(label, layoutScene, issues);

            // Check required fields (using original storyboard)
            if (scene.getTitle() == null || scene.getTitle().isBlank()) {
                issues.add(label + ": missing title");
            }
            if (scene.getGoal() == null || scene.getGoal().isBlank()) {
                issues.add(label + ": missing goal");
            }
        }

        // All remaining checks use the original storyboard
        issues.addAll(validateStoryboardObjectStructure(storyboard));
        issues.addAll(validateAsciiText(storyboard));
        issues.addAll(validateStoryboardColors(storyboard));
        issues.addAll(validateStructuredConstraints(storyboard));
        issues.addAll(validateGeometricMarkerDefinitions(storyboard));

        return issues;
    }

    private List<StoryboardScene> buildValidationLayoutScenes(Storyboard storyboard) {
        List<StoryboardScene> layoutScenes = new ArrayList<>();
        if (storyboard == null || storyboard.getScenes() == null) {
            return layoutScenes;
        }

        Map<String, StoryboardObject> registryDefinitions = buildRegistryDefinitions(storyboard);
        Map<String, StoryboardObject> visibleState = new LinkedHashMap<>();
        for (StoryboardScene scene : storyboard.getScenes()) {
            if (scene == null) {
                continue;
            }
            Map<String, StoryboardObject> sceneVisibleState = copyObjectMapById(visibleState);
            applyValidationScenePatches(sceneVisibleState, scene.getPersistentObjects(), registryDefinitions);
            applyValidationScenePatches(sceneVisibleState, scene.getEnteringObjects(), registryDefinitions);
            removeValidationSceneObjects(sceneVisibleState, scene.getExitingObjects());

            StoryboardScene layoutScene = new StoryboardScene();
            layoutScene.setSceneId(scene.getSceneId());
            layoutScene.setPersistentObjects(new ArrayList<>(copyObjectMapById(sceneVisibleState).values()));
            layoutScene.setEnteringObjects(new ArrayList<>());
            layoutScene.setExitingObjects(new ArrayList<>());
            layoutScenes.add(layoutScene);

            visibleState = sceneVisibleState;
        }
        return layoutScenes;
    }

    private Map<String, StoryboardObject> buildRegistryDefinitions(Storyboard storyboard) {
        Map<String, StoryboardObject> definitions = new LinkedHashMap<>();
        if (storyboard == null || storyboard.getObjectRegistry() == null) {
            return definitions;
        }
        for (StoryboardObject object : storyboard.getObjectRegistry()) {
            String id = StoryboardPatchResolver.objectId(object);
            if (id == null) {
                continue;
            }
            StoryboardObject copy = StoryboardPatchResolver.copyObject(object);
            if (copy != null) {
                copy.setPlacement(null);
                definitions.put(id, copy);
            }
        }
        return definitions;
    }

    private Map<String, StoryboardObject> copyObjectMapById(Map<String, StoryboardObject> source) {
        Map<String, StoryboardObject> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<String, StoryboardObject> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            StoryboardObject objectCopy = StoryboardPatchResolver.copyObject(entry.getValue());
            if (objectCopy != null) {
                copy.put(entry.getKey(), objectCopy);
            }
        }
        return copy;
    }

    private void applyValidationScenePatches(Map<String, StoryboardObject> visibleState,
                                             List<StoryboardObject> patches,
                                             Map<String, StoryboardObject> registryDefinitions) {
        if (visibleState == null || patches == null) {
            return;
        }
        for (StoryboardObject patch : patches) {
            String id = StoryboardPatchResolver.objectId(patch);
            if (id == null) {
                continue;
            }
            StoryboardObject merged = StoryboardPatchResolver.copyObject(visibleState.get(id));
            if (merged == null && registryDefinitions != null) {
                merged = StoryboardPatchResolver.copyObject(registryDefinitions.get(id));
            }
            if (merged == null) {
                merged = new StoryboardObject();
                merged.setId(id);
            }
            applyValidationScenePatch(merged, patch);
            visibleState.put(id, merged);
        }
    }

    private void applyValidationScenePatch(StoryboardObject target, StoryboardObject patch) {
        if (target == null || patch == null) {
            return;
        }
        String id = StoryboardPatchResolver.objectId(patch);
        if (id != null) {
            target.setId(id);
        }
        if (patch.getPlacement() != null && patch.getPlacement().hasData()) {
            StoryboardObject patchCopy = StoryboardPatchResolver.copyObject(patch);
            target.setPlacement(patchCopy != null ? patchCopy.getPlacement() : patch.getPlacement());
        }
        if (patch.getStyle() != null && patch.getStyle().hasData()) {
            StoryboardObject patchCopy = StoryboardPatchResolver.copyObject(patch);
            target.setStyle(patchCopy != null ? patchCopy.getStyle() : patch.getStyle());
        }
    }

    private void removeValidationSceneObjects(Map<String, StoryboardObject> visibleState,
                                              List<StoryboardObject> exitingObjects) {
        if (visibleState == null || exitingObjects == null) {
            return;
        }
        for (StoryboardObject exitingObject : exitingObjects) {
            String id = StoryboardPatchResolver.objectId(exitingObject);
            if (id != null) {
                visibleState.remove(id);
            }
        }
    }

    private List<String> validateStoryboardObjectStructure(Storyboard storyboard) {
        List<String> issues = new ArrayList<>();
        if (storyboard == null) {
            return issues;
        }

        Set<String> registryIds = new LinkedHashSet<>();
        Set<String> duplicateRegistryIds = new LinkedHashSet<>();
        if (storyboard.getObjectRegistry() != null) {
            for (int i = 0; i < storyboard.getObjectRegistry().size(); i++) {
                StoryboardObject object = storyboard.getObjectRegistry().get(i);
                String id = StoryboardPatchResolver.objectId(object);
                if (id == null) {
                    issues.add("object_registry[" + i + "]: missing id");
                    continue;
                }
                if (!registryIds.add(id)) {
                    duplicateRegistryIds.add(id);
                }
                if (object.getKind() == null || object.getKind().isBlank()) {
                    issues.add("object_registry object '" + id + "': missing kind");
                }
            }
        }
        for (String id : duplicateRegistryIds) {
            issues.add("object_registry: duplicate object id '" + id + "'");
        }

        if (storyboard.getScenes() != null) {
            for (int i = 0; i < storyboard.getScenes().size(); i++) {
                StoryboardScene scene = storyboard.getScenes().get(i);
                String sceneLabel = "scene " + (i + 1) + " (" + (scene != null ? scene.getSceneId() : null) + ")";
                if (scene == null) {
                    continue;
                }
                validateScenePatchReferences(sceneLabel, "entering_objects", scene.getEnteringObjects(), registryIds, issues);
                validateScenePatchReferences(sceneLabel, "persistent_objects", scene.getPersistentObjects(), registryIds, issues);
                validateScenePatchReferences(sceneLabel, "exiting_objects", scene.getExitingObjects(), registryIds, issues);
                validateActionTargetReferences(sceneLabel, scene.getActions(), registryIds, issues);
            }
        }
        return issues;
    }

    private void validateScenePatchReferences(String sceneLabel,
                                              String fieldName,
                                              List<StoryboardObject> objects,
                                              Set<String> registryIds,
                                              List<String> issues) {
        if (objects == null) {
            return;
        }
        Set<String> seenIds = new LinkedHashSet<>();
        for (int i = 0; i < objects.size(); i++) {
            StoryboardObject object = objects.get(i);
            String id = StoryboardPatchResolver.objectId(object);
            String itemLabel = sceneLabel + " " + fieldName + "[" + i + "]";
            if (id == null) {
                issues.add(itemLabel + ": missing id");
                continue;
            }
            if (!seenIds.add(id)) {
                issues.add(itemLabel + ": duplicate object id '" + id + "' in " + fieldName);
            }
            if (!registryIds.contains(id)) {
                issues.add(itemLabel + ": references unknown object_registry id '" + id + "'");
            }
        }
    }

    private void validateActionTargetReferences(String sceneLabel,
                                                List<Narrative.StoryboardAction> actions,
                                                Set<String> registryIds,
                                                List<String> issues) {
        if (actions == null) {
            return;
        }
        for (int i = 0; i < actions.size(); i++) {
            Narrative.StoryboardAction action = actions.get(i);
            if (action == null || action.getTargets() == null) {
                continue;
            }
            for (String target : action.getTargets()) {
                String id = target != null ? target.trim() : "";
                if (id.isEmpty()) {
                    issues.add(sceneLabel + " actions[" + i + "]: targets contains a blank id");
                } else if (!registryIds.contains(id)) {
                    issues.add(sceneLabel + " actions[" + i + "]: targets references unknown object_registry id '" + id + "'");
                }
            }
        }
    }

    private List<String> validateGeometricMarkerDefinitions(Storyboard storyboard) {
        return StoryboardGeometricMarkerValidator.validateStoryboard(storyboard);
    }

    /**
     * Resolves a ref value to a string id. Handles both String values and
     * nested structures that might appear in constraint refs.
     */
    private String resolveRefId(Object refValue) {
        if (refValue == null) {
            return null;
        }
        if (refValue instanceof String) {
            String s = ((String) refValue).trim();
            return s.isBlank() ? null : s;
        }
        // Nested maps or lists are not simple id refs
        return null;
    }

    private List<String> validateStructuredConstraints(Storyboard storyboard) {
        List<String> issues = new ArrayList<>();
        if (storyboard == null) {
            return issues;
        }
        Set<String> knownIds = new LinkedHashSet<>();
        Map<String, StoryboardObject> registryById = new LinkedHashMap<>();
        if (storyboard.getObjectRegistry() != null) {
            for (StoryboardObject object : storyboard.getObjectRegistry()) {
                String id = StoryboardPatchResolver.objectId(object);
                if (id != null) {
                    knownIds.add(id);
                    registryById.put(id, object);
                }
            }
        }

        if (storyboard.getObjectRegistry() != null) {
            for (StoryboardObject object : storyboard.getObjectRegistry()) {
                String objectId = StoryboardPatchResolver.objectId(object);
                validateConstraintList("object_registry object '" + objectId + "'",
                        object != null ? object.getConstraints() : null,
                        knownIds,
                        registryById,
                        objectId,
                        Scope.OBJECT,
                        issues);

            }
        }

        if (storyboard.getScenes() != null) {
            for (int i = 0; i < storyboard.getScenes().size(); i++) {
                StoryboardScene scene = storyboard.getScenes().get(i);
                String sceneId = scene != null ? scene.getSceneId() : null;
                validateConstraintList("scene " + (i + 1) + " (" + sceneId + ")",
                        scene != null ? scene.getConstraints() : null,
                        knownIds,
                        registryById,
                        null,
                        Scope.SCENE,
                        issues);
            }
        }
        return issues;
    }

    private void validateConstraintList(String scope,
                                        List<StoryboardConstraint> constraints,
                                        Set<String> knownIds,
                                        Map<String, StoryboardObject> registryById,
                                        String ownerId,
                                        Scope constraintScope,
                                        List<String> issues) {
        if (constraints == null || constraints.isEmpty()) {
            return;
        }
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < constraints.size(); i++) {
            StoryboardConstraint constraint = constraints.get(i);
            String label = scope + " constraints[" + i + "]";
            if (constraint == null || !constraint.hasData()) {
                issues.add(label + ": empty constraint must be removed");
                continue;
            }
            RelationSpec relationSpec = null;
            if (constraint.getDomain() == null || constraint.getDomain().isBlank()) {
                issues.add(label + ": missing domain");
            } else if (!StoryboardConstraintCatalog.isValidDomain(constraint.getDomain())) {
                issues.add(label + ": unknown domain '" + constraint.getDomain().trim() + "'");
            }
            if (constraint.getRelation() == null || constraint.getRelation().isBlank()) {
                issues.add(label + ": missing relation");
            } else {
                relationSpec = StoryboardConstraintCatalog.relation(constraint.getRelation());
                if (relationSpec == null) {
                    issues.add(label + ": unknown relation '" + constraint.getRelation().trim()
                            + "'; use one of " + StoryboardConstraintCatalog.relationList());
                } else {
                    if (!relationSpec.allowsScope(constraintScope)) {
                        issues.add(label + ": relation '" + relationSpec.relation()
                                + "' is not valid for " + constraintScope.name().toLowerCase(Locale.ROOT)
                                + "-level constraints");
                    }
                    String domain = normalizeConstraintKey(constraint.getDomain());
                    if (!domain.isBlank() && !domain.equals(relationSpec.domain())) {
                        issues.add(label + ": domain '" + constraint.getDomain().trim()
                                + "' does not match relation '" + relationSpec.relation()
                                + "' domain '" + relationSpec.domain() + "'");
                    }
                }
            }
            if (constraint.getId() != null && !constraint.getId().isBlank()
                    && !seenIds.add(constraint.getId().trim())) {
                issues.add(label + ": duplicate constraint id '" + constraint.getId().trim() + "'");
            }
            if (constraint.getStrength() == null || constraint.getStrength().isBlank()) {
                issues.add(label + ": missing strength");
            } else if (!StoryboardConstraintCatalog.isValidStrength(constraint.getStrength())) {
                issues.add(label + ": strength must be hard, repair_hard, or soft");
            }
            Map<String, Object> refs = constraint.getRefs();
            if (refs == null || refs.isEmpty()) {
                issues.add(label + ": refs must map semantic roles to referenced object ids");
            } else {
                validateConstraintRefRoles(label, refs, relationSpec, issues);
                Set<String> referencedIds = new LinkedHashSet<>();
                collectConstraintRefs(label, refs, referencedIds, issues);
                for (String refId : referencedIds) {
                    if (!knownIds.contains(refId)) {
                        issues.add(label + ": refs references unknown id '" + refId + "'");
                    }
                }
                if (ownerId != null && !ownerId.isBlank() && !referencedIds.contains(ownerId)) {
                    issues.add(label + ": object-level constraint should include its owner id '" + ownerId + "' in refs");
                }
                validateRelationKindCompatibility(label, constraint, registryById, issues);
            }
            validateConstraintParameters(label, constraint.getParameters(), relationSpec, knownIds, issues);
        }
    }

    private void validateRelationKindCompatibility(String label,
                                                   StoryboardConstraint constraint,
                                                   Map<String, StoryboardObject> registryById,
                                                   List<String> issues) {
        if (constraint == null || registryById == null || registryById.isEmpty()) {
            return;
        }
        String relation = normalizeConstraintKey(constraint.getRelation());
        switch (relation) {
            case "label_for":
                validateRefKind(label, constraint, registryById, "label",
                        List.of(" text ", " equation ", " formula ", " label ", " caption ", " title "), issues);
                break;
            case "moves_on_object":
                validateRefKind(label, constraint, registryById, "point", List.of(" point "), issues);
                break;
            case "connects_points":
                validateAnyRefKind(label, constraint, registryById,
                        List.of("object", "connector", "segment", "line", "ray"),
                        List.of(" segment ", " line ", " ray ", " vector "), issues);
                break;
            case "angle_between":
                validateRefKind(label, constraint, registryById, "marker",
                        List.of(" angle_marker ", " anglemarker ", " right_angle ", " rightangle ", " arc_marker "), issues);
                break;
            case "arc_sweep":
                validateAnyRefKind(label, constraint, registryById, List.of("marker", "arc"),
                        List.of(" arc ", " arc_marker ", " angle_marker ", " anglemarker "), issues);
                break;
            case "right_angle_at":
                validateRefKind(label, constraint, registryById, "marker",
                        List.of(" right_angle ", " rightangle ", " right_angle_marker ", " angle_marker ", " anglemarker "), issues);
                break;
            default:
                break;
        }
    }

    private void validateRefKind(String label,
                                 StoryboardConstraint constraint,
                                 Map<String, StoryboardObject> registryById,
                                 String role,
                                 List<String> allowedKindTokens,
                                 List<String> issues) {
        String id = resolveRefId(constraint.getRefs() != null ? constraint.getRefs().get(role) : null);
        if (id == null) {
            return;
        }
        StoryboardObject object = registryById.get(id);
        if (object == null) {
            return;
        }
        String kind = normalizeForSemanticCheck(object.getKind());
        if (!containsAny(kind, allowedKindTokens.toArray(new String[0]))) {
            issues.add(label + ": relation '" + constraint.getRelation() + "' refs." + role
                    + " must reference a compatible kind, but '" + id + "' has kind '" + object.getKind() + "'");
        }
    }

    private void validateAnyRefKind(String label,
                                    StoryboardConstraint constraint,
                                    Map<String, StoryboardObject> registryById,
                                    List<String> roles,
                                    List<String> allowedKindTokens,
                                    List<String> issues) {
        if (constraint.getRefs() == null) {
            return;
        }
        for (String role : roles) {
            String id = resolveRefId(constraint.getRefs().get(role));
            if (id == null) {
                continue;
            }
            StoryboardObject object = registryById.get(id);
            if (object == null) {
                return;
            }
            String kind = normalizeForSemanticCheck(object.getKind());
            if (!containsAny(kind, allowedKindTokens.toArray(new String[0]))) {
                issues.add(label + ": relation '" + constraint.getRelation() + "' refs." + role
                        + " must reference a compatible kind, but '" + id + "' has kind '" + object.getKind() + "'");
            }
            return;
        }
    }

    private void validateConstraintRefRoles(String label,
                                            Map<String, Object> refs,
                                            RelationSpec relationSpec,
                                            List<String> issues) {
        if (relationSpec == null || refs == null || refs.isEmpty()) {
            return;
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String role : refs.keySet()) {
            String normalizedRole = normalizeConstraintKey(role);
            if (normalizedRole.isBlank()) {
                issues.add(label + ": refs contains a blank role name");
                continue;
            }
            roles.add(normalizedRole);
            if (!relationSpec.allowedRefs().contains(normalizedRole)) {
                issues.add(label + ": relation '" + relationSpec.relation()
                        + "' does not allow refs role '" + role + "'");
            }
        }
        for (Set<String> requiredGroup : relationSpec.requiredRefGroups()) {
            boolean present = false;
            for (String role : requiredGroup) {
                if (roles.contains(role)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                issues.add(label + ": relation '" + relationSpec.relation()
                        + "' requires refs role " + describeRequiredRoleGroup(requiredGroup));
            }
        }
    }

    private void validateConstraintParameters(String label,
                                              Map<String, Object> parameters,
                                              RelationSpec relationSpec,
                                              Set<String> knownIds,
                                              List<String> issues) {
        if (relationSpec == null) {
            return;
        }
        Map<String, Object> safeParameters = parameters != null ? parameters : Map.of();
        Set<String> parameterKeys = new LinkedHashSet<>();
        for (String parameterName : safeParameters.keySet()) {
            String normalizedName = normalizeConstraintKey(parameterName);
            if (normalizedName.isBlank()) {
                issues.add(label + ": parameters contains a blank key");
                continue;
            }
            parameterKeys.add(normalizedName);
            if (!relationSpec.allowedParameters().contains(normalizedName)) {
                issues.add(label + ": relation '" + relationSpec.relation()
                        + "' does not allow parameter '" + parameterName + "'");
            }
        }
        for (String requiredParameter : relationSpec.requiredParameters()) {
            if (!parameterKeys.contains(requiredParameter)) {
                issues.add(label + ": relation '" + relationSpec.relation()
                        + "' requires parameter '" + requiredParameter + "'");
            }
        }
        for (Map.Entry<String, Object> entry : safeParameters.entrySet()) {
            String normalizedName = normalizeConstraintKey(entry.getKey());
            Set<String> enumValues = relationSpec.enumParameters().get(normalizedName);
            if (enumValues != null) {
                Object value = entry.getValue();
                if (!(value instanceof String)) {
                    issues.add(label + ": parameter '" + entry.getKey()
                            + "' must be one of " + String.join(", ", enumValues));
                } else {
                    String normalizedValue = normalizeConstraintKey((String) value);
                    if (!enumValues.contains(normalizedValue)) {
                        issues.add(label + ": parameter '" + entry.getKey()
                                + "' must be one of " + String.join(", ", enumValues));
                    }
                }
            }
            collectParameterObjectIds(label + " parameters." + entry.getKey(),
                    entry.getValue(), knownIds, issues);
        }
    }

    private void collectParameterObjectIds(String label,
                                           Object value,
                                           Set<String> knownIds,
                                           List<String> issues) {
        if (value == null || knownIds == null || knownIds.isEmpty()) {
            return;
        }
        if (value instanceof String) {
            String stringValue = ((String) value).trim();
            if (knownIds.contains(stringValue)) {
                issues.add(label + ": parameters must not contain object id '" + stringValue
                        + "'; put object references in refs");
            }
            return;
        }
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectParameterObjectIds(label, item, knownIds, issues);
            }
            return;
        }
        if (value instanceof Map<?, ?>) {
            for (Object nestedValue : ((Map<?, ?>) value).values()) {
                collectParameterObjectIds(label, nestedValue, knownIds, issues);
            }
        }
    }

    private String describeRequiredRoleGroup(Set<String> roles) {
        return roles.size() == 1 ? "'" + roles.iterator().next() + "'"
                : "one of [" + String.join(", ", roles) + "]";
    }

    private String normalizeConstraintKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void collectConstraintRefs(String label,
                                       Object value,
                                       Set<String> referencedIds,
                                       List<String> issues) {
        if (value == null) {
            issues.add(label + ": refs contains a null value");
            return;
        }
        if (value instanceof String) {
            String stringValue = (String) value;
            if (stringValue.isBlank()) {
                issues.add(label + ": refs contains a blank id");
            } else {
                referencedIds.add(stringValue.trim());
            }
            return;
        }
        if (value instanceof Iterable<?>) {
            Iterable<?> iterableValue = (Iterable<?>) value;
            for (Object item : iterableValue) {
                collectConstraintRefs(label, item, referencedIds, issues);
            }
            return;
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> mapValue = (Map<?, ?>) value;
            for (Object nestedValue : mapValue.values()) {
                collectConstraintRefs(label, nestedValue, referencedIds, issues);
            }
            return;
        }
        issues.add(label + ": refs values must be object ids or nested id lists/maps");
    }

    private boolean isTextRenderKind(String kind) {
        return containsAny(kind,
                " text ", " label ", " text_card ", " equation ", " formula ", " formula_card ",
                " title ", " caption ");
    }

    private String normalizeForSemanticCheck(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_*']+", " ").trim() + " ";
    }

    private boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private List<String> validateStoryboardColors(Storyboard storyboard) {
        List<String> issues = new ArrayList<>();
        if (storyboard == null) {
            return issues;
        }
        validateObjectColors("object_registry", storyboard.getObjectRegistry(), issues);
        if (storyboard.getScenes() != null) {
            for (int i = 0; i < storyboard.getScenes().size(); i++) {
                StoryboardScene scene = storyboard.getScenes().get(i);
                String sceneLabel = "scene " + (i + 1) + " (" + (scene != null ? scene.getSceneId() : null) + ")";
                if (scene == null) {
                    continue;
                }
                validateObjectColors(sceneLabel + " entering_objects", scene.getEnteringObjects(), issues);
                validateObjectColors(sceneLabel + " persistent_objects", scene.getPersistentObjects(), issues);
            }
        }
        return issues;
    }

    private void validateObjectColors(String context,
                                      List<StoryboardObject> objects,
                                      List<String> issues) {
        if (objects == null || objects.isEmpty()) {
            return;
        }
        for (StoryboardObject object : objects) {
            if (object == null || object.getStyle() == null) {
                continue;
            }
            String objectId = StoryboardPatchResolver.objectId(object);
            List<ColorReference> colors = collectColorReferences(object);
            for (ColorReference color : colors) {
                if (!isSixDigitHexColor(color.value)) {
                    issues.add(context + ": object '" + objectId + "' uses invalid color '" + color.value
                            + "' at style." + color.propertyPath
                            + "; expected 6-digit hex format #RRGGBB with opacity in a separate opacity field");
                }
            }
            validateObjectColorContrast(context, objectId, object, colors, issues);
        }
    }

    private List<ColorReference> collectColorReferences(StoryboardObject object) {
        List<ColorReference> colors = new ArrayList<>();
        if (object == null || object.getStyle() == null) {
            return colors;
        }
        Narrative.StoryboardStyle style = object.getStyle();
        boolean isTextKind = isTextual(object);
        boolean isTextCard = containsAny(normalizeForSemanticCheck(object.getKind()), " text_card ", " formula_card ");
        boolean hasExplicitTextBackground = isTextKind && style.getFillOpacity() != null && style.getFillOpacity() > 0.0;
        collectColorValue("color", style.getColor(), isTextKind, false, colors);
        collectColorValue("fill_color", style.getFillColor(), false, isTextCard || hasExplicitTextBackground, colors);
        collectColorValue("stroke_color", style.getStrokeColor(), false, isTextCard, colors);
        collectColorValue("highlight_color", style.getHighlightColor(), false, false, colors);
        return colors;
    }

    private void collectColorValue(String propertyPath,
                                   Object rawValue,
                                   boolean textLayer,
                                   boolean explicitBackground,
                                   List<ColorReference> colors) {
        if (rawValue == null || propertyPath == null) {
            return;
        }
        String color = String.valueOf(rawValue).trim();
        if (color.isBlank()) {
            return;
        }
        colors.add(new ColorReference(propertyPath, color, textLayer, explicitBackground));
    }

    private void validateObjectColorContrast(String context,
                                             String objectId,
                                             StoryboardObject object,
                                             List<ColorReference> colors,
                                             List<String> issues) {
        List<ColorReference> validColors = colors.stream()
                .filter(color -> isSixDigitHexColor(color.value))
                .collect(java.util.stream.Collectors.toList());
        if (validColors.isEmpty()) {
            return;
        }

        if (isTextualColorObject(object, validColors)) {
            ColorReference foreground = selectTextForeground(validColors);
            if (foreground == null) {
                return;
            }
            ColorReference background = selectTextBackground(validColors);
            String backgroundColor = background != null ? background.value : defaultStoryboardBackground();
            validateContrast(context, objectId, foreground.value, backgroundColor,
                    TEXT_CONTRAST_THRESHOLD, "text", issues);
            return;
        }

        for (ColorReference foreground : validColors) {
            if (foreground.isExplicitBackground()) {
                continue;
            }
            validateContrast(context, objectId, foreground.value, defaultStoryboardBackground(),
                    NON_TEXT_CONTRAST_THRESHOLD, "non-text", issues);
        }
    }

    private boolean isSixDigitHexColor(String color) {
        return color != null && color.matches("#[0-9A-Fa-f]{6}");
    }

    private void validateContrast(String context,
                                  String objectId,
                                  String foreground,
                                  String background,
                                  double threshold,
                                  String category,
                                  List<String> issues) {
        double contrast = contrastRatio(foreground, background);
        if (contrast + 1e-9 < threshold) {
            issues.add(context + ": object '" + objectId + "' has insufficient " + category
                    + " color contrast; foreground=" + foreground.toUpperCase(Locale.ROOT)
                    + ", background=" + background.toUpperCase(Locale.ROOT)
                    + ", contrast=" + formatContrast(contrast)
                    + ", required>=" + formatContrast(threshold));
        }
    }

    private double contrastRatio(String foreground, String background) {
        double fg = relativeLuminance(foreground);
        double bg = relativeLuminance(background);
        double lighter = Math.max(fg, bg);
        double darker = Math.min(fg, bg);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private double relativeLuminance(String color) {
        int r = Integer.parseInt(color.substring(1, 3), 16);
        int g = Integer.parseInt(color.substring(3, 5), 16);
        int b = Integer.parseInt(color.substring(5, 7), 16);
        return 0.2126 * linearRgbChannel(r)
                + 0.7152 * linearRgbChannel(g)
                + 0.0722 * linearRgbChannel(b);
    }

    private double linearRgbChannel(int channel) {
        double srgb = channel / 255.0;
        return srgb <= 0.03928 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    private String formatContrast(double contrast) {
        return String.format(Locale.ROOT, "%.2f", contrast);
    }

    private boolean isTextualColorObject(StoryboardObject object, List<ColorReference> colors) {
        if (isTextual(object)) {
            return true;
        }
        return colors.stream().anyMatch(ColorReference::isTextLayer);
    }

    private ColorReference selectTextForeground(List<ColorReference> colors) {
        for (ColorReference color : colors) {
            if (color.isTextLayer() && !color.isExplicitBackground()) {
                return color;
            }
        }
        for (ColorReference color : colors) {
            if (!color.isExplicitBackground()) {
                return color;
            }
        }
        return null;
    }

    private ColorReference selectTextBackground(List<ColorReference> colors) {
        for (ColorReference color : colors) {
            if (color.isTextBackgroundFill()) {
                return color;
            }
        }
        return null;
    }

    private List<String> validateAsciiText(Storyboard storyboard) {
        List<String> issues = new ArrayList<>();
        List<String> nonAsciiTokens = findNonAsciiTextTokens(storyboard);
        if (!nonAsciiTokens.isEmpty()) {
            issues.add("Storyboard contains non-ASCII text tokens that must be replaced with ASCII equivalents: "
                    + nonAsciiTokens);
        }
        return issues;
    }

    private List<String> findNonAsciiTextTokens(Storyboard storyboard) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (storyboard == null) {
            return new ArrayList<>();
        }
        JsonNode root = JsonUtils.mapper().valueToTree(storyboard);
        collectNonAsciiTextTokens(root, tokens);
        return new ArrayList<>(tokens);
    }

    private void collectNonAsciiTextTokens(JsonNode node, Set<String> tokens) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            addNonAsciiTokens(node.asText(), tokens);
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectNonAsciiTextTokens(item, tokens);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    collectNonAsciiTextTokens(entry.getValue(), tokens));
        }
    }

    private void addNonAsciiTokens(String text, Set<String> tokens) {
        if (isBlank(text) || !containsNonAscii(text)) {
            return;
        }
        for (String rawToken : text.split("\\s+")) {
            String token = trimAsciiBoundaryPunctuation(rawToken);
            if (!token.isBlank() && containsNonAscii(token)) {
                tokens.add(token);
            }
        }
    }

    private boolean containsNonAscii(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return true;
            }
        }
        return false;
    }

    private String trimAsciiBoundaryPunctuation(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        int start = 0;
        int end = token.length();
        while (start < end && isAsciiBoundaryPunctuation(token.charAt(start))) {
            start++;
        }
        while (end > start && isAsciiBoundaryPunctuation(token.charAt(end - 1))) {
            end--;
        }
        return token.substring(start, end);
    }

    private boolean isAsciiBoundaryPunctuation(char ch) {
        return ch <= 0x7F && !Character.isLetterOrDigit(ch);
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

        for (StoryboardLayoutElement element : elements) {
            String overflowSummary = summarizeOverflow(element.bounds);
            if (overflowSummary != null) {
                issues.add(formatOffscreenIssue(sceneLabel, element, overflowSummary, elements));
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
            String rawAnchorId = resolveAttachmentAnchorId(object);
            String anchorId = StoryboardPatchResolver.objectId(visibleObjects.get(rawAnchorId));
            if (anchorId == null) {
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

        return inferObjectBounds(object, xBounds, yBounds);
    }

    private StoryboardLayoutBounds inferObjectBounds(StoryboardObject object,
                                                     AxisBounds xBounds,
                                                     AxisBounds yBounds) {
        if (object == null || xBounds == null || yBounds == null) {
            return new StoryboardLayoutBounds(
                    xBounds != null ? xBounds.min : 0.0,
                    xBounds != null ? xBounds.max : 0.0,
                    yBounds != null ? yBounds.min : 0.0,
                    yBounds != null ? yBounds.max : 0.0);
        }
        double width = Math.max(xBounds.max - xBounds.min, 0.0);
        double height = Math.max(yBounds.max - yBounds.min, 0.0);
        if (width > 1e-9 && height > 1e-9) {
            return new StoryboardLayoutBounds(xBounds.min, xBounds.max, yBounds.min, yBounds.max);
        }

        double inferredWidth = width;
        double inferredHeight = height;
        boolean inferredSize = false;
        if (isTextLike(object)) {
            double fontSize = object.getStyle() != null && object.getStyle().getFontSize() != null
                    ? object.getStyle().getFontSize()
                    : 24.0;
            double textUnitHeight = Math.max(fontSize / 72.0, 0.18);
            int textLength = visibleTextLength(object);
            inferredWidth = Math.max(inferredWidth, Math.max(textLength * textUnitHeight * 0.33, textUnitHeight));
            inferredHeight = Math.max(inferredHeight, textUnitHeight);
            inferredSize = true;
        } else if (isPointLike(object)) {
            double radius = pointRadius(object);
            inferredWidth = Math.max(inferredWidth, radius * 2.0);
            inferredHeight = Math.max(inferredHeight, radius * 2.0);
            inferredSize = true;
        }

        if (!inferredSize) {
            return new StoryboardLayoutBounds(xBounds.min, xBounds.max, yBounds.min, yBounds.max);
        }
        if (inferredWidth <= 1e-9) {
            inferredWidth = inferredHeight;
        }
        if (inferredHeight <= 1e-9) {
            inferredHeight = inferredWidth;
        }

        double centerX = (xBounds.min + xBounds.max) / 2.0;
        double centerY = (yBounds.min + yBounds.max) / 2.0;
        return new StoryboardLayoutBounds(
                round(centerX - inferredWidth / 2.0),
                round(centerX + inferredWidth / 2.0),
                round(centerY - inferredHeight / 2.0),
                round(centerY + inferredHeight / 2.0));
    }

    private int visibleTextLength(StoryboardObject object) {
        String text = object != null ? object.getContent() : null;
        if (text == null || text.isBlank()) {
            return 1;
        }
        return Math.max(text.replaceAll("\\s+", "").length(), 1);
    }

    private boolean isTextLike(StoryboardObject object) {
        return object != null && isTextRenderKind(normalizeForSemanticCheck(object.getKind()));
    }

    private boolean isPointLike(StoryboardObject object) {
        return object != null && containsAny(normalizeForSemanticCheck(object.getKind()), " point ", " dot ");
    }

    private double pointRadius(StoryboardObject object) {
        if (object != null && object.getStyle() != null) {
            Narrative.StoryboardStyle style = object.getStyle();
            if (style.getRadius() != null && style.getRadius() > 0.0) {
                return style.getRadius();
            }
            if (style.getPointSize() != null && style.getPointSize() > 0.0) {
                return style.getPointSize();
            }
        }
        return 0.12;
    }

    private String resolveAttachmentAnchorId(StoryboardObject object) {
        if (object == null || object.getConstraints() == null) {
            return null;
        }
        String objectId = StoryboardPatchResolver.objectId(object);
        for (StoryboardConstraint constraint : object.getConstraints()) {
            if (!StoryboardConstraintCatalog.isAttachmentRelation(constraint.getRelation())
                    || constraint.getRefs() == null) {
                continue;
            }
            String anchorId = resolveRefId(constraint.getRefs().get("anchor"));
            if (anchorId == null || anchorId.isBlank()) {
                continue;
            }
            Set<String> ownerIds = StoryboardConstraintUtils.ownerIds(constraint);
            if (ownerIds.isEmpty() || ownerIds.contains(objectId)) {
                return anchorId;
            }
        }
        return null;
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

    private String formatOffscreenIssue(String sceneLabel,
                                        StoryboardLayoutElement element,
                                        String overflowSummary,
                                        List<StoryboardLayoutElement> elements) {
        StringBuilder sb = new StringBuilder();
        sb.append("Issue: ").append(sceneLabel).append(": object '").append(element.objectId)
                .append("' extends outside the frame bounds (").append(overflowSummary).append(")");
        String dependencyContext = formatDependencyContext(element.objectId, element.object, elements);
        if (!dependencyContext.isBlank()) {
            sb.append("\n").append(dependencyContext);
        }
        return sb.toString();
    }

    private String formatDependencyContext(String objectId,
                                           StoryboardObject object,
                                           List<StoryboardLayoutElement> elements) {
        List<String> dependencies = constraintDependencyIds(object);
        if (object == null || dependencies.isEmpty()) {
            return "";
        }
        Map<String, StoryboardLayoutElement> byId = new LinkedHashMap<>();
        for (StoryboardLayoutElement element : elements) {
            if (element != null && element.objectId != null) {
                byId.put(element.objectId, element);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Dependency chain:\n");
        sb.append("- ").append(objectId).append(" depends on [")
                .append(String.join(", ", dependencies)).append("]");
        String relationSummary = formatConstraintRelationSummary(object);
        if (!relationSummary.isBlank()) {
            sb.append(" via ").append(relationSummary);
        }
        sb.append("\n");
        appendDependencyPlacementLines(dependencies, byId, new LinkedHashSet<>(), sb);
        return sb.toString();
    }

    private String formatConstraintRelationSummary(StoryboardObject object) {
        if (object == null || object.getConstraints() == null) {
            return "";
        }
        LinkedHashSet<String> relations = new LinkedHashSet<>();
        for (StoryboardConstraint constraint : object.getConstraints()) {
            if (constraint != null && constraint.getRelation() != null && !constraint.getRelation().isBlank()) {
                relations.add(constraint.getRelation().trim());
            }
        }
        return String.join(", ", relations);
    }

    private List<String> constraintDependencyIds(StoryboardObject object) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (object == null || object.getConstraints() == null) {
            return new ArrayList<>();
        }
        String objectId = StoryboardPatchResolver.objectId(object);
        for (StoryboardConstraint constraint : object.getConstraints()) {
            ids.addAll(StoryboardConstraintUtils.dependencyIds(constraint));
        }
        ids.remove(objectId);
        return new ArrayList<>(ids);
    }

    private void appendDependencyPlacementLines(List<String> dependencyIds,
                                                Map<String, StoryboardLayoutElement> byId,
                                                LinkedHashSet<String> visited,
                                                StringBuilder sb) {
        for (String dependencyId : dependencyIds) {
            if (dependencyId == null || !visited.add(dependencyId)) {
                continue;
            }
            StoryboardLayoutElement dependencyElement = byId.get(dependencyId);
            if (dependencyElement == null || dependencyElement.object == null) {
                sb.append("- ").append(dependencyId).append(": placement unavailable\n");
                continue;
            }
            StoryboardObject dependencyObject = dependencyElement.object;
            sb.append("- ").append(dependencyId).append(": ")
                    .append(formatPlacementSummary(dependencyObject)).append("\n");
            List<String> nestedDependencies = constraintDependencyIds(dependencyObject);
            if (!nestedDependencies.isEmpty()) {
                sb.append("- ").append(dependencyId).append(" depends on [")
                        .append(String.join(", ", nestedDependencies)).append("]\n");
                appendDependencyPlacementLines(nestedDependencies, byId, visited, sb);
            }
        }
    }

    private String formatPlacementSummary(StoryboardObject object) {
        if (object == null || object.getPlacement() == null || !object.getPlacement().hasData()) {
            return "placement unavailable";
        }
        StoryboardPlacement placement = object.getPlacement();
        StringBuilder sb = new StringBuilder();
        String coordinateSpace = !isBlank(placement.getCoordinateSpace())
                ? placement.getCoordinateSpace().trim()
                : "unknown";
        sb.append(coordinateSpace).append(" placement");
        if (Narrative.StoryboardPlacement.COORDINATE_SPACE_ANCHOR.equalsIgnoreCase(coordinateSpace)) {
            String anchorId = resolveAttachmentAnchorId(object);
            if (!isBlank(anchorId)) {
                sb.append(" anchor=").append(anchorId.trim());
            }
        }
        List<String> axisParts = new ArrayList<>();
        String xSummary = formatAxisSummary("x", placement.getX());
        String ySummary = formatAxisSummary("y", placement.getY());
        if (!xSummary.isBlank()) {
            axisParts.add(xSummary);
        }
        if (!ySummary.isBlank()) {
            axisParts.add(ySummary);
        }
        if (!axisParts.isEmpty()) {
            sb.append(" ").append(String.join(", ", axisParts));
        }
        return sb.toString();
    }

    private String formatAxisSummary(String axisName, StoryboardPlacementAxis axis) {
        if (axis == null || !axis.hasData()) {
            return "";
        }
        if (axis.getValue() != null) {
            return axisName + "=" + round(axis.getValue());
        }
        Double min = axis.getMin();
        Double max = axis.getMax();
        if (min != null && max != null) {
            double roundedMin = round(min);
            double roundedMax = round(max);
            if (Double.compare(roundedMin, roundedMax) == 0) {
                return axisName + "=" + roundedMin;
            }
            return axisName + "=" + roundedMin + ".." + roundedMax;
        }
        if (min != null) {
            return axisName + ">=" + round(min);
        }
        if (max != null) {
            return axisName + "<=" + round(max);
        }
        return "";
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

                        String blockingIssue = classifyLayoutOverlap(sceneLabel, left, right, elements);
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
        return !left.objectId.equals(right.objectId);
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
                                         StoryboardLayoutElement right,
                                         List<StoryboardLayoutElement> elements) {
        String baseIssue;
        boolean leftText = isTextual(left.object);
        boolean rightText = isTextual(right.object);
        if (leftText && rightText) {
            baseIssue = sceneLabel + ": text objects '" + left.objectId
                    + "' and '" + right.objectId + "' overlap";
        } else if (leftText ^ rightText) {
            StoryboardLayoutElement textElement = leftText ? left : right;
            StoryboardLayoutElement otherElement = leftText ? right : left;
            if (isAttachedLabelPair(textElement.object, otherElement.object)) {
                return null;
            }
            baseIssue = sceneLabel + ": text object '" + textElement.objectId
                    + "' overlaps object '" + otherElement.objectId + "'";
        } else {
            baseIssue = sceneLabel + ": objects '" + left.objectId
                    + "' and '" + right.objectId + "' overlap";
        }
        return formatOverlapIssue(baseIssue, left, right, elements);
    }

    private String formatOverlapIssue(String baseIssue,
                                      StoryboardLayoutElement left,
                                      StoryboardLayoutElement right,
                                      List<StoryboardLayoutElement> elements) {
        StringBuilder sb = new StringBuilder(baseIssue);
        appendDependencyContext(sb, left, elements);
        appendDependencyContext(sb, right, elements);
        return sb.toString();
    }

    private void appendDependencyContext(StringBuilder sb,
                                         StoryboardLayoutElement element,
                                         List<StoryboardLayoutElement> elements) {
        if (sb == null || element == null) {
            return;
        }
        String dependencyContext = formatDependencyContext(element.objectId, element.object, elements);
        if (!dependencyContext.isBlank()) {
            sb.append("\n").append(dependencyContext);
        }
    }

    private boolean isTextual(StoryboardObject object) {
        if (object == null) {
            return false;
        }
        if (isTextRenderKind(normalizeForSemanticCheck(object.getKind()))) {
            return true;
        }
        if (object.getStyle() == null) {
            return false;
        }
        Narrative.StoryboardStyle style = object.getStyle();
        return !isBlank(style.getColor()) || style.getFontSize() != null || !isBlank(style.getFontFamily());
    }

    private boolean isAttachedLabelPair(StoryboardObject textObject,
                                        StoryboardObject otherObject) {
        if (textObject == null || otherObject == null) {
            return false;
        }
        String otherId = StoryboardPatchResolver.objectId(otherObject);
        String textId = StoryboardPatchResolver.objectId(textObject);
        if (textId == null || otherId == null || textObject.getConstraints() == null) {
            return false;
        }
        for (StoryboardConstraint constraint : textObject.getConstraints()) {
            if (!StoryboardConstraintCatalog.isAttachmentRelation(constraint.getRelation())) {
                continue;
            }
            Map<String, Object> refs = constraint.getRefs();
            if (refs == null) {
                continue;
            }
            String labelId = resolveRefId(refs.get("label"));
            String objectId = resolveRefId(refs.get("object"));
            String attachedId = resolveRefId(refs.get("attached"));
            String anchorId = resolveRefId(refs.get("anchor"));
            boolean ownsText = textId.equals(labelId) || textId.equals(objectId) || textId.equals(attachedId);
            if (ownsText && otherId.equals(anchorId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
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

    private void appendValidationTraceEntry(Storyboard storyboard,
                                            String phase,
                                            int cleanupAttempt,
                                            boolean fixAttempted,
                                            boolean fixApplied,
                                            List<String> issues,
                                            int toolCalls,
                                            double executionTimeSeconds,
                                            String message) {
        if (storyboardValidationReport == null) {
            return;
        }
        List<String> resolvedIssues = issues != null ? new ArrayList<>(issues) : new ArrayList<>();
        StoryboardValidationTraceEntry entry = new StoryboardValidationTraceEntry();
        entry.setSequence(storyboardValidationReport.getEntries().size() + 1);
        entry.setPhase(phase);
        entry.setCleanupAttempt(cleanupAttempt);
        entry.setPassed(resolvedIssues.isEmpty());
        entry.setSceneCount(countScenes(storyboard));
        entry.setIssueCount(resolvedIssues.size());
        entry.setIssues(resolvedIssues);
        entry.setFixAttempted(fixAttempted);
        entry.setFixApplied(fixApplied);
        entry.setToolCalls(toolCalls);
        entry.setExecutionTimeSeconds(executionTimeSeconds);
        entry.setMessage(message);
        storyboardValidationReport.addEntry(entry);
    }

    private int countScenes(Storyboard storyboard) {
        return storyboard != null && storyboard.getScenes() != null
                ? storyboard.getScenes().size()
                : 0;
    }

    // ---- Placement enrichment for layout validation ----

    /**
     * Asks the LLM to compute placements for objects that lack coordinates,
     * so that layout validation (offscreen / overlap checks) can cover derived
     * objects whose positions are determined by structured constraints rather
     * than explicit placement fields.
     *
     * <p>The returned storyboard is <strong>only</strong> used for
     * {@code validateSceneLayout}; the original storyboard is retained for
     * all other checks and for the final output.
     */
    private Storyboard resolvePlacementEnrichedStoryboard(Storyboard storyboard) {
        if (aiClient == null) {
            log.debug("No AI client available for placement enrichment; skipping");
            return null;
        }
        try {
            if (!hasVisibleObjectsNeedingPlacement(storyboard)) {
                return null;
            }

            String storyboardJson = JsonUtils.mapper().writeValueAsString(storyboard);

            NodeConversationContext conversationContext = new NodeConversationContext(Integer.MAX_VALUE);
            conversationContext.setSystemMessage(NarrativePrompts.PLACEMENT_ENRICHMENT_SYSTEM_PROMPT);

            String userPrompt = NarrativePrompts.buildPlacementEnrichmentUserPrompt(storyboardJson);

            JsonNode enrichedData = AiRequestUtils.requestJsonObjectAsync(
                            aiClient,
                            log,
                            "placement-enrichment",
                            conversationContext.getPinnedMessages(),
                            conversationContext.getMaxInputTokens(),
                            SystemPrompts.buildCurrentRequestSection(userPrompt),
                            ToolSchemas.storyboard(outputTarget),
                            () -> toolCalls++)
                    .join();

            if (enrichedData == null) {
                log.debug("Placement enrichment returned no data");
                return null;
            }

            JsonNode storyboardNode = enrichedData.has("storyboard")
                    ? enrichedData.get("storyboard") : enrichedData;
            if (storyboardNode == null || !storyboardNode.has("scenes")) {
                log.debug("Placement enrichment returned invalid storyboard structure");
                return null;
            }

            Storyboard enrichedStoryboard = JsonUtils.mapper().treeToValue(storyboardNode, Storyboard.class);
            enrichedStoryboard = StoryboardNormalizer.normalize(enrichedStoryboard);

            // Verify existing placements are preserved: compare object counts with placements
            int originalWithPlacement = countObjectsWithPlacement(storyboard);
            int enrichedWithPlacement = countObjectsWithPlacement(enrichedStoryboard);
            log.info("Placement enrichment: objects with placement {} -> {}",
                    originalWithPlacement, enrichedWithPlacement);

            return enrichedStoryboard;
        } catch (CompletionException e) {
            log.warn("Placement enrichment call failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Placement enrichment failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasVisibleObjectsNeedingPlacement(Storyboard storyboard) {
        Storyboard mergedStoryboard = StoryboardPatchResolver.buildMergedStoryboard(storyboard);
        if (mergedStoryboard == null || mergedStoryboard.getScenes() == null) {
            return false;
        }
        for (StoryboardScene scene : mergedStoryboard.getScenes()) {
            if (scene == null) {
                continue;
            }
            if (hasVisibleObjectsNeedingPlacement(scene.getPersistentObjects())
                    || hasVisibleObjectsNeedingPlacement(scene.getEnteringObjects())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasVisibleObjectsNeedingPlacement(List<StoryboardObject> objects) {
        if (objects == null) {
            return false;
        }
        for (StoryboardObject object : objects) {
            if (object != null && (object.getPlacement() == null || !object.getPlacement().hasData())) {
                return true;
            }
        }
        return false;
    }

    private int countObjectsWithPlacement(Storyboard storyboard) {
        int count = 0;
        if (storyboard == null) {
            return count;
        }
        if (storyboard.getObjectRegistry() != null) {
            for (StoryboardObject object : storyboard.getObjectRegistry()) {
                if (object != null && object.getPlacement() != null && object.getPlacement().hasData()) {
                    count++;
                }
            }
        }
        if (storyboard.getScenes() != null) {
            for (StoryboardScene scene : storyboard.getScenes()) {
                count += countObjectsWithPlacementInList(scene != null ? scene.getEnteringObjects() : null);
                count += countObjectsWithPlacementInList(scene != null ? scene.getPersistentObjects() : null);
            }
        }
        return count;
    }

    private int countObjectsWithPlacementInList(List<StoryboardObject> objects) {
        int count = 0;
        if (objects == null) {
            return count;
        }
        for (StoryboardObject object : objects) {
            if (object != null && object.getPlacement() != null && object.getPlacement().hasData()) {
                count++;
            }
        }
        return count;
    }

    // ---- LLM fix pass ----

    private Narrative attemptLlmFix(Narrative narrative, List<String> issues) {
        try {
            if (aiClient == null) {
                log.warn("No AI client available for storyboard cleanup");
                return null;
            }
            String storyboardJson = JsonUtils.mapper().writeValueAsString(narrative.getStoryboard());
            NodeConversationContext conversationContext = fixConversationContext(narrative);
            String userPrompt = NarrativePrompts.buildCleanupUserPrompt(storyboardJson, issues);

            JsonNode fixedData = AiRequestUtils.requestJsonObjectAsync(
                            aiClient,
                            log,
                            "storyboard-fix",
                            conversationContext,
                            SystemPrompts.buildCurrentRequestSection(userPrompt),
                            ToolSchemas.storyboard(outputTarget),
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
            preserveStepRefs(narrative.getStoryboard(), fixedStoryboard);

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

    private NodeConversationContext fixConversationContext(Narrative narrative) {
        if (fixConversationContext == null) {
            int maxInputTokens = TargetDescriptionBuilder.resolveMaxInputTokens(workflowConfig);
            fixConversationContext = new NodeConversationContext(maxInputTokens, 2);
            String systemPrompt = NarrativePrompts.buildRulesPrompt(outputTarget)
                    + "\n\n" + NarrativePrompts.buildRepairRules(outputTarget);
            fixConversationContext.setSystemMessage(systemPrompt);
            fixConversationContext.setFixedContextMessage(NarrativePrompts.buildFixedContextPrompt(
                    narrative.getTargetConcept(),
                    narrative.getTargetDescription(),
                    outputTarget,
                    buildDagChainSummary(narrative.getStoryboard())));
        }
        return fixConversationContext;
    }

    private void preserveStepRefs(Storyboard source, Storyboard target) {
        if (source == null || target == null
                || source.getScenes() == null || target.getScenes() == null) {
            return;
        }
        int count = Math.min(source.getScenes().size(), target.getScenes().size());
        for (int i = 0; i < count; i++) {
            StoryboardScene sourceScene = source.getScenes().get(i);
            StoryboardScene targetScene = target.getScenes().get(i);
            if (sourceScene != null && targetScene != null) {
                targetScene.setStepRefs(sourceScene.getStepRefs() != null
                        ? new ArrayList<>(sourceScene.getStepRefs())
                        : new ArrayList<>());
            }
        }
    }

    private String buildDagChainSummary(Storyboard storyboard) {
        String dagSummary = TargetDescriptionBuilder.buildSolutionChain(knowledgeGraph, null);
        if (dagSummary != null && !dagSummary.isBlank()) {
            return dagSummary;
        }
        if (storyboard == null || storyboard.getScenes() == null || storyboard.getScenes().isEmpty()) {
            return "DAG summary chain: unavailable.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("DAG summary chain:\n");
        int step = 1;
        for (StoryboardScene scene : storyboard.getScenes()) {
            if (scene == null) {
                continue;
            }
            sb.append(step).append(". ");
            String sceneId = scene.getSceneId();
            if (sceneId != null && !sceneId.isBlank()) {
                sb.append(sceneId.trim()).append(" - ");
            }
            String title = scene.getTitle();
            if (title != null && !title.isBlank()) {
                sb.append(title.trim());
            } else {
                sb.append("Untitled scene");
            }
            if (scene.getGoal() != null && !scene.getGoal().isBlank()) {
                sb.append(" | goal: ").append(scene.getGoal().trim());
            }
            if (scene.getLayoutGoal() != null && !scene.getLayoutGoal().isBlank()) {
                sb.append(" | layout: ").append(scene.getLayoutGoal().trim());
            }
            sb.append("\n");
            step++;
        }
        return sb.toString().trim();
    }

    private String buildStoryboardChainSummary(Storyboard storyboard) {
        if (storyboard == null || storyboard.getScenes() == null || storyboard.getScenes().isEmpty()) {
            return "DAG summary chain: no scenes available.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("DAG summary chain:\n");
        int step = 1;
        for (StoryboardScene scene : storyboard.getScenes()) {
            if (scene == null) {
                continue;
            }
            sb.append(step).append(". ");
            String sceneId = scene.getSceneId();
            if (sceneId != null && !sceneId.isBlank()) {
                sb.append(sceneId.trim()).append(" - ");
            }
            String title = scene.getTitle();
            if (title != null && !title.isBlank()) {
                sb.append(title.trim());
            } else {
                sb.append("Untitled scene");
            }
            if (scene.getGoal() != null && !scene.getGoal().isBlank()) {
                sb.append(" | goal: ").append(scene.getGoal().trim());
            }
            if (scene.getLayoutGoal() != null && !scene.getLayoutGoal().isBlank()) {
                sb.append(" | layout: ").append(scene.getLayoutGoal().trim());
            }
            sb.append("\n");
            step++;
        }
        return sb.toString().trim();
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

    private static final class ColorReference {
        private final String propertyPath;
        private final String value;
        private final boolean textLayer;
        private final boolean explicitBackground;

        private ColorReference(String propertyPath,
                               String value,
                               boolean textLayer,
                               boolean explicitBackground) {
            this.propertyPath = propertyPath == null ? "" : propertyPath;
            this.value = value == null ? "" : value.trim();
            this.textLayer = textLayer;
            this.explicitBackground = explicitBackground;
        }

        private boolean isTextLayer() {
            return textLayer;
        }

        private boolean isExplicitBackground() {
            return explicitBackground;
        }

        private boolean isTextBackgroundFill() {
            return "fill_color".equals(propertyPath) && explicitBackground;
        }
    }

}
