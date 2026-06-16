package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.model.CodeResult;
import com.mathvision.model.RenderResult;
import com.mathvision.model.CodeEvaluationResult;
import com.mathvision.model.CodeFixRequest;
import com.mathvision.model.CodeFixResult;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.mathvision.service.GeoGebraRenderService;
import com.mathvision.service.ManimRendererService;
import com.mathvision.support.AiClientTestSupport;
import com.mathvision.util.GeoGebraCodeUtils;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        codeEvaluationResult.setGateReason("layout_and_hierarchy rule failed");

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
    void timeoutWithUnderlyingPythonErrorRoutesToFix() {
        WorkflowConfig config = new WorkflowConfig();
        config.setRenderEnabled(true);
        config.setRenderMaxRetries(2);

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

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);

        String stderrWithError = "Traceback (most recent call last):\n"
                + "  File \"scene_render.py\", line 37\n"
                + "ValueError: zip() argument 2 is shorter than argument 1\n"
                + "Render timed out after 10 minutes";

        ManimRendererService renderer = new ManimRendererService() {
            @Override
            public RenderAttemptResult render(String code, String sceneName, String quality, java.nio.file.Path outputDir) {
                return new RenderAttemptResult(false, "", stderrWithError, null, null, true);
            }
        };

        RenderNode renderNode = new RenderNode(renderer);
        renderNode.run(ctx);

        // Should request fix because there's a fixable error behind the timeout
        assertTrue(ctx.containsKey(WorkflowKeys.CODE_FIX_REQUEST),
                "Timeout with underlying Python error should route to code fix");
    }

    @Test
    void pureTimeoutWithNoUnderlyingErrorStopsRetries() {
        WorkflowConfig config = new WorkflowConfig();
        config.setRenderEnabled(true);
        config.setRenderMaxRetries(2);

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

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);

        ManimRendererService renderer = new ManimRendererService() {
            @Override
            public RenderAttemptResult render(String code, String sceneName, String quality, java.nio.file.Path outputDir) {
                return new RenderAttemptResult(false, "", "Render timed out after 10 minutes", null, null, true);
            }
        };

        RenderNode renderNode = new RenderNode(renderer);
        renderNode.run(ctx);

        // Should NOT request fix — pure timeout with no underlying error
        assertFalse(ctx.containsKey(WorkflowKeys.CODE_FIX_REQUEST),
                "Pure timeout without underlying Python error should stop retries");
    }

    @Test
    void preflightAuditRoutesToFixBeforeInvokingRenderer() {
        WorkflowConfig config = new WorkflowConfig();
        config.setRenderEnabled(true);
        config.setRenderMaxRetries(1);

        CodeResult codeResult = new CodeResult(
                String.join("\n",
                        "from manim import *",
                        "",
                        "class DemoScene(Scene):",
                        "    def construct(self):",
                        "        path = VMobject()",
                        "        path.set_points([LEFT, RIGHT])",
                        "        self.add(path)"),
                "DemoScene",
                "demo",
                "Demo concept",
                "Demo description");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);

        ManimRendererService renderer = new ManimRendererService() {
            @Override
            public RenderAttemptResult render(String code, String sceneName, String quality, java.nio.file.Path outputDir) {
                throw new AssertionError("renderer.render should not be called when preflight fails");
            }
        };

        RenderNode renderNode = new RenderNode(renderer);
        renderNode.run(ctx);

        assertTrue(ctx.containsKey(WorkflowKeys.CODE_FIX_REQUEST));
        com.mathvision.model.CodeFixRequest request =
                (com.mathvision.model.CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertEquals("summary_signature", request.getErrorContextMode());
        assertTrue(request.getStaticAuditIssueCount() > 0);
        assertTrue(request.getStaticAuditSummary().contains("unsafe VMobject.set_points() call"));
        assertNotEquals("", request.getInputTextHealth());
    }

    @Test
    void geogebraPreflightRoutesToFixBeforeInvokingRenderer() {
        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        config.setRenderEnabled(true);
        config.setRenderMaxRetries(1);

        CodeResult codeResult = new CodeResult(
                "A = (0, 0); B = (1, 0)",
                GeoGebraCodeUtils.EXPECTED_FIGURE_NAME,
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
            public RenderAttemptResult render(String commandScript, String figureName, Path outputDir) {
                throw new AssertionError("GeoGebra renderer should not be called when preflight fails");
            }
        };

        new RenderNode(new ManimRendererService(), geoGebraRenderer).run(ctx);

        assertTrue(ctx.containsKey(WorkflowKeys.CODE_FIX_REQUEST));
        com.mathvision.model.CodeFixRequest request =
                (com.mathvision.model.CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertEquals(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA, request.getOutputTarget());
        assertTrue(request.getStaticAuditIssueCount() > 0);
        assertTrue(request.getStaticAuditSummary().contains("multiple commands on one line"));
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
                GeoGebraCodeUtils.EXPECTED_FIGURE_NAME,
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
                                                                   List<String> commands,
                                                                   List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                                                   Path geometryPath) {
                return successfulValidationReport(figureName, commands);
            }
        };

        new RenderNode(new ManimRendererService(), geoGebraRenderer).run(ctx);

        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        assertNotNull(renderResult);
        assertTrue(renderResult.isSuccess());
        assertEquals(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA, renderResult.getOutputTarget());
        assertTrue(renderResult.getArtifactPath().endsWith(GeoGebraRenderService.PREVIEW_FILE));
        assertTrue(Files.exists(Path.of(renderResult.getArtifactPath())));
        assertTrue(Files.exists(tempDir.resolve(GeoGebraRenderService.VALIDATION_FILE)));
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
                GeoGebraCodeUtils.EXPECTED_FIGURE_NAME,
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
                    return new RenderAttemptResult(true, outputDir.resolve(GeoGebraRenderService.PREVIEW_FILE).toString(), null, null);
                }
                return new RenderAttemptResult(
                        false,
                        outputDir.resolve(GeoGebraRenderService.PREVIEW_FILE).toString(),
                        null,
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
        assertTrue(finalCodeResult.getGeneratedCode().contains("mid = Midpoint(A, B)"));
    }

    @Test
    void geogebraTimeoutValidationFailureStopsWithoutRetryingCodeFix() {
        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        config.setRenderEnabled(true);
        config.setRenderMaxRetries(1);

        CodeResult codeResult = new CodeResult(
                String.join("\n",
                        "A = (0, 0)",
                        "B = (4, 0)",
                        "mid = Midpoint(lineAB)"),
                GeoGebraCodeUtils.EXPECTED_FIGURE_NAME,
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
                    return new RenderAttemptResult(true, outputDir.resolve(GeoGebraRenderService.PREVIEW_FILE).toString(), null, null);
                }
                return new RenderAttemptResult(
                        false,
                        outputDir.resolve(GeoGebraRenderService.PREVIEW_FILE).toString(),
                        null,
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
        CodeResult finalCodeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);

        assertNotNull(renderResult);
        assertFalse(renderResult.isSuccess());
        assertEquals(1, renderResult.getAttempts());
        assertTrue(renderResult.getLastError().contains("Timeout 30000ms exceeded"));
        assertTrue(finalCodeResult.getGeneratedCode().contains("mid = Midpoint(lineAB)"));
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

    @Test
    void renderFixHistoryDoesNotClaimStaticFixWasRenderedSuccessfully() {
        WorkflowConfig config = new WorkflowConfig();
        config.setRenderEnabled(true);
        config.setRenderMaxRetries(1);

        String code = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.wait(1)");
        CodeResult codeResult = new CodeResult(
                code,
                "MainScene",
                "demo",
                "Demo concept",
                "Demo description");

        CodeFixResult previousFix = new CodeFixResult();
        previousFix.setSource(com.mathvision.model.CodeFixSource.CODE_RENDER);
        previousFix.setReturnAction(WorkflowActions.RETRY_RENDER);
        previousFix.setApplied(true);
        previousFix.setOutcome(CodeFixResult.FixOutcome.FIXED);
        previousFix.setErrorReason("TypeError: demo failure");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);
        ctx.put(WorkflowKeys.CODE_FIX_RESULT, previousFix);

        ManimRendererService renderer = new ManimRendererService() {
            @Override
            public RenderAttemptResult render(String code, String sceneName, String quality, java.nio.file.Path outputDir) {
                return new RenderAttemptResult(false, "", "TypeError: second failure", null, null);
            }
        };

        new RenderNode(renderer).run(ctx);

        CodeFixRequest request = (CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(request);
        assertTrue(request.getFixHistory().stream()
                .anyMatch(entry -> entry.contains("render not yet confirmed")));
        assertFalse(request.getFixHistory().stream()
                .anyMatch(entry -> entry.contains("fixed cleanly")));
    }

    private static final class GeoGebraFixAiClient implements AiClient {
        @Override
        public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
            return CompletableFuture.completedFuture(AiClientTestSupport.textResponse(String.join("\n",
                    "```geogebra",
                    "A = (0, 0)",
                    "B = (4, 0)",
                    "lineAB = Line(A, B)",
                    "mid = Midpoint(A, B)",
                    "```")));
        }
    }
}
