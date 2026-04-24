package com.mathvision.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ManimCodeUtils shared utility methods.
 */
class ManimCodeUtilsTest {

    @Test
    void extractCode_extractsFromPythonBlock() {
        String response = "Here's the code:\n```python\nfrom manim import *\n\nclass MainScene(Scene):\n    pass\n```";
        String extracted = ManimCodeUtils.extractCode(response);
        assertTrue(extracted.contains("from manim import"));
        assertTrue(extracted.contains("class MainScene"));
        assertFalse(extracted.contains("```"));
    }

    @Test
    void extractCode_returnsRawTextIfNoBlock() {
        String response = "from manim import *";
        String extracted = ManimCodeUtils.extractCode(response);
        assertEquals("from manim import *", extracted);
    }

    @Test
    void extractCode_handlesNullAndEmpty() {
        assertEquals("", ManimCodeUtils.extractCode(null));
        assertEquals("", ManimCodeUtils.extractCode(""));
        assertEquals("", ManimCodeUtils.extractCode("   "));
    }

    @Test
    void enforceMainSceneName_renamesOtherSceneClasses() {
        String code = "class MyCustomScene(Scene):\n    def construct(self):\n        pass";
        String enforced = ManimCodeUtils.enforceMainSceneName(code);
        assertTrue(enforced.contains("class MainScene(Scene)"));
        assertFalse(enforced.contains("MyCustomScene"));
    }

    @Test
    void enforceMainSceneName_preservesMainScene() {
        String code = "class MainScene(Scene):\n    def construct(self):\n        pass";
        String enforced = ManimCodeUtils.enforceMainSceneName(code);
        assertTrue(enforced.contains("class MainScene(Scene)"));
    }

    @Test
    void validateStructure_detectsEmptyCode() {
        List<String> violations = ManimCodeUtils.validateStructure("");
        assertTrue(violations.contains("Code is empty"));
    }

    @Test
    void validateStructure_detectsMissingImport() {
        String code = "class MainScene(Scene):\n    def construct(self):\n        pass";
        List<String> violations = ManimCodeUtils.validateStructure(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Missing 'from manim import'")));
    }

    @Test
    void validateStructure_detectsMissingMainScene() {
        String code = "from manim import *\nclass OtherScene(Scene):\n    def construct(self):\n        pass";
        List<String> violations = ManimCodeUtils.validateStructure(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Scene class must be named MainScene")));
    }

    @Test
    void validateStructure_detectsMissingConstruct() {
        String code = "from manim import *\nclass MainScene(Scene):\n    pass";
        List<String> violations = ManimCodeUtils.validateStructure(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Missing construct()")));
    }

    @Test
    void validateStructure_passesValidCode() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        self.wait(1)";
        List<String> violations = ManimCodeUtils.validateStructure(code);
        assertTrue(violations.isEmpty());
    }

    @Test
    void validateStructure_allowsNonAsciiTextLiterals() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        label = Text(\"最小值 = 2, Δ\")";
        List<String> violations = ManimCodeUtils.validateStructure(code);
        assertTrue(violations.isEmpty());
    }

    @Test
    void validateManimRules_detectsHardcodedIndexing() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        eq[0][11:13].set_color(RED)";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Static rule violation")));
    }

    @Test
    void validateManimRules_allowsSelfSceneHelperMethods() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        self.scene_1_intro()\n\n    def scene_1_intro(self):\n        pass";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        assertTrue(violations.stream().noneMatch(v -> v.contains("Static rule violation")));
    }

    @Test
    void expectedSceneName_returnsMainScene() {
        assertEquals("MainScene", ManimCodeUtils.expectedSceneName());
    }

    @Test
    void countLines_countsCorrectly() {
        assertEquals(0, ManimCodeUtils.countLines(null));
        assertEquals(0, ManimCodeUtils.countLines(""));
        assertEquals(1, ManimCodeUtils.countLines("single line"));
        assertEquals(3, ManimCodeUtils.countLines("line1\nline2\nline3"));
    }

    @Test
    void hasMainSceneClass_detectsPresence() {
        assertTrue(ManimCodeUtils.hasMainSceneClass("class MainScene(Scene):"));
        assertTrue(ManimCodeUtils.hasMainSceneClass("class MainScene(ThreeDScene):"));
        assertFalse(ManimCodeUtils.hasMainSceneClass("class OtherScene(Scene):"));
        assertFalse(ManimCodeUtils.hasMainSceneClass(null));
    }

    @Test
    void validateManimRules_detectsUndocumentedSetPoints() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        path = VMobject()\n"
                + "        path.set_points([A.get_center(), P.get_center()])";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Static rule violation")
                && v.contains("set_points")));
    }

    @Test
    void validateManimRules_allowsDocumentedSetPointsAsCornersAndSmoothly() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        path = VMobject()\n"
                + "        path.set_points_as_corners([LEFT, UP, RIGHT])\n"
                + "        path.set_points_smoothly([LEFT, UP, RIGHT])";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        assertTrue(violations.stream().noneMatch(v -> v.contains("Static rule violation")));
    }

    @Test
    void validateManimRules_allowsDocumentedMethods() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        dot = Dot()\n"
                + "        dot.move_to(UP)\n"
                + "        dot.set_color(RED)\n"
                + "        dot.next_to(other, RIGHT)\n"
                + "        dot.add_updater(lambda m: m.move_to(UP))";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        assertTrue(violations.stream().noneMatch(v -> v.contains("Static rule violation")));
    }

    @Test
    void validateManimRules_detectsOtherUndocumentedMethods() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        mob.apply_over_attr_arrays(func)";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Static rule violation")
                && v.contains("apply_over_attr_arrays")));
    }

    @Test
    void validateManimRules_skipsCommentLines() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        # path.set_points([LEFT, RIGHT])  <- wrong\n"
                + "        path.set_points_as_corners([LEFT, RIGHT])";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        assertTrue(violations.stream().noneMatch(v -> v.contains("Static rule violation")));
    }

    @Test
    void validateManimRules_flagsTexMathModeMisuse() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        label = Tex(r\"B^\\\\prime\")";

        List<String> violations = ManimCodeUtils.validateManimRules(code);

        assertTrue(violations.stream().anyMatch(v -> v.contains("Tex constructor mismatch")));
    }

    @Test
    void validateManimRules_flagsTextLatexMisuse() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        label = Text(r\"\\\\theta\")";

        List<String> violations = ManimCodeUtils.validateManimRules(code);

        assertTrue(violations.stream().anyMatch(v -> v.contains("Text constructor mismatch")));
    }

    @Test
    void validateManimRules_flagsMathTexPlainSentenceMisuse() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        label = MathTex(\"minimum distance equals segment AB\")";

        List<String> violations = ManimCodeUtils.validateManimRules(code);

        assertTrue(violations.stream().anyMatch(v -> v.contains("MathTex constructor mismatch")));
    }
}
