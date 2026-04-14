package com.mathvision.service;

import com.mathvision.config.ModelConfig;
import com.mathvision.util.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractOpenAiCompatibleAiClientTest {

    @Test
    void extractTextContentSupportsSegmentedContent() {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode content = message.putArray("content");
        content.addObject().put("type", "text").put("text", "line 1");
        content.addObject().put("type", "text").put("text", "line 2");

        assertEquals("line 1\nline 2", AbstractOpenAiCompatibleAiClient.extractTextContent(response));
    }

    @Test
    void extractReasoningContentSupportsSegmentedReasoning() {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode reasoning = message.putArray("reasoning_content");
        reasoning.addObject().put("type", "text").put("text", "step 1");
        reasoning.addObject().put("type", "text").put("text", "step 2");

        assertEquals("step 1\nstep 2",
                AbstractOpenAiCompatibleAiClient.extractReasoningContent(response));
    }

    @Test
    void extractTextContentReturnsNullForBlankContent() {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        response.putArray("choices").addObject().putObject("message");

        assertNull(AbstractOpenAiCompatibleAiClient.extractTextContent(response));
    }

    @Test
    void isRetryableFailureRecognizesRstStreamAndIoErrors() {
        assertTrue(AbstractOpenAiCompatibleAiClient.isRetryableFailure(
                new RuntimeException("Received RST_STREAM: Internal error")));
        assertTrue(AbstractOpenAiCompatibleAiClient.isRetryableFailure(
                new IOException("Connection reset by peer")));
        assertFalse(AbstractOpenAiCompatibleAiClient.isRetryableFailure(
                new RuntimeException("Bad request")));
    }

    @Test
    void chatRetriesOnRetryableHttpStatus() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (attempts.get() < 3) {
                respond(exchange, 503, "{\"error\":\"busy\"}");
                return;
            }
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok after retry\"}}]}");
        });

        try {
            AbstractOpenAiCompatibleAiClient client = new TestOpenAiCompatibleAiClient(
                    testModelConfig(server));

            assertEquals("ok after retry", client.chat("hello", "system"));
            assertEquals(3, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatDoesNotRetryOnClientError() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 400, "{\"error\":\"bad request\"}");
        });

        try {
            AbstractOpenAiCompatibleAiClient client = new TestOpenAiCompatibleAiClient(
                    testModelConfig(server));

            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> client.chat("hello", "system"));

            assertTrue(error.getMessage().contains("HTTP 400"));
            assertEquals(1, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(com.sun.net.httpserver.HttpHandler handler)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int statusCode, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ModelConfig testModelConfig(HttpServer server) {
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setModel("test-model");
        modelConfig.setProvider("openai");
        modelConfig.setApiKeyEnv("PATH");
        modelConfig.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        modelConfig.setTemperature(0.1);
        modelConfig.setMaxOutputTokens(256);
        return modelConfig;
    }

    private static final class TestOpenAiCompatibleAiClient
            extends AbstractOpenAiCompatibleAiClient {

        private TestOpenAiCompatibleAiClient(ModelConfig modelConfig) {
            super(LoggerFactory.getLogger(TestOpenAiCompatibleAiClient.class), modelConfig);
        }
    }
}
