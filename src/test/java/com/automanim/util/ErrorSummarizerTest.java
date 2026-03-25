package com.automanim.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ErrorSummarizer shared utility methods.
 */
class ErrorSummarizerTest {

    @Test
    void isEnvironmentError_detectsModuleNotFound() {
        assertTrue(ErrorSummarizer.isEnvironmentError("ModuleNotFoundError: No module named 'nonexistent'"));
    }

    @Test
    void isEnvironmentError_detectsCommandNotFound() {
        assertTrue(ErrorSummarizer.isEnvironmentError("manim: command not found"));
    }

    @Test
    void isEnvironmentError_detectsPermissionDenied() {
        assertTrue(ErrorSummarizer.isEnvironmentError("Error: Permission denied"));
    }

    @Test
    void isEnvironmentError_detectsOutOfMemory() {
        assertTrue(ErrorSummarizer.isEnvironmentError("Java runtime: Out of memory"));
    }

    @Test
    void isEnvironmentError_detectsFFmpegNotFound() {
        assertTrue(ErrorSummarizer.isEnvironmentError("Error: ffmpeg not found in PATH"));
    }

    @Test
    void isEnvironmentError_returnsFalseForCodeError() {
        assertFalse(ErrorSummarizer.isEnvironmentError("NameError: name 'x' is not defined"));
        assertFalse(ErrorSummarizer.isEnvironmentError("SyntaxError: invalid syntax"));
        assertFalse(ErrorSummarizer.isEnvironmentError("TypeError: unsupported operand"));
    }

    @Test
    void isEnvironmentError_handlesNullAndEmpty() {
        assertFalse(ErrorSummarizer.isEnvironmentError(null));
        assertFalse(ErrorSummarizer.isEnvironmentError(""));
    }

    @Test
    void summarizeTraceback_extractsErrorFromTraceback() {
        String traceback = "Traceback (most recent call last):\n"
                + "  File \"scene.py\", line 10, in construct\n"
                + "    x = undefined_var\n"
                + "NameError: name 'undefined_var' is not defined";

        String summary = ErrorSummarizer.summarizeTraceback(traceback);
        assertTrue(summary.contains("Traceback"));
        assertTrue(summary.contains("NameError"));
    }

    @Test
    void summarizeTraceback_handlesNoTraceback() {
        String output = "Some random output without traceback";
        String summary = ErrorSummarizer.summarizeTraceback(output);
        assertFalse(summary.contains("Traceback"));
    }

    @Test
    void extractFocusedError_extractsLastError() {
        String stderr = "Some warning\nAnother message\nTypeError: invalid argument type";
        String focused = ErrorSummarizer.extractFocusedError(stderr);
        assertTrue(focused.contains("TypeError"));
    }

    @Test
    void classifyError_identifiesSyntaxError() {
        assertEquals(ErrorSummarizer.ErrorCategory.SYNTAX,
                ErrorSummarizer.classifyError("SyntaxError: invalid syntax"));
        assertEquals(ErrorSummarizer.ErrorCategory.SYNTAX,
                ErrorSummarizer.classifyError("IndentationError: unexpected indent"));
    }

    @Test
    void classifyError_identifiesNameError() {
        assertEquals(ErrorSummarizer.ErrorCategory.NAME_RESOLUTION,
                ErrorSummarizer.classifyError("NameError: name 'x' is not defined"));
        assertEquals(ErrorSummarizer.ErrorCategory.NAME_RESOLUTION,
                ErrorSummarizer.classifyError("AttributeError: 'list' object has no attribute 'foo'"));
    }

    @Test
    void classifyError_identifiesTypeError() {
        assertEquals(ErrorSummarizer.ErrorCategory.TYPE_VALUE,
                ErrorSummarizer.classifyError("TypeError: unsupported operand"));
        assertEquals(ErrorSummarizer.ErrorCategory.TYPE_VALUE,
                ErrorSummarizer.classifyError("ValueError: invalid literal"));
    }

    @Test
    void classifyError_identifiesEnvironmentError() {
        assertEquals(ErrorSummarizer.ErrorCategory.ENVIRONMENT,
                ErrorSummarizer.classifyError("No module named 'missing'"));
    }

    @Test
    void classifyError_returnsRuntimeForUnknown() {
        assertEquals(ErrorSummarizer.ErrorCategory.RUNTIME,
                ErrorSummarizer.classifyError("ZeroDivisionError: division by zero"));
    }

    @Test
    void classifyError_handlesNullAndEmpty() {
        assertEquals(ErrorSummarizer.ErrorCategory.UNKNOWN, ErrorSummarizer.classifyError(null));
        assertEquals(ErrorSummarizer.ErrorCategory.UNKNOWN, ErrorSummarizer.classifyError(""));
    }
}
