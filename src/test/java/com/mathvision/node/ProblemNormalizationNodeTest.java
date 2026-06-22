package com.mathvision.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.model.ProblemBundle;
import com.mathvision.model.ProblemSource;
import com.mathvision.model.SourceAsset;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.mathvision.support.AiClientTestSupport;
import com.mathvision.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemNormalizationNodeTest {

    @TempDir
    Path tempDir;

    @Test
    void multimodalNormalizationExtractsToolCallPayload() throws Exception {
        Path image = tempDir.resolve("diagram.jpg");
        Files.write(image, new byte[] {1, 2, 3});

        SourceAsset asset = new SourceAsset();
        asset.setId("image_1");
        asset.setType("image");
        asset.setPath(image.toString());
        asset.setMimeType("image/jpeg");

        ProblemSource source = new ProblemSource();
        source.setSourceType("mixed");
        source.setRawText("# Geometry problem");
        source.setAssets(List.of(asset));

        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, new ToolPayloadAiClient());
        ctx.put(WorkflowKeys.CONFIG, config);

        ProblemNormalizationNode node = new ProblemNormalizationNode();
        node.prep(ctx);

        ProblemBundle bundle = node.exec(source);

        assertEquals("p1", bundle.getId());
        assertEquals(WorkflowConfig.INPUT_MODE_PROBLEM, bundle.getInputMode());
        assertEquals("2d", bundle.getSceneMode());
        assertEquals("Find the minimum value of AQ.", bundle.getStatement());
        assertNotNull(bundle.getDiagram());
        assertTrue(bundle.getDiagram().isPresent());
        assertTrue(bundle.getDiagram().hasDescriptionPayload());
    }

    @Test
    void textNormalizationReturnsReviewedAndRepairedBundle() {
        ProblemSource source = new ProblemSource();
        source.setSourceType("text");
        source.setRawText("Given triangle ABC with AB = 5, find angle C.");

        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_MANIM);

        QueuePayloadAiClient aiClient = new QueuePayloadAiClient(
                problemBundlePayload(
                        "p_wrong",
                        "Triangle ABC",
                        "Given triangle ABC with AB = 6, find angle C."),
                problemBundlePayload(
                        "p_fixed",
                        "Triangle ABC",
                        "Given triangle ABC with AB = 5, find angle C.")
        );

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);

        ProblemNormalizationNode node = new ProblemNormalizationNode();
        node.prep(ctx);

        ProblemBundle bundle = node.exec(source);
        node.post(ctx, source, bundle);

        assertEquals("p_fixed", bundle.getId());
        assertEquals("Given triangle ABC with AB = 5, find angle C.", bundle.getStatement());
        assertEquals(2, aiClient.toolCallCount);
        assertEquals(2, ctx.get(WorkflowKeys.PROBLEM_NORMALIZATION_API_CALLS));

        ProblemBundle storedBundle = (ProblemBundle) ctx.get(WorkflowKeys.PROBLEM_BUNDLE);
        assertEquals("p_fixed", storedBundle.getId());
        assertEquals("Given triangle ABC with AB = 5, find angle C.", storedBundle.getStatement());
    }

    @Test
    void normalizationFailsWhenInitialLlmResponseIsNotProblemBundle() {
        ProblemSource source = new ProblemSource();
        source.setSourceType("text");
        source.setRawText("Given triangle ABC with AB = 5, find angle C.");

        QueuePayloadAiClient aiClient = new QueuePayloadAiClient("{\"not_a_problem_bundle\":true}");

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, new WorkflowConfig());

        ProblemNormalizationNode node = new ProblemNormalizationNode();
        node.prep(ctx);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> node.exec(source));

        assertTrue(error.getMessage().contains("did not look like a ProblemBundle"));
        assertEquals(1, aiClient.toolCallCount);
    }

    @Test
    void normalizationFailsWhenReviewLlmResponseIsNotProblemBundle() {
        ProblemSource source = new ProblemSource();
        source.setSourceType("text");
        source.setRawText("Given triangle ABC with AB = 5, find angle C.");

        QueuePayloadAiClient aiClient = new QueuePayloadAiClient(
                problemBundlePayload(
                        "p_generated",
                        "Triangle ABC",
                        "Given triangle ABC with AB = 5, find angle C."),
                "{\"review_notes\":\"looks ok\"}"
        );

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, new WorkflowConfig());

        ProblemNormalizationNode node = new ProblemNormalizationNode();
        node.prep(ctx);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> node.exec(source));

        assertTrue(error.getMessage().contains("did not look like a ProblemBundle"));
        assertEquals(2, aiClient.toolCallCount);
    }

    private static final class ToolPayloadAiClient implements AiClient {
        @Override
        public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
            return CompletableFuture.completedFuture(AiClientTestSupport.rawResponse(rawToolResponse()));
        }

        private JsonNode rawToolResponse() {
            return ProblemNormalizationNodeTest.rawToolResponse(problemBundlePayload(
                    "p1",
                    "Geometry problem",
                    "Find the minimum value of AQ.",
                    true));
        }
    }

    private static final class QueuePayloadAiClient implements AiClient {
        private final Queue<String> payloads = new ArrayDeque<>();
        private int toolCallCount;

        private QueuePayloadAiClient(String... payloads) {
            this.payloads.addAll(List.of(payloads));
        }

        @Override
        public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
            toolCallCount++;
            String payload = payloads.remove();
            return CompletableFuture.completedFuture(
                    AiClientTestSupport.rawResponse(rawToolResponse(payload)));
        }
    }

    private static String problemBundlePayload(String id, String title, String statement) {
        return problemBundlePayload(id, title, statement, false);
    }

    private static String problemBundlePayload(String id, String title, String statement, boolean diagramPresent) {
        String diagramPayload = diagramPresent
                ? "\"present\":true,"
                        + "\"source_observed\":true,"
                        + "\"diagram_description\":{\"overall_shape\":\"Quarter-circle diagram\"},"
                        + "\"coordinate_model\":{},"
                        + "\"unknowns\":[],"
                        + "\"ambiguities\":[],"
                        + "\"normalization_notes\":[]"
                : "\"present\":false,"
                        + "\"source_observed\":false,"
                        + "\"diagram_description\":{},"
                        + "\"coordinate_model\":{},"
                        + "\"unknowns\":[],"
                        + "\"ambiguities\":[],"
                        + "\"normalization_notes\":[]";
        return "{"
                + "\"id\":" + JsonUtils.toJson(id) + ","
                + "\"title\":" + JsonUtils.toJson(title) + ","
                + "\"input_mode\":\"problem\","
                + "\"scene_mode\":\"2d\","
                + "\"statement\":" + JsonUtils.toJson(statement) + ","
                + "\"diagram\":{"
                + diagramPayload
                + "}"
                + "}";
    }

    private static JsonNode rawToolResponse(String arguments) {
        String response = "{"
                + "\"choices\":[{"
                + "\"message\":{"
                + "\"role\":\"assistant\","
                + "\"tool_calls\":[{"
                + "\"type\":\"function\","
                + "\"function\":{"
                + "\"name\":\"write_problem_bundle\","
                + "\"arguments\":" + JsonUtils.toJson(arguments)
                + "}"
                + "}]"
                + "}"
                + "}]"
                + "}";
        return JsonUtils.parseTree(response);
    }
}
