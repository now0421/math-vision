package com.mathvision.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.mathvision.config.ModelConfig;
import com.mathvision.model.AiError;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.model.AiToolCall;
import com.mathvision.service.AiClient;
import com.mathvision.service.AiRetryPolicy;
import com.mathvision.service.AiTraceLogger;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Shared async AI request helpers organized by expected business return format.
 */
public final class AiRequestUtils {

    private static final ModelConfig DEFAULT_RETRY_CONFIG = new ModelConfig();
    private static final List<String> NO_PREFERRED_PAYLOAD_FIELDS = List.of();

    private AiRequestUtils() {
    }

    // ---- Rate-limit / transient failure classification ----

    static boolean isRateLimitError(Throwable error) {
        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
        if (cause instanceof AiResponseException) {
            return ((AiResponseException) cause).getAiError().isRateLimited();
        }
        return AiRetryPolicy.isRateLimitFailure(cause);
    }

    static boolean isTransportOrTimeoutFailure(Throwable error) {
        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
        if (cause instanceof AiResponseException) {
            AiError aiError = ((AiResponseException) cause).getAiError();
            return aiError.isTransientFailure() || aiError.isRateLimited();
        }
        return AiRetryPolicy.isTimeoutFailure(cause)
                || AiRetryPolicy.isRetryableTransportFailure(cause)
                || isRetryableHttpFailure(cause);
    }

    private static boolean isRetryableHttpFailure(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return false;
        }
        String message = error.getMessage().toLowerCase();
        return message.contains("http 408")
                || message.contains("http 425")
                || message.contains("http 429")
                || message.matches(".*http 5\\d\\d.*");
    }

    private static long rateLimitDelayMillis(int attempt) {
        return AiRetryPolicy.rateLimitDelayMillis(DEFAULT_RETRY_CONFIG, attempt);
    }

    static <T> CompletableFuture<T> withRateLimitRetry(
            Supplier<CompletableFuture<T>> futureSupplier,
            Logger log,
            String subject,
            Runnable onApiCall) {
        return doRateLimitRetry(futureSupplier, log, subject, onApiCall, 0);
    }

    private static <T> CompletableFuture<T> requestWithOptionalRateLimitRetry(
            AiClient aiClient,
            Supplier<CompletableFuture<T>> futureSupplier,
            Logger log,
            String subject,
            Runnable onApiCall) {
        return futureSupplier.get();
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
            int maxRetries = AiRetryPolicy.rateLimitRetries(DEFAULT_RETRY_CONFIG);
            if (isRateLimitError(cause) && attempt < maxRetries) {
                long delay = rateLimitDelayMillis(attempt);
                log.warn("Rate limit hit for '{}' (attempt {}/{}), retrying in {} ms: {}",
                        subject, attempt + 1, maxRetries + 1, delay, cause.getMessage());
                safeRun(onApiCall);
                return new RetryOutcome<>(null, null, true);
            }
            return new RetryOutcome<>(null, error, false);
        });
        return handled.thenCompose((RetryOutcome<T> outcome) -> {
            if (outcome.retry) {
                long delay = rateLimitDelayMillis(attempt);
                CompletableFuture<Void> wait = CompletableFuture.runAsync(() -> {
                }, CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS));
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

    // ---- Format-oriented request APIs ----

    public static CompletableFuture<JsonObjectResult> requestJsonAsync(
            AiClient aiClient,
            Logger log,
        String subject,
        AiRequest request,
        JsonRequestOptions options) {
        JsonRequestOptions opts = JsonRequestOptions.defaults(options);
        return send(aiClient, log, subject, request, opts.onApiCall)
                .thenCompose(response -> {
                    JsonObjectResult result = toJsonObjectResult(response, opts);
                    if (result.getPayload() != null || !shouldRetryWithoutTools(request, response)) {
                        return CompletableFuture.completedFuture(result);
                    }
                    logToolExtractionFallback(log, subject, "JSON", result.getFailureReason());
                    return send(aiClient, log, fallbackSubject(subject), withoutTools(request), opts.onApiCall)
                            .thenApply(fallbackResponse -> {
                                JsonObjectResult fallbackResult = toJsonObjectResult(fallbackResponse, opts);
                                if (fallbackResult.getPayload() != null) {
                                    return fallbackResult;
                                }
                                return JsonObjectResult.failure(
                                        combineAssistantTranscripts(
                                                result.getAssistantTranscript(),
                                                fallbackResult.getAssistantTranscript()),
                                        combineFailureReasons(
                                                result.getFailureReason(),
                                                fallbackResult.getFailureReason()));
                            });
                });
    }

    public static CompletableFuture<CodeResult> requestCodeAsync(
            AiClient aiClient,
            Logger log,
        String subject,
        AiRequest request,
        CodeRequestOptions options) {
        CodeRequestOptions opts = CodeRequestOptions.defaults(options);
        return send(aiClient, log, subject, request, opts.onApiCall)
                .thenCompose(response -> {
                    CodeResult result = toCodeResult(response, opts, subject);
                    if (result.getCode() != null || !shouldRetryWithoutTools(request, response)) {
                        return CompletableFuture.completedFuture(result);
                    }
                    logToolExtractionFallback(log, subject, "code", result.getFailureReason());
                    return send(aiClient, log, fallbackSubject(subject), withoutTools(request), opts.onApiCall)
                            .thenApply(fallbackResponse -> {
                                CodeResult fallbackResult = toCodeResult(fallbackResponse, opts, subject);
                                if (fallbackResult.getCode() != null) {
                                    return fallbackResult;
                                }
                                return CodeResult.failure(
                                        combineAssistantTranscripts(
                                                result.getAssistantTranscript(),
                                                fallbackResult.getAssistantTranscript()),
                                        combineFailureReasons(
                                                result.getFailureReason(),
                                                fallbackResult.getFailureReason()));
                            });
                });
    }

    public static CompletableFuture<TextResult> requestTextAsync(
            AiClient aiClient,
            Logger log,
        String subject,
        AiRequest request,
        TextRequestOptions options) {
        TextRequestOptions opts = TextRequestOptions.defaults(options);
        return send(aiClient, log, subject, request, opts.onApiCall)
                .thenCompose(response -> {
                    TextResult result = toTextResult(response, opts, subject);
                    if (result.getText() != null || !shouldRetryWithoutTools(request, response)) {
                        return CompletableFuture.completedFuture(result);
                    }
                    logToolExtractionFallback(log, subject, "text", result.getFailureReason());
                    return send(aiClient, log, fallbackSubject(subject), withoutTools(request), opts.onApiCall)
                            .thenApply(fallbackResponse -> {
                                TextResult fallbackResult = toTextResult(fallbackResponse, opts, subject);
                                if (fallbackResult.getText() != null) {
                                    return fallbackResult;
                                }
                                return TextResult.failure(
                                        combineAssistantTranscripts(
                                                result.getAssistantTranscript(),
                                                fallbackResult.getAssistantTranscript()),
                                        combineFailureReasons(
                                                result.getFailureReason(),
                                                fallbackResult.getFailureReason()));
                            });
                });
    }

    private static JsonObjectResult toJsonObjectResult(AiResponse response, JsonRequestOptions opts) {
        if (response == null) {
            return JsonObjectResult.failure("", "AI response was null");
        }

        JsonObjectExtractionResult extraction = extractJsonObjectResult(
                response,
                opts.plainTextParser,
                opts.payloadValidator);
        JsonNode data = extraction.payload();
        if (data != null) {
            return new JsonObjectResult(data, buildAssistantTranscript(response, data));
        }
        if (response.getError() != null) {
            return JsonObjectResult.failureFromError(
                    buildAssistantTranscript(response, null),
                    response.getError());
        }
        return JsonObjectResult.failure(
                buildAssistantTranscript(response, null),
                extraction.failureReason());
    }

    private static CodeResult toCodeResult(AiResponse response, CodeRequestOptions opts, String subject) {
        if (response == null) {
            return CodeResult.failure("", "AI response was null");
        }

        TextExtractionResult extraction = extractCodeResult(
                response,
                opts.preferredPayloadFields,
                opts.codeExtractor,
                opts.codeValidator);
        if (extraction == null || extraction.text() == null) {
            if (response.getError() != null) {
                return CodeResult.failureFromError(
                        buildAssistantTranscript(response, null),
                        response.getError());
            }
            return CodeResult.failure(
                    buildAssistantTranscript(response, null),
                    extraction != null ? extraction.failureReason() : "No usable code found in AI response");
        }
        logTextDiagnostics("code:" + subject,
                extraction.assistantTranscript(),
                extraction.text());
        return new CodeResult(
                extraction.payload(),
                extraction.text(),
                extraction.assistantTranscript());
    }

    private static TextResult toTextResult(AiResponse response, TextRequestOptions opts, String subject) {
        if (response == null) {
            return TextResult.failure("", "AI response was null");
        }

        TextExtractionResult extraction = extractTextResult(
                response,
                opts.preferredPayloadFields,
                opts.textExtractor,
                opts.textValidator);
        if (extraction == null || extraction.text() == null) {
            if (response.getError() != null) {
                return TextResult.failureFromError(
                        buildAssistantTranscript(response, null),
                        response.getError());
            }
            return TextResult.failure(
                    buildAssistantTranscript(response, null),
                    extraction != null ? extraction.failureReason() : "No usable text found in AI response");
        }
        logTextDiagnostics("text:" + subject,
                extraction.assistantTranscript(),
                extraction.text());
        return new TextResult(
                extraction.payload(),
                extraction.text(),
                extraction.assistantTranscript());
    }

    private static boolean shouldRetryWithoutTools(AiRequest request, AiResponse response) {
        return hasTools(request)
                && response != null
                && response.getError() == null;
    }

    private static boolean hasTools(AiRequest request) {
        return request != null
                && request.getToolsJson() != null
                && !request.getToolsJson().isBlank();
    }

    private static AiRequest withoutTools(AiRequest request) {
        return AiRequest.of(request != null ? request.getMessages() : List.of());
    }

    private static String fallbackSubject(String subject) {
        return subject + " plain-text fallback";
    }

    private static void logToolExtractionFallback(
            Logger log,
            String subject,
            String format,
            String failureReason) {
        if (log != null) {
            log.debug("  Tool response did not contain usable {} for '{}', retrying without tools: {}",
                    format, subject, failureReason);
        }
    }

    private static String combineAssistantTranscripts(String firstTranscript, String fallbackTranscript) {
        boolean hasFirst = firstTranscript != null && !firstTranscript.isBlank();
        boolean hasFallback = fallbackTranscript != null && !fallbackTranscript.isBlank();
        if (hasFirst && hasFallback) {
            return firstTranscript + "\n\n[plain_text_retry]\n" + fallbackTranscript;
        }
        if (hasFirst) {
            return firstTranscript;
        }
        return hasFallback ? fallbackTranscript : "";
    }

    private static String combineFailureReasons(String firstReason, String fallbackReason) {
        boolean hasFirst = firstReason != null && !firstReason.isBlank();
        boolean hasFallback = fallbackReason != null && !fallbackReason.isBlank();
        if (hasFirst && hasFallback) {
            return "Tool response extraction failed: " + firstReason
                    + "; plain-text retry failed: " + fallbackReason;
        }
        if (hasFirst) {
            return "Tool response extraction failed: " + firstReason;
        }
        return hasFallback ? "plain-text retry failed: " + fallbackReason : "AI response did not contain usable output";
    }

    private static CompletableFuture<AiResponse> send(
            AiClient aiClient,
            Logger log,
            String subject,
            AiRequest request,
            Runnable onApiCall) {
        if (aiClient == null) {
            return CompletableFuture.completedFuture(
                    AiResponse.failure(AiError.fromException(
                            new IllegalStateException("AI client was null"))));
        }

        CompletableFuture<AiResponse> responseFuture;
        try {
            responseFuture = requestWithOptionalRateLimitRetry(
                    aiClient,
                    () -> aiClient.chatAsync(request),
                    log,
                    subject,
                    onApiCall);
        } catch (Throwable error) {
            safeRun(onApiCall);
            return CompletableFuture.completedFuture(
                    AiResponse.failure(AiError.fromException(
                            ConcurrencyUtils.unwrapCompletionException(error))));
        }

        return responseFuture.handle((response, error) -> {
            safeRun(onApiCall);
            if (error != null) {
                return AiResponse.failure(AiError.fromException(
                        ConcurrencyUtils.unwrapCompletionException(error)));
            }
            return response;
        });
    }

    private static void safeRun(Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }

    // ---- Extraction ----

    private static JsonObjectExtractionResult extractJsonObjectResult(
            AiResponse response,
            Function<String, JsonNode> plainTextParser,
            Predicate<JsonNode> payloadValidator) {
        Predicate<JsonNode> validator = payloadValidator != null
                ? payloadValidator
                : AiRequestUtils::isUsablePayload;

        JsonNode toolPayload = extractToolPayload(response);
        if (validator.test(toolPayload)) {
            return JsonObjectExtractionResult.success(toolPayload);
        }

        String textContent = extractTextContent(response);
        JsonObjectExtractionResult textResult =
                extractFromTextContentResult(textContent, plainTextParser, validator);
        if (textResult.payload() != null) {
            return textResult;
        }

        String reason = textResult.failureReason();
        if (toolPayload != null && !toolPayload.isNull()) {
            String toolReason = "Tool-call payload was rejected by validator";
            reason = reason == null || reason.isBlank()
                    ? toolReason
                    : toolReason + "; " + reason;
        }
        if (reason == null || reason.isBlank()) {
            reason = toolPayload == null
                    ? "No tool-call payload and no parseable JSON object found in message content"
                    : "Tool-call payload was rejected by validator and no parseable JSON object found in message content";
        }
        return JsonObjectExtractionResult.failure(reason);
    }

    private static TextExtractionResult extractCodeResult(
            AiResponse response,
            List<String> preferredPayloadFields,
            Function<String, String> codeExtractor,
            Predicate<String> codeValidator) {
        return extractTextLikeResult(
                response,
                preferredPayloadFields,
                codeExtractor,
                codeValidator,
                true,
                "code");
    }

    private static TextExtractionResult extractTextResult(
            AiResponse response,
            List<String> preferredPayloadFields,
            Function<String, String> textExtractor,
            Predicate<String> textValidator) {
        return extractTextLikeResult(
                response,
                preferredPayloadFields,
                textExtractor,
                textValidator,
                false,
                "text");
    }

    private static TextExtractionResult extractTextLikeResult(
            AiResponse response,
            List<String> preferredPayloadFields,
            Function<String, String> textExtractor,
            Predicate<String> textValidator,
            boolean preferFencedCodeBlock,
            String formatLabel) {
        List<String> payloadFields = preferredPayloadFields != null
                ? preferredPayloadFields
                : NO_PREFERRED_PAYLOAD_FIELDS;
        Function<String, String> extractor = textExtractor != null
                ? textExtractor
                : AiRequestUtils::defaultTextExtractor;
        Predicate<String> validator = textValidator != null
                ? textValidator
                : AiRequestUtils::isUsableExtractedText;

        JsonNode payload = extractToolPayload(response);
        TextExtractionAttempt payloadAttempt = extractPreferredPayloadText(
                payload,
                payloadFields,
                extractor,
                validator,
                formatLabel);
        if (payloadAttempt.text() != null) {
            return TextExtractionResult.success(
                    payload,
                    payloadAttempt.text(),
                    buildAssistantTranscript(response, payload));
        }

        String textContent = extractTextContent(response);
        TextExtractionAttempt contentAttempt = preferFencedCodeBlock
                ? extractCodeFromContent(textContent, extractor, validator)
                : extractPlainTextFromContent(textContent, extractor, validator);
        if (contentAttempt.text() != null) {
            return TextExtractionResult.success(
                    payload,
                    contentAttempt.text(),
                    textContent != null ? textContent : contentAttempt.text());
        }

        return TextExtractionResult.failure(payload, combineTextFailureReasons(
                payloadAttempt.failureReason(),
                contentAttempt.failureReason(),
                formatLabel));
    }

    private static JsonObjectExtractionResult extractFromTextContentResult(
            String textContent,
            Function<String, JsonNode> plainTextParser,
            Predicate<JsonNode> validator) {
        if (textContent == null || textContent.isBlank()) {
            return JsonObjectExtractionResult.failure("message.content was empty");
        }

        if (plainTextParser == null) {
            JsonUtils.JsonObjectExtractionResult extraction =
                    JsonUtils.extractJsonObjectResult("message.content", textContent);
            JsonNode parsed = extraction.getPayload();
            if (parsed != null && validator.test(parsed)) {
                return JsonObjectExtractionResult.success(parsed);
            }
            return JsonObjectExtractionResult.failure(bestFailureReason(
                    extraction.getFailureReason(),
                    parsed != null ? "Parsed JSON payload was rejected by validator" : null,
                    "No parseable JSON object found in message content"));
        }

        JsonObjectExtractionResult parsedResult =
                parseCustomPlainTextResponseResult("message.content", textContent, plainTextParser);
        JsonNode parsed = parsedResult.payload();
        if (parsed != null && validator.test(parsed)) {
            return JsonObjectExtractionResult.success(parsed);
        }

        String codeBlock = JsonUtils.extractCodeBlock(textContent);
        JsonObjectExtractionResult codeBlockResult = null;
        if (codeBlock != null && !codeBlock.isBlank()) {
            codeBlockResult = parseCustomPlainTextResponseResult(
                    "message.content code block",
                    codeBlock,
                    plainTextParser);
            parsed = codeBlockResult.payload();
            if (parsed != null && validator.test(parsed)) {
                return JsonObjectExtractionResult.success(parsed);
            }
        }

        return JsonObjectExtractionResult.failure(bestFailureReason(
                codeBlockResult != null ? codeBlockResult.failureReason() : null,
                parsedResult.failureReason(),
                parsed != null ? "Parsed JSON payload was rejected by validator" : null,
                "No parseable JSON object found in message content"));
    }

    private static JsonObjectExtractionResult parseCustomPlainTextResponseResult(
            String source,
            String response,
            Function<String, JsonNode> plainTextParser) {
        try {
            JsonNode parsed = plainTextParser.apply(response);
            if (parsed != null) {
                return JsonObjectExtractionResult.success(parsed);
            }
            return JsonObjectExtractionResult.failure(source + " did not contain a parseable JSON object");
        } catch (RuntimeException e) {
            return JsonObjectExtractionResult.failure(
                    source + " custom JSON parser failed: " + e.getMessage());
        }
    }

    private static TextExtractionAttempt extractCodeFromContent(
            String textContent,
            Function<String, String> textExtractor,
            Predicate<String> validator) {
        if (textContent == null || textContent.isBlank()) {
            return TextExtractionAttempt.failure("message.content was empty");
        }

        String codeBlock = JsonUtils.extractCodeBlock(textContent);
        TextExtractionAttempt codeBlockAttempt = null;
        if (hasMarkdownCodeFence(textContent) && codeBlock != null && !codeBlock.isBlank()) {
            codeBlockAttempt = extractAndValidateText(
                    "message.content code block",
                    codeBlock,
                    textExtractor,
                    validator,
                    "code");
            if (codeBlockAttempt.text() != null) {
                return codeBlockAttempt;
            }
        }

        TextExtractionAttempt wholeTextAttempt = extractAndValidateText(
                "message.content",
                textContent,
                textExtractor,
                validator,
                "code");
        if (wholeTextAttempt.text() != null) {
            return wholeTextAttempt;
        }
        return TextExtractionAttempt.failure(bestFailureReason(
                codeBlockAttempt != null ? codeBlockAttempt.failureReason() : null,
                wholeTextAttempt.failureReason(),
                "message.content did not contain usable code"));
    }

    private static boolean hasMarkdownCodeFence(String text) {
        return text != null && (text.contains("```") || text.contains("~~~"));
    }

    private static TextExtractionAttempt extractPlainTextFromContent(
            String textContent,
            Function<String, String> textExtractor,
            Predicate<String> validator) {
        if (textContent == null || textContent.isBlank()) {
            return TextExtractionAttempt.failure("message.content was empty");
        }

        return extractAndValidateText(
                "message.content",
                textContent,
                textExtractor,
                validator,
                "text");
    }

    private static TextExtractionAttempt extractPreferredPayloadText(
            JsonNode payload,
            List<String> preferredPayloadFields,
            Function<String, String> textExtractor,
            Predicate<String> textValidator,
            String formatLabel) {
        if (payload == null || payload.isNull()) {
            return TextExtractionAttempt.failure("No tool-call payload");
        }
        if (preferredPayloadFields == null || preferredPayloadFields.isEmpty()) {
            return TextExtractionAttempt.failure(
                    "Tool-call payload was present, but no preferred payload fields were configured");
        }

        List<String> missingFields = new ArrayList<>();
        List<String> rejectedFields = new ArrayList<>();
        for (String fieldName : preferredPayloadFields) {
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }
            if (!payload.has(fieldName)) {
                missingFields.add(fieldName);
                continue;
            }
            JsonNode fieldValue = payload.get(fieldName);
            if (fieldValue == null || fieldValue.isNull()) {
                rejectedFields.add(fieldName + " was null");
                continue;
            }

            String rawText = fieldValue.isTextual() ? fieldValue.asText() : fieldValue.toString();
            TextExtractionAttempt attempt = extractAndValidateText(
                    "Tool-call payload field '" + fieldName + "'",
                    rawText,
                    textExtractor,
                    textValidator,
                    formatLabel);
            if (attempt.text() != null) {
                return attempt;
            }
            rejectedFields.add(fieldName + " rejected: " + attempt.failureReason());
        }
        return TextExtractionAttempt.failure(buildPayloadFieldFailureReason(
                preferredPayloadFields,
                missingFields,
                rejectedFields));
    }

    private static JsonNode extractToolPayload(AiResponse response) {
        if (response == null) {
            return null;
        }
        if (response.getToolCalls() != null) {
            for (AiToolCall toolCall : response.getToolCalls()) {
                if (toolCall == null) {
                    continue;
                }
                if (toolCall.getArguments() != null && !toolCall.getArguments().isNull()) {
                    return toolCall.getArguments();
                }
                if (toolCall.getArgumentsText() != null && !toolCall.getArgumentsText().isBlank()) {
                    JsonNode parsed = JsonUtils.parseTreeBestEffort(toolCall.getArgumentsText());
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }
        return response.getRaw() != null ? JsonUtils.extractToolCallPayload(response.getRaw()) : null;
    }

    private static String extractTextContent(AiResponse response) {
        if (response == null) {
            return "";
        }
        if (response.getContent() != null && !response.getContent().isBlank()) {
            return response.getContent();
        }
        return response.getRaw() != null
                ? JsonUtils.extractBestEffortTextFromResponse(response.getRaw())
                : "";
    }

    // ---- Failure and diagnostics ----

    private static String buildFailureReason(AiError error) {
        if (error == null) {
            return "AI request failed";
        }
        StringBuilder sb = new StringBuilder("AI request failed");
        appendFailurePart(sb, "provider", error.getProvider());
        appendFailurePart(sb, "model", error.getModel());
        if (error.getHttpStatus() != null) {
            appendFailurePart(sb, "http_status", String.valueOf(error.getHttpStatus()));
        }
        appendFailurePart(sb, "request_id", error.getRequestId());
        appendFailurePart(sb, "message", error.getMessage());
        appendFailurePart(sb, "exception", error.getExceptionClass());
        if (error.isRateLimited()) {
            appendFailurePart(sb, "rate_limited", "true");
        }
        if (error.isTransientFailure()) {
            appendFailurePart(sb, "transient", "true");
        }
        appendFailurePart(sb, "response_body", error.getResponseBody());
        return sb.toString();
    }

    private static void appendFailurePart(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("; ").append(key).append("=").append(value);
    }

    private static TextExtractionAttempt extractAndValidateText(
            String source,
            String rawText,
            Function<String, String> textExtractor,
            Predicate<String> textValidator,
            String formatLabel) {
        String label = source == null || source.isBlank() ? "text candidate" : source;
        if (rawText == null || rawText.isBlank()) {
            return TextExtractionAttempt.failure(label + " was empty");
        }
        String extractedText;
        try {
            extractedText = textExtractor.apply(rawText);
        } catch (RuntimeException e) {
            return TextExtractionAttempt.failure(label + " "
                    + formatLabel
                    + " extractor failed: "
                    + exceptionMessage(e));
        }
        if (textValidator.test(extractedText)) {
            return TextExtractionAttempt.success(extractedText);
        }
        return TextExtractionAttempt.failure(label + " "
                + formatLabel
                + " validator rejected extracted "
                + formatLabel
                + ": "
                + describeTextCandidate(extractedText));
    }

    private static String buildPayloadFieldFailureReason(
            List<String> preferredPayloadFields,
            List<String> missingFields,
            List<String> rejectedFields) {
        List<String> reasons = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();
        if (preferredPayloadFields != null) {
            for (String field : preferredPayloadFields) {
                if (field != null && !field.isBlank()) {
                    fieldNames.add(field);
                }
            }
        }
        if (fieldNames.isEmpty()) {
            reasons.add("Tool-call payload was present, but no preferred payload fields were configured");
        } else {
            reasons.add("Tool-call payload did not contain usable preferred fields: "
                    + String.join(", ", fieldNames));
        }
        if (missingFields != null && !missingFields.isEmpty()) {
            reasons.add("missing fields: " + String.join(", ", missingFields));
        }
        if (rejectedFields != null && !rejectedFields.isEmpty()) {
            reasons.add("rejected fields: " + String.join("; ", rejectedFields));
        }
        return String.join("; ", reasons);
    }

    private static String combineTextFailureReasons(
            String payloadFailureReason,
            String contentFailureReason,
            String formatLabel) {
        List<String> reasons = new ArrayList<>();
        if (payloadFailureReason != null && !payloadFailureReason.isBlank()) {
            reasons.add(payloadFailureReason);
        }
        if (contentFailureReason != null && !contentFailureReason.isBlank()) {
            reasons.add(contentFailureReason);
        }
        if (reasons.isEmpty()) {
            reasons.add("AI response did not contain usable " + formatLabel);
        }
        return String.join("; ", reasons);
    }

    private static String describeTextCandidate(String text) {
        if (text == null) {
            return "null";
        }
        if (text.isBlank()) {
            return "blank text";
        }
        String trimmed = text.trim()
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ');
        int maxLength = 120;
        if (trimmed.length() > maxLength) {
            trimmed = trimmed.substring(0, maxLength) + "...";
        }
        return "'" + trimmed + "'";
    }

    private static String exceptionMessage(RuntimeException e) {
        if (e == null) {
            return "RuntimeException";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String bestFailureReason(String... reasons) {
        if (reasons == null) {
            return null;
        }
        for (String reason : reasons) {
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
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

    private static String buildToolCallTranscript(AiResponse response) {
        if (response == null || response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
            return "";
        }
        AiToolCall toolCall = response.getToolCalls().get(0);
        if (toolCall == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[tool_call]").append("\n");
        if (toolCall.getName() != null && !toolCall.getName().isBlank()) {
            sb.append("name: ").append(toolCall.getName()).append("\n");
        }
        if (toolCall.getArguments() != null) {
            sb.append("arguments:").append("\n");
            sb.append(toolCall.getArguments().toPrettyString()).append("\n");
        } else if (toolCall.getArgumentsText() != null && !toolCall.getArgumentsText().isBlank()) {
            sb.append("arguments:").append("\n");
            sb.append(toolCall.getArgumentsText()).append("\n");
        }
        String textContent = extractTextContent(response);
        if (textContent != null && !textContent.isBlank()) {
            sb.append("assistant_text:").append("\n");
            sb.append(textContent.trim()).append("\n");
        }
        sb.append("[/tool_call]");
        return sb.toString();
    }

    private static String buildAssistantTranscript(AiResponse response, JsonNode payload) {
        String rawToolTranscript = response != null && response.getRaw() != null
                ? JsonUtils.buildToolCallTranscript(response.getRaw())
                : "";
        if (rawToolTranscript != null && !rawToolTranscript.isBlank()) {
            return rawToolTranscript;
        }

        String responseToolTranscript = buildToolCallTranscript(response);
        if (responseToolTranscript != null && !responseToolTranscript.isBlank()) {
            return responseToolTranscript;
        }

        String textContent = extractTextContent(response);
        if (textContent != null && !textContent.isBlank()) {
            return textContent;
        }

        return payload != null ? payload.toPrettyString() : "";
    }

    private static void logTextDiagnostics(String source, String assistantTranscript, String extractedText) {
        if (normalizeForDiagnosticCompare(assistantTranscript)
                .equals(normalizeForDiagnosticCompare(extractedText))) {
            AiTraceLogger.logTextSample(source, "extracted_text", extractedText);
            return;
        }
        AiTraceLogger.logTextSample(source, "assistant_transcript", assistantTranscript);
        AiTraceLogger.logTextSample(source, "extracted_text", extractedText);
    }

    private static String normalizeForDiagnosticCompare(String text) {
        if (text == null) {
            return "";
        }
        return text.trim();
    }

    // ---- Options ----

    public static final class JsonRequestOptions {
        private final Runnable onApiCall;
        private final Function<String, JsonNode> plainTextParser;
        private final Predicate<JsonNode> payloadValidator;

        private JsonRequestOptions(Builder builder) {
            this.onApiCall = builder.onApiCall;
            this.plainTextParser = builder.plainTextParser;
            this.payloadValidator = builder.payloadValidator != null
                    ? builder.payloadValidator
                    : AiRequestUtils::isUsablePayload;
        }

        public static JsonRequestOptions defaults() {
            return builder().build();
        }

        public static JsonRequestOptions of(Runnable onApiCall) {
            return builder().onApiCall(onApiCall).build();
        }

        public static Builder builder() {
            return new Builder();
        }

        private static JsonRequestOptions defaults(JsonRequestOptions options) {
            return options != null ? options : defaults();
        }

        public static final class Builder {
            private Runnable onApiCall;
            private Function<String, JsonNode> plainTextParser;
            private Predicate<JsonNode> payloadValidator;

            public Builder onApiCall(Runnable onApiCall) {
                this.onApiCall = onApiCall;
                return this;
            }

            public Builder plainTextParser(Function<String, JsonNode> plainTextParser) {
                this.plainTextParser = plainTextParser;
                return this;
            }

            public Builder payloadValidator(Predicate<JsonNode> payloadValidator) {
                this.payloadValidator = payloadValidator;
                return this;
            }

            public JsonRequestOptions build() {
                return new JsonRequestOptions(this);
            }
        }
    }

    public static final class CodeRequestOptions {
        private final Runnable onApiCall;
        private final List<String> preferredPayloadFields;
        private final Function<String, String> codeExtractor;
        private final Predicate<String> codeValidator;

        private CodeRequestOptions(Builder builder) {
            this.onApiCall = builder.onApiCall;
            this.preferredPayloadFields = immutableFields(builder.preferredPayloadFields);
            this.codeExtractor = builder.codeExtractor != null
                    ? builder.codeExtractor
                    : AiRequestUtils::defaultTextExtractor;
            this.codeValidator = builder.codeValidator != null
                    ? builder.codeValidator
                    : AiRequestUtils::isUsableExtractedText;
        }

        public static CodeRequestOptions defaults() {
            return builder().build();
        }

        public static CodeRequestOptions of(Runnable onApiCall) {
            return builder().onApiCall(onApiCall).build();
        }

        public static Builder builder() {
            return new Builder();
        }

        private static CodeRequestOptions defaults(CodeRequestOptions options) {
            return options != null ? options : defaults();
        }

        public static final class Builder {
            private Runnable onApiCall;
            private List<String> preferredPayloadFields;
            private Function<String, String> codeExtractor;
            private Predicate<String> codeValidator;

            public Builder onApiCall(Runnable onApiCall) {
                this.onApiCall = onApiCall;
                return this;
            }

            public Builder preferredPayloadFields(List<String> preferredPayloadFields) {
                this.preferredPayloadFields = preferredPayloadFields;
                return this;
            }

            public Builder codeExtractor(Function<String, String> codeExtractor) {
                this.codeExtractor = codeExtractor;
                return this;
            }

            public Builder codeValidator(Predicate<String> codeValidator) {
                this.codeValidator = codeValidator;
                return this;
            }

            public CodeRequestOptions build() {
                return new CodeRequestOptions(this);
            }
        }
    }

    public static final class TextRequestOptions {
        private final Runnable onApiCall;
        private final List<String> preferredPayloadFields;
        private final Function<String, String> textExtractor;
        private final Predicate<String> textValidator;

        private TextRequestOptions(Builder builder) {
            this.onApiCall = builder.onApiCall;
            this.preferredPayloadFields = immutableFields(builder.preferredPayloadFields);
            this.textExtractor = builder.textExtractor != null
                    ? builder.textExtractor
                    : AiRequestUtils::defaultTextExtractor;
            this.textValidator = builder.textValidator != null
                    ? builder.textValidator
                    : AiRequestUtils::isUsableExtractedText;
        }

        public static TextRequestOptions defaults() {
            return builder().build();
        }

        public static TextRequestOptions of(Runnable onApiCall) {
            return builder().onApiCall(onApiCall).build();
        }

        public static Builder builder() {
            return new Builder();
        }

        private static TextRequestOptions defaults(TextRequestOptions options) {
            return options != null ? options : defaults();
        }

        public static final class Builder {
            private Runnable onApiCall;
            private List<String> preferredPayloadFields;
            private Function<String, String> textExtractor;
            private Predicate<String> textValidator;

            public Builder onApiCall(Runnable onApiCall) {
                this.onApiCall = onApiCall;
                return this;
            }

            public Builder preferredPayloadFields(List<String> preferredPayloadFields) {
                this.preferredPayloadFields = preferredPayloadFields;
                return this;
            }

            public Builder textExtractor(Function<String, String> textExtractor) {
                this.textExtractor = textExtractor;
                return this;
            }

            public Builder textValidator(Predicate<String> textValidator) {
                this.textValidator = textValidator;
                return this;
            }

            public TextRequestOptions build() {
                return new TextRequestOptions(this);
            }
        }
    }

    private static List<String> immutableFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return NO_PREFERRED_PAYLOAD_FIELDS;
        }
        return Collections.unmodifiableList(new ArrayList<>(fields));
    }

    // ---- Results ----

    public static final class JsonObjectResult {
        private final JsonNode payload;
        private final String assistantTranscript;
        private final String failureReason;
        private final AiError error;

        public JsonObjectResult(JsonNode payload, String assistantTranscript) {
            this(payload, assistantTranscript, "", null);
        }

        private JsonObjectResult(JsonNode payload, String assistantTranscript, String failureReason) {
            this(payload, assistantTranscript, failureReason, null);
        }

        private JsonObjectResult(JsonNode payload, String assistantTranscript, String failureReason, AiError error) {
            this.payload = payload;
            this.assistantTranscript = assistantTranscript == null ? "" : assistantTranscript;
            this.failureReason = failureReason == null ? "" : failureReason;
            this.error = error;
        }

        public static JsonObjectResult failure(String assistantTranscript, String failureReason) {
            return new JsonObjectResult(null, assistantTranscript, failureReason);
        }

        private static JsonObjectResult failureFromError(String assistantTranscript, AiError error) {
            return new JsonObjectResult(null, assistantTranscript, buildFailureReason(error), error);
        }

        public JsonNode getPayload() {
            return payload;
        }

        public String getAssistantTranscript() {
            return assistantTranscript;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public AiError getError() {
            return error;
        }
    }

    public static final class CodeResult {
        private final JsonNode payload;
        private final String code;
        private final String assistantTranscript;
        private final String failureReason;
        private final AiError error;

        public CodeResult(JsonNode payload, String code, String assistantTranscript) {
            this(payload, code, assistantTranscript, "", null);
        }

        private CodeResult(JsonNode payload, String code, String assistantTranscript, String failureReason) {
            this(payload, code, assistantTranscript, failureReason, null);
        }

        private CodeResult(
                JsonNode payload,
                String code,
                String assistantTranscript,
                String failureReason,
                AiError error) {
            this.payload = payload;
            this.code = code;
            this.assistantTranscript = assistantTranscript == null ? "" : assistantTranscript;
            this.failureReason = failureReason == null ? "" : failureReason;
            this.error = error;
        }

        public static CodeResult failure(String assistantTranscript, String failureReason) {
            return new CodeResult(null, null, assistantTranscript, failureReason);
        }

        private static CodeResult failureFromError(String assistantTranscript, AiError error) {
            return new CodeResult(null, null, assistantTranscript, buildFailureReason(error), error);
        }

        public JsonNode getPayload() {
            return payload;
        }

        public String getCode() {
            return code;
        }

        public String getAssistantTranscript() {
            return assistantTranscript;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public AiError getError() {
            return error;
        }
    }

    public static final class TextResult {
        private final JsonNode payload;
        private final String text;
        private final String assistantTranscript;
        private final String failureReason;
        private final AiError error;

        public TextResult(JsonNode payload, String text, String assistantTranscript) {
            this(payload, text, assistantTranscript, "", null);
        }

        private TextResult(JsonNode payload, String text, String assistantTranscript, String failureReason) {
            this(payload, text, assistantTranscript, failureReason, null);
        }

        private TextResult(
                JsonNode payload,
                String text,
                String assistantTranscript,
                String failureReason,
                AiError error) {
            this.payload = payload;
            this.text = text;
            this.assistantTranscript = assistantTranscript == null ? "" : assistantTranscript;
            this.failureReason = failureReason == null ? "" : failureReason;
            this.error = error;
        }

        public static TextResult failure(String assistantTranscript, String failureReason) {
            return new TextResult(null, null, assistantTranscript, failureReason);
        }

        private static TextResult failureFromError(String assistantTranscript, AiError error) {
            return new TextResult(null, null, assistantTranscript, buildFailureReason(error), error);
        }

        public JsonNode getPayload() {
            return payload;
        }

        public String getText() {
            return text;
        }

        public String getAssistantTranscript() {
            return assistantTranscript;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public AiError getError() {
            return error;
        }
    }

    private static final class JsonObjectExtractionResult {
        private final JsonNode payload;
        private final String failureReason;

        private JsonObjectExtractionResult(JsonNode payload, String failureReason) {
            this.payload = payload;
            this.failureReason = failureReason == null ? "" : failureReason;
        }

        private static JsonObjectExtractionResult success(JsonNode payload) {
            return new JsonObjectExtractionResult(payload, "");
        }

        private static JsonObjectExtractionResult failure(String failureReason) {
            return new JsonObjectExtractionResult(null, failureReason);
        }

        private JsonNode payload() {
            return payload;
        }

        private String failureReason() {
            return failureReason;
        }
    }

    private static final class TextExtractionResult {
        private final JsonNode payload;
        private final String text;
        private final String assistantTranscript;
        private final String failureReason;

        private TextExtractionResult(
                JsonNode payload,
                String text,
                String assistantTranscript,
                String failureReason) {
            this.payload = payload;
            this.text = text;
            this.assistantTranscript = assistantTranscript == null ? "" : assistantTranscript;
            this.failureReason = failureReason == null ? "" : failureReason;
        }

        private static TextExtractionResult success(JsonNode payload, String text, String assistantTranscript) {
            return new TextExtractionResult(payload, text, assistantTranscript, "");
        }

        private static TextExtractionResult failure(JsonNode payload, String failureReason) {
            return new TextExtractionResult(payload, null, "", failureReason);
        }

        private JsonNode payload() {
            return payload;
        }

        private String text() {
            return text;
        }

        private String assistantTranscript() {
            return assistantTranscript;
        }

        private String failureReason() {
            return failureReason;
        }
    }

    private static final class TextExtractionAttempt {
        private final String text;
        private final String failureReason;

        private TextExtractionAttempt(String text, String failureReason) {
            this.text = text;
            this.failureReason = failureReason == null ? "" : failureReason;
        }

        private static TextExtractionAttempt success(String text) {
            return new TextExtractionAttempt(text, "");
        }

        private static TextExtractionAttempt failure(String failureReason) {
            return new TextExtractionAttempt(null, failureReason);
        }

        private String text() {
            return text;
        }

        private String failureReason() {
            return failureReason;
        }
    }

    private static final class AiResponseException extends RuntimeException {
        private final AiError aiError;

        private AiResponseException(AiError aiError) {
            super(aiError != null && aiError.getMessage() != null
                    ? aiError.getMessage()
                    : "AI request failed");
            this.aiError = aiError != null ? aiError : new AiError();
        }

        private AiError getAiError() {
            return aiError;
        }
    }
}
