package com.mathvision.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.mathvision.model.AiContentPart;
import com.mathvision.model.AiMessage;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.model.AiToolCall;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;

import java.util.ArrayList;
import java.util.List;

public final class AiClientTestSupport {

    private AiClientTestSupport() {
    }

    public static AiResponse textResponse(String text) {
        return AiResponse.success(text, List.of(), null);
    }

    public static AiResponse rawResponse(JsonNode raw) {
        List<AiToolCall> toolCalls = new ArrayList<>();
        JsonNode payload = JsonUtils.extractToolCallPayload(raw);
        String toolName = JsonUtils.extractToolCallName(raw);
        if (payload != null || (toolName != null && !toolName.isBlank())) {
            AiToolCall toolCall = new AiToolCall();
            toolCall.setName(toolName);
            toolCall.setArguments(payload);
            toolCall.setArgumentsText(payload != null ? payload.toString() : "");
            toolCall.setRaw(raw);
            toolCalls.add(toolCall);
        }
        return AiResponse.success(JsonUtils.extractBestEffortTextFromResponse(raw), toolCalls, raw);
    }

    public static List<NodeConversationContext.Message> snapshot(AiRequest request) {
        List<NodeConversationContext.Message> snapshot = new ArrayList<>();
        if (request == null || request.getMessages() == null) {
            return snapshot;
        }
        for (AiMessage message : request.getMessages()) {
            snapshot.add(new NodeConversationContext.Message(
                    message.getRole(),
                    textContent(message)));
        }
        return snapshot;
    }

    public static String lastUserContent(AiRequest request) {
        List<NodeConversationContext.Message> snapshot = snapshot(request);
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            NodeConversationContext.Message message = snapshot.get(i);
            if ("user".equals(message.getRole())) {
                return message.getContent();
            }
        }
        return "";
    }

    public static String systemContent(AiRequest request) {
        return NodeConversationContext.getSystemContent(snapshot(request));
    }

    public static String textContent(AiMessage message) {
        if (message == null || message.getParts() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AiContentPart part : message.getParts()) {
            if (part == null || !"text".equals(part.getType())) {
                continue;
            }
            if (part.getText() == null || part.getText().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(part.getText());
        }
        return sb.toString();
    }
}
