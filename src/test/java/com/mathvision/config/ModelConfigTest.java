package com.mathvision.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelConfigTest {

    @Test
    void promptInputBudgetReservesConfiguredOutputTokens() {
        ModelConfig config = new ModelConfig();
        config.setMaxInputTokens(200_000);
        config.setMaxOutputTokens(128_000);

        assertEquals(72_000, config.resolvePromptInputBudgetTokens());
    }

    @Test
    void promptInputBudgetKeepsInputLimitWhenOutputBudgetWouldExceedIt() {
        ModelConfig config = new ModelConfig();
        config.setMaxInputTokens(8_192);
        config.setMaxOutputTokens(16_384);

        assertEquals(8_192, config.resolvePromptInputBudgetTokens());
    }
}
