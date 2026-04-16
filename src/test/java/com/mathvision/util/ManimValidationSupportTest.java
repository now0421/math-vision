package com.mathvision.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ensures the hardcoded catalog in {@link ManimValidationSupport} stays in
 * sync with the Manim syntax manual.
 */
class ManimValidationSupportTest {

    /** Matches {@code obj.method_name(} inside Python code blocks, excluding {@code self.method(}. */
    private static final Pattern INSTANCE_METHOD_CALL = Pattern.compile(
            "(?<!self)\\.([a-z][a-z0-9_]*)\\s*\\(");

    @Test
    void documentedInstanceMethodsCoverManualCodeBlocks() throws Exception {
        String markdown = readResource("llm/manim_syntax_manual.md");
        Set<String> manualMethods = extractSnakeCaseInstanceMethods(markdown);
        Set<String> catalogMethods = ManimValidationSupport.documentedInstanceMethodNames();

        // Every method in the catalog must appear in the manual
        for (String catalogMethod : catalogMethods) {
            assertTrue(manualMethods.contains(catalogMethod),
                    "Catalog method '" + catalogMethod + "' is not found in the syntax manual code blocks");
        }

        // Every snake_case method in the manual must appear in the catalog
        for (String manualMethod : manualMethods) {
            assertTrue(catalogMethods.contains(manualMethod),
                    "Manual code block method '" + manualMethod + "' is missing from ManimValidationSupport catalog");
        }
    }

    /**
     * Extracts all unique snake_case instance method names (containing at
     * least one underscore) from Python code blocks in the manual.
     * Skips the "Common Render Failure Guardrails" section since it
     * demonstrates anti-patterns.
     */
    private static Set<String> extractSnakeCaseInstanceMethods(String markdown) {
        Set<String> methods = new LinkedHashSet<>();
        boolean insidePythonBlock = false;
        boolean insideGuardrailSection = false;

        for (String rawLine : markdown.split("\\R")) {
            String line = rawLine.trim();

            // Track heading to skip guardrail anti-patterns
            if (line.startsWith("## ") || line.startsWith("### ")) {
                String heading = line.replaceFirst("^#+\\s+", "").trim();
                insideGuardrailSection = heading.contains("Guardrail")
                        || heading.contains("Recommended Rules")
                        || heading.contains("Global Rules");
                continue;
            }

            // Track fenced code blocks
            if (line.startsWith("```")) {
                String language = line.substring(3).trim();
                if (!insidePythonBlock) {
                    insidePythonBlock = "python".equals(language);
                } else {
                    insidePythonBlock = false;
                }
                continue;
            }

            if (!insidePythonBlock || insideGuardrailSection) {
                continue;
            }

            // Skip comment lines
            if (line.startsWith("#")) {
                continue;
            }

            Matcher matcher = INSTANCE_METHOD_CALL.matcher(line);
            while (matcher.find()) {
                String methodName = matcher.group(1);
                // Only include snake_case methods (contain underscore)
                if (methodName.contains("_")) {
                    methods.add(methodName);
                }
            }
        }

        // Remove known Python / NumPy builtins that happen to appear in examples
        methods.removeAll(Set.of(
                "to_rgb", "to_hex", "to_hsv", // color utility, not a mobject method
                "pop_tips", "add_tip",  // Arrow tip methods rarely used directly
                "is_integer"            // Python float method
        ));

        return methods;
    }

    private static String readResource(String resourceName) throws IOException {
        try (InputStream input = ManimValidationSupportTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, "Missing classpath resource: " + resourceName);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
