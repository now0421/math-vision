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
    void enforceMainSceneName_preservesVoiceoverSceneBase() {
        String code = "class VoiceScene(VoiceoverScene):\n    def construct(self):\n        pass";
        String enforced = ManimCodeUtils.enforceMainSceneName(code);
        assertTrue(enforced.contains("class MainScene(VoiceoverScene)"));
        assertFalse(enforced.contains("VoiceScene"));
    }

    @Test
    void enforceMainSceneName_preservesOtherSceneBaseClasses() {
        String code = "class FocusScene(MovingCameraScene):\n    def construct(self):\n        pass";
        String enforced = ManimCodeUtils.enforceMainSceneName(code);
        assertTrue(enforced.contains("class MainScene(MovingCameraScene)"));
        assertFalse(enforced.contains("FocusScene"));
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
    void validateStructure_passesVoiceoverSceneCode() {
        String code = String.join("\n",
                "from manim import *",
                "from manim_voiceover import VoiceoverScene",
                "",
                "class MainScene(VoiceoverScene):",
                "    def construct(self):",
                "        self.wait(1)");
        List<String> violations = ManimCodeUtils.validateStructure(code);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void validateStructure_allowsNonAsciiTextLiterals() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        label = Text(\"最小值 = 2, Δ\")";
        List<String> violations = ManimCodeUtils.validateStructure(code);
        assertTrue(violations.isEmpty());
    }

    @Test
    void validateStructure_detectsClassStubWithTopLevelSceneImplementation() {
        String code = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.scene_1_intro()",
                "",
                "    def scene_1_intro(self):",
                "        pass",
                "",
                "def scene_1_intro(self):",
                "    title = Text(\"real implementation\")",
                "    self.play(Write(title))");

        List<String> violations = ManimCodeUtils.validateFull(code);

        assertTrue(violations.stream().anyMatch(v -> v.contains("only contains pass")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("implemented outside MainScene")));
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
        assertTrue(ManimCodeUtils.hasMainSceneClass("class MainScene(VoiceoverScene):"));
        assertTrue(ManimCodeUtils.hasMainSceneClass("class MainScene(MovingCameraScene):"));
        assertTrue(ManimCodeUtils.hasMainSceneClass("class MainScene(ZoomedScene):"));
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
                + "        dot.set_color(\"#EF4444\")\n"
                + "        dot.next_to(other, RIGHT)\n"
                + "        dot.add_updater(lambda m: m.move_to(UP))";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        assertTrue(violations.stream().noneMatch(v -> v.contains("Static rule violation")));
    }

    @Test
    void validateManimRules_doesNotValidateGeneratedCodeColors() {
        String code = "from manim import *\n\nBG = BLACK\nPRIMARY = BLUE\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        self.camera.background_color = BG\n"
                + "        dot = Dot(color=YELLOW)\n"
                + "        dot.set_color(RED)\n"
                + "        other = Dot(color=\"#AAFFCCDD\")";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        assertTrue(violations.stream().noneMatch(v -> v.contains("color")));
    }

    @Test
    void validateManimRules_allowsImportedExternalModuleCalls() {
        String code = "from manim import *\nimport numpy as np\nimport math\n\nclass MainScene(Scene):\n"
                + "    def construct(self):\n"
                + "        point = Dot(np.array([0, 0, 0]))\n"
                + "        length = np.linalg.norm(point.get_center())\n"
                + "        angle = math.atan2(1, 1)\n"
                + "        self.wait(0.5)";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        assertTrue(violations.stream().noneMatch(v -> v.contains("Static rule violation")));
    }

    @Test
    void validateManimRules_warnsOnlyForUnimportedExternalModuleCalls() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        point = Dot(np.array([0, 0, 0]))";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        List<String> warnings = ManimCodeUtils.validateManimApiWhitelistWarnings(code);
        assertTrue(violations.stream().noneMatch(v -> v.contains("np.array")));
        assertTrue(warnings.stream().anyMatch(v -> v.contains("Static rule warning")
                && v.contains("np.array")));
    }

    @Test
    void validateManimRules_warnsOnlyForOtherUndocumentedMethods() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        mob.apply_over_attr_arrays(func)";
        List<String> violations = ManimCodeUtils.validateManimRules(code);
        List<String> warnings = ManimCodeUtils.validateManimApiWhitelistWarnings(code);
        assertTrue(violations.stream().noneMatch(v -> v.contains("apply_over_attr_arrays")));
        assertTrue(warnings.stream().anyMatch(v -> v.contains("Static rule warning")
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
    void validateManimRules_allowsAmbiguousSingleWordMobjectMethods() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        length_number = DecimalNumber(0)\n"
                + "        length_number.scale(0.72)\n"
                + "        length_number.become(DecimalNumber(1))\n"
                + "        length_number.update()";

        List<String> warnings = ManimCodeUtils.validateFullWarnings(code);

        assertTrue(warnings.stream().noneMatch(v -> v.contains("scale")), () -> String.join("\n", warnings));
        assertTrue(warnings.stream().noneMatch(v -> v.contains("become")), () -> String.join("\n", warnings));
        assertTrue(warnings.stream().noneMatch(v -> v.contains("update")), () -> String.join("\n", warnings));
    }

    @Test
    void validateManimRules_allowsChineseProseWithInlineMathLabels() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        finalText = Text(\"最佳取水点就是 P₀\", font=\"Microsoft YaHei\")\n"
                + "        hint = Text(\"交点记作 P₀\", font=\"Microsoft YaHei\")\n"
                + "        reflection = Text(\"把 B′ 接回原来的 B\", font=\"Microsoft YaHei\")";

        List<String> warnings = ManimCodeUtils.validateFullWarnings(code);

        assertTrue(warnings.stream().noneMatch(v -> v.contains("Text constructor with math-like content")),
                () -> String.join("\n", warnings));
    }

    @Test
    void validateManimRules_stillFlagsFormulaDominantTextConstructorMisuse() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        formula = Text(\"L(P)=AP+PB\")\n"
                + "        ineq = Text(r\"AP+PB\\\\geq AP_0+P_0B\")";

        List<String> warnings = ManimCodeUtils.validateFullWarnings(code);

        assertTrue(warnings.stream().anyMatch(v -> v.contains("Text constructor with math-like content")
                && v.contains("L(P)=AP+PB")), () -> String.join("\n", warnings));
        assertTrue(warnings.stream().anyMatch(v -> v.contains("Text constructor with math-like content")
                && v.contains("AP+PB")), () -> String.join("\n", warnings));
    }

    @Test
    void validateManimRules_stillFlagsUnimportedCommonExternalModuleCalls() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        point = Dot(np.array([0, 0, 0]))";

        List<String> warnings = ManimCodeUtils.validateFullWarnings(code);

        assertTrue(warnings.stream().anyMatch(v -> v.contains("Static rule warning")
                && v.contains("np.array")), () -> String.join("\n", warnings));
    }

    @Test
    void validateManimRules_flagsTexMathModeMisuse() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        label = Tex(r\"B^\\\\prime\")";

        List<String> warnings = ManimCodeUtils.validateFullWarnings(code);

        assertTrue(warnings.stream().anyMatch(v -> v.contains("Tex constructor with math-mode content")));
    }

    @Test
    void validateManimRules_flagsTextLatexMisuse() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        label = Text(r\"\\\\theta\")";

        List<String> warnings = ManimCodeUtils.validateFullWarnings(code);

        assertTrue(warnings.stream().anyMatch(v -> v.contains("Text constructor with math-like content")));
    }

    @Test
    void validateManimRules_flagsMathTexPlainSentenceMisuse() {
        String code = "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n"
                + "        label = MathTex(\"minimum distance equals segment AB\")";

        List<String> warnings = ManimCodeUtils.validateFullWarnings(code);

        assertTrue(warnings.stream().anyMatch(v -> v.contains("MathTex constructor with plain-language content")));
    }
}
