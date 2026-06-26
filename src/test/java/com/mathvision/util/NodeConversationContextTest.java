package com.mathvision.util;

import org.junit.jupiter.api.Test;

import com.mathvision.model.AiRequest;
import com.mathvision.node.support.NodeSupport;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeConversationContextTest {

    @Test
    void defaultConstructorKeepsRollingHistoryUntilBudgetRequiresTrim() {
        NodeConversationContext context = new NodeConversationContext(10_000);

        context.appendTurn("first user", "first assistant");
        context.appendTurn("second user", "second assistant");

        List<NodeConversationContext.Message> rollingMessages = context.getRollingMessages();
        assertEquals(4, rollingMessages.size());
        assertEquals("first user", rollingMessages.get(0).getContent());
        assertEquals("second assistant", rollingMessages.get(3).getContent());
    }

    @Test
    void explicitRoundLimitKeepsOnlyLatestRollingTurn() {
        NodeConversationContext context = new NodeConversationContext(10_000, 1);

        context.appendTurn("first user", "first assistant");
        context.appendTurn("second user", "second assistant");

        List<NodeConversationContext.Message> rollingMessages = context.getRollingMessages();
        assertEquals(2, rollingMessages.size());
        assertEquals("second user", rollingMessages.get(0).getContent());
        assertEquals("second assistant", rollingMessages.get(1).getContent());
    }

    @Test
    void explicitZeroRoundLimitKeepsUnlimitedRollingHistory() {
        NodeConversationContext context = new NodeConversationContext(10_000, 0);

        context.appendTurn("first user", "first assistant");
        context.appendTurn("second user", "second assistant");

        List<NodeConversationContext.Message> rollingMessages = context.getRollingMessages();
        assertEquals(4, rollingMessages.size());
        assertEquals("first user", rollingMessages.get(0).getContent());
        assertEquals("second assistant", rollingMessages.get(3).getContent());
    }

    @Test
    void requestPayloadBudgetTriggersSnapshotTrim() {
        List<NodeConversationContext.Message> snapshot = new java.util.ArrayList<>(List.of(
                new NodeConversationContext.Message("system", "rules"),
                new NodeConversationContext.Message("user", "u".repeat(80)),
                new NodeConversationContext.Message("assistant", "a".repeat(80)),
                new NodeConversationContext.Message("user", "current")));

        NodeConversationContext.trimSnapshotToFitBudget(snapshot, 100, "t".repeat(220));

        assertEquals(List.of("system", "user"), roles(snapshot));
        assertEquals("current", snapshot.get(1).getContent());
    }

    @Test
    void nodeSupportBuildAiRequestTrimsAgainstToolSchema() {
        NodeConversationContext context = new NodeConversationContext(100, 0);
        context.setSystemMessage("rules");
        context.appendTurn("u".repeat(80), "a".repeat(80));

        AiRequest request = NodeSupport.buildAiRequest(context, "current", "t".repeat(220));

        assertEquals(List.of("system", "user"), request.getMessages().stream()
                .map(message -> message.getRole())
                .collect(Collectors.toList()));
        assertEquals("current", request.getMessages().get(1).getParts().get(0).getText());
    }

    @Test
    void trimSnapshotWarnsAndKeepsCurrentUserMessageWhenStillOverBudget() {
        String longUserPrompt = "head-" + "x".repeat(400) + "-tail";
        List<NodeConversationContext.Message> snapshot = List.of(
                new NodeConversationContext.Message("system", "rules"),
                new NodeConversationContext.Message("user", longUserPrompt));

        NodeConversationContext.trimSnapshotToFitBudget(snapshot, 80);

        assertEquals(2, snapshot.size());
        assertEquals(longUserPrompt, snapshot.get(1).getContent());
    }

    private static List<String> roles(List<NodeConversationContext.Message> messages) {
        return messages.stream()
                .map(NodeConversationContext.Message::getRole)
                .collect(Collectors.toList());
    }
}
