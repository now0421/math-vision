package com.automanim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManimRendererServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesTemporarySceneFileWhenProcessFailsToStart() {
        ManimRendererService service = new ManimRendererService() {
            @Override
            protected Process startProcess(List<String> cmd, Path workingDir, Path geometryOutputPath)
                    throws IOException {
                throw new IOException("simulated manim startup failure");
            }
        };

        Path codeFile = tempDir.resolve("scene_render.py");
        ManimRendererService.RenderAttemptResult result = service.render(
                "from manim import *\n\nclass DemoScene(Scene):\n    def construct(self):\n        pass\n",
                "DemoScene",
                "low",
                tempDir
        );

        assertFalse(result.success());
        assertTrue(result.stderr().contains("simulated manim startup failure"));
        assertFalse(Files.exists(codeFile));
    }

    @Test
    void injectsGeometryExportHookIntoTemporaryRenderScript() {
        Path helperFile = tempDir.resolve("automanim_geometry_export.py");

        ManimRendererService service = new ManimRendererService() {
            @Override
            protected Process startProcess(List<String> cmd, Path workingDir, Path geometryOutputPath)
                    throws IOException {
                String script = Files.readString(workingDir.resolve("scene_render.py"));
                assertTrue(script.contains("from automanim_geometry_export import patch_scene_for_geometry_export"));
                assertTrue(script.contains("DemoScene = __automanim_patch_scene(DemoScene)"));
                assertTrue(Files.exists(workingDir.resolve("automanim_geometry_export.py")));
                assertNotNull(geometryOutputPath);
                assertEquals(workingDir.resolve("5_mobject_geometry.json"), geometryOutputPath);
                throw new IOException("stop after inspection");
            }
        };

        ManimRendererService.RenderAttemptResult result = service.render(
                String.join("\n",
                        "from manim import *",
                        "",
                        "class DemoScene(Scene):",
                        "    def construct(self):",
                        "        self.add(Dot())"),
                "DemoScene",
                "low",
                tempDir
        );

        assertFalse(result.success());
        assertTrue(result.stderr().contains("stop after inspection"));
        assertFalse(Files.exists(tempDir.resolve("scene_render.py")));
        assertFalse(Files.exists(helperFile));
    }

    @Test
    void geometryExportHelperTracksOnlyExplicitRemovalTargets() {
        ManimRendererService service = new ManimRendererService() {
            @Override
            protected Process startProcess(List<String> cmd, Path workingDir, Path geometryOutputPath)
                    throws IOException {
                String helperScript = Files.readString(workingDir.resolve("automanim_geometry_export.py"));
                assertTrue(helperScript.contains("\"expected_removed_object_ids\": []"));
                assertTrue(helperScript.contains("def _iter_removal_target_descriptors_from_arg"));
                assertTrue(helperScript.contains("for attr_name in (\"mobject\", \"starting_mobject\")"));
                assertTrue(helperScript.contains("\"fixed_in_frame_mobjects\""));
                assertTrue(helperScript.contains("\"fixed_orientation_mobjects\""));
                throw new IOException("stop after inspection");
            }
        };

        ManimRendererService.RenderAttemptResult result = service.render(
                String.join("\n",
                        "from manim import *",
                        "",
                        "class DemoScene(Scene):",
                        "    def construct(self):",
                        "        dot = Dot()",
                        "        self.add(dot)",
                        "        self.play(Flash(dot))"),
                "DemoScene",
                "low",
                tempDir
        );

        assertFalse(result.success());
        assertTrue(result.stderr().contains("stop after inspection"));
        assertFalse(Files.exists(tempDir.resolve("scene_render.py")));
    }

    @Test
    void stripsMarkdownFencesBeforeWritingTemporaryRenderScript() {
        ManimRendererService service = new ManimRendererService() {
            @Override
            protected Process startProcess(List<String> cmd, Path workingDir, Path geometryOutputPath)
                    throws IOException {
                String script = Files.readString(workingDir.resolve("scene_render.py"));
                assertTrue(script.startsWith("from manim import *"));
                assertFalse(script.startsWith("```"));
                throw new IOException("stop after inspection");
            }
        };

        ManimRendererService.RenderAttemptResult result = service.render(
                String.join("\n",
                        "```python",
                        "from manim import *",
                        "",
                        "class DemoScene(Scene):",
                        "    def construct(self):",
                        "        self.add(Dot())"),
                "DemoScene",
                "low",
                tempDir
        );

        assertFalse(result.success());
        assertTrue(result.stderr().contains("stop after inspection"));
        assertFalse(Files.exists(tempDir.resolve("scene_render.py")));
    }
}
