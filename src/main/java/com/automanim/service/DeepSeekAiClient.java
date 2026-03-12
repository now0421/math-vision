package com.automanim.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DeepSeek client using the OpenAI-compatible chat completion API.
 *
 * Reads configuration from environment variables:
 *   DEEPSEEK_API_KEY  API key
 *   DEEPSEEK_BASE_URL base URL (default: https://api.deepseek.com/v1)
 *   DEEPSEEK_MODEL    model name (default: deepseek-reasoner)
 */
public class DeepSeekAiClient extends AbstractOpenAiCompatibleAiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAiClient.class);

    public DeepSeekAiClient() {
        super(
                log,
                "deepseek",
                "DEEPSEEK_API_KEY",
                "DEEPSEEK_BASE_URL",
                "https://api.deepseek.com/v1",
                "DEEPSEEK_MODEL",
                "deepseek-reasoner",
                0.6,
                8192,
                true
        );
    }
}
