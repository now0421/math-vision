package com.mathvision.node;

import com.mathvision.model.CodeFixRequest;
import com.mathvision.model.CodeFixResult;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.CodeResult;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
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

    private CodeFixRequest buildRenderFailureRequest(String generatedCode) {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.RENDER_FAILURE);
        request.setReturnAction(WorkflowActions.RETRY_RENDER);
        request.setGeneratedCode(generatedCode);
        request.setErrorReason("AttributeError: demo");
        request.setTargetConcept("Demo concept");
        request.setTargetDescription("Demo description");
        request.setSceneName("MainScene");
        request.setExpectedSceneName("MainScene");
        return request;
    }

    private static final class StubAiClient implements AiClient {
        private final String response;

        private StubAiClient(String response) {
            this.response = response;
        }

        @Override
        public String chat(String userMessage, String systemPrompt) {
            return response;
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            throw new UnsupportedOperationException("Tools are not used in this test");
        }

        @Override
        public String providerName() {
            return "stub";
        }
    }
}
