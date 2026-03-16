package com.automanim.service;

import com.automanim.util.ConcurrencyUtils;
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
     * Returns a client identifier for logging.
     */
    String providerName();
}
