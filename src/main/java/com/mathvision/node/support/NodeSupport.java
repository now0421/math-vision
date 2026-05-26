package com.mathvision.node.support;

import com.mathvision.config.ModelConfig;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeFixResult;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.util.NodeConversationContext;

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

    /**
     * Returns the caller-owned code-fix conversation context, creating and pinning
     * the caller-selected prompts on first use.
     */
    public static NodeConversationContext ensureCodeFixConversationContext(FixRetryState state,
                                                                           WorkflowConfig workflowConfig,
                                                                           int maxRollingRounds,
                                                                           String rulesPrompt,
                                                                           String fixedContextPrompt) {
        int maxInputTokens = workflowConfig != null
                ? workflowConfig.resolveMaxInputTokens()
                : ModelConfig.DEFAULT_MAX_INPUT_TOKENS;
        NodeConversationContext context = state != null ? state.getConversationContext() : null;
        if (context == null) {
            context = new NodeConversationContext(maxInputTokens, Math.max(maxRollingRounds, 0));
            if (rulesPrompt != null && !rulesPrompt.isBlank()) {
                context.setSystemMessage(rulesPrompt);
            }
            if (fixedContextPrompt != null && !fixedContextPrompt.isBlank()) {
                context.setFixedContextMessage(fixedContextPrompt);
            }
            if (state != null) {
                state.setConversationContext(context);
            }
        }
        return context;
    }
}
