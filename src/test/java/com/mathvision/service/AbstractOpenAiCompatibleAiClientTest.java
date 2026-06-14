package com.mathvision.service;

import com.mathvision.config.ModelConfig;
import com.mathvision.model.AiContentPart;
import com.mathvision.model.AiMessage;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

            assertEquals("ok after retry", content(client.chatAsync(textRequest("system", "hello")).join()));
            assertEquals(3, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatRetriesRateLimitWithRetryAfterHeader() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (attempts.get() < 3) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                respond(exchange, 429, "{\"error\":\"rate limited\"}");
                return;
            }
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok after rate limit\"}}]}");
        });

        try {
            ModelConfig config = testModelConfig(server);
            config.setTransientFailureRetries(0);
            config.setRateLimitRetries(3);
            config.setRateLimitBaseDelayMillis(1);
            config.setRateLimitMaxDelayMillis(1);
            AbstractOpenAiCompatibleAiClient client = new TestOpenAiCompatibleAiClient(config);

            assertEquals("ok after rate limit", content(client.chatAsync(textRequest("system", "hello")).join()));
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

            AiResponse response = client.chatAsync(textRequest("system", "hello")).join();

            assertNotNull(response.getError());
            assertTrue(response.getError().getMessage().contains("HTTP 400"));
            assertEquals(1, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatRequestIncludesMaxTokens() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            attempts.incrementAndGet();
            JsonNode request = JsonUtils.mapper().readTree(exchange.getRequestBody());
            assertEquals(256, request.path("max_tokens").asInt());
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });

        try {
            AbstractOpenAiCompatibleAiClient client = new TestOpenAiCompatibleAiClient(
                    testModelConfig(server));

            assertEquals("ok", content(client.chatAsync(textRequest("system", "hello")).join()));
            assertEquals(1, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void multimodalRequestIncludesMaxTokens() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            attempts.incrementAndGet();
            JsonNode request = JsonUtils.mapper().readTree(exchange.getRequestBody());
            assertEquals(256, request.path("max_tokens").asInt());
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });

        try {
            AbstractOpenAiCompatibleAiClient client = new TestOpenAiCompatibleAiClient(
                    testModelConfig(server));

            assertEquals("ok", content(client.chatAsync(new AiRequest(List.of(
                    AiMessage.system("system"),
                    AiMessage.user(List.of(AiContentPart.text("hello")))
            ), null)).join()));
            assertEquals(1, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timeoutRetryUsesIncreasedTimeoutAndEventuallySucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            int attempt = attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (attempt == 1) {
                sleepMillis(1_500);
            }
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok after timeout retry\"}}]}");
        });

        try {
            ModelConfig config = testModelConfig(server);
            config.setRequestTimeoutSeconds(1);
            config.setTimeoutRetryAttempts(1);
            config.setTimeoutRetryMultiplier(2.0);
            config.setMaxRequestTimeoutSeconds(3);
            AbstractOpenAiCompatibleAiClient client = new TestOpenAiCompatibleAiClient(config);

            assertEquals("ok after timeout retry", content(client.chatAsync(textRequest("system", "hello")).join()));
            assertEquals(2, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timeoutRetryFailsAfterConfiguredAttempts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            sleepMillis(1_500);
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"too late\"}}]}");
        });

        try {
            ModelConfig config = testModelConfig(server);
            config.setRequestTimeoutSeconds(1);
            config.setTimeoutRetryAttempts(0);
            AbstractOpenAiCompatibleAiClient client = new TestOpenAiCompatibleAiClient(config);

            AiResponse response = client.chatAsync(textRequest("system", "hello")).join();

            assertNotNull(response.getError());
            assertTrue(response.getError().getMessage().contains("request timed out")
                    || response.getError().getStackTrace().contains("request timed out"));
            assertEquals(1, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
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

    private static AiRequest textRequest(String system, String user) {
        return new AiRequest(List.of(
                AiMessage.system(system),
                AiMessage.user(List.of(AiContentPart.text(user)))
        ), null);
    }

    private static String content(AiResponse response) {
        assertNull(response.getError());
        return response.getContent();
    }

    private static final class TestOpenAiCompatibleAiClient
            extends AbstractOpenAiCompatibleAiClient {

        private TestOpenAiCompatibleAiClient(ModelConfig modelConfig) {
            super(LoggerFactory.getLogger(TestOpenAiCompatibleAiClient.class), modelConfig);
        }
    }
}
