package com.mathvision;

import com.mathvision.config.ConfigLoader;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeFixTraceReport;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.Narrative;
import com.mathvision.model.ProblemBundle;
import com.mathvision.model.RenderResult;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.FileOutputService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathVisionApplicationTest {

    @TempDir
    Path tempDir;

    @Test
    void workflowSummaryUsesProblemBundleStatementAndResolvedModeNames() throws Exception {
        WorkflowConfig config = ConfigLoader.load(null, null);
        config.setInputMode(WorkflowConfig.INPUT_MODE_AUTO);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.PROBLEM_BUNDLE,
                problemBundle("Given A and B, find the shortest path.", WorkflowConfig.INPUT_MODE_PROBLEM));
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, problemGraph());
        ctx.put(WorkflowKeys.RESOLVED_INPUT_MODE, WorkflowConfig.INPUT_MODE_PROBLEM);

        Map<String, Object> summary = buildSummary(ctx);

        assertEquals("Given A and B, find the shortest path.", summary.get("target_input"));
        assertEquals(WorkflowConfig.INPUT_MODE_AUTO, summary.get("input_mode_configured"));
        assertEquals(WorkflowConfig.INPUT_MODE_PROBLEM, summary.get("input_mode_resolved"));
        assertFalse(summary.containsKey("concept"));
        assertFalse(summary.containsKey("input_mode"));
    }

    @Test
    void markdownProblemInputExtractsRelativeImageAssets() throws Exception {
        Path image = tempDir.resolve("diagram.jpg");
        Files.write(image, new byte[] {1, 2, 3});
        Path markdown = tempDir.resolve("problem.md");
        Files.writeString(markdown,
                "# Geometry problem\n\n![diagram](diagram.jpg)\n",
                StandardCharsets.UTF_8);

        Object problemInput = loadProblemInput(markdown);

        assertEquals("# Geometry problem", readField(problemInput, "rawText"));
        @SuppressWarnings("unchecked")
        List<Path> assets = (List<Path>) readField(problemInput, "markdownAssets");
        assertEquals(1, assets.size());
        assertEquals(image.toAbsolutePath().normalize(), assets.get(0));
    }

    @Test
    void existingMarkdownFileCanBePositionalProblemInput() throws Exception {
        Path markdown = tempDir.resolve("problem.md");
        Files.writeString(markdown, "# Problem", StandardCharsets.UTF_8);

        Method method = MathVisionApplication.class.getDeclaredMethod("isExistingMarkdownFile", String.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(null, markdown.toString()));
    }

    @Test
    void workflowSummaryIncludesProblemNormalizationCalls() throws Exception {
        WorkflowConfig config = ConfigLoader.load(null, null);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.PROBLEM_BUNDLE,
                problemBundle("Given a diagram, extract the problem.", WorkflowConfig.INPUT_MODE_PROBLEM));
        ctx.put(WorkflowKeys.PROBLEM_NORMALIZATION_API_CALLS, 1);

        Map<String, Object> summary = buildSummary(ctx);

        assertEquals(1, summary.get("total_llm_calls"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> breakdown = (Map<String, Integer>) summary.get("llm_calls_breakdown");
        assertEquals(1, breakdown.get("problem_normalization"));
    }

    @Test
    void workflowSummaryKeepsRenderSuccessTrueAfterAnySuccessfulRender() throws Exception {
        WorkflowConfig config = ConfigLoader.load(null, null);
        RenderResult finalRenderResult = new RenderResult();
        finalRenderResult.setSuccess(false);
        finalRenderResult.setAttempts(2);
        finalRenderResult.setLastError("later render failed");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.RENDER_RESULT, finalRenderResult);
        ctx.put(WorkflowKeys.RENDER_EVER_SUCCEEDED, true);

        Map<String, Object> summary = buildSummary(ctx);

        assertTrue(Boolean.TRUE.equals(summary.get("render_success")));
        assertFalse(Boolean.TRUE.equals(summary.get("render_final_success")));
        assertTrue(Boolean.TRUE.equals(summary.get("render_ever_succeeded")));
    }

    @Test
    void createsProblemNormalizationOnlyFlow() {
        assertNotNull(WorkflowFlow.createProblemNormalizationOnly());
    }

    @Test
    void artifactResumeFromValidatedStoryboardLoadsPriorContextAndValidatedStoryboard() throws Exception {
        WorkflowConfig config = ConfigLoader.load(null, null);
        FileOutputService.saveProblemBundle(tempDir,
                problemBundle("Given A and B, find the shortest path.", WorkflowConfig.INPUT_MODE_PROBLEM));
        FileOutputService.saveEnrichedGraph(tempDir, problemGraph());

        Narrative narrative = new Narrative("Draft target", storyboard("draft scene"));
        FileOutputService.saveNarrative(tempDir, narrative);
        Narrative.Storyboard validatedStoryboard = storyboard("validated scene");
        FileOutputService.saveValidatedStoryboard(tempDir, validatedStoryboard);

        Object resume = loadArtifactResume(tempDir.resolve(FileOutputService.VALIDATED_STORYBOARD_FILE));
        Map<String, Object> ctx = new LinkedHashMap<>();
        applyArtifactResume(resume, ctx, config);

        assertEquals(4, invokeInt(resume, "completedStage"));
        assertEquals("Given A and B, find the shortest path.", invokeString(resume, "rawInput"));
        assertEquals(WorkflowConfig.INPUT_MODE_PROBLEM,
                ((ProblemBundle) ctx.get(WorkflowKeys.PROBLEM_BUNDLE)).getInputMode());
        Narrative restoredNarrative = (Narrative) ctx.get(WorkflowKeys.NARRATIVE);
        assertEquals("validated scene", restoredNarrative.getStoryboard().getScenes().get(0).getTitle());
    }

    @Test
    void artifactResumeRejectsTerminalSceneEvaluationArtifact() throws Exception {
        Path sceneEvaluation = tempDir.resolve(FileOutputService.SCENE_EVALUATION_FILE);
        Files.writeString(sceneEvaluation, "{}", StandardCharsets.UTF_8);

        InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> loadArtifactResume(sceneEvaluation));

        assertTrue(thrown.getCause().getMessage().contains("no downstream workflow stage remains"));
    }

    @Test
    void createsResumeFlowAfterValidatedStoryboard() {
        WorkflowConfig config = ConfigLoader.load(null, null);
        assertNotNull(WorkflowFlow.createAfterStage(4, config));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSummary(Map<String, Object> ctx) throws Exception {
        Method method = MathVisionApplication.class.getDeclaredMethod(
                "buildSummary", Map.class, Duration.class, CodeFixTraceReport.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(
                null, ctx, Duration.ofSeconds(2), new CodeFixTraceReport());
    }

    private KnowledgeGraph problemGraph() {
        KnowledgeNode start = new KnowledgeNode("start", "Set up the problem", 0);
        start.setNodeType(KnowledgeNode.NODE_TYPE_PROBLEM);
        return new KnowledgeGraph(
                "start",
                "Given A and B, find the shortest path.",
                Map.of("start", start),
                Map.of("start", List.of()),
                List.of("start"));
    }

    private ProblemBundle problemBundle(String statement, String inputMode) {
        ProblemBundle bundle = new ProblemBundle();
        bundle.setId("test-problem");
        bundle.setTitle(statement);
        bundle.setInputMode(inputMode);
        bundle.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_MANIM);
        bundle.setSceneMode("2d");
        bundle.setStatement(statement);
        return bundle;
    }

    private Object loadProblemInput(Path path) throws Exception {
        Method method = MathVisionApplication.class.getDeclaredMethod("loadProblemInputFromFile", String.class);
        method.setAccessible(true);
        return method.invoke(null, path.toString());
    }

    private Object loadArtifactResume(Path path) throws Exception {
        Method method = MathVisionApplication.class.getDeclaredMethod("loadArtifactResume", String.class);
        method.setAccessible(true);
        return method.invoke(null, path.toString());
    }

    private void applyArtifactResume(Object resume, Map<String, Object> ctx, WorkflowConfig config) throws Exception {
        Method method = resume.getClass().getDeclaredMethod("applyTo", Map.class, WorkflowConfig.class);
        method.setAccessible(true);
        method.invoke(resume, ctx, config);
    }

    private int invokeInt(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (Integer) method.invoke(target);
    }

    private String invokeString(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (String) method.invoke(target);
    }

    private Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private Narrative.Storyboard storyboard(String sceneTitle) {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle(sceneTitle);
        scene.setGoal("Show the construction");
        storyboard.setScenes(List.of(scene));
        return storyboard;
    }
}
