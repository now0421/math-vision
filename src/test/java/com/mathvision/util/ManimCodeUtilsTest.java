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
    void buildSceneMethodName_usesOnlySequentialSceneNumber() {
        assertEquals("scene_1", ManimCodeUtils.buildSceneMethodName("intro", "Setup Coordinates", 0));
        assertEquals("scene_2", ManimCodeUtils.buildSceneMethodName("custom_id", "Finish!", 1));
    }

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
    void validateManimRules_detectsConstructorOpacityKeyword() {
        String code = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        seg = Line(LEFT, RIGHT, color=BLUE, opacity=0.6)",
                "        arc = Arc(",
                "            radius=1.5,",
                "            start_angle=0,",
                "            angle=PI,",
                "            opacity=0.8,",
                "        )",
                "        dot = Dot(color=RED)",
                "        dot.set_opacity(0.5)",
                "        seg.set_stroke(opacity=0.4)",
                "        label = Text(\"opacity=0.9 is only prose\")");

        List<String> violations = ManimCodeUtils.validateManimRules(code);

        assertTrue(violations.stream().anyMatch(v -> v.contains("constructor opacity keyword")
                && v.contains("Line(...)")), () -> String.join("\n", violations));
        assertTrue(violations.stream().anyMatch(v -> v.contains("constructor opacity keyword")
                && v.contains("Arc(...)")), () -> String.join("\n", violations));
        assertTrue(violations.stream().noneMatch(v -> v.contains("set_opacity")),
                () -> String.join("\n", violations));
        assertTrue(violations.stream().noneMatch(v -> v.contains("set_stroke")),
                () -> String.join("\n", violations));
        assertTrue(violations.stream().noneMatch(v -> v.contains("only prose")),
                () -> String.join("\n", violations));
    }

    @Test
    void validateManimRules_allowsDocumentedOpacityStyling() {
        String code = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        circle = Circle(radius=1.2, color=BLUE, fill_opacity=0.25)",
                "        card = BackgroundRectangle(circle, fill_opacity=0.8, buff=0.2)",
                "        curve = VMobject()",
                "        curve.set_stroke(BLUE, width=2, opacity=0.5)",
                "        circle.set_fill(BLUE, opacity=0.3)",
                "        circle.set_opacity(0.7)");

        List<String> violations = ManimCodeUtils.validateManimRules(code);

        assertTrue(violations.stream().noneMatch(v -> v.contains("constructor opacity keyword")),
                () -> String.join("\n", violations));
    }

    @Test
    void validateFullDetectsGeneratedCoordinateScaleRegression() {
        String code = String.join("\n",
                "from manim import *",
                "import numpy as np",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.setup_shared_scene()",
                "        self.wait(1)",
                "",
                "    def setup_shared_scene(self):",
                "        self._mv_x_range = [-3.5, 7.0, 1.0]",
                "        self._mv_y_range = [-6.5, 5.0, 1.0]",
                "        self._mv_z_range = None",
                "        self._mv_axes = None",
                "        self.axes = Axes(",
                "            x_range=self._mv_x_range,",
                "            y_range=self._mv_y_range,",
                "            x_length=11.0,",
                "            y_length=8.5,",
                "            tips=False,",
                "        )",
                "        self._mv_axes = self.axes",
                "",
                "    def world_point(self, x, y=0.0, z=0.0):",
                "        return self._mv_axes.c2p(x, y)",
                "",
                "    def c2p(self, x, y=0.0, z=0.0):",
                "        return self.world_point(x, y, z)");

        List<String> violations = ManimCodeUtils.validateFull(code);

        assertTrue(ManimCodeUtils.hasCoordinateScaleContractViolation(violations));
        assertTrue(violations.stream().anyMatch(v -> v.contains("_mv_unit_scale")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("raw numeric axis lengths")));
    }

    @Test
    void validateFullDetectsRawStoryboardCircleAndArcRadii() {
        String code = String.join("\n",
                "from manim import *",
                "import numpy as np",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.setup_shared_scene()",
                "        circle = Circle(radius=4)",
                "        arc = Arc(radius=4.0, start_angle=0, angle=PI)",
                "        self.add(circle, arc)",
                "",
                "    def setup_shared_scene(self):",
                "        self._mv_x_range = [-5.0, 5.0, 1.0]",
                "        self._mv_y_range = [-5.0, 5.0, 1.0]",
                "        self._mv_z_range = None",
                "        self._mv_frame_width = 10.5",
                "        self._mv_frame_height = 6.5",
                "        self._mv_unit_scale = 1.0",
                "        self._mv_x_length = self._mv_frame_width",
                "        self._mv_y_length = self._mv_frame_height",
                "        self._mv_axes = None",
                "        self.axes = Axes(",
                "            x_range=self._mv_x_range,",
                "            y_range=self._mv_y_range,",
                "            x_length=self._mv_x_length,",
                "            y_length=self._mv_y_length,",
                "            tips=False,",
                "        )",
                "        self._mv_axes = self.axes",
                "",
                "    def world_point(self, x, y=0.0, z=0.0):",
                "        return self._mv_axes.c2p(x, y)",
                "",
                "    def c2p(self, x, y=0.0, z=0.0):",
                "        return self.world_point(x, y, z)",
                "",
                "    def world_radius(self, radius):",
                "        return abs(radius) * getattr(self, '_mv_unit_scale', 1.0)",
                "",
                "    def world_circle(self, x, y, radius, **kwargs):",
                "        circle = Circle(radius=self.world_radius(radius), **kwargs)",
                "        circle.move_to(self.c2p(x, y))",
                "        return circle",
                "",
                "    def world_arc(self, x, y, radius, start_angle=0.0, angle=TAU, **kwargs):",
                "        return Arc(radius=self.world_radius(radius), start_angle=start_angle,",
                "                   angle=angle, arc_center=self.c2p(x, y), **kwargs)");

        List<String> violations = ManimCodeUtils.validateFull(code);

        assertTrue(ManimCodeUtils.hasCoordinateScaleContractViolation(violations));
        assertTrue(violations.stream().anyMatch(v -> v.contains("Circle/Arc radius")));
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
