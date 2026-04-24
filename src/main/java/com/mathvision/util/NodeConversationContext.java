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
 * Stores messages in two layers:
 * <ul>
 *   <li><b>pinnedMessages</b> — system rules and fixed context that are never trimmed.
 *       Always prepended to snapshots in the order: rules first, fixed context second.</li>
 *   <li><b>rollingMessages</b> — dynamic conversation turns that are trimmed from the
 *       front when the token budget is exceeded.</li>
 * </ul>
 *
 * Snapshot assembly order: pinnedMessages → rollingMessages → current user message.
 *
 * Thread-safe: all public methods are synchronized to support concurrent
 * access from parallel depth-level processing in enrichment/design nodes.
 */
public class NodeConversationContext {

    private static final Logger log = LoggerFactory.getLogger(NodeConversationContext.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double SAFETY_MARGIN = 0.9;

    private final int maxInputTokens;
    private final List<Message> pinnedMessages = new ArrayList<>();
    private final List<Message> rollingMessages = new ArrayList<>();
    private final Map<Long, PendingTurn> pendingTurns = new TreeMap<>();
    private long nextTurnSequence = 0L;
    private long nextCommittedTurnSequence = 0L;

    public NodeConversationContext(int maxInputTokens) {
        this.maxInputTokens = maxInputTokens;
    }

    // ---- Pinned message management ----

    public synchronized void setSystemMessage(String content) {
        pinnedMessages.removeIf(m -> "system".equals(m.role));
        pinnedMessages.add(0, new Message("system", content));
    }

    public synchronized void setFixedContextMessage(String content) {
        pinnedMessages.add(new Message("system", content));
    }

    public synchronized void setPinnedMessages(List<Message> messages) {
        pinnedMessages.clear();
        pinnedMessages.addAll(messages);
    }

    public synchronized List<Message> getPinnedMessages() {
        return Collections.unmodifiableList(new ArrayList<>(pinnedMessages));
    }

    // ---- Rolling message management ----

    public synchronized void addUserMessage(String content) {
        rollingMessages.add(new Message("user", content));
        trimRollingToFitBudgetLocked();
    }

    public synchronized void addAssistantMessage(String content) {
        rollingMessages.add(new Message("assistant", content));
        trimRollingToFitBudgetLocked();
    }

    public synchronized List<Message> getRollingMessages() {
        return Collections.unmodifiableList(new ArrayList<>(rollingMessages));
    }

    public synchronized void appendTurn(String userContent, String assistantContent) {
        rollingMessages.add(new Message("user", userContent));
        rollingMessages.add(new Message("assistant", assistantContent));
        trimRollingToFitBudgetLocked();
    }

    public synchronized void appendTurnRaw(String userContent, String assistantContent) {
        appendTurn(userContent, assistantContent);
    }

    public synchronized void appendTurnSummary(String userContent, String assistantContent) {
        appendTurn(userContent, assistantContent);
    }

    // ---- Accessors ----

    public synchronized String getSystemContent() {
        StringBuilder sb = new StringBuilder();
        for (Message m : pinnedMessages) {
            if ("system".equals(m.role)) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(m.content);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    public synchronized String getLastUserContent() {
        for (int i = rollingMessages.size() - 1; i >= 0; i--) {
            if ("user".equals(rollingMessages.get(i).role)) {
                return rollingMessages.get(i).content;
            }
        }
        for (int i = pinnedMessages.size() - 1; i >= 0; i--) {
            if ("user".equals(pinnedMessages.get(i).role)) {
                return pinnedMessages.get(i).content;
            }
        }
        return null;
    }

    public synchronized void clear() {
        pinnedMessages.clear();
        rollingMessages.clear();
        pendingTurns.clear();
        nextTurnSequence = 0L;
        nextCommittedTurnSequence = 0L;
    }

    public synchronized boolean isEmpty() {
        return pinnedMessages.isEmpty() && rollingMessages.isEmpty();
    }

    public synchronized int messageCount() {
        return pinnedMessages.size() + rollingMessages.size();
    }

    public synchronized List<Message> getMessages() {
        List<Message> all = new ArrayList<>(pinnedMessages.size() + rollingMessages.size());
        all.addAll(pinnedMessages);
        all.addAll(rollingMessages);
        return Collections.unmodifiableList(all);
    }

    // ---- Snapshot ----

    /**
     * Creates a snapshot: pinnedMessages + rollingMessages + additional user message.
     * Does NOT mutate this context.
     */
    public synchronized List<Message> snapshotWithUserMessage(String userContent) {
        List<Message> snapshot = new ArrayList<>(
                pinnedMessages.size() + rollingMessages.size() + 1);
        snapshot.addAll(pinnedMessages);
        snapshot.addAll(rollingMessages);
        snapshot.add(new Message("user", userContent));
        return snapshot;
    }

    public synchronized TurnReservation reserveTurn(String userContent) {
        return new TurnReservation(nextTurnSequence++, snapshotWithUserMessage(userContent));
    }

    public synchronized void appendReservedTurn(long turnSequence,
                                                String userContent,
                                                String assistantContent) {
        pendingTurns.put(turnSequence, new PendingTurn(userContent, assistantContent));
        flushPendingTurnsLocked();
        trimRollingToFitBudgetLocked();
    }

    public synchronized void cancelReservedTurn(long turnSequence) {
        pendingTurns.put(turnSequence, PendingTurn.skipped());
        flushPendingTurnsLocked();
    }

    // ---- Token management ----

    public synchronized int estimateTotalTokens() {
        int total = 0;
        for (Message m : pinnedMessages) total += m.estimatedTokens;
        for (Message m : rollingMessages) total += m.estimatedTokens;
        return total;
    }

    public int getMaxInputTokens() {
        return maxInputTokens;
    }

    /**
     * Trims the oldest rolling turns until the total fits within
     * {@code maxInputTokens * SAFETY_MARGIN}. Pinned messages are never removed.
     */
    public synchronized void trimToFitBudget() {
        trimRollingToFitBudgetLocked();
    }

    private void trimRollingToFitBudgetLocked() {
        int effectiveBudget = (int) (maxInputTokens * SAFETY_MARGIN);
        int pinnedTokens = 0;
        for (Message m : pinnedMessages) {
            pinnedTokens += m.estimatedTokens;
        }

        if (pinnedTokens >= effectiveBudget) {
            log.warn("Pinned messages alone exceed budget: {} tokens >= budget {}",
                    pinnedTokens, effectiveBudget);
            return;
        }

        int rollingBudget = effectiveBudget - pinnedTokens;
        int rollingTokens = 0;
        for (Message m : rollingMessages) {
            rollingTokens += m.estimatedTokens;
        }

        if (rollingTokens <= rollingBudget) {
            return;
        }

        int removedMessages = 0;
        int beforeTokens = rollingTokens;
        while (rollingTokens > rollingBudget && rollingMessages.size() > 1) {
            Message removed = rollingMessages.remove(0);
            rollingTokens -= removed.estimatedTokens;
            removedMessages++;

            if (!rollingMessages.isEmpty()
                    && "assistant".equals(rollingMessages.get(0).role)) {
                Message pair = rollingMessages.remove(0);
                rollingTokens -= pair.estimatedTokens;
                removedMessages++;
            }
        }

        if (removedMessages > 0) {
            log.info("Rolling context trimmed: {} messages removed, "
                            + "rolling ~{} -> ~{} tokens (budget: {} of {} total)",
                    removedMessages, beforeTokens, rollingTokens, rollingBudget, effectiveBudget);
        }
    }

    private void flushPendingTurnsLocked() {
        while (true) {
            PendingTurn pending = pendingTurns.remove(nextCommittedTurnSequence);
            if (pending == null) {
                return;
            }
            if (!pending.skipped) {
                rollingMessages.add(new Message("user", pending.userContent));
                rollingMessages.add(new Message("assistant", pending.assistantContent));
            }
            nextCommittedTurnSequence++;
        }
    }

    // ---- Snapshot serialization helpers ----

    public static ArrayNode buildOpenAiMessages(List<Message> snapshot) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Message m : snapshot) {
            ObjectNode msg = array.addObject();
            msg.put("role", m.role);
            msg.put("content", m.content);
        }
        return array;
    }

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
     * Returns the concatenated system content from a snapshot list,
     * or null if absent. Concatenates all consecutive system-role messages
     * at the start of the list.
     */
    public static String getSystemContent(List<Message> snapshot) {
        StringBuilder sb = new StringBuilder();
        for (Message m : snapshot) {
            if ("system".equals(m.role)) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(m.content);
            } else {
                break;
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Trims a snapshot list in-place by removing old turns until it fits
     * the given token budget, preserving all leading system messages and
     * the last user message.
     */
    public static void trimSnapshotToFitBudget(List<Message> snapshot, int maxInputTokens) {
        int effectiveBudget = (int) (maxInputTokens * SAFETY_MARGIN);
        if (estimateTokens(snapshot) <= effectiveBudget) {
            return;
        }

        int firstNonSystem = 0;
        while (firstNonSystem < snapshot.size()
                && "system".equals(snapshot.get(firstNonSystem).role)) {
            firstNonSystem++;
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

    // ---- Instance serialization ----

    public synchronized ArrayNode buildOpenAiMessages() {
        ArrayNode array = MAPPER.createArrayNode();
        for (Message m : pinnedMessages) {
            ObjectNode msg = array.addObject();
            msg.put("role", m.role);
            msg.put("content", m.content);
        }
        for (Message m : rollingMessages) {
            ObjectNode msg = array.addObject();
            msg.put("role", m.role);
            msg.put("content", m.content);
        }
        return array;
    }

    public synchronized ArrayNode buildGeminiContents() {
        ArrayNode array = MAPPER.createArrayNode();
        for (Message m : pinnedMessages) {
            if ("system".equals(m.role)) {
                continue;
            }
            ObjectNode entry = array.addObject();
            entry.put("role", "assistant".equals(m.role) ? "model" : m.role);
            ArrayNode parts = entry.putArray("parts");
            parts.addObject().put("text", m.content);
        }
        for (Message m : rollingMessages) {
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

    // ---- Inner types ----

    public static class Message {
        private final String role;
        private final String content;
        private final int estimatedTokens;

        public Message(String role, String content) {
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
