package com.mathvision.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Provider-level shared connection settings loaded from JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ProviderConfig {

    private String apiKeyEnv;
    private String baseUrl;
    private String baseUrlEnv;

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
}
