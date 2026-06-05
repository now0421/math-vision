package com.mathvision.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Model-level settings loaded from JSON configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ModelConfig {

    public static final int DEFAULT_MAX_INPUT_TOKENS = 131072;
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_TRANSIENT_FAILURE_RETRIES = 2;

    private String model;
    private String provider;
    private String apiKeyEnv;
    private String baseUrl;
    private String baseUrlEnv;
    private boolean reasoningContentFallback;
    private double temperature;
    private int maxOutputTokens;
    private int maxInputTokens = DEFAULT_MAX_INPUT_TOKENS;
    private boolean adaptiveThinking;
    private String effort;
    private String thinking;
    private boolean supportsVision;
    private int requestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS;
    private int timeoutRetryAttempts = 0;
    private double timeoutRetryMultiplier = 2.0;
    private int maxRequestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS;
    private int transientFailureRetries = DEFAULT_TRANSIENT_FAILURE_RETRIES;

    public ModelConfig copyWithModel(String modelName) {
        ModelConfig copy = new ModelConfig();
        copy.model = (model != null && !model.isBlank()) ? model : modelName;
        copy.provider = provider;
        copy.apiKeyEnv = apiKeyEnv;
        copy.baseUrl = baseUrl;
        copy.baseUrlEnv = baseUrlEnv;
        copy.reasoningContentFallback = reasoningContentFallback;
        copy.temperature = temperature;
        copy.maxOutputTokens = maxOutputTokens;
        copy.maxInputTokens = maxInputTokens;
        copy.adaptiveThinking = adaptiveThinking;
        copy.effort = effort;
        copy.thinking = thinking;
        copy.supportsVision = supportsVision;
        copy.requestTimeoutSeconds = requestTimeoutSeconds;
        copy.timeoutRetryAttempts = timeoutRetryAttempts;
        copy.timeoutRetryMultiplier = timeoutRetryMultiplier;
        copy.maxRequestTimeoutSeconds = maxRequestTimeoutSeconds;
        copy.transientFailureRetries = transientFailureRetries;
        return copy;
    }

    public ModelConfig applyProviderDefaults(ProviderConfig providerConfig) {
        if (providerConfig == null) {
            return this;
        }
        if (isBlank(apiKeyEnv)) {
            apiKeyEnv = providerConfig.getApiKeyEnv();
        }
        if (isBlank(baseUrl)) {
            baseUrl = providerConfig.getBaseUrl();
        }
        if (isBlank(baseUrlEnv)) {
            baseUrlEnv = providerConfig.getBaseUrlEnv();
        }
        return this;
    }

    public String resolveBaseUrl() {
        if (baseUrlEnv == null || baseUrlEnv.isBlank()) {
            return baseUrl;
        }
        String value = System.getenv(baseUrlEnv);
        return (value == null || value.isBlank()) ? baseUrl : value;
    }

    public String resolveProvider() {
        if (provider != null && !provider.isBlank()) {
            return provider.trim().toLowerCase();
        }
        if (model == null || model.isBlank()) {
            return "";
        }

        String normalizedModel = model.trim().toLowerCase();
        if (normalizedModel.contains("gemini")) {
            return "gemini";
        }
        if (normalizedModel.contains("deepseek")) {
            return "deepseek";
        }
        if (normalizedModel.contains("kimi") || normalizedModel.contains("moonshot")) {
            return "moonshot";
        }
        if (normalizedModel.contains("glm") || normalizedModel.contains("bigmodel")) {
            return "zhipu";
        }
        if (normalizedModel.startsWith("gpt-")
                || normalizedModel.startsWith("o1")
                || normalizedModel.startsWith("o3")
                || normalizedModel.startsWith("o4")) {
            return "openai";
        }
        return "";
    }

    public void validate(String modelName) {
        if (resolveProvider().isBlank()) {
            throw new IllegalStateException("Missing provider and failed to infer it for model '" + modelName + "'");
        }
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
            throw new IllegalStateException("Missing api_key_env for model '" + modelName + "'");
        }
        if ((baseUrl == null || baseUrl.isBlank()) && !"anthropic".equals(resolveProvider())) {
            throw new IllegalStateException("Missing base_url for model '" + modelName + "'");
        }
        if (maxInputTokens <= 0) {
            throw new IllegalStateException("max_input_tokens must be > 0 for model '" + modelName + "'");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalStateException("max_output_tokens must be > 0 for model '" + modelName + "'");
        }
        if (requestTimeoutSeconds <= 0) {
            throw new IllegalStateException("request_timeout_seconds must be > 0 for model '" + modelName + "'");
        }
        if (timeoutRetryAttempts < 0) {
            throw new IllegalStateException("timeout_retry_attempts must be >= 0 for model '" + modelName + "'");
        }
        if (timeoutRetryMultiplier <= 1.0 && timeoutRetryAttempts > 0) {
            throw new IllegalStateException("timeout_retry_multiplier must be > 1.0 when timeout retries are enabled for model '" + modelName + "'");
        }
        if (maxRequestTimeoutSeconds > 0 && maxRequestTimeoutSeconds < requestTimeoutSeconds) {
            throw new IllegalStateException("max_request_timeout_seconds must be >= request_timeout_seconds for model '" + modelName + "'");
        }
        if (transientFailureRetries < 0) {
            throw new IllegalStateException("transient_failure_retries must be >= 0 for model '" + modelName + "'");
        }
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKeyEnv() {
        return apiKeyEnv;
    }

    public void setApiKeyEnv(String apiKeyEnv) {
        this.apiKeyEnv = apiKeyEnv;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getBaseUrlEnv() {
        return baseUrlEnv;
    }

    public void setBaseUrlEnv(String baseUrlEnv) {
        this.baseUrlEnv = baseUrlEnv;
    }

    public boolean isReasoningContentFallback() {
        return reasoningContentFallback;
    }

    public void setReasoningContentFallback(boolean reasoningContentFallback) {
        this.reasoningContentFallback = reasoningContentFallback;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getMaxInputTokens() {
        return maxInputTokens;
    }

    public void setMaxInputTokens(int maxInputTokens) {
        this.maxInputTokens = maxInputTokens;
    }

    public boolean isAdaptiveThinking() {
        return adaptiveThinking;
    }

    public void setAdaptiveThinking(boolean adaptiveThinking) {
        this.adaptiveThinking = adaptiveThinking;
    }

    public String getEffort() {
        return effort;
    }

    public void setEffort(String effort) {
        this.effort = effort;
    }

    public String getThinking() {
        return thinking;
    }

    public void setThinking(String thinking) {
        this.thinking = thinking;
    }

    public boolean isSupportsVision() {
        return supportsVision;
    }

    public void setSupportsVision(boolean supportsVision) {
        this.supportsVision = supportsVision;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getTimeoutRetryAttempts() {
        return timeoutRetryAttempts;
    }

    public void setTimeoutRetryAttempts(int timeoutRetryAttempts) {
        this.timeoutRetryAttempts = timeoutRetryAttempts;
    }

    public double getTimeoutRetryMultiplier() {
        return timeoutRetryMultiplier;
    }

    public void setTimeoutRetryMultiplier(double timeoutRetryMultiplier) {
        this.timeoutRetryMultiplier = timeoutRetryMultiplier;
    }

    public int getMaxRequestTimeoutSeconds() {
        return maxRequestTimeoutSeconds;
    }

    public void setMaxRequestTimeoutSeconds(int maxRequestTimeoutSeconds) {
        this.maxRequestTimeoutSeconds = maxRequestTimeoutSeconds;
    }

    public int getTransientFailureRetries() {
        return transientFailureRetries;
    }

    public void setTransientFailureRetries(int transientFailureRetries) {
        this.transientFailureRetries = transientFailureRetries;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
