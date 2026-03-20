package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.RenderResult;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.ManimRendererService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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

        new RenderNode(renderer).run(ctx);

        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        assertNotNull(renderResult);
        assertFalse(renderResult.isSuccess());
        assertEquals(2, renderResult.getAttempts());
        assertNull(renderResult.getGeometryPath());
        assertTrue(renderResult.getFinalCode().startsWith("from manim import *"));
    }

    private static final class StubAiClient implements AiClient {
        @Override
        public String chat(String userMessage, String systemPrompt) {
            return String.join("\n",
                    "```python",
                    "from manim import *",
                    "",
                    "class DemoScene(Scene):",
                    "    def construct(self):",
                    "        pass",
                    "",
                    "# retry marker",
                    "```");
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
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
