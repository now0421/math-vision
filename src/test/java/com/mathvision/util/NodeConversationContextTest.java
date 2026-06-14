package com.mathvision.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeConversationContextTest {

    @Test
    void trimSnapshotFailsWhenCurrentUserMessageStillExceedsBudget() {
        String longUserPrompt = "head-" + "x".repeat(400) + "-tail";
        List<NodeConversationContext.Message> snapshot = List.of(
                new NodeConversationContext.Message("system", "rules"),
                new NodeConversationContext.Message("user", longUserPrompt));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> NodeConversationContext.trimSnapshotToFitBudget(snapshot, 80));

        assertTrue(error.getMessage().contains("Refusing to truncate the current user prompt"));
    }
}
