package com.mathvision;

import com.mathvision.config.ConfigLoader;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeFixTraceReport;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.WorkflowKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathVisionApplicationTest {

    @TempDir
    Path tempDir;

    @Test
    void workflowSummaryUsesTargetInputAndResolvedModeNames() throws Exception {
        WorkflowConfig config = ConfigLoader.load(null, null);
        config.setInputMode(WorkflowConfig.INPUT_MODE_AUTO);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.TARGET_INPUT, "Given A and B, find the shortest path.");
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
        ctx.put(WorkflowKeys.TARGET_INPUT, "Given a diagram, extract the problem.");
        ctx.put(WorkflowKeys.PROBLEM_NORMALIZATION_API_CALLS, 1);

        Map<String, Object> summary = buildSummary(ctx);

        assertEquals(1, summary.get("total_llm_calls"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> breakdown = (Map<String, Integer>) summary.get("llm_calls_breakdown");
        assertEquals(1, breakdown.get("problem_normalization"));
    }

    @Test
    void createsProblemNormalizationOnlyFlow() {
        assertNotNull(WorkflowFlow.createProblemNormalizationOnly());
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

    private Object loadProblemInput(Path path) throws Exception {
        Method method = MathVisionApplication.class.getDeclaredMethod("loadProblemInputFromFile", String.class);
        method.setAccessible(true);
        return method.invoke(null, path.toString());
    }

    private Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
