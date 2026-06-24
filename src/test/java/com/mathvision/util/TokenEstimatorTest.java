package com.mathvision.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    void estimatesAsciiTextConservatively() {
        assertEquals(4, TokenEstimator.estimateTokens("abcdefgh"));
    }

    @Test
    void estimatesNonAsciiNearOneTokenPerCharacter() {
        assertEquals(6, TokenEstimator.estimateTokens("如图求最小值"));
    }

    @Test
    void jsonAndToolSchemaPunctuationCostsMoreThanPlainAsciiText() {
        String plain = "namewriteScenepropertiesobjectrequired";
        String json = "{\"name\":\"writeScene\",\"parameters\":{\"type\":\"object\",\"required\":[\"scene\"]}}";

        assertTrue(TokenEstimator.estimateTokens(json) > TokenEstimator.estimateTokens(plain));
    }

    @Test
    void whitespaceContributesToBudget() {
        assertTrue(TokenEstimator.estimateTokens("        ") > 0);
    }
}
