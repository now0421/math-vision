package com.automanim.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Abstraction for AI chat completion APIs.
 */
public interface AiClient {

    /**
     * Send a chat message and get a response.
     */
    String chat(String userMessage, String systemPrompt);

    /**
     * Chat with function/tool calling support.
     * Returns the raw API response as a JsonNode so callers can extract
     * both tool call payloads and text content.
     *
     * @param userMessage   the user message
     * @param systemPrompt  the system instruction
     * @param toolsJson     JSON array describing available tools (OpenAI format)
     * @return the raw API response JSON (choices[].message with tool_calls or content)
     */
    JsonNode chatWithToolsRaw(String userMessage, String systemPrompt, String toolsJson);

    /**
     * Chat with tools, returning text only (for backward compatibility).
     */
    default String chatWithTools(String userMessage, String systemPrompt, String toolsJson) {
        return chat(userMessage, systemPrompt);
    }

    /**
     * Returns the provider name (e.g., "kimi", "gemini").
     */
    String providerName();
}
