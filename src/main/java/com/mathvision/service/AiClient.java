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

    /**
     * Send a chat message and get a response.
     */
    String chat(String userMessage, String systemPrompt);

    /**
     * Send a chat message asynchronously and get a future for the response.
     */
    default CompletableFuture<String> chatAsync(String userMessage, String systemPrompt) {
        return CompletableFuture.supplyAsync(() -> chat(userMessage, systemPrompt));
    }

    /**
     * Send a chat message with function/tool calling support and get the raw
     * API response asynchronously.
     */
    CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                      String systemPrompt,
                                                      String toolsJson);

    default JsonNode chatWithToolsRaw(String userMessage, String systemPrompt, String toolsJson) {
        try {
            return chatWithToolsRawAsync(userMessage, systemPrompt, toolsJson).join();
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("AI chat with tools failed: " + cause.getMessage(), cause);
        }
    }

    /**
     * Send a multi-turn conversation and get a response synchronously.
     */
    default String chat(NodeConversationContext context) {
        return chat(context.getLastUserContent(), context.getSystemContent());
    }

    /**
     * Send a multi-turn conversation asynchronously.
     */
    default CompletableFuture<String> chatAsync(NodeConversationContext context) {
        return chatAsync(context.getLastUserContent(), context.getSystemContent());
    }

    /**
     * Send a multi-turn conversation with tool calling support asynchronously.
     */
    default CompletableFuture<JsonNode> chatWithToolsRawAsync(
            NodeConversationContext context, String toolsJson) {
        return chatWithToolsRawAsync(
                context.getLastUserContent(), context.getSystemContent(), toolsJson);
    }

    /**
     * Send a snapshot of messages (not the live context) asynchronously.
     * Used by concurrent callers to avoid mutating the shared context.
     */
    default CompletableFuture<String> chatAsync(
            java.util.List<NodeConversationContext.Message> snapshot) {
        NodeConversationContext context = new NodeConversationContext(Integer.MAX_VALUE);
        for (NodeConversationContext.Message message : snapshot) {
            if ("system".equals(message.getRole())) {
                context.setSystemMessage(message.getContent());
            } else if ("user".equals(message.getRole())) {
                context.addUserMessage(message.getContent());
            } else if ("assistant".equals(message.getRole())) {
                context.addAssistantMessage(message.getContent());
            }
        }
        return chatAsync(context);
    }

    /**
     * Send a snapshot of messages with tool calling support asynchronously.
     */
    default CompletableFuture<JsonNode> chatWithToolsRawAsync(
            java.util.List<NodeConversationContext.Message> snapshot, String toolsJson) {
        NodeConversationContext context = new NodeConversationContext(Integer.MAX_VALUE);
        for (NodeConversationContext.Message message : snapshot) {
            if ("system".equals(message.getRole())) {
                context.setSystemMessage(message.getContent());
            } else if ("user".equals(message.getRole())) {
                context.addUserMessage(message.getContent());
            } else if ("assistant".equals(message.getRole())) {
                context.addAssistantMessage(message.getContent());
            }
        }
        return chatWithToolsRawAsync(context, toolsJson);
    }

    /**
     * Returns a client identifier for logging.
     */
    String providerName();
}
