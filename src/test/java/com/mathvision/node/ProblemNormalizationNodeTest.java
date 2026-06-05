package com.mathvision.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.AiMessage;
import com.mathvision.model.ProblemBundle;
import com.mathvision.model.ProblemSource;
import com.mathvision.model.SourceAsset;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    }

    private static final class ToolPayloadAiClient implements AiClient {
        @Override
        public CompletableFuture<String> chatAsync(List<NodeConversationContext.Message> snapshot) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(
                List<NodeConversationContext.Message> snapshot, String toolsJson) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<JsonNode> chatMultimodalWithToolsRawAsync(
                List<AiMessage> messages, String toolsJson) {
            return CompletableFuture.completedFuture(rawToolResponse());
        }

        @Override
        public String providerName() {
            return "fake";
        }

        private JsonNode rawToolResponse() {
            String arguments = "{"
                    + "\"id\":\"p1\","
                    + "\"title\":\"Geometry problem\","
                    + "\"input_mode\":\"problem\","
                    + "\"statement\":\"Find the minimum value of AQ.\","
                    + "\"diagram\":{"
                    + "\"present\":true,"
                    + "\"description\":\"Quarter-circle diagram\","
                    + "\"objects\":[],"
                    + "\"constraints\":[],"
                    + "\"construction_notes\":[]"
                    + "}"
                    + "}";
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
}
