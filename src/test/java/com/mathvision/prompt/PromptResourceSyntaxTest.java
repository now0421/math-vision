package com.mathvision.prompt;

import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptResourceSyntaxTest {

    private static final Pattern FENCED_BLOCK =
            Pattern.compile("```([A-Za-z0-9_+-]*)\\R(.*?)\\R```", Pattern.DOTALL);
    private static final Pattern GEOGEBRA_ASSIGNMENT =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\([^()]*\\))?\\s*=\\s*.+");
    private static final Pattern GEOGEBRA_COMMAND =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*\\s*\\(.*\\)");

    @Test
    void manimSyntaxManualPythonExamplesParseAsPython() throws Exception {
        String markdown = readResource("llm/manim_syntax_manual.md");
        List<String> pythonBlocks = extractBlocks(markdown, "python");

        assertFalse(pythonBlocks.isEmpty(), "Expected Python examples in manim_syntax_manual.md");

        for (int i = 0; i < pythonBlocks.size(); i++) {
            String block = pythonBlocks.get(i);
            String snippet = toParseablePythonModule(block);
            PythonParseResult result = parsePython(snippet);
            assertEquals(0, result.exitCode,
                    "Python syntax error in manim_syntax_manual.md block #" + (i + 1)
                            + "\nOriginal block:\n" + block
                            + "\n\nstderr:\n" + result.stderr);
        }
    }

    @Test
    void geogebraSyntaxManualExamplesFollowSupportedSnippetGrammar() throws Exception {
        String markdown = readResource("llm/geogebra_syntax_manual.md");
        List<String> geogebraBlocks = extractBlocks(markdown, "geogebra");

        assertFalse(geogebraBlocks.isEmpty(), "Expected GeoGebra examples in geogebra_syntax_manual.md");

        for (int i = 0; i < geogebraBlocks.size(); i++) {
            String block = geogebraBlocks.get(i);
            assertTrue(isAscii(block),
                    "GeoGebra block #" + (i + 1) + " contains non-ASCII characters:\n" + block);
            assertTrue(hasBalancedDelimiters(block),
                    "GeoGebra block #" + (i + 1) + " has unbalanced delimiters:\n" + block);

            String[] lines = block.split("\\R");
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                assertFalse(line.contains("`"),
                        "GeoGebra block #" + (i + 1) + " contains stray markdown fencing:\n" + line);
                assertTrue(GEOGEBRA_ASSIGNMENT.matcher(line).matches()
                                || GEOGEBRA_COMMAND.matcher(line).matches(),
                        "GeoGebra block #" + (i + 1)
                                + " should use assignment-style or command-style GeoGebra syntax:\n" + line);
            }
        }
    }

    @Test
    void styleReferenceFilesContainNoCorruptedCodeFences() throws Exception {
        assertTrue(extractBlocks(readResource("llm/manim_style_reference.md"), "python").isEmpty());
        assertTrue(extractBlocks(readResource("llm/geogebra_style_reference.md"), "geogebra").isEmpty());
    }

    private static String readResource(String resourceName) throws IOException {
        try (InputStream input = PromptResourceSyntaxTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, "Missing classpath resource: " + resourceName);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> extractBlocks(String markdown, String language) {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = FENCED_BLOCK.matcher(markdown);
        while (matcher.find()) {
            if (language.equalsIgnoreCase(matcher.group(1).trim())) {
                blocks.add(matcher.group(2));
            }
        }
        return blocks;
    }

    private static String toParseablePythonModule(String block) {
        String trimmed = block.trim();
        if (trimmed.startsWith("class ") || trimmed.startsWith("from ") || trimmed.startsWith("import ")) {
            return trimmed + "\n";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("def _snippet():\n");
        for (String line : block.split("\\R", -1)) {
            if (line.isEmpty()) {
                builder.append("    \n");
            } else {
                builder.append("    ").append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private static PythonParseResult parsePython(String code) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd.exe", "/c", "python", "-c",
                "import ast, sys; ast.parse(sys.stdin.read())")
                .start();

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(code);
        }

        int exitCode = process.waitFor();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new PythonParseResult(exitCode, stderr);
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasBalancedDelimiters(String text) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            char prev = i > 0 ? text.charAt(i - 1) : '\0';

            if (ch == '\'' && !inDoubleQuote && prev != '\\') {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote && prev != '\\') {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (inSingleQuote || inDoubleQuote) {
                continue;
            }

            if (ch == '(' || ch == '[' || ch == '{') {
                stack.push(ch);
            } else if (ch == ')' || ch == ']' || ch == '}') {
                if (stack.isEmpty()) {
                    return false;
                }
                char open = stack.pop();
                if (!matches(open, ch)) {
                    return false;
                }
            }
        }

        return stack.isEmpty() && !inSingleQuote && !inDoubleQuote;
    }

    private static boolean matches(char open, char close) {
        return (open == '(' && close == ')')
                || (open == '[' && close == ']')
                || (open == '{' && close == '}');
    }

    private static final class PythonParseResult {
        private final int exitCode;
        private final String stderr;

        private PythonParseResult(int exitCode, String stderr) {
            this.exitCode = exitCode;
            this.stderr = stderr;
        }
    }
}
