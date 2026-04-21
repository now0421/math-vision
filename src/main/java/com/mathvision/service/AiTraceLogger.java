package com.mathvision.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;

/**
 * Shared trace logging helper for outbound AI requests and raw responses.
 */
final class AiTraceLogger {

    private static final Logger TRACE_LOG = LoggerFactory.getLogger("com.mathvision.ai.trace");

    private AiTraceLogger() {
    }

    static void logRequestSummary(String clientName,
                                  String model,
                                  int messageCount,
                                  int toolCount,
                                  String url,
                                  Logger log) {
        log.debug("{} request: model={}, messages={}, tools={}, url={}",
                clientName, model, messageCount, toolCount, url);
    }

    static void logRequestBody(String clientName, String body) {
        TRACE_LOG.debug("{} request body:\n{}", clientName, safe(body));
    }

    static void logRetryRequest(String clientName,
                                int attempt,
                                String model,
                                int messageCount,
                                int toolCount,
                                String url,
                                String body) {
        TRACE_LOG.debug("{} retry request: attempt={}, model={}, messages={}, tools={}, url={}, body=\n{}",
                clientName, attempt, model, messageCount, toolCount, url, safe(body));
    }

    static void logRequest(String clientName, String model, String url, String body) {
        TRACE_LOG.debug("{} request body: model={}, url={}\n{}",
                clientName, model, url, safe(body));
    }

    static void logResponse(String clientName, HttpResponse<String> response) {
        TRACE_LOG.debug("{} raw response: http={}, body=\n{}",
                clientName, response.statusCode(), safe(response.body()));
    }

    static String safe(String text) {
        return text == null ? "" : text;
    }

    static int arraySize(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }
}
