package com.mathvision.node;

import com.mathvision.model.CodeFixRequest;
import com.mathvision.model.CodeFixResult;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.CodeResult;
import com.mathvision.model.AiError;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.mathvision.support.AiClientTestSupport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFixNodeTest {

    @Test
    void appliesFixWhenReturnedCodeDiffersFromSourceOnlyByTrailingNewline() {
        String originalCode = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.wait(1)") + "\n";
        String returnedCode = originalCode.substring(0, originalCode.length() - 1);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, new StubAiClient(returnedCode));
        ctx.put(WorkflowKeys.CODE_RESULT, new CodeResult(
                originalCode,
                "MainScene",
                "demo",
                "Demo concept",
                "Demo description"));
        ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildRenderFailureRequest(originalCode));

        new CodeFixNode().run(ctx);

        CodeFixResult fixResult = (CodeFixResult) ctx.get(WorkflowKeys.CODE_FIX_RESULT);
        CodeResult updatedCodeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);

        assertNotNull(fixResult);
        assertTrue(fixResult.isApplied());
        assertEquals(returnedCode, fixResult.getFixedGeneratedCode());
        assertEquals(returnedCode, updatedCodeResult.getGeneratedCode());
    }

    @Test
    void rejectsManimFixThatBreaksCoordinateScaleContract() {
        String originalCode = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.wait(1)");
        String returnedCode = coordinateScaleRegressionCode();

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, new StubAiClient(returnedCode));
        ctx.put(WorkflowKeys.CODE_RESULT, new CodeResult(
                originalCode,
                "MainScene",
                "demo",
                "Demo concept",
                "Demo description"));
        ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildRenderFailureRequest(originalCode));

        new CodeFixNode().run(ctx);

        CodeFixResult fixResult = (CodeFixResult) ctx.get(WorkflowKeys.CODE_FIX_RESULT);
        CodeResult updatedCodeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);

        assertNotNull(fixResult);
        assertFalse(fixResult.isApplied());
        assertEquals(CodeFixResult.FixOutcome.FAILED, fixResult.getOutcome());
        assertTrue(fixResult.getFailureReason().contains("Manim coordinate scale contract"));
        assertTrue(fixResult.getPostFixStaticAuditSummary().contains("Manim coordinate scale contract"));
        assertEquals(originalCode, updatedCodeResult.getGeneratedCode());
    }

    @Test
    void doesNotApplyFixWhenReturnedCodeIsIdenticalToSource() {
        String originalCode = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.wait(1)");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, new StubAiClient(originalCode));
        ctx.put(WorkflowKeys.CODE_RESULT, new CodeResult(
                originalCode,
                "MainScene",
                "demo",
                "Demo concept",
                "Demo description"));
        ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildRenderFailureRequest(originalCode));

        new CodeFixNode().run(ctx);

        CodeFixResult fixResult = (CodeFixResult) ctx.get(WorkflowKeys.CODE_FIX_RESULT);

        assertNotNull(fixResult);
        assertFalse(fixResult.isApplied());
        assertEquals("Code fix returned code identical to source code", fixResult.getFailureReason());
    }

    @Test
    void marksRateLimitBlockedWhenProviderRetriesAreExhausted() {
        String originalCode = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.wait(1)");

        AiError error = new AiError();
        error.setHttpStatus(429);
        error.setMessage("zhipu:GLM-5V-Turbo API returned HTTP 429");
        error.setRateLimited(true);
        error.setTransientFailure(true);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, new ResponseAiClient(AiResponse.failure(error)));
        ctx.put(WorkflowKeys.CODE_RESULT, new CodeResult(
                originalCode,
                "MainScene",
                "demo",
                "Demo concept",
                "Demo description"));
        ctx.put(WorkflowKeys.CODE_FIX_REQUEST, buildRenderFailureRequest(originalCode));

        new CodeFixNode().run(ctx);

        CodeFixResult fixResult = (CodeFixResult) ctx.get(WorkflowKeys.CODE_FIX_RESULT);

        assertNotNull(fixResult);
        assertFalse(fixResult.isApplied());
        assertEquals(CodeFixResult.FixOutcome.RATE_LIMIT_BLOCKED, fixResult.getOutcome());
        assertEquals("Provider rate limit exhausted after 12 retries", fixResult.getFailureReason());
    }

    private CodeFixRequest buildRenderFailureRequest(String generatedCode) {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.CODE_RENDER);
        request.setReturnAction(WorkflowActions.RETRY_RENDER);
        request.setGeneratedCode(generatedCode);
        request.setErrorReason("AttributeError: demo");
        request.setTargetConcept("Demo concept");
        request.setTargetDescription("Demo description");
        request.setSceneName("MainScene");
        request.setExpectedSceneName("MainScene");
        return request;
    }

    private String coordinateScaleRegressionCode() {
        return String.join("\n",
                "from manim import *",
                "import numpy as np",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.setup_shared_scene()",
                "        self.wait(1)",
                "",
                "    def setup_shared_scene(self):",
                "        self._mv_x_range = [-3.5, 7.0, 1.0]",
                "        self._mv_y_range = [-6.5, 5.0, 1.0]",
                "        self._mv_z_range = None",
                "        self._mv_axes = None",
                "        self.axes = Axes(",
                "            x_range=self._mv_x_range,",
                "            y_range=self._mv_y_range,",
                "            x_length=11.0,",
                "            y_length=8.5,",
                "            tips=False,",
                "        )",
                "        self._mv_axes = self.axes",
                "",
                "    def world_point(self, x, y=0.0, z=0.0):",
                "        return self._mv_axes.c2p(x, y)",
                "",
                "    def c2p(self, x, y=0.0, z=0.0):",
                "        return self.world_point(x, y, z)");
    }

    private static final class StubAiClient implements AiClient {
        private final String response;

        private StubAiClient(String response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
            return CompletableFuture.completedFuture(AiClientTestSupport.textResponse(response));
        }
    }

    private static final class ResponseAiClient implements AiClient {
        private final AiResponse response;

        private ResponseAiClient(AiResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
            return CompletableFuture.completedFuture(response);
        }
    }
}
