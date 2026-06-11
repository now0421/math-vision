package com.mathvision.model;

import java.util.ArrayList;
import java.util.List;

public class AiRequest {

    private List<AiMessage> messages = new ArrayList<>();
    private String toolsJson;

    public AiRequest() {
    }

    public AiRequest(List<AiMessage> messages, String toolsJson) {
        setMessages(messages);
        this.toolsJson = toolsJson;
    }

    public static AiRequest of(List<AiMessage> messages) {
        return new AiRequest(messages, null);
    }

    public static AiRequest withTools(List<AiMessage> messages, String toolsJson) {
        return new AiRequest(messages, toolsJson);
    }

    public List<AiMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AiMessage> messages) {
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
    }

    public String getToolsJson() {
        return toolsJson;
    }

    public void setToolsJson(String toolsJson) {
        this.toolsJson = toolsJson;
    }
}
