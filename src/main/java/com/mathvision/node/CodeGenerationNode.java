package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeResult;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardPlacement;
import com.mathvision.model.Narrative.StoryboardPlacementAxis;
import com.mathvision.model.Narrative.StoryboardStyle;
import com.mathvision.model.SceneCodeEntry;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.node.support.NodeSupport;
import com.mathvision.prompt.CodeGenerationPrompts;
import com.mathvision.prompt.NarrativePrompts;
import com.mathvision.prompt.StoryboardJsonBuilder;
import com.mathvision.prompt.SystemPrompts;
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
import com.mathvision.util.StoryboardConstraintCatalog;
import com.mathvision.util.StoryboardConstraintUtils;
import com.mathvision.util.StoryboardPatchResolver;
import com.mathvision.util.TargetDescriptionBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Stage 2: Code Generation - generates backend-specific code
 * from the narrative storyboard.
 */
public class CodeGenerationNode extends PocketFlow.Node<CodeGenerationNode.CodeGenerationInput, CodeResult, String> {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationNode.class);
    static final String MANIM_SCENE_METHODS_MARKER = "# __SCENE_METHODS__";

    private AiClient aiClient;
    private WorkflowConfig workflowConfig;
    private NodeConversationContext conversationContext;
    private int toolCalls = 0;

    public CodeGenerationNode() {
        this(2);
    }

    public CodeGenerationNode(int maxRetries) {
        super(Math.max(maxRetries, 1), 2000);
    }

    public static class CodeGenerationInput {
        private final Narrative narrative;
        private final CodeResult existingCodeResult;

        public CodeGenerationInput(Narrative narrative,
                                   CodeResult existingCodeResult) {
            this.narrative = narrative;
            this.existingCodeResult = existingCodeResult;
        }

        public Narrative narrative() { return narrative; }
        public CodeResult existingCodeResult() { return existingCodeResult; }
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

        return new CodeGenerationInput(
                (Narrative) ctx.get(WorkflowKeys.NARRATIVE),
                (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT)
        );
    }

    @Override
    public CodeResult exec(CodeGenerationInput input) {
        Instant start = Instant.now();
        log.info("=== Stage 2: Code Generation ===");
        toolCalls = 0;

        if (this.conversationContext == null) {
            int maxInputTokens = TargetDescriptionBuilder.resolveMaxInputTokens(workflowConfig);
            this.conversationContext = new NodeConversationContext(maxInputTokens, 3);
        }

        Narrative narrative = input.narrative();

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

        // Build object registry JSON for fixed context (shared across all scene generations)
        String objectRegistryJson = "";
        if (narrative != null && narrative.hasStoryboard()
                && narrative.getStoryboard().getObjectRegistry() != null
                && !narrative.getStoryboard().getObjectRegistry().isEmpty()) {
            try {
                objectRegistryJson = JsonUtils.mapper().writeValueAsString(
                        narrative.getStoryboard().getObjectRegistry());
            } catch (Exception e) {
                log.warn("Failed to serialize object registry: {}", e.getMessage());
            }
        }

        this.conversationContext.setSystemMessage(
                CodeGenerationPrompts.buildRulesPrompt(NodeSupport.resolveOutputTarget(workflowConfig)));
        this.conversationContext.setFixedContextMessage(
                CodeGenerationPrompts.buildFixedContextPrompt(
                        targetConcept, targetDescription,
                        NodeSupport.resolveOutputTarget(workflowConfig), objectRegistryJson));

        String generatedCode;
        String artifactName = defaultArtifactName();
        CodeResult sceneResult = null;
        if (narrative != null && narrative.hasStoryboard()
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
        result.setToolCalls(toolCalls);
        result.setExecutionTimeSeconds(TimeUtils.secondsSince(start));

        log.info("Code generated: {} lines, artifact={}", result.codeLineCount(), artifactName);
        return result;
    }

    private CompletableFuture<CodeDraft> requestCodeAsync(String userPrompt, String expectedArtifactName) {
        return AiRequestUtils.requestExtractedTextResultAsync(
                        aiClient,
                        log,
                        expectedArtifactName,
                        conversationContext,
                        userPrompt,
                        resolveToolSchema(),
                        () -> toolCalls++,
                        List.of(resolveGeneratedCodeFieldName()),
                        this::extractCodeFromText,
                        text -> text != null && !text.isBlank()
                )
                .thenApply(result -> toCodeDraft(
                        result != null ? result.getPayload() : null,
                        result != null ? result.getExtractedText() : null,
                        expectedArtifactName));
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
        Map<String, StoryboardObject> createdRuntimeObjects = new LinkedHashMap<>();
        Map<String, StoryboardObject> visibleRuntimeObjects = new LinkedHashMap<>();
        String skeletonPrompt = isGeoGebra
                ? CodeGenerationPrompts.geoGebraSkeletonUserPrompt(storyboardJson, sceneNames)
                : CodeGenerationPrompts.manimSkeletonUserPrompt(storyboardJson, sceneNames);
        AiRequestUtils.ExtractedTextResult skeletonResult = AiRequestUtils.requestExtractedTextResultAsync(
                aiClient, log, "skeleton", conversationContext,
                skeletonPrompt, ToolSchemas.CODE_SKELETON, () -> toolCalls++,
                List.of("headerCode"),
                this::extractCodeFromText,
                text -> text != null && !text.isBlank()
        ).join();

        String headerCode = skeletonResult != null ? skeletonResult.getExtractedText() : "";
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

            // Constraint summary: explicit per-object and scene-level hard invariants
            String constraintSummaryBlock = enrichedRegistry != null
                    ? toConstraintBlock(buildSceneConstraintSummary(scene, enrichedRegistry)) : "";
            String sceneRegistryBlock = enrichedRegistry != null
                    ? toConstraintBlock(buildSceneRegistryJsonBlock(scene, enrichedRegistry)) : "";
            String runtimeStateBlock = toConstraintBlock(buildRuntimeObjectStateBlock(
                    visibleRuntimeObjects, createdRuntimeObjects, scene, enrichedRegistry, isGeoGebra));
            String scenePrompt = (isGeoGebra
                    ? CodeGenerationPrompts.geoGebraSceneCodeUserPrompt(sceneJson, sceneName, i, scenes.size())
                    : CodeGenerationPrompts.manimSceneCodeUserPrompt(sceneJson, sceneName, i, scenes.size()))
                    + sceneRegistryBlock
                    + runtimeStateBlock
                    + constraintSummaryBlock;
            AiRequestUtils.ExtractedTextResult sceneResult = AiRequestUtils.requestExtractedTextResultAsync(
                    aiClient, log, sceneName, conversationContext,
                    scenePrompt, ToolSchemas.SCENE_CODE, () -> toolCalls++,
                    List.of("sceneCode"),
                    this::extractCodeFromText,
                    text -> text != null && !text.isBlank()
            ).join();

            // Apply this scene's patches after code generation (for next scene's context)
            updateRuntimeObjectState(createdRuntimeObjects, visibleRuntimeObjects, scene, enrichedRegistry);
            if (enrichedRegistry != null) {
                applyScenePatches(enrichedRegistry, scene);
            }

            String sceneCode = sceneResult != null ? sceneResult.getExtractedText() : "";
            JsonNode scenePayload = sceneResult != null ? sceneResult.getPayload() : null;
            if (scenePayload != null && !isGeoGebra && scenePayload.has("sceneMethodName")) {
                String returnedName = scenePayload.get("sceneMethodName").asText("");
                if (!returnedName.isBlank() && !returnedName.equals(sceneName)) {
                    log.debug("  Ignoring returned Manim scene method name '{}' for skeleton method '{}'",
                            returnedName, sceneName);
                }
            } else if (scenePayload != null && isGeoGebra && scenePayload.has("sceneMethodName")) {
                String returnedName = scenePayload.get("sceneMethodName").asText("");
                if (!returnedName.isBlank()) {
                    sceneName = returnedName;
                }
            }

            entries.add(new SceneCodeEntry(i, scene.getSceneId(), sceneName, sceneCode, false));
            log.debug("  Scene {} ({}) generated: {} lines", i + 1, sceneName, sceneCode.lines().count());
        }

        // 3. Assemble
        CodeResult result = new CodeResult();
        result.setHeaderCode(headerCode);
        result.setSceneEntries(entries);
        if (isGeoGebra) {
            result.rebuildGeneratedCode();
        } else {
            result.setGeneratedCode(assembleManimPerSceneCode(headerCode, entries));
        }

        log.info("  Per-scene assembly complete: {} total lines", result.codeLineCount());
        return result;
    }

    static String assembleManimPerSceneCode(String headerCode, List<SceneCodeEntry> entries) {
        String skeleton = ManimCodeUtils.extractCode(headerCode);
        String methods = buildManimSceneMethods(entries);
        if (skeleton == null || skeleton.isBlank()) {
            return methods;
        }

        String normalizedSkeleton = trimTrailingWhitespaceLines(skeleton);
        if (normalizedSkeleton.contains(MANIM_SCENE_METHODS_MARKER)) {
            return normalizedSkeleton.replace(MANIM_SCENE_METHODS_MARKER, methods.trim());
        }

        if (methods.isBlank()) {
            return normalizedSkeleton;
        }
        return normalizedSkeleton + "\n\n" + methods;
    }

    private static String buildManimSceneMethods(List<SceneCodeEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SceneCodeEntry entry : entries) {
            if (entry == null || entry.getSceneMethodName() == null || entry.getSceneMethodName().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            String methodName = sanitizeManimMethodName(entry.getSceneMethodName());
            String body = normalizeManimSceneMethodBody(methodName, entry.getSceneCode());
            sb.append("    def ").append(methodName).append("(self):\n")
                    .append(indentMethodBody(body));
        }
        return sb.toString();
    }

    private static String sanitizeManimMethodName(String methodName) {
        String normalized = methodName != null ? methodName.trim() : "";
        if (normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return normalized;
        }
        String sanitized = normalized.replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("^[^A-Za-z_]+", "")
                .replaceAll("_+", "_");
        return sanitized.isBlank() ? "scene_method" : sanitized;
    }

    private static String normalizeManimSceneMethodBody(String methodName, String sceneCode) {
        String code = ManimCodeUtils.extractCode(sceneCode);
        if (code == null || code.isBlank()) {
            return "pass";
        }
        String normalized = code.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").trim();
        String[] lines = normalized.split("\n", -1);
        int defLine = findFirstMethodDefinitionLine(lines);
        if (defLine >= 0) {
            StringBuilder body = new StringBuilder();
            for (int i = defLine + 1; i < lines.length; i++) {
                body.append(lines[i]);
                if (i < lines.length - 1) {
                    body.append('\n');
                }
            }
            normalized = dedentBlock(body.toString()).trim();
        } else {
            normalized = dedentBlock(normalized).trim();
        }
        return normalized.isBlank() ? "pass" : normalized;
    }

    private static int findFirstMethodDefinitionLine(String[] lines) {
        if (lines == null) {
            return -1;
        }
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i] != null ? lines[i].trim() : "";
            if (trimmed.matches("def\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\s*self\\s*\\)\\s*:")) {
                return i;
            }
        }
        return -1;
    }

    private static String dedentBlock(String block) {
        if (block == null || block.isBlank()) {
            return "";
        }
        String normalized = block.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            int indent = countLeadingSpaces(line);
            minIndent = Math.min(minIndent, indent);
        }
        if (minIndent == Integer.MAX_VALUE || minIndent == 0) {
            return normalized;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line != null && line.length() >= minIndent) {
                sb.append(line.substring(minIndent));
            } else if (line != null) {
                sb.append(line.trim());
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String indentMethodBody(String body) {
        String normalized = body == null || body.isBlank() ? "pass" : body;
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append("        ");
            if (lines[i] != null) {
                sb.append(lines[i]);
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static int countLeadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static String trimTrailingWhitespaceLines(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s&&[^\\n]]+$", "").replaceAll("(\\R\\s*)+$", "");
    }

    @Override
    public String post(Map<String, Object> ctx, CodeGenerationInput input, CodeResult codeResult) {
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveCodeResult(outputDir, codeResult);
        }
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
            return SystemPrompts.buildCurrentRequestSection((basePrompt + registryBlock
                    + "\n\nFigure name: " + expectedSceneName
                    + "\nUse this as the primary GeoGebra figure name when naming the construction.").replaceFirst("^\\[CURRENT_REQUEST\\]\\n", ""));
        }

        return SystemPrompts.buildCurrentRequestSection((basePrompt + registryBlock
                + "\n\nScene class name: " + expectedSceneName
                + "\nUse this exact scene class name verbatim in the generated code.").replaceFirst("^\\[CURRENT_REQUEST\\]\\n", ""));
    }

    private CodeDraft toCodeDraft(JsonNode payload, String generatedCode, String expectedArtifactName) {
        String artifactName = expectedArtifactName;

        if (payload != null && payload.has("scene_name")) {
            artifactName = payload.get("scene_name").asText(expectedArtifactName);
        } else if (payload != null && payload.has("figure_name")) {
            artifactName = payload.get("figure_name").asText(expectedArtifactName);
        }

        return new CodeDraft(generatedCode, artifactName);
    }

    private static String toConstraintBlock(String constraintSummary) {
        return constraintSummary.isBlank() ? "" : "\n\n" + constraintSummary;
    }

    /**
     * Builds the runtime lifecycle context that is not visible from a single
     * scene JSON: which storyboard ids already have backend objects, and which
     * of those objects are currently on screen before this scene begins.
     */
    static String buildRuntimeObjectStateBlock(Map<String, StoryboardObject> visibleRuntimeObjects,
                                               Map<String, StoryboardObject> createdRuntimeObjects,
                                               StoryboardScene scene,
                                               Map<String, StoryboardObject> enrichedRegistry,
                                               boolean isGeoGebra) {
        String handleName = isGeoGebra ? "the existing GeoGebra object name" : "self.objects[\"id\"]";
        StringBuilder sb = new StringBuilder();
        sb.append("Runtime object state before this scene (authoritative for object reuse):\n");
        appendRuntimeObjectList(sb, "currently_visible", visibleRuntimeObjects);

        Map<String, StoryboardObject> invisibleCreated = new LinkedHashMap<>();
        if (createdRuntimeObjects != null) {
            for (Map.Entry<String, StoryboardObject> entry : createdRuntimeObjects.entrySet()) {
                if (entry.getKey() != null
                        && (visibleRuntimeObjects == null || !visibleRuntimeObjects.containsKey(entry.getKey()))) {
                    invisibleCreated.put(entry.getKey(), entry.getValue());
                }
            }
        }
        appendRuntimeObjectList(sb, "already_created_but_currently_invisible", invisibleCreated);

        sb.append("Reuse rule: every id in `currently_visible` or `already_created_but_currently_invisible` ")
                .append("has already been created. Reuse ")
                .append(handleName)
                .append(" for those ids; do not construct a replacement object with the same storyboard id.\n");
        if (!isGeoGebra) {
            sb.append("Exit rule: when an existing object exits, remove it from the scene visually but keep its ")
                    .append("`self.objects[id]` reference so a later scene can re-add or transform the same mobject.\n");
        }
        appendSceneLifecycleGuidance(sb, scene, visibleRuntimeObjects, createdRuntimeObjects, enrichedRegistry);
        return sb.toString().trim();
    }

    private static void appendRuntimeObjectList(StringBuilder sb,
                                                String label,
                                                Map<String, StoryboardObject> objects) {
        sb.append(label).append(":\n");
        if (objects == null || objects.isEmpty()) {
            sb.append("- none\n");
            return;
        }
        for (StoryboardObject obj : objects.values()) {
            appendRuntimeObjectSummary(sb, obj);
        }
    }

    private static void appendRuntimeObjectSummary(StringBuilder sb, StoryboardObject obj) {
        if (obj == null) {
            return;
        }
        sb.append("- id=").append(obj.getId())
                .append(", kind=").append(obj.getKind())
                .append(", content=").append(truncate(obj.getContent(), 80));
        if (obj.getPlacement() != null && obj.getPlacement().hasData()) {
            sb.append(", placement=").append(formatPlacementSummary(obj.getPlacement()));
        }
        if (obj.getStyle() != null && obj.getStyle().hasData()) {
            sb.append(", style=").append(formatStyleSummary(obj.getStyle()));
        }
        if (obj.getConstraints() != null && !obj.getConstraints().isEmpty()) {
            sb.append(", constraints=").append(truncate(JsonUtils.toJson(obj.getConstraints()), 500));
        }
        sb.append("\n");
    }

    private static void appendSceneLifecycleGuidance(StringBuilder sb,
                                                     StoryboardScene scene,
                                                     Map<String, StoryboardObject> visibleRuntimeObjects,
                                                     Map<String, StoryboardObject> createdRuntimeObjects,
                                                     Map<String, StoryboardObject> enrichedRegistry) {
        if (scene == null) {
            return;
        }
        sb.append("Scene lifecycle guidance:\n");
        appendLifecyclePatchGuidance(sb, "persistent_objects", scene.getPersistentObjects(),
                visibleRuntimeObjects, createdRuntimeObjects, enrichedRegistry);
        appendLifecyclePatchGuidance(sb, "entering_objects", scene.getEnteringObjects(),
                visibleRuntimeObjects, createdRuntimeObjects, enrichedRegistry);
        appendLifecyclePatchGuidance(sb, "exiting_objects", scene.getExitingObjects(),
                visibleRuntimeObjects, createdRuntimeObjects, enrichedRegistry);
    }

    private static void appendLifecyclePatchGuidance(StringBuilder sb,
                                                     String fieldName,
                                                     List<StoryboardObject> patches,
                                                     Map<String, StoryboardObject> visibleRuntimeObjects,
                                                     Map<String, StoryboardObject> createdRuntimeObjects,
                                                     Map<String, StoryboardObject> enrichedRegistry) {
        if (patches == null || patches.isEmpty()) {
            sb.append("- ").append(fieldName).append(": none\n");
            return;
        }
        List<String> parts = new ArrayList<>();
        for (StoryboardObject patch : patches) {
            String id = StoryboardPatchResolver.objectId(patch);
            if (id == null) {
                continue;
            }
            boolean currentlyVisible = visibleRuntimeObjects != null && visibleRuntimeObjects.containsKey(id);
            boolean alreadyCreated = currentlyVisible
                    || (createdRuntimeObjects != null && createdRuntimeObjects.containsKey(id));
            boolean knownInRegistry = enrichedRegistry != null && enrichedRegistry.containsKey(id);
            String status;
            if (currentlyVisible) {
                status = "reuse visible object";
            } else if (alreadyCreated) {
                status = "re-add existing invisible object";
            } else if (knownInRegistry) {
                status = "first-time creation from storyboard id";
            } else {
                status = "unknown id; preserve storyboard id if possible";
            }
            parts.add(id + " (" + status + ")");
        }
        if (parts.isEmpty()) {
            sb.append("- ").append(fieldName).append(": none\n");
        } else {
            sb.append("- ").append(fieldName).append(": ")
                    .append(String.join(", ", parts)).append("\n");
        }
    }

    private static void updateRuntimeObjectState(Map<String, StoryboardObject> createdRuntimeObjects,
                                                 Map<String, StoryboardObject> visibleRuntimeObjects,
                                                 StoryboardScene scene,
                                                 Map<String, StoryboardObject> enrichedRegistry) {
        if (scene == null) {
            return;
        }
        mergeRuntimeObjects(createdRuntimeObjects, visibleRuntimeObjects,
                scene.getPersistentObjects(), enrichedRegistry, true);
        mergeRuntimeObjects(createdRuntimeObjects, visibleRuntimeObjects,
                scene.getEnteringObjects(), enrichedRegistry, true);
        mergeRuntimeObjects(createdRuntimeObjects, visibleRuntimeObjects,
                scene.getExitingObjects(), enrichedRegistry, false);
        removeRuntimeVisibleObjects(visibleRuntimeObjects, scene.getExitingObjects());
    }

    private static void mergeRuntimeObjects(Map<String, StoryboardObject> createdRuntimeObjects,
                                            Map<String, StoryboardObject> visibleRuntimeObjects,
                                            List<StoryboardObject> patches,
                                            Map<String, StoryboardObject> enrichedRegistry,
                                            boolean visibleAfterScene) {
        if (patches == null || createdRuntimeObjects == null || visibleRuntimeObjects == null) {
            return;
        }
        for (StoryboardObject patch : patches) {
            String id = StoryboardPatchResolver.objectId(patch);
            if (id == null) {
                continue;
            }
            StoryboardObject merged = StoryboardPatchResolver.copyObject(visibleRuntimeObjects.get(id));
            if (merged == null) {
                merged = StoryboardPatchResolver.copyObject(createdRuntimeObjects.get(id));
            }
            if (merged == null && enrichedRegistry != null) {
                merged = StoryboardPatchResolver.copyObject(enrichedRegistry.get(id));
            }
            if (merged == null) {
                merged = new StoryboardObject();
                merged.setId(id);
            }
            applyRuntimePatch(merged, patch);
            createdRuntimeObjects.put(id, StoryboardPatchResolver.copyObject(merged));
            if (visibleAfterScene) {
                visibleRuntimeObjects.put(id, StoryboardPatchResolver.copyObject(merged));
            }
        }
    }

    private static void removeRuntimeVisibleObjects(Map<String, StoryboardObject> visibleRuntimeObjects,
                                                    List<StoryboardObject> exitingObjects) {
        if (visibleRuntimeObjects == null || exitingObjects == null) {
            return;
        }
        for (StoryboardObject exitingObject : exitingObjects) {
            String id = StoryboardPatchResolver.objectId(exitingObject);
            if (id != null) {
                visibleRuntimeObjects.remove(id);
            }
        }
    }

    private static void applyRuntimePatch(StoryboardObject target, StoryboardObject patch) {
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

    /**
     * Builds a compact structured JSON block for the object_registry entries
     * referenced by a specific scene, so the LLM has exact semantic data
     * (kind, content, style, constraints, etc.)
     * alongside the human-readable text summary.
     */
    static String buildSceneRegistryJsonBlock(StoryboardScene scene,
                                               Map<String, StoryboardObject> enrichedRegistry) {
        if (enrichedRegistry == null || enrichedRegistry.isEmpty()) {
            return "";
        }
        // Collect all object IDs referenced in this scene
        Map<String, String> objectIdToKind = new LinkedHashMap<>();
        collectSceneObjectIds(scene, objectIdToKind, enrichedRegistry);

        if (objectIdToKind.isEmpty()) {
            return "";
        }

        // Also include objects that are dependencies of referenced objects
        // (transitive closure one level deep)
        Set<String> allIds = new LinkedHashSet<>(objectIdToKind.keySet());
        for (String id : new ArrayList<>(objectIdToKind.keySet())) {
            collectConstraintReferencedIds(id, enrichedRegistry, allIds);
        }

        // Build compact JSON array of the relevant objects
        StringBuilder sb = new StringBuilder();
        sb.append("Object registry (structured JSON for this scene's objects):\n```json\n[\n");
        boolean first = true;
        for (String id : allIds) {
            StoryboardObject obj = enrichedRegistry.get(id);
            if (obj == null) continue;
            if (!first) sb.append(",\n");
            first = false;
            sb.append("  ");
            try {
                String objJson = JsonUtils.mapper().writeValueAsString(obj);
                // Indent for readability
                sb.append(objJson);
            } catch (Exception e) {
                sb.append("{\"id\":\"").append(id).append("\"}");
            }
        }
        sb.append("\n]\n```");
        return sb.toString();
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
            StoryboardObject copy = StoryboardPatchResolver.copyObject(obj);
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
            if (so.getStyle() != null && so.getStyle().hasData()) {
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
            if (obj.getConstraints() != null && !obj.getConstraints().isEmpty()) {
                sb.append(", constraints=").append(truncate(JsonUtils.toJson(obj.getConstraints()), 500));
            }
            if (obj.getPlacement() != null && obj.getPlacement().hasData()) {
                sb.append(", placement=").append(formatPlacementSummary(obj.getPlacement()));
            }
            if (obj.getStyle() != null && obj.getStyle().hasData()) {
                sb.append(", style=").append(formatStyleSummary(obj.getStyle()));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Builds a clear constraint summary for all objects referenced in a scene.
     * Collects constraints from both scene-level and object-registry-level,
     * deduplicates them, and formats each as a human-readable line.
     */
    static String buildSceneConstraintSummary(StoryboardScene scene,
                                               Map<String, StoryboardObject> enrichedRegistry) {
        if (enrichedRegistry == null || enrichedRegistry.isEmpty()) {
            return "";
        }
        // Collect all object IDs referenced in this scene
        Map<String, String> objectIdToKind = new LinkedHashMap<>();
        collectSceneObjectIds(scene, objectIdToKind, enrichedRegistry);

        Set<String> allIds = new LinkedHashSet<>(objectIdToKind.keySet());
        for (String id : new ArrayList<>(objectIdToKind.keySet())) {
            collectConstraintReferencedIds(id, enrichedRegistry, allIds);
        }
        for (String id : allIds) {
            StoryboardObject refObj = enrichedRegistry.get(id);
            if (refObj != null) {
                objectIdToKind.putIfAbsent(id, refObj.getKind() != null ? refObj.getKind() : "?");
            }
        }

        // Gather constraints per object
        List<String> lines = new ArrayList<>();

        // 1. Object-registry constraints for each referenced object
        for (Map.Entry<String, String> entry : objectIdToKind.entrySet()) {
            String objId = entry.getKey();
            StoryboardObject obj = enrichedRegistry.get(objId);
            if (obj == null || obj.getConstraints() == null || obj.getConstraints().isEmpty()) {
                continue;
            }
            for (var c : obj.getConstraints()) {
                if (c == null) continue;
                String line = formatConstraintLine(objId, entry.getValue(), c);
                if (line != null && !line.isBlank()) {
                    lines.add(line);
                }
            }
        }

        // 2. Scene-level constraints (may override or supplement object-level)
        if (scene.getConstraints() != null) {
            for (var c : scene.getConstraints()) {
                if (c == null) continue;
                String targetId = extractConstraintTargetId(c);
                String kind = targetId != null ? objectIdToKind.getOrDefault(targetId, "?") : "?";
                String line = formatConstraintLine(targetId != null ? targetId : "scene", kind, c);
                if (line != null && !line.isBlank()) {
                    lines.add(line);
                }
            }
        }

        if (lines.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Constraint summary (HARD invariants for this scene):\n");
        for (String line : lines) {
            sb.append("- ").append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private static void collectSceneObjectIds(StoryboardScene scene,
                                               Map<String, String> objectIdToKind,
                                               Map<String, StoryboardObject> enrichedRegistry) {
        collectObjectIds(scene.getEnteringObjects(), objectIdToKind, enrichedRegistry);
        collectObjectIds(scene.getPersistentObjects(), objectIdToKind, enrichedRegistry);
        collectObjectIds(scene.getExitingObjects(), objectIdToKind, enrichedRegistry);
        // Also collect targets from actions
        if (scene.getActions() != null) {
            for (var action : scene.getActions()) {
                if (action.getTargets() != null) {
                    for (String targetId : action.getTargets()) {
                        StoryboardObject obj = enrichedRegistry.get(targetId);
                        if (obj != null) {
                            objectIdToKind.putIfAbsent(targetId, obj.getKind() != null ? obj.getKind() : "?");
                        }
                    }
                }
            }
        }
    }

    private static void collectConstraintReferencedIds(String id,
                                                       Map<String, StoryboardObject> enrichedRegistry,
                                                       Set<String> allIds) {
        StoryboardObject obj = enrichedRegistry.get(id);
        if (obj == null || obj.getConstraints() == null) {
            return;
        }
        for (var constraint : obj.getConstraints()) {
            for (String refId : StoryboardConstraintUtils.referencedObjectIds(constraint)) {
                if (enrichedRegistry.containsKey(refId) && allIds.add(refId)) {
                    collectConstraintReferencedIds(refId, enrichedRegistry, allIds);
                }
            }
        }
    }

    private static void collectObjectIds(List<StoryboardObject> objects,
                                          Map<String, String> objectIdToKind,
                                          Map<String, StoryboardObject> enrichedRegistry) {
        if (objects == null) return;
        for (StoryboardObject obj : objects) {
            if (obj == null || obj.getId() == null) continue;
            String kind = obj.getKind();
            if (kind == null || kind.isBlank()) {
                StoryboardObject regObj = enrichedRegistry.get(obj.getId());
                kind = regObj != null && regObj.getKind() != null ? regObj.getKind() : "?";
            }
            objectIdToKind.putIfAbsent(obj.getId(), kind);
        }
    }

    private static String extractConstraintTargetId(Narrative.StoryboardConstraint c) {
        for (String ownerId : StoryboardConstraintUtils.ownerIds(c)) {
            if (ownerId != null && !ownerId.isBlank()) {
                return ownerId;
            }
        }
        if (c.getRefs() == null) return null;
        for (String key : new String[]{"point", "object", "source", "label", "segment"}) {
            Object val = c.getRefs().get(key);
            if (val instanceof String && !((String) val).isBlank()) {
                return (String) val;
            }
        }
        return null;
    }

    private static String formatConstraintLine(String objId, String kind,
                                                Narrative.StoryboardConstraint c) {
        StringBuilder sb = new StringBuilder();
        sb.append(objId).append("(").append(kind).append("): ");
        sb.append(c.getDomain() != null ? c.getDomain() : "?").append("/");
        sb.append(c.getRelation() != null ? c.getRelation() : "?");
        if (StoryboardConstraintCatalog.isCoordinateDerivedRelation(c.getRelation())) {
            sb.append(" [coordinate-derived]");
        }
        if (StoryboardConstraintCatalog.isMotionSensitiveRelation(c.getRelation())) {
            sb.append(" [motion-sensitive]");
        }
        Set<String> ownerRoles = StoryboardConstraintCatalog.ownerRefRoles(c.getRelation());
        Set<String> dependencyRoles = StoryboardConstraintCatalog.dependencyRefRoles(c.getRelation());
        Set<String> ownerIds = StoryboardConstraintUtils.ownerIds(c);
        Set<String> dependencyIds = StoryboardConstraintUtils.dependencyIds(c);
        if (!ownerIds.isEmpty()) {
            sb.append(" owners=").append(ownerIds);
        }
        if (!dependencyIds.isEmpty()) {
            sb.append(" dependencies=").append(dependencyIds);
        }
        String strength = c.getStrength();
        if (strength != null && !strength.isBlank()) {
            sb.append(" strength=").append(strength.trim());
        }
        if (!ownerRoles.isEmpty()) {
            sb.append(" owner_roles=").append(ownerRoles);
        }
        if (!dependencyRoles.isEmpty()) {
            sb.append(" dependency_roles=").append(dependencyRoles);
        }
        // Key refs
        if (c.getRefs() != null && !c.getRefs().isEmpty()) {
            sb.append(" refs=").append(c.getRefs());
        }
        // Parameters (often contains range)
        if (c.getParameters() != null && !c.getParameters().isEmpty()) {
            sb.append(" params=").append(c.getParameters());
        }
        if (c.getReason() != null && !c.getReason().isBlank()) {
            sb.append(" — ").append(c.getReason());
        }
        return sb.toString();
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

    private static String formatStyleSummary(StoryboardStyle style) {
        List<String> parts = new ArrayList<>();
        appendStylePart(parts, "color", style.getColor());
        appendStylePart(parts, "fill_color", style.getFillColor());
        appendStylePart(parts, "stroke_color", style.getStrokeColor());
        appendStylePart(parts, "highlight_color", style.getHighlightColor());
        appendStylePart(parts, "font_family", style.getFontFamily());
        appendStylePart(parts, "font_weight", style.getFontWeight());
        appendStylePart(parts, "font_style", style.getFontStyle());
        appendStylePart(parts, "line_style", style.getLineStyle());
        appendStylePart(parts, "opacity", style.getOpacity());
        appendStylePart(parts, "fill_opacity", style.getFillOpacity());
        appendStylePart(parts, "stroke_opacity", style.getStrokeOpacity());
        appendStylePart(parts, "stroke_width", style.getStrokeWidth());
        appendStylePart(parts, "font_size", style.getFontSize());
        appendStylePart(parts, "padding", style.getPadding());
        appendStylePart(parts, "corner_radius", style.getCornerRadius());
        appendStylePart(parts, "z_index", style.getZIndex());
        appendStylePart(parts, "point_size", style.getPointSize());
        appendStylePart(parts, "radius", style.getRadius());
        appendStylePart(parts, "marker_size", style.getMarkerSize());
        appendStylePart(parts, "point_style", style.getPointStyle());
        appendStylePart(parts, "decoration", style.getDecoration());
        appendStylePart(parts, "label_visible", style.getLabelVisible());
        return "{" + String.join(", ", parts) + "}";
    }

    private static void appendStylePart(List<String> parts, String key, Object value) {
        if (value instanceof String && ((String) value).isBlank()) {
            return;
        }
        if (value != null) {
            parts.add(key + "=" + value);
        }
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

    private String resolveToolSchema() {
        return NodeSupport.isGeoGebraTarget(workflowConfig)
                ? ToolSchemas.GEOGEBRA_CODE
                : ToolSchemas.MANIM_CODE;
    }

    private String resolveGeneratedCodeFieldName() {
        return NodeSupport.isGeoGebraTarget(workflowConfig) ? "geogebraCode" : "manimCode";
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

}
