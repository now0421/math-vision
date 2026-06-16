package com.mathvision.node.support;

import com.mathvision.config.ModelConfig;
import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.AiContentPart;
import com.mathvision.model.AiMessage;
import com.mathvision.model.AiRequest;
import com.mathvision.model.CodeFixResult;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.util.NodeConversationContext;

import java.util.ArrayList;
import java.util.List;
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
                ? workflowConfig.resolvePromptInputBudgetTokens()
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

    public static AiRequest buildAiRequest(NodeConversationContext context,
                                           String userPrompt,
                                           String toolsJson) {
        List<NodeConversationContext.Message> snapshot = context != null
                ? context.snapshotWithUserMessage(userPrompt)
                : List.of(new NodeConversationContext.Message("user", userPrompt));
        if (context != null) {
            NodeConversationContext.trimSnapshotToFitBudget(snapshot, context.getPromptInputBudgetTokens());
        }
        return AiRequest.withTools(toAiMessages(snapshot), toolsJson);
    }

    public static AiRequest buildAiRequest(List<NodeConversationContext.Message> snapshot,
                                           int maxInputTokens,
                                           String userPrompt,
                                           String toolsJson) {
        List<NodeConversationContext.Message> requestSnapshot = new ArrayList<>();
        if (snapshot != null && !snapshot.isEmpty()) {
            requestSnapshot.addAll(snapshot);
        }
        requestSnapshot.add(new NodeConversationContext.Message("user", userPrompt));
        NodeConversationContext.trimSnapshotToFitBudget(requestSnapshot, maxInputTokens);
        return AiRequest.withTools(toAiMessages(requestSnapshot), toolsJson);
    }

    public static List<AiMessage> toAiMessages(List<NodeConversationContext.Message> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return List.of();
        }
        ArrayList<AiMessage> messages = new ArrayList<>(snapshot.size());
        for (NodeConversationContext.Message message : snapshot) {
            messages.add(new AiMessage(
                    message.getRole(),
                    List.of(AiContentPart.text(message.getContent()))));
        }
        return messages;
    }
}
