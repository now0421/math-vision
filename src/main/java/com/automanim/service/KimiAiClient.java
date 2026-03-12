package com.automanim.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kimi (Moonshot AI) client using the OpenAI-compatible chat completion API.
 *
 * Reads configuration from environment variables:
 *   MOONSHOT_API_KEY — API key
 *   MOONSHOT_BASE_URL — base URL (default: https://api.moonshot.cn/v1)
 *   KIMI_K2_MODEL — model name (default: kimi-k2-0711-preview)
 */
public class KimiAiClient extends AbstractOpenAiCompatibleAiClient {

    private static final Logger log = LoggerFactory.getLogger(KimiAiClient.class);

    public KimiAiClient() {
        super(
                log,
                "kimi",
                "MOONSHOT_API_KEY",
                "MOONSHOT_BASE_URL",
                "https://api.moonshot.cn/v1",
                "KIMI_K2_MODEL",
                "kimi-k2-turbo-preview",
                0.6,
                8192,
                true
        );
    }
}
