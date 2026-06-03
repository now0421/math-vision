package com.mathvision.service;

import com.mathvision.model.AiMessage;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.NodeConversationContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Abstraction for AI chat completion APIs.
 */
public interface AiClient {

    CompletableFuture<String> chatAsync(java.util.List<NodeConversationContext.Message> snapshot);

    CompletableFuture<JsonNode> chatWithToolsRawAsync(
            java.util.List<NodeConversationContext.Message> snapshot, String toolsJson);

    /**
     * Multimodal chat: sends messages containing text and image parts.
     * Default implementation throws UnsupportedOperationException.
     */
    default CompletableFuture<String> chatMultimodalAsync(List<AiMessage> messages) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Multimodal not supported by " + providerName()));
    }

    /**
     * Multimodal chat with tool calling support.
     * Default implementation throws UnsupportedOperationException.
     */
    default CompletableFuture<JsonNode> chatMultimodalWithToolsRawAsync(
            List<AiMessage> messages, String toolsJson) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Multimodal not supported by " + providerName()));
    }

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
