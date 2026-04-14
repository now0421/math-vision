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

    private String model;
    private String provider;
    private String apiKeyEnv;
    private String baseUrl;
    private String baseUrlEnv;
    private boolean reasoningContentFallback;
    private double temperature;
    private int maxOutputTokens;
    private int maxInputTokens = DEFAULT_MAX_INPUT_TOKENS;

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
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Missing base_url for model '" + modelName + "'");
        }
        if (maxInputTokens <= 0) {
            throw new IllegalStateException("max_input_tokens must be > 0 for model '" + modelName + "'");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalStateException("max_output_tokens must be > 0 for model '" + modelName + "'");
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
