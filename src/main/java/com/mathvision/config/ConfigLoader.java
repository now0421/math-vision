package com.mathvision.config;

import com.mathvision.util.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unified loader for workflow and model configuration files.
 */
public final class ConfigLoader {

    public static final String DEFAULT_WORKFLOW_RESOURCE = "workflow-config.json";
    public static final String DEFAULT_MODEL_RESOURCE = "model-config.json";

    private ConfigLoader() {}

    public static WorkflowConfig load(String workflowConfigPath, String modelConfigPath) {
        try {
            WorkflowConfig workflowConfig = isBlank(workflowConfigPath)
                    ? loadFromClasspath(DEFAULT_WORKFLOW_RESOURCE, WorkflowConfig.class)
                    : loadFromFile(workflowConfigPath, WorkflowConfig.class);
            ModelCatalogConfig modelCatalogConfig = isBlank(modelConfigPath)
                    ? loadFromClasspath(DEFAULT_MODEL_RESOURCE, ModelCatalogConfig.class)
                    : loadFromFile(modelConfigPath, ModelCatalogConfig.class);

            workflowConfig.validate();
            modelCatalogConfig.validate();
            return workflowConfig.resolve(modelCatalogConfig);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config: " + e.getMessage(), e);
        }
    }

    private static <T> T loadFromClasspath(String resourceName, Class<T> clazz) throws IOException {
        try (InputStream input = ConfigLoader.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Classpath resource not found: " + resourceName);
            }
            return JsonUtils.mapper().readValue(input, clazz);
        }
    }

    private static <T> T loadFromFile(String configPath, Class<T> clazz) throws IOException {
        try (InputStream input = Files.newInputStream(Path.of(configPath))) {
            return JsonUtils.mapper().readValue(input, clazz);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
