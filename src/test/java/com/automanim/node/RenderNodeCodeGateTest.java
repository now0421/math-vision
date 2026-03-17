package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.RenderResult;
import com.automanim.model.CodeEvaluationResult;
import com.automanim.model.WorkflowKeys;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderNodeCodeGateTest {

    @Test
    void skipsRenderWhenCodeEvaluationBlocksIt() {
        WorkflowConfig config = new WorkflowConfig();
        config.setRenderEnabled(true);

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

        new RenderNode().run(ctx);

        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        assertNotNull(renderResult);
        assertFalse(renderResult.isSuccess());
        assertEquals(0, renderResult.getAttempts());
        assertTrue(renderResult.getLastError().contains("Code evaluation blocked render"));
    }
}
