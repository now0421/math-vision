package com.mathvision.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        Path helperFile = tempDir.resolve("mathvision_geometry_export.py");

        ManimRendererService service = new ManimRendererService() {
            @Override
            protected Process startProcess(List<String> cmd, Path workingDir, Path geometryOutputPath)
                    throws IOException {
                assertFalse(cmd.contains("--disable_caching"));
                String script = Files.readString(workingDir.resolve("scene_render.py"));
                assertTrue(script.contains("from mathvision_geometry_export import patch_scene_for_geometry_export"));
                assertTrue(script.contains("DemoScene = __mathvision_patch_scene(DemoScene)"));
                assertTrue(Files.exists(workingDir.resolve("mathvision_geometry_export.py")));
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
                String helperScript = Files.readString(workingDir.resolve("mathvision_geometry_export.py"));
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

    @Test
    void fatalTracebackOnStderrTerminatesRenderBeforeTimeout() {
        BlockingProcess process = new BlockingProcess(String.join("\n",
                "Animation 7: FadeIn(Text('AP + PB = 0.0 km')):   0%|          | 0/12 [00:00<?, ?it/s]",
                "Traceback (most recent call last):",
                "  File \"scene_render.py\", line 114, in scene_2_drag_p_and_watch_the_path",
                "ValueError: zip() argument 2 is longer than argument 1"));

        ManimRendererService service = new ManimRendererService() {
            @Override
            protected Process startProcess(List<String> cmd, Path workingDir, Path geometryOutputPath) {
                return process;
            }
        };

        long startedAt = System.nanoTime();
        ManimRendererService.RenderAttemptResult result = service.render(
                "from manim import *\n\nclass DemoScene(Scene):\n    def construct(self):\n        pass\n",
                "DemoScene",
                "low",
                tempDir
        );
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertFalse(result.success());
        assertFalse(result.timedOut());
        assertTrue(process.destroyed);
        assertTrue(elapsedMillis < 2_000, "fatal stderr should not wait for the render timeout");
        assertTrue(result.stderr().contains("ValueError: zip() argument 2 is longer than argument 1"));
        assertTrue(result.stderr().contains("Fatal Manim traceback detected in stderr"));
    }

    private static final class BlockingProcess extends Process {
        private final InputStream stdout = new ByteArrayInputStream(new byte[0]);
        private final InputStream stderr;
        private final CountDownLatch destroyedLatch = new CountDownLatch(1);
        private volatile boolean destroyed;

        private BlockingProcess(String stderr) {
            this.stderr = new ByteArrayInputStream(stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            destroyedLatch.await();
            return 1;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return destroyedLatch.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (!destroyed) {
                throw new IllegalThreadStateException("process still running");
            }
            return 1;
        }

        @Override
        public void destroy() {
            destroyed = true;
            destroyedLatch.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }
    }
}
