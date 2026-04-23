package com.mathvision.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextHealthDiagnosticsTest {

    @Test
    void inspectDetectsSuspiciousMojibake() {
        TextHealthDiagnostics.TextHealthReport report =
                TextHealthDiagnostics.inspect("B鈥? and 鈭?AP*l = 鈭?BP*l");

        assertTrue(report.suspicious());
        assertTrue(report.hasSuspiciousEncoding());
        assertTrue(report.summary().contains("suspicious_mojibake"));
    }

    @Test
    void inspectKeepsCleanMathSymbolsClean() {
        TextHealthDiagnostics.TextHealthReport report =
                TextHealthDiagnostics.inspect("θ ∠ B′ P*");

        assertFalse(report.suspicious());
        assertFalse(report.hasSuspiciousEncoding());
    }
}
