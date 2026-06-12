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
                "        self.scene_1_intro()",
                "        self.scene_2_finish()",
                "",
                "    # __SCENE_METHODS__");
        List<SceneCodeEntry> entries = List.of(
                new SceneCodeEntry(0, "scene_1", "scene_1_intro",
                        "title = Text(\"Intro\")\nself.play(Write(title))", false),
                new SceneCodeEntry(1, "scene_2", "scene_2_finish",
                        "def scene_2_finish(self):\n    self.wait(1)", false)
        );

        String code = CodeGenerationNode.assembleManimPerSceneCode(skeleton, entries);

        assertTrue(code.contains("    def scene_1_intro(self):\n        title = Text(\"Intro\")"));
        assertTrue(code.contains("    def scene_2_finish(self):\n        self.wait(1)"));
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
                List.of("scene_1_intro", "scene_2_finish"),
                "2d");
        String code = CodeGenerationNode.assembleManimPerSceneCode(skeleton, List.of(
                new SceneCodeEntry(0, "intro", "scene_1_intro", "self.wait(0.1)", false),
                new SceneCodeEntry(1, "finish", "scene_2_finish", "self.wait(0.1)", false)
        ));

        assertTrue(skeleton.contains("class MainScene(Scene):"));
        assertTrue(skeleton.contains("self.objects = {}"));
        assertTrue(skeleton.contains("self.scene_1_intro()"));
        assertTrue(skeleton.contains("self.scene_2_finish()"));
        assertTrue(ManimCodeUtils.validateFull(code).isEmpty());
    }

    @Test
    void staticManimSkeletonEnablesVoiceoverWhenStoryboardHasVoiceoverText() {
        Narrative.StoryboardAction action = new Narrative.StoryboardAction();
        action.setVoiceoverText("这里引入关键结论。");
        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setActions(List.of(action));

        String skeleton = CodeGenerationNode.staticManimSkeleton(
                List.of(scene),
                List.of("scene_1_intro"),
                "2d");

        assertTrue(skeleton.contains("from manim_voiceover import VoiceoverScene"));
        assertTrue(skeleton.contains("from manim_voiceover.services.gtts import GTTSService"));
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
