package com.mathvision.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManimRendererCommandResolutionTest {

    @Test
    void windowsUsesCmdWrapperForDefaultManimCommand() {
        ManimRendererService service = new ManimRendererService() {
            @Override
            protected boolean isWindows() {
                return true;
            }

            @Override
            protected String resolveConfiguredManimExecutable() {
                return null;
            }
        };

        List<String> cmd = service.resolveManimLauncherPrefix();

        assertEquals(List.of("cmd.exe", "/c", "manim"), cmd);
    }

    @Test
    void configuredWindowsCmdScriptAlsoUsesCmdWrapper() {
        ManimRendererService service = new ManimRendererService() {
            @Override
            protected boolean isWindows() {
                return true;
            }

            @Override
            protected String resolveConfiguredManimExecutable() {
                return "D:\\tools\\ffmpeg\\ffmpeg-8.0.1-essentials_build\\bin\\manim.cmd";
            }
        };

        List<String> cmd = service.resolveManimLauncherPrefix();

        assertEquals(List.of(
                "cmd.exe",
                "/c",
                "D:\\tools\\ffmpeg\\ffmpeg-8.0.1-essentials_build\\bin\\manim.cmd"
        ), cmd);
    }

    @Test
    void configuredExecutablePathIsUsedAsIsWhenNotBatchScript() {
        ManimRendererService service = new ManimRendererService() {
            @Override
            protected boolean isWindows() {
                return true;
            }

            @Override
            protected String resolveConfiguredManimExecutable() {
                return "C:\\Python\\Scripts\\manim.exe";
            }
        };

        List<String> cmd = service.resolveManimLauncherPrefix();

        assertEquals(List.of("C:\\Python\\Scripts\\manim.exe"), cmd);
    }
}
