package com.mathvision.node;

import com.mathvision.model.SceneCodeEntry;
import com.mathvision.model.Narrative;
import com.mathvision.util.ManimCodeUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGenerationNodeAssemblyTest {

    @Test
    void assembleManimPerSceneCodeInsertsMethodsInsideMainScene() {
        String skeleton = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.scene_1()",
                "        self.scene_2()",
                "",
                "    # __SCENE_METHODS__");
        List<SceneCodeEntry> entries = List.of(
                new SceneCodeEntry(0, "scene_1", "scene_1",
                        "title = Text(\"Intro\")\nself.play(Write(title))", false),
                new SceneCodeEntry(1, "scene_2", "scene_2",
                        "def scene_2_finish(self):\n    self.wait(1)", false)
        );

        String code = CodeGenerationNode.assembleManimPerSceneCode(skeleton, entries);

        assertTrue(code.contains("    def scene_1(self):\n        title = Text(\"Intro\")"));
        assertTrue(code.contains("    def scene_2(self):\n        self.wait(1)"));
        assertFalse(code.contains("def scene_1_intro"));
        assertFalse(code.contains("def scene_2_finish"));
        assertFalse(code.contains("\ndef scene_1_intro(self):"));
        assertFalse(code.contains("\ndef scene_2_finish(self):"));
        assertTrue(ManimCodeUtils.validateFull(code).isEmpty());
    }

    @Test
    void staticManimSkeletonBuildsRunnableMainScene() {
        Narrative.StoryboardScene first = new Narrative.StoryboardScene();
        first.setSceneId("intro");
        first.setTitle("Intro");
        Narrative.StoryboardScene second = new Narrative.StoryboardScene();
        second.setSceneId("finish");
        second.setTitle("Finish");

        String skeleton = CodeGenerationNode.staticManimSkeleton(
                List.of(first, second),
                List.of("scene_1", "scene_2"),
                "2d");
        String code = CodeGenerationNode.assembleManimPerSceneCode(skeleton, List.of(
                new SceneCodeEntry(0, "intro", "scene_1", "self.wait(0.1)", false),
                new SceneCodeEntry(1, "finish", "scene_2", "self.wait(0.1)", false)
        ));

        assertTrue(skeleton.contains("class MainScene(Scene):"));
        assertTrue(skeleton.contains("import numpy as np"));
        assertTrue(skeleton.contains("self.objects = {}"));
        assertTrue(skeleton.contains("self.setup_shared_scene()"));
        assertTrue(skeleton.contains("def register_object(self, object_id, mobject):"));
        assertTrue(skeleton.contains("def get_object(self, object_id):"));
        assertTrue(skeleton.contains("self.scene_1()"));
        assertTrue(skeleton.contains("self.scene_2()"));
        assertFalse(skeleton.contains("scene_1_intro"));
        assertFalse(skeleton.contains("scene_2_finish"));
        assertTrue(ManimCodeUtils.validateFull(code).isEmpty());
    }

    @Test
    void staticManimSkeletonBuildsSharedCoordinateHelpersFromStoryboardBounds() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        Narrative.StoryboardCoordinateBounds bounds = new Narrative.StoryboardCoordinateBounds();
        bounds.setX(new Narrative.StoryboardCoordinateBoundsAxis(-4.0, 4.0));
        bounds.setY(new Narrative.StoryboardCoordinateBoundsAxis(-2.0, 3.0));
        bounds.setPadding(1.0);
        storyboard.setCoordinateBounds(bounds);

        Narrative.StoryboardScene first = new Narrative.StoryboardScene();
        first.setSceneId("intro");
        first.setTitle("Intro");

        String skeleton = CodeGenerationNode.staticManimSkeleton(
                storyboard,
                List.of(first),
                List.of("scene_1"),
                "2d");
        String code = CodeGenerationNode.assembleManimPerSceneCode(skeleton, List.of(
                new SceneCodeEntry(0, "intro", "scene_1",
                        "point = Dot(self.world_point(0, 0))\nself.register_object(\"origin\", point)\nself.add(point)",
                        false)
        ));

        assertTrue(skeleton.contains("self._mv_x_range = [-5.0, 5.0, 2.0]"));
        assertTrue(skeleton.contains("self._mv_y_range = [-3.0, 4.0, 1.0]"));
        assertTrue(skeleton.contains("self.axes = Axes("));
        assertTrue(skeleton.contains("scale_candidates = [self._mv_frame_width / x_span, self._mv_frame_height / y_span]"));
        assertTrue(skeleton.contains("self._mv_unit_scale = min(scale_candidates)"));
        assertTrue(skeleton.contains("x_length=self._mv_x_length"));
        assertTrue(skeleton.contains("y_length=self._mv_y_length"));
        assertFalse(skeleton.contains("x_length=10.5"));
        assertFalse(skeleton.contains("y_length=6.5"));
        assertTrue(skeleton.contains("def world_point(self, x, y=0.0, z=0.0):"));
        assertTrue(skeleton.contains("def c2p(self, x, y=0.0, z=0.0):"));
        assertTrue(skeleton.contains("def world_radius(self, radius):"));
        assertTrue(skeleton.contains("def world_circle(self, x, y, radius, **kwargs):"));
        assertTrue(skeleton.contains("def world_arc(self, x, y, radius, start_angle=0.0, angle=TAU, **kwargs):"));
        assertTrue(ManimCodeUtils.validateFull(code).isEmpty());
    }

    @Test
    void staticManimSkeletonUsesThreeDSceneAndThreeDAxesFor3dModeWithoutVoiceover() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        Narrative.StoryboardCoordinateBounds bounds = new Narrative.StoryboardCoordinateBounds();
        bounds.setX(new Narrative.StoryboardCoordinateBoundsAxis(-2.0, 2.0));
        bounds.setY(new Narrative.StoryboardCoordinateBoundsAxis(-2.0, 2.0));
        bounds.setZ(new Narrative.StoryboardCoordinateBoundsAxis(-1.0, 3.0));
        storyboard.setCoordinateBounds(bounds);
        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();

        String skeleton = CodeGenerationNode.staticManimSkeleton(
                storyboard,
                List.of(scene),
                List.of("scene_1"),
                "3d");

        assertTrue(skeleton.contains("class MainScene(ThreeDScene):"));
        assertTrue(skeleton.contains("self._mv_z_range = [-2.0, 4.0, 1.0]"));
        assertTrue(skeleton.contains("self.axes = ThreeDAxes("));
        assertTrue(skeleton.contains("scale_candidates.append(self._mv_frame_depth / z_span)"));
        assertTrue(skeleton.contains("z_length=self._mv_z_length"));
    }

    @Test
    void staticManimSkeletonEnablesVoiceoverWhenStoryboardHasVoiceoverText() {
        Narrative.StoryboardAction action = new Narrative.StoryboardAction();
        action.setVoiceoverText("这里引入关键结论。");
        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setActions(List.of(action));

        String skeleton = CodeGenerationNode.staticManimSkeleton(
                List.of(scene),
                List.of("scene_1"),
                "2d");

        assertTrue(skeleton.contains("from manim_voiceover import VoiceoverScene"));
        assertTrue(skeleton.contains("from manim_voiceover.services.gtts import GTTSService"));
        assertTrue(skeleton.contains("VOICEOVER_SPEED = 1.5"));
        assertTrue(skeleton.contains("class MainScene(VoiceoverScene):"));
        assertTrue(skeleton.contains("self.set_speech_service(GTTSService(lang=\"zh-CN\", global_speed=VOICEOVER_SPEED))"));
    }

    @Test
    void staticGeoGebraSkeletonUsesStoryboardBounds() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        Narrative.StoryboardCoordinateBounds bounds = new Narrative.StoryboardCoordinateBounds();
        bounds.setX(new Narrative.StoryboardCoordinateBoundsAxis(-4.0, 4.0));
        bounds.setY(new Narrative.StoryboardCoordinateBoundsAxis(-2.0, 3.0));
        storyboard.setCoordinateBounds(bounds);

        String skeleton = CodeGenerationNode.staticGeoGebraSkeleton(
                storyboard,
                List.of("Scene 1: Intro", "Scene 2: Finish"));

        assertTrue(skeleton.contains("SetCoordSystem(-4, 4, -2, 3)"));
        assertTrue(skeleton.contains("Scene sequence: Scene 1: Intro | Scene 2: Finish"));
    }
}
