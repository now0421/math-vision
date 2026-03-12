package com.automanim.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes manim CLI to render Manim Python code into video.
 * This is infrastructure — not a PocketFlow node.
 */
public class ManimRendererService {

    private static final Logger log = LoggerFactory.getLogger(ManimRendererService.class);
    private static final long RENDER_TIMEOUT_MINUTES = 10;

    /**
     * Result of a single manim render attempt.
     */
    public static class RenderAttemptResult {
        private final boolean success;
        private final String stdout;
        private final String stderr;
        private final String videoPath;

        public RenderAttemptResult(boolean success, String stdout, String stderr, String videoPath) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.videoPath = videoPath;
        }

        public boolean success() { return success; }
        public String stdout() { return stdout; }
        public String stderr() { return stderr; }
        public String videoPath() { return videoPath; }
    }

    /**
     * Render a Manim scene from code.
     *
     * @param code       complete Manim Python code
     * @param sceneName  name of the Scene class to render
     * @param quality    "low", "medium", or "high"
     * @param outputDir  directory for output artifacts
     * @return render attempt result
     */
    public RenderAttemptResult render(String code, String sceneName, String quality, Path outputDir) {
        try {
            // Write code to temp file
            Path codeFile = outputDir.resolve("scene_render.py");
            Files.writeString(codeFile, code);

            // Build manim command
            List<String> cmd = buildManimCommand(codeFile, sceneName, quality, outputDir);
            log.info("Rendering: manim {} (quality={})", sceneName, quality);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(outputDir.toFile());
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Capture stdout and stderr
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            boolean finished = process.waitFor(RENDER_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return new RenderAttemptResult(false, stdout, "Render timed out after " + RENDER_TIMEOUT_MINUTES + " minutes", null);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("Manim render failed (exit={}): {}", exitCode, stderr);
                return new RenderAttemptResult(false, stdout, stderr, null);
            }

            // Find output video
            String videoPath = findVideoFile(outputDir, sceneName);
            log.info("Render successful: {}", videoPath);
            return new RenderAttemptResult(true, stdout, stderr, videoPath);

        } catch (IOException | InterruptedException e) {
            log.error("Render execution error: {}", e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new RenderAttemptResult(false, "", e.getMessage(), null);
        }
    }

    private List<String> buildManimCommand(Path codeFile, String sceneName, String quality, Path outputDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add("manim");
        cmd.add("render");

        switch (quality) {
            case "high":
                cmd.add("-qh");
                break;
            case "medium":
                cmd.add("-qm");
                break;
            default:
                cmd.add("-ql");
                break;
        }

        cmd.add("--media_dir");
        cmd.add(outputDir.resolve("media").toString());
        cmd.add("--disable_caching");
        cmd.add(codeFile.toString());
        cmd.add(sceneName);

        return cmd;
    }

    private String findVideoFile(Path outputDir, String sceneName) {
        // Manim outputs to media/videos/<filename>/<quality>/<sceneName>.mp4
        Path mediaDir = outputDir.resolve("media").resolve("videos");
        if (!Files.exists(mediaDir)) return null;

        try (var stream = Files.walk(mediaDir, 4)) {
            return stream
                    .filter(p -> p.toString().endsWith(".mp4"))
                    .filter(p -> p.getFileName().toString().contains(sceneName))
                    .findFirst()
                    .map(Path::toString)
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Could not search for video file: {}", e.getMessage());
            return null;
        }
    }

    private String readStream(java.io.InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
