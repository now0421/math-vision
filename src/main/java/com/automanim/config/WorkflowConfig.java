package com.automanim.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Locale;

/**
 * Workflow configuration with resolved model settings attached at load time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class WorkflowConfig {

    public static final String INPUT_MODE_AUTO = "auto";
    public static final String INPUT_MODE_CONCEPT = "concept";
    public static final String INPUT_MODE_PROBLEM = "problem";
    public static final String OUTPUT_TARGET_MANIM = "manim";
    public static final String OUTPUT_TARGET_GEOGEBRA = "geogebra";

    private int maxDepth;
    private int minDepth;
    private boolean parallelMathEnrichment;
    private boolean parallelVisualDesign;
    private int maxConcurrent;
    private int codeGenMaxRetries;
    private boolean renderEnabled;
    private String renderQuality;
    private int renderMaxRetries;
    private int sceneEvaluationMaxRetries = 2;
    private String inputMode = INPUT_MODE_AUTO;
    private String outputTarget = OUTPUT_TARGET_MANIM;
    private String model;
    @JsonIgnore
    private ModelConfig modelConfig;

    public static boolean isExplicitInputMode(String value) {
        String normalized = normalizeInputMode(value);
        return INPUT_MODE_CONCEPT.equals(normalized) || INPUT_MODE_PROBLEM.equals(normalized);
    }

    public static String normalizeInputMode(String value) {
        return value == null ? INPUT_MODE_AUTO : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeOutputTarget(String value) {
        return value == null ? OUTPUT_TARGET_MANIM : value.trim().toLowerCase(Locale.ROOT);
    }

    public void validate() {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Workflow config is missing 'model'");
        }
        String normalizedOutputTarget = normalizeOutputTarget(outputTarget);
        if (!OUTPUT_TARGET_MANIM.equals(normalizedOutputTarget)
                && !OUTPUT_TARGET_GEOGEBRA.equals(normalizedOutputTarget)) {
            throw new IllegalStateException(
                    "Workflow config 'output_target' must be either 'manim' or 'geogebra'");
        }
    }

    public WorkflowConfig resolve(ModelCatalogConfig modelCatalogConfig) {
        String selectedModel = getModel();
        ModelConfig sourceModelConfig = modelCatalogConfig.getModels().get(selectedModel);
        if (sourceModelConfig == null) {
            throw new IllegalStateException("No model config found for '" + selectedModel + "'");
        }

        ModelConfig resolvedModelConfig = sourceModelConfig.copyWithModel(selectedModel);
        String provider = resolvedModelConfig.resolveProvider();
        if (!provider.isBlank()) {
            resolvedModelConfig.applyProviderDefaults(modelCatalogConfig.getProviders().get(provider));
        }
        resolvedModelConfig.validate(selectedModel);
        this.modelConfig = resolvedModelConfig;
        this.inputMode = normalizeInputMode(inputMode);
        this.outputTarget = normalizeOutputTarget(outputTarget);
        return this;
    }

    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
    public int getMinDepth() { return minDepth; }
    public void setMinDepth(int minDepth) { this.minDepth = minDepth; }
    public boolean isParallelMathEnrichment() { return parallelMathEnrichment; }
    public void setParallelMathEnrichment(boolean parallelMathEnrichment) {
        this.parallelMathEnrichment = parallelMathEnrichment;
    }
    public boolean isParallelVisualDesign() { return parallelVisualDesign; }
    public void setParallelVisualDesign(boolean parallelVisualDesign) {
        this.parallelVisualDesign = parallelVisualDesign;
    }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public int getCodeGenMaxRetries() { return codeGenMaxRetries; }
    public void setCodeGenMaxRetries(int codeGenMaxRetries) { this.codeGenMaxRetries = codeGenMaxRetries; }
    public boolean isRenderEnabled() { return renderEnabled; }
    public void setRenderEnabled(boolean renderEnabled) { this.renderEnabled = renderEnabled; }
    public String getRenderQuality() { return renderQuality; }
    public void setRenderQuality(String renderQuality) { this.renderQuality = renderQuality; }
    public int getRenderMaxRetries() { return renderMaxRetries; }
    public void setRenderMaxRetries(int renderMaxRetries) { this.renderMaxRetries = renderMaxRetries; }
    public int getSceneEvaluationMaxRetries() { return sceneEvaluationMaxRetries; }
    public void setSceneEvaluationMaxRetries(int sceneEvaluationMaxRetries) {
        this.sceneEvaluationMaxRetries = sceneEvaluationMaxRetries;
    }
    public String getInputMode() { return inputMode; }
    public void setInputMode(String inputMode) { this.inputMode = normalizeInputMode(inputMode); }
    public String getOutputTarget() { return outputTarget; }
    public void setOutputTarget(String outputTarget) {
        this.outputTarget = normalizeOutputTarget(outputTarget);
    }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public ModelConfig getModelConfig() { return modelConfig; }

    public boolean isManimTarget() {
        return OUTPUT_TARGET_MANIM.equals(outputTarget);
    }

    public boolean isGeoGebraTarget() {
        return OUTPUT_TARGET_GEOGEBRA.equals(outputTarget);
    }
}
