package com.automanim.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodeUtils shared utility methods.
 */
class CodeUtilsTest {

    @Test
    void extractCode_extractsFromPythonBlock() {
        String response = "Here's the code:\n```python\nfrom manim import *\n\nclass MainScene(Scene):\n    pass\n```";
        String extracted = CodeUtils.extractCode(response);
        assertTrue(extracted.contains("from manim import"));
        assertTrue(extracted.contains("class MainScene"));
        assertFalse(extracted.contains("```"));
    }

    @Test
    void extractCode_returnsRawTextIfNoBlock() {
        String response = "from manim import *";
        String extracted = CodeUtils.extractCode(response);
        assertEquals("from manim import *", extracted);
    }

    @Test
    void extractCode_handlesNullAndEmpty() {
        assertEquals("", CodeUtils.extractCode(null));
        assertEquals("", CodeUtils.extractCode(""));
        assertEquals("", CodeUtils.extractCode("   "));
    }

    @Test
    void enforceMainSceneName_renamesOtherSceneClasses() {
        String code = "class MyCustomScene(Scene):\n    def construct(self):\n        pass";
        String enforced = CodeUtils.enforceMainSceneName(code);
        assertTrue(enforced.contains("class MainScene(Scene)"));
        assertFalse(enforced.contains("MyCustomScene"));
    }

    @Test
    void enforceMainSceneName_preservesMainScene() {
        String code = "class MainScene(Scene):\n    def construct(self):\n        pass";
        String enforced = CodeUtils.enforceMainSceneName(code);
        assertTrue(enforced.contains("class MainScene(Scene)"));
    }

    @Test
    void validateStructure_detectsEmptyCode() {
        List<String> violations = CodeUtils.validateStructure("");
        assertTrue(violations.contains("Code is empty"));
    }

    @Test
    void validateStructure_detectsMissingImport() {
        String code = "class MainScene(Scene):\n    def construct(self):\n        pass";
        List<String> violations = CodeUtils.validateStructure(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Missing 'from manim import'")));
    }

    @Test
    void validateStructure_detectsMissingMainScene() {
        String code = "from manim import *\nclass OtherScene(Scene):\n    def construct(self):\n        pass";
        List<String> violations = CodeUtils.validateStructure(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Scene class must be named MainScene")));
    }

    @Test
    void validateStructure_detectsMissingConstruct() {
        String code = "from manim import *\nclass MainScene(Scene):\n    pass";
        List<String> violations = CodeUtils.validateStructure(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Missing construct()")));
    }

    @Test
    void validateStructure_passesValidCode() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        self.wait(1)";
        List<String> violations = CodeUtils.validateStructure(code);
        assertTrue(violations.isEmpty());
    }

    @Test
    void validateManimRules_detectsInstanceFieldViolation() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        self.my_text = Text('hello')";
        List<String> violations = CodeUtils.validateManimRules(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Rule 1 violation")));
    }

    @Test
    void validateManimRules_detectsHardcodedIndexing() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        eq[0][11:13].set_color(RED)";
        List<String> violations = CodeUtils.validateManimRules(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Rule 3 violation")));
    }

    @Test
    void expectedSceneName_returnsMainScene() {
        assertEquals("MainScene", CodeUtils.expectedSceneName());
    }

    @Test
    void countLines_countsCorrectly() {
        assertEquals(0, CodeUtils.countLines(null));
        assertEquals(0, CodeUtils.countLines(""));
        assertEquals(1, CodeUtils.countLines("single line"));
        assertEquals(3, CodeUtils.countLines("line1\nline2\nline3"));
    }

    @Test
    void hasMainSceneClass_detectsPresence() {
        assertTrue(CodeUtils.hasMainSceneClass("class MainScene(Scene):"));
        assertTrue(CodeUtils.hasMainSceneClass("class MainScene(ThreeDScene):"));
        assertFalse(CodeUtils.hasMainSceneClass("class OtherScene(Scene):"));
        assertFalse(CodeUtils.hasMainSceneClass(null));
    }
}
