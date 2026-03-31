package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.RenderResult;
import com.automanim.model.CodeEvaluationResult;
import com.automanim.model.WorkflowActions;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.GeoGebraRenderService;
import com.automanim.service.ManimRendererService;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderNodeCodeGateTest {

    @TempDir
    Path tempDir;

    @Test
    void continuesRenderWhenCodeEvaluationOnlyAdvisesAgainstRender() {
        WorkflowConfig config = new WorkflowConfig();
        config.setRenderEnabled(true);
        config.setRenderMaxRetries(0);

        CodeResult codeResult = new CodeResult(
                String.join("\n",
                        "from manim import *",
                        "",
                        "class DemoScene(Scene):",
                        "    def construct(self):",
                        "        pass"),
                "DemoScene",
                "demo",
                "Demo concept",
                "Demo description");

        CodeEvaluationResult codeEvaluationResult = new CodeEvaluationResult();
        codeEvaluationResult.setApprovedForRender(false);
        codeEvaluationResult.setGateReason("layout_score=5 < 7");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
        ctx.put(WorkflowKeys.CODE_EVALUATION_RESULT, codeEvaluationResult);

        ManimRendererService renderer = new ManimRendererService() {
            @Override
            public RenderAttemptResult render(String code, String sceneName, String quality, java.nio.file.Path outputDir) {
                return new RenderAttemptResult(false, "", "render failed", null, null);
            }
        };

        new RenderNode(renderer).run(ctx);

        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        assertNotNull(renderResult);
        assertFalse(renderResult.isSuccess());
        assertEquals(1, renderResult.getAttempts());
        assertTrue(renderResult.getLastError().contains("render failed"));
    }

    @Test
    void geogebraTargetExportsPreviewHtml() throws Exception {
        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        config.setRenderEnabled(true);

        CodeResult codeResult = new CodeResult(
                String.join("\n",
                        "A = (0, 0)",
                        "B = (4, 0)",
                        "lineAB = Line(A, B)"),
                "GeoGebraFigure",
                "demo",
                "Demo concept",
                "Demo description");
        codeResult.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        codeResult.setArtifactFormat("commands");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);

        GeoGebraRenderService geoGebraRenderer = new GeoGebraRenderService() {
            @Override
            protected ValidationReport validateWithHeadlessBrowser(Path previewPath,
                                                                   String figureName,
                                                                   List<String> commands) {
                return successfulValidationReport(figureName, commands);
            }
        };

        new RenderNode(new ManimRendererService(), geoGebraRenderer).run(ctx);

        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        assertNotNull(renderResult);
        assertTrue(renderResult.isSuccess());
        assertEquals(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA, renderResult.getOutputTarget());
        assertTrue(renderResult.getArtifactPath().endsWith("5_geogebra_preview.html"));
        assertTrue(Files.exists(Path.of(renderResult.getArtifactPath())));
        assertTrue(Files.exists(tempDir.resolve("5_geogebra_validation.json")));
    }

    @Test
    void geogebraRenderFailureRoutesThroughSharedCodeFixAndRetriesRender() {
        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        config.setRenderEnabled(true);
        config.setRenderMaxRetries(1);

        CodeResult codeResult = new CodeResult(
                String.join("\n",
                        "A = (0, 0)",
                        "B = (4, 0)",
                        "mid = Midpoint(lineAB)"),
                "GeoGebraFigure",
                "demo",
                "Demo concept",
                "Demo description");
        codeResult.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        codeResult.setArtifactFormat("commands");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);
        ctx.put(WorkflowKeys.AI_CLIENT, new GeoGebraFixAiClient());

        GeoGebraRenderService geoGebraRenderer = new GeoGebraRenderService() {
            @Override
            public RenderAttemptResult render(String commandScript, String figureName, Path outputDir) {
                if (commandScript.contains("mid = Midpoint(A, B)")) {
                    return new RenderAttemptResult(true, outputDir.resolve("5_geogebra_preview.html").toString(), null);
                }
                return new RenderAttemptResult(
                        false,
                        outputDir.resolve("5_geogebra_preview.html").toString(),
                        "Command 3 returned false: mid = Midpoint(lineAB)"
                );
            }
        };

        RenderNode renderNode = new RenderNode(new ManimRendererService(), geoGebraRenderer);
        CodeFixNode codeFixNode = new CodeFixNode();
        renderNode.next(codeFixNode, WorkflowActions.FIX_CODE);
        codeFixNode.next(renderNode, WorkflowActions.RETRY_RENDER);

        new PocketFlow.Flow<>(renderNode).run(ctx);

        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        CodeResult finalCodeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);

        assertNotNull(renderResult);
        assertTrue(renderResult.isSuccess());
        assertEquals(2, renderResult.getAttempts());
        assertNull(renderResult.getLastError());
        assertTrue(finalCodeResult.getCode().contains("mid = Midpoint(A, B)"));
    }

    @Test
    void geogebraTimeoutValidationFailureStillRoutesThroughRetryLoop() {
        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        config.setRenderEnabled(true);
        config.setRenderMaxRetries(1);

        CodeResult codeResult = new CodeResult(
                String.join("\n",
                        "A = (0, 0)",
                        "B = (4, 0)",
                        "mid = Midpoint(lineAB)"),
                "GeoGebraFigure",
                "demo",
                "Demo concept",
                "Demo description");
        codeResult.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        codeResult.setArtifactFormat("commands");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);
        ctx.put(WorkflowKeys.AI_CLIENT, new GeoGebraFixAiClient());

        GeoGebraRenderService geoGebraRenderer = new GeoGebraRenderService() {
            @Override
            public RenderAttemptResult render(String commandScript, String figureName, Path outputDir) {
                if (commandScript.contains("mid = Midpoint(A, B)")) {
                    return new RenderAttemptResult(true, outputDir.resolve("5_geogebra_preview.html").toString(), null);
                }
                return new RenderAttemptResult(
                        false,
                        outputDir.resolve("5_geogebra_preview.html").toString(),
                        "GeoGebra Playwright validation failed: Timeout 30000ms exceeded."
                );
            }
        };

        RenderNode renderNode = new RenderNode(new ManimRendererService(), geoGebraRenderer);
        CodeFixNode codeFixNode = new CodeFixNode();
        renderNode.next(codeFixNode, WorkflowActions.FIX_CODE);
        codeFixNode.next(renderNode, WorkflowActions.RETRY_RENDER);

        new PocketFlow.Flow<>(renderNode).run(ctx);

        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);

        assertNotNull(renderResult);
        assertTrue(renderResult.isSuccess());
        assertEquals(2, renderResult.getAttempts());
    }

    private static GeoGebraRenderService.ValidationReport successfulValidationReport(String figureName,
                                                                                     List<String> commands) {
        GeoGebraRenderService.ValidationReport report = new GeoGebraRenderService.ValidationReport();
        report.figureName = figureName;
        report.browserExecutable = "stub-browser";
        report.completed = true;
        report.appletLoaded = true;
        report.totalCommands = commands.size();
        report.successfulCommands = commands.size();
        report.failedCommands = 0;
        report.commands = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            GeoGebraRenderService.CommandValidation entry = new GeoGebraRenderService.CommandValidation();
            entry.index = i + 1;
            entry.command = commands.get(i);
            entry.success = true;
            report.commands.add(entry);
        }
        return report;
    }

    private static final class GeoGebraFixAiClient implements AiClient {
        @Override
        public String chat(String userMessage, String systemPrompt) {
            return String.join("\n",
                    "```geogebra",
                    "A = (0, 0)",
                    "B = (4, 0)",
                    "lineAB = Line(A, B)",
                    "mid = Midpoint(A, B)",
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

