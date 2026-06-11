package com.mathvision.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public class AiResponse {

    private String content;
    private List<AiToolCall> toolCalls = new ArrayList<>();
    private AiError error;
    private JsonNode raw;

    public static AiResponse success(String content, List<AiToolCall> toolCalls, JsonNode raw) {
        AiResponse response = new AiResponse();
        response.setContent(content);
        response.setToolCalls(toolCalls);
        response.setRaw(raw);
        return response;
    }

    public static AiResponse failure(AiError error) {
        AiResponse response = new AiResponse();
        response.setError(error);
        return response;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<AiToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<AiToolCall> toolCalls) {
        this.toolCalls = toolCalls != null ? new ArrayList<>(toolCalls) : new ArrayList<>();
    }

    public AiError getError() {
        return error;
    }

    public void setError(AiError error) {
        this.error = error;
    }

    public JsonNode getRaw() {
        return raw;
    }

    public void setRaw(JsonNode raw) {
        this.raw = raw;
    }
}
