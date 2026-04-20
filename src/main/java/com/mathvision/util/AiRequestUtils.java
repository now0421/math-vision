package com.mathvision.util;

import com.mathvision.service.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared async AI request helpers for JSON-oriented tool calls.
 */
public final class AiRequestUtils {

    private AiRequestUtils() {}

    public static CompletableFuture<JsonNode> requestJsonObjectAsync(AiClient aiClient,
                                                                     Logger log,
                                                                     String subject,
                                                                     String userPrompt,
                                                                     String systemPrompt,
                                                                     String toolsJson,
                                                                     Runnable onApiCall) {
        return requestJsonObjectAsync(
                aiClient,
                log,
                subject,
                userPrompt,
                systemPrompt,
                toolsJson,
                onApiCall,
                AiRequestUtils::parsePlainTextJsonObject,
                AiRequestUtils::isUsablePayload
        );
    }

    public static CompletableFuture<JsonNode> requestJsonObjectAsync(AiClient aiClient,
                                                                     Logger log,
                                                                     String subject,
                                                                     String userPrompt,
                                                                     String systemPrompt,
                                                                     String toolsJson,
                                                                     Runnable onApiCall,
                                                                     Function<String, JsonNode> plainTextParser) {
        return requestJsonObjectAsync(
                aiClient,
                log,
                subject,
                userPrompt,
                systemPrompt,
                toolsJson,
                onApiCall,
                plainTextParser,
                AiRequestUtils::isUsablePayload
        );
    }

    public static CompletableFuture<JsonNode> requestJsonObjectAsync(AiClient aiClient,
                                                                     Logger log,
                                                                     String subject,
                                                                     String userPrompt,
                                                                     String systemPrompt,
                                                                     String toolsJson,
                                                                     Runnable onApiCall,
                                                                     Function<String, JsonNode> plainTextParser,
                                                                     Predicate<JsonNode> payloadValidator) {
        Predicate<JsonNode> validator = payloadValidator != null
                ? payloadValidator
                : AiRequestUtils::isUsablePayload;
        return aiClient.chatWithToolsRawAsync(userPrompt, systemPrompt, toolsJson)
                .thenApply(rawResponse -> {
                    onApiCall.run();
                    return extractJsonObject(rawResponse, plainTextParser, validator);
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.debug("  Tool calling failed for '{}', falling back to plain chat: {}",
                            subject, cause.getMessage());
                    return null;
                })
                .thenCompose(data -> {
                    if (data != null) {
                        return CompletableFuture.completedFuture(data);
                    }
                    return aiClient.chatAsync(userPrompt, systemPrompt)
                            .thenApply(response -> {
                                onApiCall.run();
                                JsonNode parsed = parsePlainTextResponse(response, plainTextParser);
                                return validator.test(parsed) ? parsed : null;
                            });
                });
    }

    /**
     * Context-aware variant that uses a {@link NodeConversationContext} for
     * multi-turn conversations.
     *
     * <p>Concurrency-safe: takes a snapshot of the current context plus the new
     * user message, sends the snapshot to the AI (without mutating the shared
     * context), and only appends the completed user+assistant turn after the
     * response arrives. This prevents message interleaving when multiple
     * requests execute concurrently within the same depth level.
     */
    public static CompletableFuture<JsonNode> requestJsonObjectAsync(AiClient aiClient,
                                                                     Logger log,
                                                                     String subject,
                                                                     java.util.List<NodeConversationContext.Message> snapshot,
                                                                     int maxInputTokens,
                                                                     String userPrompt,
                                                                     String toolsJson,
                                                                     Runnable onApiCall) {
        return requestJsonObjectAsync(
                aiClient,
                log,
                subject,
                snapshot,
                maxInputTokens,
                userPrompt,
                toolsJson,
                onApiCall,
                AiRequestUtils::parsePlainTextJsonObject,
                AiRequestUtils::isUsablePayload
        );
    }

    public static CompletableFuture<JsonNode> requestJsonObjectAsync(AiClient aiClient,
                                                                     Logger log,
                                                                     String subject,
                                                                     java.util.List<NodeConversationContext.Message> snapshot,
                                                                     int maxInputTokens,
                                                                     String userPrompt,
                                                                     String toolsJson,
                                                                     Runnable onApiCall,
                                                                     Function<String, JsonNode> plainTextParser) {
        return requestJsonObjectAsync(
                aiClient,
                log,
                subject,
                snapshot,
                maxInputTokens,
                userPrompt,
                toolsJson,
                onApiCall,
                plainTextParser,
                AiRequestUtils::isUsablePayload
        );
    }

    public static CompletableFuture<JsonNode> requestJsonObjectAsync(AiClient aiClient,
                                                                     Logger log,
                                                                     String subject,
                                                                     java.util.List<NodeConversationContext.Message> snapshot,
                                                                     int maxInputTokens,
                                                                     String userPrompt,
                                                                     String toolsJson,
                                                                     Runnable onApiCall,
                                                                     Function<String, JsonNode> plainTextParser,
                                                                     Predicate<JsonNode> payloadValidator) {
        return requestJsonObjectResultAsync(
                aiClient,
                log,
                subject,
                snapshot,
                maxInputTokens,
                userPrompt,
                toolsJson,
                onApiCall,
                plainTextParser,
                payloadValidator
        ).thenApply(JsonObjectResult::getPayload);
    }

    public static CompletableFuture<JsonObjectResult> requestJsonObjectResultAsync(AiClient aiClient,
                                                                                   Logger log,
                                                                                   String subject,
                                                                                   java.util.List<NodeConversationContext.Message> snapshot,
                                                                                   int maxInputTokens,
                                                                                   String userPrompt,
                                                                                   String toolsJson,
                                                                                   Runnable onApiCall) {
        return requestJsonObjectResultAsync(
                aiClient,
                log,
                subject,
                snapshot,
                maxInputTokens,
                userPrompt,
                toolsJson,
                onApiCall,
                AiRequestUtils::parsePlainTextJsonObject,
                AiRequestUtils::isUsablePayload
        );
    }

    public static CompletableFuture<JsonObjectResult> requestJsonObjectResultAsync(AiClient aiClient,
                                                                                   Logger log,
                                                                                   String subject,
                                                                                   java.util.List<NodeConversationContext.Message> snapshot,
                                                                                   int maxInputTokens,
                                                                                   String userPrompt,
                                                                                   String toolsJson,
                                                                                   Runnable onApiCall,
                                                                                   Function<String, JsonNode> plainTextParser,
                                                                                   Predicate<JsonNode> payloadValidator) {
        Predicate<JsonNode> validator = payloadValidator != null
                ? payloadValidator
                : AiRequestUtils::isUsablePayload;
        java.util.List<NodeConversationContext.Message> requestSnapshot =
                snapshotWithUserMessage(snapshot, userPrompt, maxInputTokens);

        return aiClient.chatWithToolsRawAsync(requestSnapshot, toolsJson)
                .thenApply(rawResponse -> {
                    onApiCall.run();
                    JsonNode data = extractJsonObject(rawResponse, plainTextParser, validator);
                    if (data == null) {
                        return null;
                    }
                    return new JsonObjectResult(data, buildToolAssistantTranscript(rawResponse, data));
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.debug("  Tool calling failed for '{}', falling back to plain chat: {}",
                            subject, cause.getMessage());
                    return null;
                })
                .thenCompose(result -> {
                    if (result != null) {
                        return CompletableFuture.completedFuture(result);
                    }

                    return aiClient.chatAsync(requestSnapshot)
                            .thenApply(response -> {
                                onApiCall.run();
                                JsonNode parsed = parsePlainTextResponse(response, plainTextParser);
                                JsonNode payload = validator.test(parsed) ? parsed : null;
                                return new JsonObjectResult(payload, response);
                            });
                });
    }

    public static CompletableFuture<JsonNode> requestJsonObjectAsync(AiClient aiClient,
                                                                     Logger log,
                                                                     String subject,
                                                                     NodeConversationContext context,
                                                                     String userPrompt,
                                                                     String toolsJson,
                                                                     Runnable onApiCall) {
        return requestJsonObjectAsync(
                aiClient,
                log,
                subject,
                context,
                userPrompt,
                toolsJson,
                onApiCall,
                AiRequestUtils::parsePlainTextJsonObject,
                AiRequestUtils::isUsablePayload
        );
    }

    public static CompletableFuture<JsonNode> requestJsonObjectAsync(AiClient aiClient,
                                                                     Logger log,
                                                                     String subject,
                                                                     NodeConversationContext context,
                                                                     String userPrompt,
                                                                     String toolsJson,
                                                                     Runnable onApiCall,
                                                                     Function<String, JsonNode> plainTextParser) {
        return requestJsonObjectAsync(
                aiClient,
                log,
                subject,
                context,
                userPrompt,
                toolsJson,
                onApiCall,
                plainTextParser,
                AiRequestUtils::isUsablePayload
        );
    }

    public static CompletableFuture<JsonNode> requestJsonObjectAsync(AiClient aiClient,
                                                                     Logger log,
                                                                     String subject,
                                                                     NodeConversationContext context,
                                                                     String userPrompt,
                                                                     String toolsJson,
                                                                     Runnable onApiCall,
                                                                     Function<String, JsonNode> plainTextParser,
                                                                     Predicate<JsonNode> payloadValidator) {
        Predicate<JsonNode> validator = payloadValidator != null
                ? payloadValidator
                : AiRequestUtils::isUsablePayload;
        // Snapshot: frozen copy of existing messages + the new user message.
        // Does NOT mutate the shared context.
        NodeConversationContext.TurnReservation reservation = context.reserveTurn(userPrompt);
        java.util.List<NodeConversationContext.Message> snapshot = reservation.getSnapshot();
        NodeConversationContext.trimSnapshotToFitBudget(
                snapshot, context.getMaxInputTokens());

        return aiClient.chatWithToolsRawAsync(snapshot, toolsJson)
                .thenApply(rawResponse -> {
                    onApiCall.run();
                    JsonNode data = extractJsonObject(rawResponse, plainTextParser, validator);
                    if (data != null) {
                        String assistantText = JsonUtils.buildToolCallTranscript(rawResponse);
                        if (assistantText == null || assistantText.isBlank()) {
                            assistantText = data.toPrettyString();
                        }
                        context.appendReservedTurn(
                                reservation.getSequence(), userPrompt, assistantText);
                    }
                    return data;
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.debug("  Tool calling failed for '{}', falling back to plain chat: {}",
                            subject, cause.getMessage());
                    return null;
                })
                .thenCompose(data -> {
                    if (data != null) {
                        return CompletableFuture.completedFuture(data);
                    }
                    // Fallback: take a fresh snapshot (may now include turns from
                    // concurrent siblings that completed first).
                    java.util.List<NodeConversationContext.Message> fallbackSnapshot =
                            context.snapshotWithUserMessage(userPrompt);
                    NodeConversationContext.trimSnapshotToFitBudget(
                            fallbackSnapshot, context.getMaxInputTokens());

                    return aiClient.chatAsync(fallbackSnapshot)
                            .thenApply(response -> {
                                onApiCall.run();
                                context.appendReservedTurn(
                                        reservation.getSequence(), userPrompt, response);
                                JsonNode parsed = parsePlainTextResponse(response, plainTextParser);
                                return validator.test(parsed) ? parsed : null;
                            });
                })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        context.cancelReservedTurn(reservation.getSequence());
                    }
                });
    }

    private static JsonNode extractJsonObject(JsonNode rawResponse,
                                              Function<String, JsonNode> plainTextParser,
                                              Predicate<JsonNode> payloadValidator) {
        Predicate<JsonNode> validator = payloadValidator != null
                ? payloadValidator
                : AiRequestUtils::isUsablePayload;
        JsonNode data = JsonUtils.extractToolCallPayload(rawResponse);
        if (validator.test(data)) {
            return data;
        }

        String textContent = JsonUtils.extractBestEffortTextFromResponse(rawResponse);
        if (textContent != null && !textContent.isBlank()) {
            JsonNode parsed = parsePlainTextResponse(textContent, plainTextParser);
            return validator.test(parsed) ? parsed : null;
        }

        return null;
    }

    private static boolean isUsablePayload(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return false;
        }
        if (payload.isObject()) {
            return payload.size() > 0;
        }
        return true;
    }

    private static JsonNode parsePlainTextResponse(String response,
                                                   Function<String, JsonNode> plainTextParser) {
        Function<String, JsonNode> parser = plainTextParser != null
                ? plainTextParser
                : AiRequestUtils::parsePlainTextJsonObject;
        return parser.apply(response);
    }

    private static JsonNode parsePlainTextJsonObject(String response) {
        if (response == null || !response.contains("{")) {
            return null;
        }
        String candidate = JsonUtils.extractJsonObject(response);
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parseTree(candidate);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static java.util.List<NodeConversationContext.Message> snapshotWithUserMessage(
            java.util.List<NodeConversationContext.Message> snapshot,
            String userPrompt,
            int maxInputTokens) {
        java.util.List<NodeConversationContext.Message> requestSnapshot = new java.util.ArrayList<>();
        if (snapshot != null && !snapshot.isEmpty()) {
            requestSnapshot.addAll(snapshot);
        }
        requestSnapshot.add(new NodeConversationContext.Message("user", userPrompt));
        NodeConversationContext.trimSnapshotToFitBudget(requestSnapshot, maxInputTokens);
        return requestSnapshot;
    }

    private static String buildToolAssistantTranscript(JsonNode rawResponse, JsonNode payload) {
        String assistantText = JsonUtils.buildToolCallTranscript(rawResponse);
        if (assistantText == null || assistantText.isBlank()) {
            return payload != null ? payload.toPrettyString() : "";
        }
        return assistantText;
    }

    public static final class JsonObjectResult {
        private final JsonNode payload;
        private final String assistantTranscript;

        public JsonObjectResult(JsonNode payload, String assistantTranscript) {
            this.payload = payload;
            this.assistantTranscript = assistantTranscript == null ? "" : assistantTranscript;
        }

        public JsonNode getPayload() {
            return payload;
        }

        public String getAssistantTranscript() {
            return assistantTranscript;
        }
    }
}
