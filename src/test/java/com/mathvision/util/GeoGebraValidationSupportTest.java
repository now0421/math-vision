package com.mathvision.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoGebraValidationSupportTest {

    private static final Pattern COMMAND_SIGNATURE = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern INLINE_CODE_SPAN = Pattern.compile("`([^`]+)`");

    @Test
    void documentedCommandCatalogMatchesSyntaxManual() throws Exception {
        String markdown = readResource("llm/geogebra_syntax_manual.md");
        Set<String> documented = extractDocumentedCommands(markdown, null);
        Set<String> whitelist = GeoGebraValidationSupport.documentedCommandNames();

        assertTrue(whitelist.containsAll(documented),
                () -> "Whitelist is missing commands from the manual: "
                        + difference(documented, whitelist));
    }

    @Test
    void documentedScriptingCommandCatalogMatchesStyleSection() throws Exception {
        String markdown = readResource("llm/geogebra_syntax_manual.md");
        Set<String> documented = extractDocumentedCommands(markdown, "Style");
        Set<String> whitelist = GeoGebraValidationSupport.documentedScriptingCommandNames();

        assertTrue(whitelist.containsAll(documented),
                () -> "Scripting whitelist is missing commands from the Style section: "
                        + difference(documented, whitelist));
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
        Set<String> commands = new java.util.LinkedHashSet<>();
        String currentHeading = null;
        boolean insideCodeBlock = false;
        boolean insideExtendedIndex = false;
        boolean insideThreeDSection = false;

        for (String rawLine : markdown.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                currentHeading = line.substring(3).trim();
                insideExtendedIndex = "Extended Command Index".equals(currentHeading);
                insideThreeDSection = "3D Commands".equals(currentHeading);
                continue;
            }
            if (line.startsWith("```")) {
                String language = line.substring(3).trim();
                if (!insideCodeBlock) {
                    insideCodeBlock = !language.isEmpty();
                } else {
                    insideCodeBlock = false;
                }
                continue;
            }
            if ("Forbidden Syntax".equals(currentHeading)) {
                continue;
            }
            if (insideCodeBlock) {
                if (targetHeading != null && !targetHeading.equals(currentHeading)) {
                    continue;
                }
                addCommandsFromText(commands, line);
                continue;
            }
            if (insideExtendedIndex) {
                addCommandsFromInlineSpans(commands, line);
                continue;
            }
            if (insideThreeDSection) {
                addCommandsFromInlineSpans(commands, line);
            }
            if (targetHeading != null && !targetHeading.equals(currentHeading)) {
                continue;
            }
            if (insideExtendedIndex || insideThreeDSection) {
                continue;
            }
        }
        return commands;
    }

    private static void addCommandsFromInlineSpans(Set<String> commands, String text) {
        Matcher inlineMatcher = INLINE_CODE_SPAN.matcher(text);
        while (inlineMatcher.find()) {
            addCommandsFromText(commands, inlineMatcher.group(1));
        }
    }

    private static void addCommandsFromText(Set<String> commands, String text) {
        Matcher matcher = COMMAND_SIGNATURE.matcher(text);
        while (matcher.find()) {
            commands.add(matcher.group(1));
        }
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
        for (String commandName : GeoGebraValidationSupport.documentedRuntimeScriptingCommandNames()) {
            assertTrue(section.contains("case '" + commandName + "':"),
                    sectionName + " is missing handler coverage for " + commandName);
        }
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new java.util.TreeSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }
}
