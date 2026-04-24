package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeResult;
import com.mathvision.model.RenderResult;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.mathvision.service.ManimRendererService;
import com.mathvision.util.NodeConversationContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderNodeGeometryStateTest {

    @TempDir
    Path tempDir;

    @Test
    void clearsGeometryPathWhenLastAttemptProducesNoGeometry() {
        WorkflowConfig config = new WorkflowConfig();
        config.setRenderEnabled(true);
        config.setRenderMaxRetries(1);

        CodeResult codeResult = new CodeResult(
                String.join("\n",
                        "```python",
                        "from manim import *",
                        "",
                        "class DemoScene(Scene):",
                        "    def construct(self):",
                        "        pass"),
                "DemoScene",
                "demo",
                "Demo concept",
                "Demo description");

        Path expectedGeometryPath = tempDir.resolve("5_mobject_geometry.json");
        ManimRendererService renderer = new ManimRendererService() {
            private int attempts = 0;

            @Override
            public RenderAttemptResult render(String code, String sceneName, String quality, Path outputDir) {
                attempts++;
                if (attempts == 1) {
                    return new RenderAttemptResult(
                            false,
                            "",
                            "first failure",
                            null,
                            expectedGeometryPath.toString()
                    );
                }
                return new RenderAttemptResult(false, "", "second failure", null, null);
            }
        };

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);
        ctx.put(WorkflowKeys.AI_CLIENT, new StubAiClient());

        RenderNode renderNode = new RenderNode(renderer);
        CodeFixNode codeFixNode = new CodeFixNode();
        renderNode.next(codeFixNode, WorkflowActions.FIX_CODE);
        codeFixNode.next(renderNode, WorkflowActions.RETRY_RENDER);

        new PocketFlow.Flow<>(renderNode).run(ctx);

        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        assertNotNull(renderResult);
        assertFalse(renderResult.isSuccess());
        assertEquals(2, renderResult.getAttempts());
        assertNull(renderResult.getGeometryPath());
        assertTrue(renderResult.getFinalGeneratedCode().startsWith("from manim import *"));
    }

    private static final class StubAiClient implements AiClient {
        @Override
        public CompletableFuture<String> chatAsync(List<NodeConversationContext.Message> snapshot) {
            return CompletableFuture.completedFuture(String.join("\n",
                    "```python",
                    "from manim import *",
                    "",
                    "class DemoScene(Scene):",
                    "    def construct(self):",
                    "        pass",
                    "",
                    "# retry marker",
                    "```"));
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(List<NodeConversationContext.Message> snapshot,
                                                                 String toolsJson) {
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            future.completeExceptionally(new UnsupportedOperationException("tools not used"));
            return future;
        }

        @Override
        public String providerName() {
            return "stub";
        }
    }
}
