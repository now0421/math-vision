package com.mathvision.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoGebraValidationSupportTest {

    private static final Pattern COMMAND_SIGNATURE = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*)\\s*\\(");

    @Test
    void documentedCommandCatalogMatchesSyntaxManual() throws Exception {
        String markdown = readResource("llm/geogebra_syntax_manual.md");

        assertEquals(extractDocumentedCommands(markdown, null),
                GeoGebraValidationSupport.documentedCommandNames());
    }

    @Test
    void documentedScriptingCommandCatalogMatchesStyleSection() throws Exception {
        String markdown = readResource("llm/geogebra_syntax_manual.md");

        assertEquals(extractDocumentedCommands(markdown, "Style"),
                GeoGebraValidationSupport.documentedScriptingCommandNames());
    }

    @Test
    void validationRuntimeCoversEveryDocumentedScriptingCommand() throws Exception {
        String source = readMainSource("com/mathvision/service/GeoGebraRenderService.java");
        String validationSection = slice(source,
                "private String executeValidationScript",
                "private ValidationReport parseValidationReport");

        assertRuntimeSectionCoversAllDocumentedScriptingCommands(validationSection, "validation runtime");
    }

    @Test
    void previewRuntimeCoversEveryDocumentedScriptingCommand() throws Exception {
        String source = readMainSource("com/mathvision/service/GeoGebraRenderService.java");
        String previewSection = slice(source,
                "private String buildPreviewHtml",
                "private String buildValidatorHtml");

        assertRuntimeSectionCoversAllDocumentedScriptingCommands(previewSection, "preview runtime");
    }

    private static Set<String> extractDocumentedCommands(String markdown, String targetHeading) {
        Set<String> commands = new LinkedHashSet<>();
        String currentHeading = null;
        boolean insideTextBlock = false;

        for (String rawLine : markdown.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                currentHeading = line.substring(3).trim();
                continue;
            }
            if (line.startsWith("```")) {
                String language = line.substring(3).trim();
                if (!insideTextBlock) {
                    insideTextBlock = "text".equals(language);
                } else {
                    insideTextBlock = false;
                }
                continue;
            }
            if (!insideTextBlock || "Forbidden Syntax".equals(currentHeading)) {
                continue;
            }
            if (targetHeading != null && !targetHeading.equals(currentHeading)) {
                continue;
            }

            Matcher matcher = COMMAND_SIGNATURE.matcher(line);
            if (matcher.find()) {
                commands.add(matcher.group(1));
            }
        }
        return commands;
    }

    private static String readResource(String resourceName) throws IOException {
        try (InputStream input = GeoGebraValidationSupportTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, "Missing classpath resource: " + resourceName);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readMainSource(String relativePath) throws IOException {
        Path sourcePath = Path.of(System.getProperty("user.dir"), "src", "main", "java")
                .resolve(relativePath);
        return Files.readString(sourcePath, StandardCharsets.UTF_8);
    }

    private static String slice(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker);
        assertTrue(start >= 0, "Missing marker: " + startMarker);
        assertTrue(end > start, "Missing or misplaced marker: " + endMarker);
        return source.substring(start, end);
    }

    private static void assertRuntimeSectionCoversAllDocumentedScriptingCommands(String section,
                                                                                 String sectionName) {
        for (String commandName : GeoGebraValidationSupport.documentedScriptingCommandNames()) {
            assertTrue(section.contains("case '" + commandName + "':"),
                    sectionName + " is missing handler coverage for " + commandName);
        }
    }
}
