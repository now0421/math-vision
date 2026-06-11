package com.mathvision.service;

import com.mathvision.config.ModelConfig;
import com.mathvision.model.AiContentPart;
import com.mathvision.model.AiError;
import com.mathvision.model.AiMessage;
import com.mathvision.model.AiRequest;

import java.util.List;

final class AiClientSupport {

    private AiClientSupport() {
    }

    static String clientName(ModelConfig modelConfig) {
        return modelConfig.resolveProvider() + ":" + modelConfig.getModel();
    }

    static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + key + " is required");
        }
        return value;
    }

    static boolean hasToolSchema(AiRequest request) {
        return request != null
                && request.getToolsJson() != null
                && !request.getToolsJson().isBlank();
    }

    static boolean isTextOnly(List<AiContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return true;
        }
        for (AiContentPart part : parts) {
            if (part == null || !"text".equals(part.getType())) {
                return false;
            }
        }
        return true;
    }

    static String textContent(AiMessage message) {
        if (message == null) {
            return "";
        }
        return textContent(message.getParts());
    }

    static String textContent(List<AiContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AiContentPart part : parts) {
            if (part == null || !"text".equals(part.getType())) {
                continue;
            }
            if (part.getText() == null || part.getText().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(part.getText());
        }
        return sb.toString();
    }

    static AiError buildBaseError(Throwable cause, ModelConfig modelConfig) {
        AiError error = AiError.fromException(cause);
        error.setProvider(modelConfig.resolveProvider());
        error.setModel(modelConfig.getModel());
        error.setRateLimited(AiRetryPolicy.isRateLimitFailure(cause));
        error.setTransientFailure(AiRetryPolicy.isRetryableTransportFailure(cause)
                || isRetryableHttpMessage(cause));
        return error;
    }

    static boolean isRetryableHttpMessage(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return false;
        }
        String message = error.getMessage().toLowerCase();
        return message.contains("http 408")
                || message.contains("http 425")
                || message.contains("http 429")
                || message.matches(".*http 5\\d\\d.*");
    }
}
