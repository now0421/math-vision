package com.mathvision.service;

import com.mathvision.config.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic OpenAI-compatible client driven fully by model configuration.
 */
public class OpenAiCompatibleAiClient extends AbstractOpenAiCompatibleAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAiClient.class);

    public OpenAiCompatibleAiClient(ModelConfig modelConfig) {
        super(log, modelConfig);
    }
}
