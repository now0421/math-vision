package com.mathvision.node.support;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeFixResult;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.WorkflowKeys;

import java.util.Map;

/**
 * Static helper methods shared across workflow nodes.
 */
public final class NodeSupport {

    private NodeSupport() {}

    /**
     * Consumes a {@link CodeFixResult} from the shared context if it matches the expected source.
     * Removes the result from the context after consumption.
     */
    public static CodeFixResult consumeFixResult(Map<String, Object> ctx,
                                                  CodeFixSource expectedSource) {
        CodeFixResult result = (CodeFixResult) ctx.get(WorkflowKeys.CODE_FIX_RESULT);
        if (result != null && result.getSource() == expectedSource) {
            ctx.remove(WorkflowKeys.CODE_FIX_RESULT);
            return result;
        }
        return null;
    }

    /**
     * Resolves the output target from the workflow config, defaulting to manim.
     */
    public static String resolveOutputTarget(WorkflowConfig workflowConfig) {
        return workflowConfig != null
                ? workflowConfig.getOutputTarget()
                : WorkflowConfig.OUTPUT_TARGET_MANIM;
    }

    /**
     * Returns {@code true} if the workflow config targets GeoGebra.
     */
    public static boolean isGeoGebraTarget(WorkflowConfig workflowConfig) {
        return workflowConfig != null && workflowConfig.isGeoGebraTarget();
    }
}
