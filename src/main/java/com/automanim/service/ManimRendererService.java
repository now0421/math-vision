package com.automanim.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes the Manim CLI to render Manim Python code into video.
 * This is infrastructure, not a PocketFlow node.
 */
public class ManimRendererService {

    private static final Logger log = LoggerFactory.getLogger(ManimRendererService.class);
    private static final long RENDER_TIMEOUT_MINUTES = 10;

    /**
     * Result of a single Manim render attempt.
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
     * @param code complete Manim Python code
     * @param sceneName name of the Scene class to render
     * @param quality "low", "medium", or "high"
     * @param outputDir directory for output artifacts
     * @return render attempt result
     */
    public RenderAttemptResult render(String code, String sceneName, String quality, Path outputDir) {
        try {
            Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();

            Path codeFile = normalizedOutputDir.resolve("scene_render.py");
            Files.writeString(codeFile, code, StandardCharsets.UTF_8);

            List<String> cmd = buildManimCommand(codeFile, sceneName, quality, normalizedOutputDir);
            log.info("Rendering: manim {} (quality={})", sceneName, quality);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(normalizedOutputDir.toFile());
            pb.redirectErrorStream(false);
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process process = pb.start();

            ExecutorService streamReaders = Executors.newFixedThreadPool(2);
            Future<String> stdoutFuture = streamReaders.submit(
                    () -> readStream(process.getInputStream(), "stdout", false)
            );
            Future<String> stderrFuture = streamReaders.submit(
                    () -> readStream(process.getErrorStream(), "stderr", true)
            );

            boolean finished = process.waitFor(RENDER_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                log.warn("Render timed out after {} minutes; terminating process", RENDER_TIMEOUT_MINUTES);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

            String stdout = awaitCapturedOutput(stdoutFuture, "stdout");
            String stderr = awaitCapturedOutput(stderrFuture, "stderr");
            streamReaders.shutdown();

            if (!finished) {
                process.destroyForcibly();
                return new RenderAttemptResult(
                        false,
                        stdout,
                        appendMessage(stderr, "Render timed out after " + RENDER_TIMEOUT_MINUTES + " minutes"),
                        null
                );
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("Manim render failed (exit={}): {}", exitCode, stderr);
                return new RenderAttemptResult(false, stdout, stderr, null);
            }

            String videoPath = findVideoFile(normalizedOutputDir, sceneName);
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
        cmd.add(outputDir.resolve("media").toAbsolutePath().normalize().toString());
        cmd.add("--disable_caching");
        cmd.add(codeFile.getFileName().toString());
        cmd.add(sceneName);

        return cmd;
    }

    private String findVideoFile(Path outputDir, String sceneName) {
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

    private String readStream(java.io.InputStream is, String streamName, boolean errorStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
                if (errorStream) {
                    log.warn("[manim:{}] {}", streamName, line);
                } else {
                    log.info("[manim:{}] {}", streamName, line);
                }
            }
        }
        return sb.toString();
    }

    private String awaitCapturedOutput(Future<String> future, String streamName) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Timed out while waiting for {} stream reader to finish", streamName);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for {} stream reader", streamName);
            return "";
        } catch (ExecutionException e) {
            log.warn("Failed to capture {} output: {}", streamName, e.getCause().getMessage());
            return "";
        }
    }

    private String appendMessage(String existing, String message) {
        if (existing == null || existing.isBlank()) {
            return message;
        }
        return existing.strip() + "\n" + message;
    }
}
