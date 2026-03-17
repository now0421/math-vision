package com.automanim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManimRendererServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesTemporarySceneFileWhenProcessFailsToStart() {
        ManimRendererService service = new ManimRendererService() {
            @Override
            protected Process startProcess(List<String> cmd, Path workingDir) throws IOException {
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
}
