package com.mathvision.service;

import com.mathvision.util.JsonUtils;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Executes the Manim CLI to render Manim Python code into video.
 * This is infrastructure, not a PocketFlow node.
 */
public class ManimRendererService {

    private static final Logger log = LoggerFactory.getLogger(ManimRendererService.class);
    private static final long RENDER_TIMEOUT_MINUTES = 10;
    private static final String MANIM_EXECUTABLE_ENV = "MATHVISION_MANIM_EXECUTABLE";
    private static final String MANIM_PYTHON_ENV = "MATHVISION_MANIM_PYTHON";
    private static final String GENERATED_SCENE_FILE = "scene_render.py";
    private static final String GEOMETRY_EXPORT_HELPER_FILE = "mathvision_geometry_export.py";
    public static final String GEOMETRY_EXPORT_OUTPUT_FILE = "07_manim_geometry.json";
    private static final String GEOMETRY_EXPORT_HELPER_RESOURCE = "/render/mathvision_geometry_export.py";
    private static final String GEOMETRY_EXPORT_ENV = "MATHVISION_GEOMETRY_PATH";
    private static final String FATAL_STDERR_MESSAGE = "Fatal Manim traceback detected in stderr";
    private static final Pattern PYTHON_EXCEPTION_LINE = Pattern.compile(
            "^\\s*[A-Za-z_][A-Za-z0-9_.]*(?:Error|Exception):\\s+.+"
    );

    /**
     * Result of a single Manim render attempt.
     */
    public static class RenderAttemptResult {
        private final boolean success;
        private final String stdout;
        private final String stderr;
        private final String videoPath;
        private final String geometryPath;
        private final boolean timedOut;

        public RenderAttemptResult(boolean success, String stdout, String stderr,
                                   String videoPath, String geometryPath) {
            this(success, stdout, stderr, videoPath, geometryPath, false);
        }

        public RenderAttemptResult(boolean success, String stdout, String stderr,
                                   String videoPath, String geometryPath, boolean timedOut) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.videoPath = videoPath;
            this.geometryPath = geometryPath;
            this.timedOut = timedOut;
        }

        public boolean success() { return success; }
        public String stdout() { return stdout; }
        public String stderr() { return stderr; }
        public String videoPath() { return videoPath; }
        public String geometryPath() { return geometryPath; }
        public boolean timedOut() { return timedOut; }
    }

    /**
     * Render a Manim scene from code.
     *
     * @param manimCode complete Manim Python code
     * @param sceneName name of the Scene class to render
     * @param quality "low", "medium", or "high"
     * @param outputDir directory for output artifacts
     * @return render attempt result
     */
    public RenderAttemptResult render(String manimCode, String sceneName, String quality, Path outputDir) {
        Path codeFile = null;
        Path geometryHelperFile = null;
        try {
            Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
            Path geometryOutputFile = normalizedOutputDir.resolve(GEOMETRY_EXPORT_OUTPUT_FILE);
            deleteTemporaryFile(geometryOutputFile);

            String sanitizedCode = JsonUtils.extractCodeBlock(manimCode);
            if (sanitizedCode == null || sanitizedCode.isBlank()) {
                sanitizedCode = manimCode;
            }

            boolean voiceoverCode = containsVoiceoverCode(sanitizedCode);
            String renderCode = sanitizedCode;
            try {
                geometryHelperFile = normalizedOutputDir.resolve(GEOMETRY_EXPORT_HELPER_FILE);
                Files.writeString(geometryHelperFile, loadGeometryExportHelperScript(), StandardCharsets.UTF_8);
                renderCode = instrumentCodeWithGeometryExport(sanitizedCode, sceneName);
            } catch (IOException e) {
                geometryHelperFile = null;
                log.warn("Geometry export helper unavailable; rendering without geometry export: {}",
                        e.getMessage());
            }

            codeFile = normalizedOutputDir.resolve(GENERATED_SCENE_FILE);
            Files.writeString(codeFile, renderCode, StandardCharsets.UTF_8);

            List<String> cmd = buildManimCommand(codeFile, sceneName, quality, normalizedOutputDir, voiceoverCode);
            log.info("Rendering: manim {} (quality={})", sceneName, quality);
            log.debug("Render command: {}", String.join(" ", cmd));

            Process process = startProcess(cmd, normalizedOutputDir,
                    geometryHelperFile != null ? geometryOutputFile : null);
            AtomicBoolean fatalStderrDetected = new AtomicBoolean(false);
            AtomicReference<String> fatalStderrLine = new AtomicReference<>();

            ExecutorService streamReaders = Executors.newFixedThreadPool(2);
            Future<String> stdoutFuture = streamReaders.submit(
                    () -> readStream(process.getInputStream(), "stdout", false)
            );
            Future<String> stderrFuture = streamReaders.submit(
                    () -> readStream(process.getErrorStream(), "stderr", true,
                            process, fatalStderrDetected, fatalStderrLine)
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
            String geometryPath = readGeneratedGeometryPath(geometryOutputFile);

            if (fatalStderrDetected.get()) {
                String failureMessage = appendMessage(stderr,
                        FATAL_STDERR_MESSAGE + ": " + fatalStderrLine.get());
                log.warn("Manim render failed after fatal stderr: {}", fatalStderrLine.get());
                return new RenderAttemptResult(false, stdout, failureMessage, null, geometryPath);
            }

            if (!finished) {
                process.destroyForcibly();
                return new RenderAttemptResult(
                        false,
                        stdout,
                        appendMessage(stderr, "Render timed out after " + RENDER_TIMEOUT_MINUTES + " minutes"),
                        null,
                        geometryPath,
                        true
                );
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("Manim render failed (exit={}): {}", exitCode, stderr);
                return new RenderAttemptResult(false, stdout, stderr, null, geometryPath);
            }

            String videoPath = findVideoFile(normalizedOutputDir, sceneName);
            
            if (videoPath == null) {
                log.warn("Manim exited successfully but produced no video file");
                return new RenderAttemptResult(
                        false,
                        stdout,
                        appendMessage(stderr, "No video file produced despite successful exit"),
                        null,
                        geometryPath
                );
            }

            log.info("Render successful: {}", videoPath);
            return new RenderAttemptResult(true, stdout, stderr, videoPath, geometryPath);

        } catch (IOException | InterruptedException e) {
            log.error("Render execution error: {}", e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new RenderAttemptResult(false, "", e.getMessage(), null, null);
        } finally {
            deleteTemporaryFile(codeFile);
            deleteTemporaryFile(geometryHelperFile);
        }
    }

    protected Process startProcess(List<String> cmd, Path workingDir, Path geometryOutputPath)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        if (geometryOutputPath != null) {
            pb.environment().put(GEOMETRY_EXPORT_ENV,
                    geometryOutputPath.toAbsolutePath().normalize().toString());
        }
        return pb.start();
    }

    protected List<String> buildManimCommand(Path codeFile, String sceneName, String quality, Path outputDir,
                                             boolean disableCaching) {
        List<String> cmd = new ArrayList<>(resolveManimLauncherPrefix());
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

        if (disableCaching) {
            cmd.add("--disable_caching");
        }

        cmd.add("--media_dir");
        cmd.add(outputDir.resolve("media").toAbsolutePath().normalize().toString());
        cmd.add(codeFile.getFileName().toString());
        cmd.add(sceneName);

        return cmd;
    }

    private boolean containsVoiceoverCode(String manimCode) {
        return manimCode != null
                && (manimCode.contains("manim_voiceover")
                || manimCode.contains("VoiceoverScene")
                || manimCode.contains("self.voiceover("));
    }

    protected List<String> resolveManimLauncherPrefix() {
        String configuredExecutable = resolveConfiguredManimExecutable();
        if (configuredExecutable != null) {
            return wrapWindowsScriptIfNeeded(configuredExecutable);
        }
        String configuredPython = resolveConfiguredManimPython();
        if (configuredPython != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(configuredPython);
            cmd.add("-m");
            cmd.add("manim");
            return cmd;
        }
        if (isWindows()) {
            List<String> cmd = new ArrayList<>();
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("manim");
            return cmd;
        }
        return List.of("manim");
    }

    protected String resolveConfiguredManimExecutable() {
        return normalizeEnvironmentPath(System.getenv(MANIM_EXECUTABLE_ENV));
    }

    protected String resolveConfiguredManimPython() {
        return normalizeEnvironmentPath(System.getenv(MANIM_PYTHON_ENV));
    }

    private String normalizeEnvironmentPath(String configured) {
        if (configured == null) {
            return null;
        }
        String trimmed = configured.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    protected boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private List<String> wrapWindowsScriptIfNeeded(String executable) {
        if (isWindows() && isWindowsScript(executable)) {
            List<String> cmd = new ArrayList<>();
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(executable);
            return cmd;
        }
        return List.of(executable);
    }

    private boolean isWindowsScript(String executable) {
        String normalized = executable.toLowerCase();
        return normalized.endsWith(".cmd") || normalized.endsWith(".bat");
    }

    private String instrumentCodeWithGeometryExport(String manimCode, String sceneName) {
        return manimCode
                + System.lineSeparator()
                + System.lineSeparator()
                + "from mathvision_geometry_export import patch_scene_for_geometry_export as __mathvision_patch_scene"
                + System.lineSeparator()
                + sceneName + " = __mathvision_patch_scene(" + sceneName + ")"
                + System.lineSeparator();
    }

    private String loadGeometryExportHelperScript() throws IOException {
        try (var in = ManimRendererService.class.getResourceAsStream(GEOMETRY_EXPORT_HELPER_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing geometry export helper resource: " + GEOMETRY_EXPORT_HELPER_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readGeneratedGeometryPath(Path geometryOutputFile) {
        return Files.exists(geometryOutputFile)
                ? geometryOutputFile.toAbsolutePath().normalize().toString()
                : null;
    }

    private void deleteTemporaryFile(Path file) {
        if (file == null) {
            return;
        }

        try {
            if (Files.deleteIfExists(file)) {
                log.debug("Deleted temporary render artifact: {}", file);
            }
        } catch (IOException e) {
            log.warn("Failed to delete temporary render artifact {}: {}", file, e.getMessage());
        }
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
        return readStream(is, streamName, errorStream, null, null, null);
    }

    private String readStream(java.io.InputStream is, String streamName, boolean errorStream,
                              Process process, AtomicBoolean fatalStderrDetected,
                              AtomicReference<String> fatalStderrLine) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            boolean tracebackSeen = false;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
                if (errorStream) {
                    log.warn("[manim:{}] {}", streamName, line);
                    if (line.contains("Traceback (most recent call last)")) {
                        tracebackSeen = true;
                    } else if (tracebackSeen && isPythonExceptionLine(line)
                            && fatalStderrDetected != null
                            && fatalStderrDetected.compareAndSet(false, true)) {
                        fatalStderrLine.set(line.strip());
                        terminateProcessTree(process, FATAL_STDERR_MESSAGE);
                    }
                } else {
                    log.info("[manim:{}] {}", streamName, line);
                }
            }
        }
        return sb.toString();
    }

    private boolean isPythonExceptionLine(String line) {
        return PYTHON_EXCEPTION_LINE.matcher(stripRichConsoleLine(line)).matches();
    }

    private String stripRichConsoleLine(String line) {
        if (line == null) {
            return "";
        }
        String stripped = line.strip();
        if (stripped.startsWith("│") && stripped.endsWith("│") && stripped.length() > 2) {
            stripped = stripped.substring(1, stripped.length() - 1).strip();
        }
        return stripped;
    }

    private void terminateProcessTree(Process process, String reason) {
        if (process == null) {
            return;
        }

        log.warn("Terminating Manim render process: {}", reason);
        try {
            process.toHandle().descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (UnsupportedOperationException | SecurityException e) {
                    log.debug("Could not terminate descendant process {}: {}", handle.pid(), e.getMessage());
                }
            });
        } catch (UnsupportedOperationException | SecurityException e) {
            log.debug("Could not enumerate Manim descendant processes: {}", e.getMessage());
        }
        process.destroyForcibly();
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
