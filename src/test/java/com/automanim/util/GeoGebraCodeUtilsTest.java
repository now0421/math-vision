package com.automanim.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoGebraCodeUtilsTest {

    @Test
    void extractCode_extractsFromGeoGebraBlock() {
        String response = "```geogebra\nA = (0, 0)\nB = (4, 0)\n```";
        assertEquals("A = (0, 0)\nB = (4, 0)", GeoGebraCodeUtils.extractCode(response));
    }

    @Test
    void extractCommands_stripsBlankLinesAndComments() {
        List<String> commands = GeoGebraCodeUtils.extractCommands(String.join("\n",
                "",
                "# comment",
                "A = (0, 0)",
                "// another comment",
                "B = (4, 0)"
        ));

        assertEquals(List.of("A = (0, 0)", "B = (4, 0)"), commands);
    }

    @Test
    void validateStructure_detectsPythonAndJavascriptPollution() {
        String code = String.join("\n",
                "const A = (0, 0)",
                "from manim import *",
                "B = (4, 0)");

        List<String> violations = GeoGebraCodeUtils.validateStructure(code);

        assertTrue(violations.stream().anyMatch(v -> v.contains("Python or Manim syntax")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("JavaScript syntax")));
    }

    @Test
    void validateStructure_detectsInvalidCommandShape() {
        List<String> violations = GeoGebraCodeUtils.validateStructure("A -> (0, 0)");
        assertTrue(violations.stream().anyMatch(v -> v.contains("does not look like a GeoGebra command")));
    }

    @Test
    void validateStructure_detectsUnbalancedDelimiters() {
        List<String> violations = GeoGebraCodeUtils.validateStructure("A = Line((0, 0)");
        assertTrue(violations.stream().anyMatch(v -> v.contains("unbalanced")));
    }

    @Test
    void validateGeoGebraRules_detectsNonAsciiAndFullWidthPunctuation() {
        String code = String.join("\n",
                "\u4ea4\u70b9 = Intersect(A\uFF0CB)",
                "B = (4, 0)");

        List<String> violations = GeoGebraCodeUtils.validateGeoGebraRules(code);

        assertTrue(violations.stream().anyMatch(v -> v.contains("full-width punctuation")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("non-ASCII GeoGebra identifiers")));
    }

    @Test
    void validateGeoGebraRules_detectsMultipleCommandsOnOneLine() {
        List<String> violations = GeoGebraCodeUtils.validateGeoGebraRules("A = (0, 0); B = (4, 0)");
        assertTrue(violations.stream().anyMatch(v -> v.contains("multiple commands on one line")));
    }

    @Test
    void looksLikeCommandBlock_requiresMostlyCommandLikeLines() {
        assertTrue(GeoGebraCodeUtils.looksLikeCommandBlock(String.join("\n",
                "A = (0, 0)",
                "B = (4, 0)",
                "lineAB = Line(A, B)")));

        assertFalse(GeoGebraCodeUtils.looksLikeCommandBlock(String.join("\n",
                "Here is the construction:",
                "A = (0, 0)",
                "Please render it nicely.")));
    }

    @Test
    void validateFull_passesReasonableGeoGebraScript() {
        String code = String.join("\n",
                "l: y = 0",
                "A = (-4, 3)",
                "B = (4, 2)",
                "B1 = Reflect(B, l)",
                "g = Line(A, B1)",
                "Q = Intersect(g, l)");

        List<String> violations = GeoGebraCodeUtils.validateFull(code);

        assertTrue(violations.isEmpty());
    }
}
