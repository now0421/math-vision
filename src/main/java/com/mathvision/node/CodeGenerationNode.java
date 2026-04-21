package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeFixRequest;
import com.mathvision.model.CodeFixResult;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.CodeResult;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardPlacement;
import com.mathvision.model.Narrative.StoryboardPlacementAxis;
import com.mathvision.model.Narrative.StoryboardStyle;
import com.mathvision.model.SceneCodeEntry;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.node.support.FixRetryState;
import com.mathvision.node.support.NodeSupport;
import com.mathvision.prompt.CodeGenerationPrompts;
import com.mathvision.prompt.NarrativePrompts;
import com.mathvision.prompt.StoryboardJsonBuilder;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.service.FileOutputService;
import com.mathvision.util.AiRequestUtils;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.GeoGebraCodeUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.TimeUtils;
import com.mathvision.util.ManimCodeUtils;
import com.mathvision.util.NodeConversationContext;
import com.mathvision.util.StoryboardPatchResolver;
import com.mathvision.util.TargetDescriptionBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.concurrent.CompletionException;

/**
 * Stage 2: Code Generation - generates backend-specific code
 * from the narrative storyboard.
 */
public class CodeGenerationNode extends PocketFlow.Node<CodeGenerationNode.CodeGenerationInput, CodeResult, String> {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationNode.class);
    private static final int MAX_VALIDATION_FIX_ATTEMPTS = 1;

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private NodeConversationContext conversationContext;
    private int toolCalls = 0;

    public CodeGenerationNode() {
        super(2, 2000);
    }

    public static class CodeGenerationInput {
        private final Narrative narrative;
        private final CodeResult existingCodeResult;
        private final CodeFixResult previousFixResult;
        private final FixRetryState fixState;

        public CodeGenerationInput(Narrative narrative,
                                   CodeResult existingCodeResult,
                                   CodeFixResult previousFixResult,
                                   FixRetryState fixState) {
            this.narrative = narrative;
            this.existingCodeResult = existingCodeResult;
            this.previousFixResult = previousFixResult;
            this.fixState = fixState;
        }

        public Narrative narrative() { return narrative; }
        public CodeResult existingCodeResult() { return existingCodeResult; }
        public CodeFixResult previousFixResult() { return previousFixResult; }
        public FixRetryState fixState() { return fixState; }
    }

    private static final class CodeDraft {
        private final String generatedCode;
        private final String artifactName;

        private CodeDraft(String generatedCode, String artifactName) {
            this.generatedCode = generatedCode;
            this.artifactName = artifactName;
        }
    }

    @Override
    public CodeGenerationInput prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        this.workflowConfig = (WorkflowConfig) ctx.get(WorkflowKeys.CONFIG);

        FixRetryState fixState = (FixRetryState) ctx.get(WorkflowKeys.CODE_GENERATION_FIX_STATE);
        if (fixState == null) {
            fixState = new FixRetryState();
            ctx.put(WorkflowKeys.CODE_GENERATION_FIX_STATE, fixState);
        }

        CodeFixResult previousFixResult = NodeSupport.consumeFixResult(ctx, CodeFixSource.GENERATION_VALIDATION);
        if (previousFixResult != null) {
            fixState.addFixToolCalls(previousFixResult.getToolCalls());
        }

        return new CodeGenerationInput(
                (Narrative) ctx.get(WorkflowKeys.NARRATIVE),
                (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT),
                previousFixResult,
                fixState
        );
    }

    @Override
    public CodeResult exec(CodeGenerationInput input) {
        Instant start = Instant.now();
        log.info("=== Stage 2: Code Generation ===");
        toolCalls = 0;

        if (this.conversationContext == null) {
            int maxInputTokens = TargetDescriptionBuilder.resolveMaxInputTokens(workflowConfig);
            this.conversationContext = new NodeConversationContext(maxInputTokens);
        }

        Narrative narrative = input.narrative();
        FixRetryState fixState = input.fixState();
        fixState.setRequestFix(false);

        if (narrative == null && input.existingCodeResult() == null) {
            log.warn("Narrative is empty, cannot generate code");
            CodeResult emptyResult = new CodeResult("", "", "Empty narrative", "", "");
            emptyResult.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
            return emptyResult;
        }

        String targetConcept = narrative != null ? narrative.getTargetConcept()
                : input.existingCodeResult().getTargetConcept();
        String targetDescription = narrative != null ? narrative.getTargetDescription()
                : input.existingCodeResult().getTargetDescription();
        this.conversationContext.setSystemMessage(
                CodeGenerationPrompts.systemPrompt(
                        targetConcept, targetDescription, NodeSupport.resolveOutputTarget(workflowConfig)));

        String generatedCode;
        String artifactName = defaultArtifactName();
        CodeResult sceneResult = null;
        if (input.previousFixResult() != null) {
            log.info("  Re-validating code returned from shared CodeFixNode");
            generatedCode = extractRetriedCode(input);
        } else if (narrative != null && narrative.hasStoryboard()
                && narrative.getStoryboard().getScenes() != null
                && narrative.getStoryboard().getScenes().size() > 1) {
            // Per-scene generation for both Manim and GeoGebra
            try {
                sceneResult = generatePerScene(narrative, artifactName);
                generatedCode = sceneResult.getGeneratedCode();
            } catch (CompletionException e) {
                Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
                log.error("  Per-scene code generation failed: {}", cause.getMessage());
                generatedCode = "";
            }
        } else {
            String userPrompt = buildGenerationPrompt(narrative, artifactName);
            if (userPrompt.isBlank()) {
                log.warn("Narrative prompt is empty, cannot generate code");
                CodeResult emptyResult = new CodeResult(
                        "",
                        "",
                        "Empty narrative",
                        targetConcept,
                        targetDescription
                );
                emptyResult.setExecutionTimeSeconds(TimeUtils.secondsSince(start));
                return emptyResult;
            }

            try {
                CodeDraft draft = requestCodeAsync(userPrompt, artifactName).join();
                generatedCode = normalizeGeneratedCode(draft.generatedCode);
                artifactName = draft.artifactName;
            } catch (CompletionException e) {
                Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
                log.error("  Code generation failed: {}", cause.getMessage());
                generatedCode = "";
            }
        }

        generatedCode = normalizeGeneratedCode(generatedCode);
        if (NodeSupport.isGeoGebraTarget(workflowConfig)) {
            generatedCode = GeoGebraCodeUtils.enrichWithSceneButtons(
                    generatedCode,
                    narrative != null ? narrative.getStoryboard() : null);
        }
        List<String> violations = validateGeneratedCode(generatedCode);

        if (input.previousFixResult() != null) {
            if (!violations.isEmpty()) {
                if (fixState.getOriginalGeneratedCodeBeforeFix() != null
                        && violations.size() >= fixState.getOriginalIssueCount()) {
                    log.warn("  Shared code fix did not improve validation violations ({} -> {}),"
                                    + " keeping original generated code",
                            fixState.getOriginalIssueCount(), violations.size());
                    generatedCode = fixState.getOriginalGeneratedCodeBeforeFix();
                    violations = validateGeneratedCode(generatedCode);
                } else {
                    log.info("  Shared code fix reduced violations from {} to {}",
                            fixState.getOriginalIssueCount(), violations.size());
                }
            }
            fixState.clearPending();
        } else if (shouldRouteValidationFix()
                && !violations.isEmpty()
                && fixState.getAttempts() < MAX_VALIDATION_FIX_ATTEMPTS) {
            log.warn("  Code validation found {} violations, routing to shared CodeFixNode",
                    violations.size());
            fixState.recordFixRequest(generatedCode, violations);
        }

        CodeResult result = new CodeResult(
                generatedCode,
                artifactName,
                buildResultDescription(targetConcept),
                targetConcept,
                targetDescription
        );
        if (sceneResult != null) {
            result.setHeaderCode(sceneResult.getHeaderCode());
            result.setSceneEntries(sceneResult.getSceneEntries());
        }
        result.setOutputTarget(NodeSupport.resolveOutputTarget(workflowConfig));
        result.setArtifactFormat(resolveArtifactFormat());
        result.setToolCalls(toolCalls + fixState.totalToolCalls());
        result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));

        log.info("Code generated: {} lines, artifact={}", result.codeLineCount(), artifactName);
        return result;
    }

    private CompletableFuture<CodeDraft> requestCodeAsync(String userPrompt, String expectedArtifactName) {
        return AiRequestUtils.requestJsonObjectAsync(
                        aiClient,
                        log,
                        expectedArtifactName,
                        conversationContext,
                        userPrompt,
                        resolveToolSchema(),
                        () -> toolCalls++,
                        this::parseCodeTextResponse,
                        this::hasCodePayload
                )
                .thenApply(payload -> toCodeDraft(payload, expectedArtifactName));
    }

    private CodeResult generatePerScene(Narrative narrative, String artifactName) {
        boolean isGeoGebra = NodeSupport.isGeoGebraTarget(workflowConfig);
        Storyboard storyboard = narrative.getStoryboard();
        Storyboard mergedStoryboard = StoryboardPatchResolver.buildMergedStoryboard(storyboard);
        List<StoryboardScene> scenes = mergedStoryboard != null && mergedStoryboard.getScenes() != null
                ? mergedStoryboard.getScenes()
                : storyboard.getScenes();
        String storyboardJson = StoryboardJsonBuilder.buildForCodegen(storyboard);

        // Build scene identifiers
        List<String> sceneNames = new ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            StoryboardScene scene = scenes.get(i);
            if (isGeoGebra) {
                String title = scene.getTitle() != null ? scene.getTitle() : "scene_" + (i + 1);
                sceneNames.add("Scene " + (i + 1) + ": " + title);
            } else {
                sceneNames.add(ManimCodeUtils.buildSceneMethodName(scene.getSceneId(), scene.getTitle(), i));
            }
        }

        log.info("  Per-scene generation ({}): {} scenes, names={}", isGeoGebra ? "geogebra" : "manim",
                scenes.size(), sceneNames);

        // 1. Build base registry map (shared across skeleton + all scenes)
        Map<String, StoryboardObject> enrichedRegistry = buildBaseEnrichedRegistry(storyboard);
        String skeletonRegistryBlock = enrichedRegistry != null
                ? toRegistryBlock(formatRegistrySummary(enrichedRegistry, 0)) : "";
        String skeletonPrompt = (isGeoGebra
                ? CodeGenerationPrompts.geoGebraSkeletonUserPrompt(storyboardJson, sceneNames)
                : CodeGenerationPrompts.skeletonUserPrompt(storyboardJson, sceneNames))
                + skeletonRegistryBlock;
        JsonNode skeletonPayload = AiRequestUtils.requestJsonObjectAsync(
                aiClient, log, "skeleton", conversationContext,
                skeletonPrompt, ToolSchemas.CODE_SKELETON, () -> toolCalls++
        ).join();

        String headerCode = "";
        if (skeletonPayload != null && skeletonPayload.has("headerCode")) {
            headerCode = skeletonPayload.get("headerCode").asText("");
        }
        log.info("  Skeleton generated: {} lines", headerCode.lines().count());

        // 2. Generate each scene sequentially
        List<SceneCodeEntry> entries = new ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            StoryboardScene scene = scenes.get(i);
            String sceneName = sceneNames.get(i);
            String sceneJson;
            try {
                sceneJson = JsonUtils.mapper().writeValueAsString(scene);
            } catch (Exception e) {
                sceneJson = "{}";
            }

            // Registry reflects state BEFORE this scene's changes
            String sceneRegistryBlock = enrichedRegistry != null
                    ? toRegistryBlock(formatRegistrySummary(enrichedRegistry, i)) : "";
            String scenePrompt = (isGeoGebra
                    ? CodeGenerationPrompts.geoGebraSceneCodeUserPrompt(sceneJson, sceneName, i, scenes.size())
                    : CodeGenerationPrompts.sceneCodeUserPrompt(sceneJson, sceneName, i, scenes.size()))
                    + sceneRegistryBlock;
            JsonNode scenePayload = AiRequestUtils.requestJsonObjectAsync(
                    aiClient, log, sceneName, conversationContext,
                    scenePrompt, ToolSchemas.SCENE_CODE, () -> toolCalls++
            ).join();

            // Apply this scene's patches after code generation (for next scene's context)
            if (enrichedRegistry != null) {
                applyScenePatches(enrichedRegistry, scene);
            }

            String sceneCode = "";
            if (scenePayload != null) {
                if (scenePayload.has("sceneCode")) {
                    sceneCode = scenePayload.get("sceneCode").asText("");
                }
                if (!isGeoGebra && scenePayload.has("sceneMethodName")) {
                    String returnedName = scenePayload.get("sceneMethodName").asText("");
                    if (!returnedName.isBlank()) {
                        sceneName = returnedName;
                    }
                }
            }

            entries.add(new SceneCodeEntry(i, scene.getSceneId(), sceneName, sceneCode, false));
            log.debug("  Scene {} ({}) generated: {} lines", i + 1, sceneName, sceneCode.lines().count());
        }

        // 3. Assemble
        CodeResult result = new CodeResult();
        result.setHeaderCode(headerCode);
        result.setSceneEntries(entries);
        result.rebuildGeneratedCode();

        log.info("  Per-scene assembly complete: {} total lines", result.codeLineCount());
        return result;
    }

    @Override
    public String post(Map<String, Object> ctx, CodeGenerationInput input, CodeResult codeResult) {
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveCodeResult(outputDir, codeResult);
        }

        if (input.fixState().isRequestFix()) {
            input.fixState().addCarryoverToolCalls(toolCalls);
            ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildValidationFixRequest(input.narrative(), codeResult, input.fixState()));
            return WorkflowActions.FIX_CODE;
        }

        input.fixState().reset();
        return null;
    }

    private String buildGenerationPrompt(Narrative narrative, String expectedSceneName) {
        if (narrative == null || !narrative.hasStoryboard()) {
            return "";
        }

        String basePrompt = NarrativePrompts.storyboardCodegenPrompt(
                narrative.getStoryboard(),
                NodeSupport.resolveOutputTarget(workflowConfig));

        if (basePrompt == null || basePrompt.isBlank()) {
            return "";
        }

        String registrySummary = buildEnrichedRegistrySummary(narrative.getStoryboard(), Integer.MAX_VALUE);
        String registryBlock = registrySummary.isBlank() ? "" : "\n\n" + registrySummary;

        if (workflowConfig != null && workflowConfig.isGeoGebraTarget()) {
            return basePrompt + registryBlock
                    + "\n\nFigure name: " + expectedSceneName
                    + "\nUse this as the primary GeoGebra figure name when naming the construction.";
        }

        return basePrompt + registryBlock
                + "\n\nScene class name: " + expectedSceneName
                + "\nUse this exact scene class name verbatim in the generated code.";
    }

    private CodeFixRequest buildValidationFixRequest(Narrative narrative,
                                                     CodeResult codeResult,
                                                     FixRetryState fixState) {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.GENERATION_VALIDATION);
        request.setReturnAction(WorkflowActions.RETRY_CODE_GENERATION);
        request.setGeneratedCode(codeResult.getGeneratedCode());
        request.setErrorReason(String.join("\n", fixState.getCurrentIssues()));
        request.setTargetConcept(codeResult.getTargetConcept());
        request.setTargetDescription(codeResult.getTargetDescription());
        request.setSceneName(codeResult.getSceneName());
        request.setExpectedSceneName(resolveExpectedArtifactName(codeResult));
        request.setStoryboardJson(narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(narrative.getStoryboard())
                : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON);
        return request;
    }

    private String extractRetriedCode(CodeGenerationInput input) {
        if (input.existingCodeResult() != null
                && input.existingCodeResult().getGeneratedCode() != null
                && !input.existingCodeResult().getGeneratedCode().isBlank()) {
            return input.existingCodeResult().getGeneratedCode();
        }
        if (input.previousFixResult() != null) {
            return input.previousFixResult().getFixedGeneratedCode();
        }
        return "";
    }

    private JsonNode parseCodeTextResponse(String response) {
        String generatedCode = extractCodeFromText(response);
        if (generatedCode == null || generatedCode.isBlank()) {
            return null;
        }

        return JsonUtils.mapper().createObjectNode()
                .put(NodeSupport.isGeoGebraTarget(workflowConfig) ? "geogebraCode" : "manimCode", generatedCode);
    }

    private boolean hasCodePayload(JsonNode payload) {
        if (payload == null) return false;
        if (payload.has("manimCode") && !payload.get("manimCode").asText("").isBlank()) return true;
        if (payload.has("geogebraCode") && !payload.get("geogebraCode").asText("").isBlank()) return true;
        return false;
    }

    private CodeDraft toCodeDraft(JsonNode payload, String expectedArtifactName) {
        String generatedCode = null;
        String artifactName = expectedArtifactName;

        if (payload != null) {
            if (payload.has("manimCode")) {
                generatedCode = payload.get("manimCode").asText();
            } else if (payload.has("geogebraCode")) {
                generatedCode = payload.get("geogebraCode").asText();
            }
        }
        if (payload != null && payload.has("scene_name")) {
            artifactName = payload.get("scene_name").asText(expectedArtifactName);
        } else if (payload != null && payload.has("figure_name")) {
            artifactName = payload.get("figure_name").asText(expectedArtifactName);
        }

        return new CodeDraft(generatedCode, artifactName);
    }

    private static String toRegistryBlock(String registrySummary) {
        return registrySummary.isBlank() ? "" : "\n\n" + registrySummary;
    }

    /**
     * Builds a text summary of object_registry enriched with style/placement
     * accumulated from scene patches up to (but not beyond) {@code sceneLimit}.
     */
    static String buildEnrichedRegistrySummary(Storyboard storyboard, int sceneLimit) {
        Map<String, StoryboardObject> enriched = buildBaseEnrichedRegistry(storyboard);
        if (enriched == null) return "";

        if (storyboard.getScenes() != null && sceneLimit > 0) {
            List<StoryboardScene> scenes = storyboard.getScenes();
            int limit = Math.min(sceneLimit, scenes.size());
            for (int s = 0; s < limit; s++) {
                applyScenePatches(enriched, scenes.get(s));
            }
        }

        return formatRegistrySummary(enriched, sceneLimit);
    }

    /**
     * Deep-copies the storyboard's object_registry into a mutable map.
     * Returns null if the storyboard has no registry.
     */
    static Map<String, StoryboardObject> buildBaseEnrichedRegistry(Storyboard storyboard) {
        if (storyboard == null) return null;
        List<StoryboardObject> registry = storyboard.getObjectRegistry();
        if (registry == null || registry.isEmpty()) return null;

        Map<String, StoryboardObject> enriched = new LinkedHashMap<>();
        for (StoryboardObject obj : registry) {
            StoryboardObject copy = new StoryboardObject();
            copy.setId(obj.getId());
            copy.setKind(obj.getKind());
            copy.setContent(obj.getContent());
            copy.setBehavior(obj.getBehavior());
            copy.setStyle(obj.getStyle());
            copy.setPlacement(obj.getPlacement());
            enriched.put(obj.getId(), copy);
        }
        return enriched;
    }

    /**
     * Applies a single scene's entering/persistent object patches to the enriched map.
     */
    static void applyScenePatches(Map<String, StoryboardObject> enriched, StoryboardScene scene) {
        List<StoryboardObject> sceneObjs = new ArrayList<>();
        if (scene.getEnteringObjects() != null) sceneObjs.addAll(scene.getEnteringObjects());
        if (scene.getPersistentObjects() != null) sceneObjs.addAll(scene.getPersistentObjects());
        for (StoryboardObject so : sceneObjs) {
            if (so.getId() == null) continue;
            StoryboardObject target = enriched.get(so.getId());
            if (target == null) continue;
            if (so.getStyle() != null && !so.getStyle().isEmpty()) {
                target.setStyle(so.getStyle());
            }
            if (so.getPlacement() != null && so.getPlacement().hasData()) {
                target.setPlacement(so.getPlacement());
            }
        }
    }

    /**
     * Formats the enriched registry map as a text summary.
     */
    static String formatRegistrySummary(Map<String, StoryboardObject> enriched, int sceneLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("Object registry (").append(enriched.size()).append(" objects, state as of scene ").append(sceneLimit).append("):\n");
        for (StoryboardObject obj : enriched.values()) {
            sb.append("- id=").append(obj.getId())
                    .append(", kind=").append(obj.getKind())
                    .append(", content=").append(truncate(obj.getContent(), 60));
            if (obj.getBehavior() != null && !obj.getBehavior().isBlank()) {
                sb.append(", behavior=").append(obj.getBehavior());
            }
            if (obj.getPlacement() != null && obj.getPlacement().hasData()) {
                sb.append(", placement=").append(formatPlacementSummary(obj.getPlacement()));
            }
            if (obj.getStyle() != null && !obj.getStyle().isEmpty()) {
                sb.append(", style=").append(formatStyleSummary(obj.getStyle()));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static String formatPlacementSummary(StoryboardPlacement placement) {
        StringBuilder sb = new StringBuilder();
        if (placement.getCoordinateSpace() != null) {
            sb.append(placement.getCoordinateSpace());
        }
        appendAxisSummary(sb, "x", placement.getX());
        appendAxisSummary(sb, "y", placement.getY());
        appendAxisSummary(sb, "z", placement.getZ());
        return sb.toString();
    }

    private static void appendAxisSummary(StringBuilder sb, String name,
                                          StoryboardPlacementAxis axis) {
        if (axis == null) return;
        sb.append(" ").append(name).append("=");
        if (axis.getValue() != null) {
            sb.append(axis.getValue());
        }
        if (axis.getMin() != null || axis.getMax() != null) {
            sb.append("[");
            if (axis.getMin() != null) sb.append(axis.getMin());
            sb.append("..");
            if (axis.getMax() != null) sb.append(axis.getMax());
            sb.append("]");
        }
    }

    private static String formatStyleSummary(List<StoryboardStyle> styles) {
        return styles.stream()
                .map(s -> {
                    StringBuilder sb = new StringBuilder();
                    if (s.getRole() != null) sb.append(s.getRole());
                    if (s.getProperties() != null && !s.getProperties().isEmpty()) {
                        sb.append("{");
                        sb.append(s.getProperties().entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .collect(Collectors.joining(", ")));
                        sb.append("}");
                    }
                    return sb.toString();
                })
                .collect(Collectors.joining("; "));
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private String normalizeGeneratedCode(String generatedCode) {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? GeoGebraCodeUtils.extractCode(generatedCode)
                : ManimCodeUtils.enforceMainSceneName(generatedCode);
    }

    private List<String> validateGeneratedCode(String generatedCode) {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? GeoGebraCodeUtils.validateFull(generatedCode)
                : ManimCodeUtils.validateFull(generatedCode);
    }

    private boolean shouldRouteValidationFix() {
        return workflowConfig == null
                || workflowConfig.isManimTarget()
                || workflowConfig.isGeoGebraTarget();
    }

    private String resolveToolSchema() {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? ToolSchemas.GEOGEBRA_CODE
                : ToolSchemas.MANIM_CODE;
    }

    private String extractCodeFromText(String text) {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? GeoGebraCodeUtils.extractCode(text)
                : ManimCodeUtils.extractCode(text);
    }

    private String defaultArtifactName() {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? GeoGebraCodeUtils.EXPECTED_FIGURE_NAME
                : ManimCodeUtils.EXPECTED_SCENE_NAME;
    }

    private String resolveArtifactFormat() {
        return NodeSupport.isGeoGebraTarget(workflowConfig) ? "commands" : "python";
    }

    private String buildResultDescription(String targetConcept) {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? "GeoGebra construction for " + targetConcept
                : "Manim animation for " + targetConcept;
    }

    private String resolveExpectedArtifactName(CodeResult codeResult) {
        if (codeResult != null && codeResult.isGeoGebraTarget()) {
            if (codeResult.getSceneName() != null && !codeResult.getSceneName().isBlank()) {
                return codeResult.getSceneName();
            }
            return GeoGebraCodeUtils.EXPECTED_FIGURE_NAME;
        }
        return ManimCodeUtils.EXPECTED_SCENE_NAME;
    }

}
