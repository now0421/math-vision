package com.mathvision.service;

import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.NodeConversationContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Abstraction for AI chat completion APIs.
 */
public interface AiClient {

    CompletableFuture<String> chatAsync(java.util.List<NodeConversationContext.Message> snapshot);

    CompletableFuture<JsonNode> chatWithToolsRawAsync(
            java.util.List<NodeConversationContext.Message> snapshot, String toolsJson);

    default JsonNode chatWithToolsRaw(
            java.util.List<NodeConversationContext.Message> snapshot, String toolsJson) {
        try {
            return chatWithToolsRawAsync(snapshot, toolsJson).join();
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("AI chat with tools failed: " + cause.getMessage(), cause);
        }
    }

    default CompletableFuture<String> chatAsync(NodeConversationContext context) {
        context.trimToFitBudget();
        return chatAsync(context.getMessages());
    }

    default CompletableFuture<JsonNode> chatWithToolsRawAsync(
            NodeConversationContext context, String toolsJson) {
        context.trimToFitBudget();
        return chatWithToolsRawAsync(context.getMessages(), toolsJson);
    }

    /**
     * Returns a client identifier for logging.
     */
    String providerName();
}
