package com.mathvision.util;

import com.mathvision.service.AiTraceLogger;
import com.mathvision.service.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Shared async AI request helpers for JSON-oriented tool calls.
 * Includes unified 429 / rate-limit retry logic at the application layer.
 */
public final class AiRequestUtils {

    private static final int RATE_LIMIT_RETRIES = 3;
    private static final long RATE_LIMIT_BASE_DELAY_MILLIS = 2_000L;
    private static final long RATE_LIMIT_MAX_DELAY_MILLIS = 30_000L;
    private static final List<String> NO_PREFERRED_PAYLOAD_FIELDS = List.of();

    private AiRequestUtils() {}

    // ---- Rate-limit / 429 retry logic ----

    /**
     * Detects whether an exception indicates a rate-limit (429) or other
     * transient server error that is worth retrying after a wait.
     */
    static boolean isRateLimitError(Throwable error) {
        if (error == null) return false;
        String msg = error.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("429")
                || lower.contains("rate limit")
                || lower.contains("rate_limit")
                || lower.contains("too many requests")
                || lower.contains("resource exhausted")
                || lower.contains("quota exceeded");
    }

    private static long rateLimitDelayMillis(int attempt) {
        long delay = RATE_LIMIT_BASE_DELAY_MILLIS * (1L << attempt);
        return Math.min(delay, RATE_LIMIT_MAX_DELAY_MILLIS);
    }

    /**
     * Wraps a CompletableFuture supplier with rate-limit retry logic.
     * If the future completes exceptionally with a 429 / rate-limit error,
     * waits with exponential backoff and retries up to RATE_LIMIT_RETRIES times.
     */
    static <T> CompletableFuture<T> withRateLimitRetry(
            Supplier<CompletableFuture<T>> futureSupplier,
            Logger log,
            String subject,
            Runnable onApiCall) {
        return doRateLimitRetry(futureSupplier, log, subject, onApiCall, 0);
    }

    private static <T> CompletableFuture<T> doRateLimitRetry(
            Supplier<CompletableFuture<T>> futureSupplier,
            Logger log,
            String subject,
            Runnable onApiCall,
            int attempt) {
        CompletableFuture<T> future = futureSupplier.get();
        CompletableFuture<RetryOutcome<T>> handled = future.handle((T result, Throwable error) -> {
            if (error == null) {
                return new RetryOutcome<>(result, null, false);
            }
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
            if (isRateLimitError(cause) && attempt < RATE_LIMIT_RETRIES) {
                long delay = rateLimitDelayMillis(attempt);
                log.warn("Rate limit hit for '{}' (attempt {}/{}), retrying in {} ms: {}",
                        subject, attempt + 1, RATE_LIMIT_RETRIES + 1, delay, cause.getMessage());
                onApiCall.run();
                return new RetryOutcome<>(null, null, true);
            }
            return new RetryOutcome<>(null, error, false);
        });
        return handled.thenCompose((RetryOutcome<T> outcome) -> {
            if (outcome.retry) {
                long delay = rateLimitDelayMillis(attempt);
                CompletableFuture<Void> wait = CompletableFuture.runAsync(() -> { },
                        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS));
                return wait.thenCompose(ignored ->
                        doRateLimitRetry(futureSupplier, log, subject, onApiCall, attempt + 1));
            }
            if (outcome.error != null) {
                return CompletableFuture.failedFuture(outcome.error);
            }
            return CompletableFuture.completedFuture(outcome.result);
        });
    }

    private static final class RetryOutcome<T> {
        final T result;
        final Throwable error;
        final boolean retry;
        RetryOutcome(T result, Throwable error, boolean retry) {
            this.result = result;
            this.error = error;
            this.retry = retry;
        }
    }

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
        return withRateLimitRetry(
                        () -> aiClient.chatWithToolsRawAsync(userPrompt, systemPrompt, toolsJson),
                        log, subject, onApiCall)
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
                    return withRateLimitRetry(
                                    () -> aiClient.chatAsync(userPrompt, systemPrompt),
                                    log, subject, onApiCall)
                            .thenApply(response -> {
                                onApiCall.run();
                                return extractFromTextContent(response, plainTextParser, validator);
                            });
                });
    }

    public static CompletableFuture<String> requestExtractedTextAsync(AiClient aiClient,
                                                                      Logger log,
                                                                      String subject,
                                                                      String userPrompt,
                                                                      String systemPrompt,
                                                                      String toolsJson,
                                                                      Runnable onApiCall,
                                                                      List<String> preferredPayloadFields,
                                                                      Function<String, String> textExtractor,
                                                                      Predicate<String> textValidator) {
        return requestExtractedTextResultAsync(
                aiClient,
                log,
                subject,
                userPrompt,
                systemPrompt,
                toolsJson,
                onApiCall,
                preferredPayloadFields,
                textExtractor,
                textValidator
        ).thenApply(ExtractedTextResult::getExtractedText);
    }

    public static CompletableFuture<ExtractedTextResult> requestExtractedTextResultAsync(AiClient aiClient,
                                                                                         Logger log,
                                                                                         String subject,
                                                                                         String userPrompt,
                                                                                         String systemPrompt,
                                                                                         String toolsJson,
                                                                                         Runnable onApiCall,
                                                                                         List<String> preferredPayloadFields,
                                                                                         Function<String, String> textExtractor,
                                                                                         Predicate<String> textValidator) {
        Predicate<String> validator = textValidator != null
                ? textValidator
                : AiRequestUtils::isUsableExtractedText;
        Function<String, String> extractor = textExtractor != null
                ? textExtractor
                : AiRequestUtils::defaultTextExtractor;

        return withRateLimitRetry(
                        () -> aiClient.chatWithToolsRawAsync(userPrompt, systemPrompt, toolsJson),
                        log, subject, onApiCall)
                .thenApply(rawResponse -> {
                    onApiCall.run();
                    ExtractedTextResult result = extractTextResult(rawResponse, preferredPayloadFields, extractor, validator);
                    logExtractedTextDiagnostics("tool-call:" + subject, result);
                    return result;
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
                    return withRateLimitRetry(
                                    () -> aiClient.chatAsync(userPrompt, systemPrompt),
                                    log, subject, onApiCall)
                            .thenApply(response -> {
                                onApiCall.run();
                                String extractedText = extractExtractedText(response, extractor, validator);
                                ExtractedTextResult fallbackResult = new ExtractedTextResult(null, extractedText, response);
                                logExtractedTextDiagnostics("plain-chat:" + subject, fallbackResult);
                                return fallbackResult;
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

        return withRateLimitRetry(
                        () -> aiClient.chatWithToolsRawAsync(requestSnapshot, toolsJson),
                        log, subject, onApiCall)
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

                    return withRateLimitRetry(
                                    () -> aiClient.chatAsync(requestSnapshot),
                                    log, subject, onApiCall)
                            .thenApply(response -> {
                                onApiCall.run();
                                JsonNode parsed = parsePlainTextResponse(response, plainTextParser);
                                JsonNode payload = validator.test(parsed) ? parsed : null;
                                return new JsonObjectResult(payload, response);
                            });
                });
    }

    public static CompletableFuture<String> requestExtractedTextAsync(AiClient aiClient,
                                                                      Logger log,
                                                                      String subject,
                                                                      NodeConversationContext context,
                                                                      String userPrompt,
                                                                      String toolsJson,
                                                                      Runnable onApiCall,
                                                                      List<String> preferredPayloadFields,
                                                                      Function<String, String> textExtractor,
                                                                      Predicate<String> textValidator) {
        return requestExtractedTextResultAsync(
                aiClient,
                log,
                subject,
                context,
                userPrompt,
                toolsJson,
                onApiCall,
                preferredPayloadFields,
                textExtractor,
                textValidator
        ).thenApply(ExtractedTextResult::getExtractedText);
    }

    public static CompletableFuture<ExtractedTextResult> requestExtractedTextResultAsync(AiClient aiClient,
                                                                                         Logger log,
                                                                                         String subject,
                                                                                         NodeConversationContext context,
                                                                                         String userPrompt,
                                                                                         String toolsJson,
                                                                                         Runnable onApiCall,
                                                                                         List<String> preferredPayloadFields,
                                                                                         Function<String, String> textExtractor,
                                                                                         Predicate<String> textValidator) {
        Predicate<String> validator = textValidator != null
                ? textValidator
                : AiRequestUtils::isUsableExtractedText;
        Function<String, String> extractor = textExtractor != null
                ? textExtractor
                : AiRequestUtils::defaultTextExtractor;

        NodeConversationContext.TurnReservation reservation = context.reserveTurn(userPrompt);
        java.util.List<NodeConversationContext.Message> snapshot = reservation.getSnapshot();
        NodeConversationContext.trimSnapshotToFitBudget(snapshot, context.getMaxInputTokens());

        return withRateLimitRetry(
                        () -> aiClient.chatWithToolsRawAsync(snapshot, toolsJson),
                        log, subject, onApiCall)
                .thenApply(rawResponse -> {
                    onApiCall.run();
                    ExtractedTextResult result = extractTextResult(rawResponse, preferredPayloadFields, extractor, validator);
                    logExtractedTextDiagnostics("tool-call:" + subject, result);
                    if (result != null && result.getAssistantTranscript() != null
                            && !result.getAssistantTranscript().isBlank()) {
                        context.appendReservedTurn(
                                reservation.getSequence(), userPrompt, result.getAssistantTranscript());
                    }
                    return result;
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

                    java.util.List<NodeConversationContext.Message> fallbackSnapshot =
                            context.snapshotWithUserMessage(userPrompt);
                    NodeConversationContext.trimSnapshotToFitBudget(
                            fallbackSnapshot, context.getMaxInputTokens());

                    return withRateLimitRetry(
                                    () -> aiClient.chatAsync(fallbackSnapshot),
                                    log, subject, onApiCall)
                            .thenApply(response -> {
                                onApiCall.run();
                                context.appendReservedTurn(
                                        reservation.getSequence(), userPrompt, response);
                                String extractedText = extractExtractedText(response, extractor, validator);
                                ExtractedTextResult fallbackResult = new ExtractedTextResult(null, extractedText, response);
                                logExtractedTextDiagnostics("plain-chat:" + subject, fallbackResult);
                                return fallbackResult;
                            });
                })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        context.cancelReservedTurn(reservation.getSequence());
                    }
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

        return withRateLimitRetry(
                        () -> aiClient.chatWithToolsRawAsync(snapshot, toolsJson),
                        log, subject, onApiCall)
                .thenApply(rawResponse -> {
                    onApiCall.run();
                    JsonNode data = extractJsonObject(rawResponse, plainTextParser, validator);
                    if (data != null) {
                        context.appendReservedTurn(
                                reservation.getSequence(), userPrompt, data.toPrettyString());
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

                    return withRateLimitRetry(
                                    () -> aiClient.chatAsync(fallbackSnapshot),
                                    log, subject, onApiCall)
                            .thenApply(response -> {
                                onApiCall.run();
                                context.appendReservedTurn(
                                        reservation.getSequence(), userPrompt, response);
                                return extractFromTextContent(response, plainTextParser, validator);
                            });
                })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        context.cancelReservedTurn(reservation.getSequence());
                    }
                });
    }

    public static CompletableFuture<String> requestExtractedTextAsync(AiClient aiClient,
                                                                      Logger log,
                                                                      String subject,
                                                                      NodeConversationContext conversationContext,
                                                                      Runnable onApiCall,
                                                                      Function<String, String> textExtractor,
                                                                      Predicate<String> textValidator) {
        return requestChatAsync(aiClient, log, subject, conversationContext, onApiCall)
                .thenApply(response -> extractExtractedText(
                        response,
                        textExtractor != null ? textExtractor : AiRequestUtils::defaultTextExtractor,
                        textValidator != null ? textValidator : AiRequestUtils::isUsableExtractedText));
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
        return extractFromTextContent(textContent, plainTextParser, validator);
    }

    private static ExtractedTextResult extractTextResult(JsonNode rawResponse,
                                                         List<String> preferredPayloadFields,
                                                         Function<String, String> textExtractor,
                                                         Predicate<String> textValidator) {
        List<String> payloadFields = preferredPayloadFields != null
                ? preferredPayloadFields
                : NO_PREFERRED_PAYLOAD_FIELDS;
        JsonNode payload = JsonUtils.extractToolCallPayload(rawResponse);
        String extractedFromPayload = extractPreferredPayloadText(payload, payloadFields, textExtractor, textValidator);
        if (extractedFromPayload != null) {
            return new ExtractedTextResult(payload, extractedFromPayload, extractedFromPayload);
        }

        String textContent = JsonUtils.extractBestEffortTextFromResponse(rawResponse);
        String extractedText = extractExtractedText(textContent, textExtractor, textValidator);
        if (extractedText == null) {
            return null;
        }
        return new ExtractedTextResult(payload, extractedText, textContent != null ? textContent : extractedText);
    }

    /**
     * Unified text extraction pipeline: tries plain text parser (JSON by default),
     * then falls back to code block extraction (python/json/geogebra fenced blocks).
     */
    private static JsonNode extractFromTextContent(String textContent,
                                                   Function<String, JsonNode> plainTextParser,
                                                   Predicate<JsonNode> validator) {
        if (textContent == null || textContent.isBlank()) {
            return null;
        }
        JsonNode parsed = parsePlainTextResponse(textContent, plainTextParser);
        if (parsed != null && validator.test(parsed)) {
            return parsed;
        }
        String codeBlock = JsonUtils.extractCodeBlock(textContent);
        if (codeBlock != null && !codeBlock.isBlank() && plainTextParser != null) {
            parsed = plainTextParser.apply(codeBlock);
            if (parsed != null && validator.test(parsed)) {
                return parsed;
            }
        }
        return null;
    }

    private static String extractExtractedText(String textContent,
                                               Function<String, String> textExtractor,
                                               Predicate<String> validator) {
        if (textContent == null || textContent.isBlank()) {
            return null;
        }

        Function<String, String> extractor = textExtractor != null
                ? textExtractor
                : AiRequestUtils::defaultTextExtractor;
        Predicate<String> textValidator = validator != null
                ? validator
                : AiRequestUtils::isUsableExtractedText;

        String codeBlock = JsonUtils.extractCodeBlock(textContent);
        if (codeBlock != null && !codeBlock.isBlank()) {
            String extractedFromCodeBlock = extractor.apply(codeBlock);
            if (textValidator.test(extractedFromCodeBlock)) {
                return extractedFromCodeBlock;
            }
        }

        String extractedFromWholeText = extractor.apply(textContent);
        if (textValidator.test(extractedFromWholeText)) {
            return extractedFromWholeText;
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

    private static boolean isUsableExtractedText(String text) {
        return text != null && !text.isBlank();
    }

    private static String defaultTextExtractor(String text) {
        return text == null ? null : text.trim();
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

    private static String extractPreferredPayloadText(JsonNode payload,
                                                      List<String> preferredPayloadFields,
                                                      Function<String, String> textExtractor,
                                                      Predicate<String> textValidator) {
        if (payload == null || payload.isNull() || preferredPayloadFields == null || preferredPayloadFields.isEmpty()) {
            return null;
        }

        for (String fieldName : preferredPayloadFields) {
            if (fieldName == null || fieldName.isBlank() || !payload.has(fieldName)) {
                continue;
            }
            JsonNode fieldValue = payload.get(fieldName);
            if (fieldValue == null || fieldValue.isNull()) {
                continue;
            }

            String rawText = fieldValue.isTextual() ? fieldValue.asText() : fieldValue.toString();
            String extractedText = textExtractor.apply(rawText);
            if (textValidator.test(extractedText)) {
                return extractedText;
            }
        }
        return null;
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
        if (payload != null) {
            String toolTranscript = JsonUtils.buildToolCallTranscript(rawResponse);
            if (toolTranscript != null && !toolTranscript.isBlank()) {
                return toolTranscript;
            }
            return payload.toPrettyString();
        }
        String textContent = JsonUtils.extractBestEffortTextFromResponse(rawResponse);
        return textContent != null ? textContent : "";
    }

    private static void logExtractedTextDiagnostics(String source, ExtractedTextResult result) {
        if (result == null) {
            return;
        }
        AiTraceLogger.logTextSample(source, "assistant_transcript", result.getAssistantTranscript());
        AiTraceLogger.logTextSample(source, "extracted_text", result.getExtractedText());
    }

    /**
     * Simple chat request with rate-limit retry. For nodes that only need a
     * plain text response (no tool calling / JSON parsing).
     */
    public static CompletableFuture<String> requestChatAsync(AiClient aiClient,
                                                              Logger log,
                                                              String subject,
                                                              String userPrompt,
                                                              String systemPrompt,
                                                              Runnable onApiCall) {
        return withRateLimitRetry(
                        () -> aiClient.chatAsync(userPrompt, systemPrompt),
                        log, subject, onApiCall)
                .thenApply(response -> {
                    onApiCall.run();
                    return response;
                });
    }

    /**
     * Context-aware chat request with rate-limit retry.
     */
    public static CompletableFuture<String> requestChatAsync(AiClient aiClient,
                                                              Logger log,
                                                              String subject,
                                                              NodeConversationContext conversationContext,
                                                              Runnable onApiCall) {
        return withRateLimitRetry(
                        () -> aiClient.chatAsync(conversationContext),
                        log, subject, onApiCall)
                .thenApply(response -> {
                    onApiCall.run();
                    return response;
                });
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

    public static final class ExtractedTextResult {
        private final JsonNode payload;
        private final String extractedText;
        private final String assistantTranscript;

        public ExtractedTextResult(JsonNode payload, String extractedText, String assistantTranscript) {
            this.payload = payload;
            this.extractedText = extractedText;
            this.assistantTranscript = assistantTranscript == null ? "" : assistantTranscript;
        }

        public JsonNode getPayload() {
            return payload;
        }

        public String getExtractedText() {
            return extractedText;
        }

        public String getAssistantTranscript() {
            return assistantTranscript;
        }
    }
}
