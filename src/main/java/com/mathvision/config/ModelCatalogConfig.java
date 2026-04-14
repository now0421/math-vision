package com.mathvision.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Model/provider catalog loaded from JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ModelCatalogConfig {

    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    private Map<String, ModelConfig> models = new LinkedHashMap<>();

    public void validate() {
        if (models == null || models.isEmpty()) {
            throw new IllegalStateException("Model config is missing 'models'");
        }
    }

    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    public Map<String, ModelConfig> getModels() { return models; }
    public void setModels(Map<String, ModelConfig> models) { this.models = models; }
}
