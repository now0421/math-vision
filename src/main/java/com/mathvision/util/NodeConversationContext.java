package com.mathvision.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Transient per-node conversation context for multi-turn LLM interactions.
 *
 * Stores a sequence of messages (system, user, assistant) and provides
 * serialization to OpenAI and Gemini API formats. Automatically trims
 * the oldest conversation turns when the estimated token count exceeds
 * the configured budget.
 *
 * Thread-safe: all public methods are synchronized to support concurrent
 * access from parallel depth-level processing in enrichment/design nodes.
 */
public class NodeConversationContext {

    private static final Logger log = LoggerFactory.getLogger(NodeConversationContext.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double SAFETY_MARGIN = 0.9;

    private final int maxInputTokens;
    private final List<Message> messages = new ArrayList<>();
    private final Map<Long, PendingTurn> pendingTurns = new TreeMap<>();
    private long nextTurnSequence = 0L;
    private long nextCommittedTurnSequence = 0L;

    public NodeConversationContext(int maxInputTokens) {
        this.maxInputTokens = maxInputTokens;
    }

    // ---- Message management ----

    public synchronized void setSystemMessage(String content) {
        if (!messages.isEmpty() && "system".equals(messages.get(0).role)) {
            messages.set(0, new Message("system", content));
        } else {
            messages.add(0, new Message("system", content));
        }
    }

    public synchronized void addUserMessage(String content) {
        messages.add(new Message("user", content));
        trimToFitBudgetLocked();
    }

    public synchronized void addAssistantMessage(String content) {
        messages.add(new Message("assistant", content));
        trimToFitBudgetLocked();
    }

    public synchronized String getSystemContent() {
        if (!messages.isEmpty() && "system".equals(messages.get(0).role)) {
            return messages.get(0).content;
        }
        return null;
    }

    public synchronized String getLastUserContent() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role)) {
                return messages.get(i).content;
            }
        }
        return null;
    }

    public synchronized void clear() {
        messages.clear();
        pendingTurns.clear();
        nextTurnSequence = 0L;
        nextCommittedTurnSequence = 0L;
    }

    public synchronized boolean isEmpty() {
        return messages.isEmpty();
    }

    public synchronized int messageCount() {
        return messages.size();
    }

    public synchronized List<Message> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /**
     * Creates a snapshot of the current messages plus an additional user message,
     * WITHOUT mutating this context. Used by concurrent callers to build an
     * isolated request body while keeping the shared context clean.
     *
     * After the API call completes, use {@link #appendReservedTurn(long, String, String)}
     * to record the completed user+assistant pair in request order.
     */
    public synchronized List<Message> snapshotWithUserMessage(String userContent) {
        List<Message> snapshot = new ArrayList<>(messages.size() + 1);
        snapshot.addAll(messages);
        snapshot.add(new Message("user", userContent));
        return snapshot;
    }

    public synchronized TurnReservation reserveTurn(String userContent) {
        return new TurnReservation(nextTurnSequence++, snapshotWithUserMessage(userContent));
    }

    /**
     * Atomically appends a completed user→assistant turn to the context.
     * Callers within a depth level should synchronize at the level barrier
     * so that turns from the same level are written sequentially.
     */
    public synchronized void appendTurn(String userContent, String assistantContent) {
        messages.add(new Message("user", userContent));
        messages.add(new Message("assistant", assistantContent));
        trimToFitBudgetLocked();
    }

    public synchronized void appendReservedTurn(long turnSequence,
                                                String userContent,
                                                String assistantContent) {
        pendingTurns.put(turnSequence, new PendingTurn(userContent, assistantContent));
        flushPendingTurnsLocked();
        trimToFitBudgetLocked();
    }

    public synchronized void cancelReservedTurn(long turnSequence) {
        pendingTurns.put(turnSequence, PendingTurn.skipped());
        flushPendingTurnsLocked();
    }

    // ---- Snapshot serialization helpers ----

    /**
     * Builds OpenAI-format messages from an arbitrary snapshot list.
     * Does NOT lock this context — the list is already a copy.
     */
    public static ArrayNode buildOpenAiMessages(List<Message> snapshot) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Message m : snapshot) {
            ObjectNode msg = array.addObject();
            msg.put("role", m.role);
            msg.put("content", m.content);
        }
        return array;
    }

    /**
     * Builds Gemini-format contents from an arbitrary snapshot list.
     * Does NOT lock this context — the list is already a copy.
     */
    public static ArrayNode buildGeminiContents(List<Message> snapshot) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Message m : snapshot) {
            if ("system".equals(m.role)) {
                continue;
            }
            ObjectNode entry = array.addObject();
            entry.put("role", "assistant".equals(m.role) ? "model" : m.role);
            ArrayNode parts = entry.putArray("parts");
            parts.addObject().put("text", m.content);
        }
        return array;
    }

    /**
     * Returns the system content from a snapshot list, or null if absent.
     */
    public static String getSystemContent(List<Message> snapshot) {
        if (!snapshot.isEmpty() && "system".equals(snapshot.get(0).role)) {
            return snapshot.get(0).content;
        }
        return null;
    }

    /**
     * Trims a snapshot list in-place by removing old turns until it fits
     * the given token budget, preserving system (index 0) and the last
     * user message.
     */
    public static void trimSnapshotToFitBudget(List<Message> snapshot, int maxInputTokens) {
        int effectiveBudget = (int) (maxInputTokens * SAFETY_MARGIN);
        if (estimateTokens(snapshot) <= effectiveBudget) {
            return;
        }

        int firstNonSystem = 0;
        if (!snapshot.isEmpty() && "system".equals(snapshot.get(0).role)) {
            firstNonSystem = 1;
        }

        while (estimateTokens(snapshot) > effectiveBudget) {
            int nonSystemCount = snapshot.size() - firstNonSystem;
            if (nonSystemCount <= 1) {
                break;
            }
            snapshot.remove(firstNonSystem);
            if (firstNonSystem < snapshot.size()
                    && "assistant".equals(snapshot.get(firstNonSystem).role)) {
                snapshot.remove(firstNonSystem);
            }
        }
    }

    private static int estimateTokens(List<Message> msgs) {
        int total = 0;
        for (Message m : msgs) {
            total += m.estimatedTokens;
        }
        return total;
    }

    public int getMaxInputTokens() {
        return maxInputTokens;
    }

    // ---- Token management ----

    public synchronized int estimateTotalTokens() {
        int total = 0;
        for (Message m : messages) {
            total += m.estimatedTokens;
        }
        return total;
    }

    /**
     * Trims the oldest conversation turns (user + assistant pairs) until
     * the estimated total tokens fits within {@code maxInputTokens * SAFETY_MARGIN}.
     *
     * The system message (index 0) and the most recent user message are
     * never removed.
     */
    public synchronized void trimToFitBudget() {
        trimToFitBudgetLocked();
    }

    private void trimToFitBudgetLocked() {
        int effectiveBudget = (int) (maxInputTokens * SAFETY_MARGIN);
        int beforeTokens = estimateTotalTokens();
        if (beforeTokens <= effectiveBudget) {
            return;
        }

        int firstNonSystem = 0;
        if (!messages.isEmpty() && "system".equals(messages.get(0).role)) {
            firstNonSystem = 1;
        }

        int removedMessages = 0;
        while (estimateTotalTokens() > effectiveBudget) {
            int nonSystemCount = messages.size() - firstNonSystem;
            if (nonSystemCount <= 1) {
                break;
            }

            messages.remove(firstNonSystem);
            removedMessages++;

            // If we removed a user message and the next is its paired assistant,
            // remove the pair together
            if (firstNonSystem < messages.size()
                    && "assistant".equals(messages.get(firstNonSystem).role)) {
                messages.remove(firstNonSystem);
                removedMessages++;
            }
        }

        if (removedMessages > 0) {
            log.info("Conversation context trimmed: {} messages removed, "
                            + "~{} -> ~{} tokens (budget: {})",
                    removedMessages, beforeTokens, estimateTotalTokens(), effectiveBudget);
        }
    }

    private void flushPendingTurnsLocked() {
        while (true) {
            PendingTurn pending = pendingTurns.remove(nextCommittedTurnSequence);
            if (pending == null) {
                return;
            }
            if (!pending.skipped) {
                messages.add(new Message("user", pending.userContent));
                messages.add(new Message("assistant", pending.assistantContent));
            }
            nextCommittedTurnSequence++;
        }
    }

    // ---- Serialization ----

    /**
     * OpenAI-compatible messages array: [{role, content}, ...]
     */
    public synchronized ArrayNode buildOpenAiMessages() {
        ArrayNode array = MAPPER.createArrayNode();
        for (Message m : messages) {
            ObjectNode msg = array.addObject();
            msg.put("role", m.role);
            msg.put("content", m.content);
        }
        return array;
    }

    /**
     * Gemini-format contents array (user/model turns, system excluded).
     * Maps "assistant" role to "model".
     */
    public synchronized ArrayNode buildGeminiContents() {
        ArrayNode array = MAPPER.createArrayNode();
        for (Message m : messages) {
            if ("system".equals(m.role)) {
                continue;
            }
            ObjectNode entry = array.addObject();
            entry.put("role", "assistant".equals(m.role) ? "model" : m.role);
            ArrayNode parts = entry.putArray("parts");
            parts.addObject().put("text", m.content);
        }
        return array;
    }

    // ---- Inner type ----

    public static class Message {
        private final String role;
        private final String content;
        private final int estimatedTokens;

        Message(String role, String content) {
            this.role = role;
            this.content = content;
            this.estimatedTokens = TokenEstimator.estimateTokens(content);
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }

        public int getEstimatedTokens() {
            return estimatedTokens;
        }
    }

    public static class TurnReservation {
        private final long sequence;
        private final List<Message> snapshot;

        private TurnReservation(long sequence, List<Message> snapshot) {
            this.sequence = sequence;
            this.snapshot = snapshot;
        }

        public long getSequence() {
            return sequence;
        }

        public List<Message> getSnapshot() {
            return snapshot;
        }
    }

    private static class PendingTurn {
        private final String userContent;
        private final String assistantContent;
        private final boolean skipped;

        private PendingTurn(String userContent, String assistantContent) {
            this.userContent = userContent;
            this.assistantContent = assistantContent;
            this.skipped = false;
        }

        private PendingTurn(boolean skipped) {
            this.userContent = "";
            this.assistantContent = "";
            this.skipped = skipped;
        }

        private static PendingTurn skipped() {
            return new PendingTurn(true);
        }
    }
}
