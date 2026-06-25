package com.mathvision.service;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeResult;
import com.mathvision.model.CodeFixTraceReport;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.Narrative;
import com.mathvision.model.ProblemBundle;
import com.mathvision.model.ProblemSource;
import com.mathvision.model.RenderResult;
import com.mathvision.model.CodeEvaluationResult;
import com.mathvision.model.SceneEvaluationResult;
import com.mathvision.model.StoryboardValidationReport;
import com.mathvision.util.GeoGebraCodeUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles persisting intermediate workflow results to disk.
 */
public class FileOutputService {

    private static final Logger log = LoggerFactory.getLogger(FileOutputService.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    public static final String PROBLEM_SOURCE_FILE = "00_problem_source.json";
    public static final String PROBLEM_BUNDLE_FILE = "00_problem_bundle.json";
    public static final String KNOWLEDGE_GRAPH_FILE = "01_knowledge_graph.json";
    public static final String KNOWLEDGE_GRAPH_PRETTY_FILE = "01_knowledge_graph_pretty.txt";
    public static final String MATH_ENRICHED_GRAPH_FILE = "02_math_enriched_graph.json";
    public static final String VISUAL_NARRATIVE_FILE = "03_visual_narrative.json";
    public static final String VALIDATED_STORYBOARD_FILE = "04_storyboard_validated.json";
    public static final String STORYBOARD_VALIDATION_REPORT_FILE = "04_storyboard_validation_report.json";
    public static final String CODE_RESULT_FILE = "05_code_result.json";
    public static final String MANIM_CODE_FILE = "05_manim_code.py";
    public static final String GEOGEBRA_COMMANDS_FILE = "05_geogebra_commands.txt";
    public static final String CODE_EVALUATION_FILE = "06_code_evaluation.json";
    public static final String MANIM_REVIEWED_CODE_FILE = "06_manim_code_reviewed.py";
    public static final String GEOGEBRA_REVIEWED_COMMANDS_FILE = "06_geogebra_commands_reviewed.txt";
    public static final String RENDER_RESULT_FILE = "07_render_result.json";
    public static final String MANIM_FINAL_CODE_FILE = "07_manim_code_final.py";
    public static final String GEOGEBRA_FINAL_COMMANDS_FILE = "07_geogebra_commands_final.txt";
    public static final String SCENE_EVALUATION_FILE = "08_scene_evaluation.json";
    public static final String WORKFLOW_SUMMARY_FILE = "09_workflow_summary.json";
    public static final String CODE_FIX_TRACE_FILE = "09_code_fix_trace.json";

    public static final String LEGACY_KNOWLEDGE_GRAPH_FILE = "1_knowledge_graph.json";
    public static final String LEGACY_MANIM_CODE_FILE = "4_manim_code.py";
    public static final String LEGACY_GEOGEBRA_COMMANDS_FILE = "4_geogebra_commands.txt";
    public static final String LEGACY_MANIM_FINAL_CODE_FILE = "5_manim_code_final.py";
    public static final String LEGACY_GEOGEBRA_FINAL_COMMANDS_FILE = "5_geogebra_commands_final.txt";

    private static final String LEGACY_CODE_RESULT_FILE = "4_code_result.json";
    private static final Pattern SCENE_CLASS_PATTERN =
            Pattern.compile("class\\s+(\\w+)\\s*\\(.*?Scene.*?\\)");

    public static Path createOutputDir(Path baseDir, String rawInput) {
        return createOutputDir(baseDir, rawInput, WorkflowConfig.OUTPUT_TARGET_MANIM);
    }

    public static Path createOutputDir(Path baseDir, String rawInput, String outputTarget) {
        String safeName = rawInput.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
        if (safeName.length() > 50) {
            safeName = safeName.substring(0, 50);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String targetDir = resolveOutputTargetDirectoryName(outputTarget);
        Path dir = baseDir.resolve(targetDir).resolve(safeName + "_" + timestamp);

        try {
            Files.createDirectories(dir);
            log.info("Output directory: {}", dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory: " + dir, e);
        }
        return dir;
    }

    public static void saveProblemBundle(Path outputDir, Object bundle) {
        writeJson(outputDir.resolve(PROBLEM_BUNDLE_FILE), bundle, "problem bundle");
    }

    public static void saveProblemSource(Path outputDir, Object source) {
        writeJson(outputDir.resolve(PROBLEM_SOURCE_FILE), source, "problem source");
    }

    public static void saveKnowledgeGraph(Path outputDir, KnowledgeGraph graph) {
        writeJson(outputDir.resolve(KNOWLEDGE_GRAPH_FILE), graph, "knowledge graph");
        writeText(outputDir.resolve(KNOWLEDGE_GRAPH_PRETTY_FILE), graph.printGraph(), "knowledge graph (pretty)");
    }

    public static KnowledgeGraph loadKnowledgeGraph(Path path) {
        return readJson(path, KnowledgeGraph.class, "knowledge graph");
    }

    public static ProblemSource loadProblemSource(Path outputDir) {
        if (outputDir == null) {
            return null;
        }
        Path path = outputDir.resolve(PROBLEM_SOURCE_FILE);
        if (!Files.exists(path)) {
            return null;
        }
        return readJson(path, ProblemSource.class, "problem source");
    }

    public static ProblemBundle loadProblemBundle(Path outputDir) {
        if (outputDir == null) {
            return null;
        }
        Path path = outputDir.resolve(PROBLEM_BUNDLE_FILE);
        if (!Files.exists(path)) {
            return null;
        }
        return readJson(path, ProblemBundle.class, "problem bundle");
    }

    public static CodeResult loadCodeResult(Path path) {
        try {
            log.info("[Load] code <- {}", path);
            String generatedCode = Files.readString(path, StandardCharsets.UTF_8);

            Path metadataPath = path.toAbsolutePath().normalize().getParent();
            JsonNode metadata = loadFirstExistingMetadata(metadataPath, CODE_RESULT_FILE, LEGACY_CODE_RESULT_FILE);

            String outputTarget = inferOutputTarget(path, metadata);

            String sceneName = readTextField(metadata, "scene_name");
            if (sceneName.isBlank()) {
                sceneName = WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equals(outputTarget)
                        ? defaultFigureName(path)
                        : extractSceneName(generatedCode, fileStem(path));
            }

            String description = readTextField(metadata, "description");

            CodeResult codeResult = new CodeResult(
                    generatedCode,
                    sceneName,
                    description);
            String targetConcept = readTextField(metadata, "target_concept");
            codeResult.setTargetConcept(!targetConcept.isBlank() ? targetConcept : sceneName);
            codeResult.setTargetDescription(readTextField(metadata, "target_description"));
            codeResult.setOutputTarget(outputTarget);
            codeResult.setArtifactFormat(resolveArtifactFormat(outputTarget, metadata));
            codeResult.setToolCalls(0);
            codeResult.setExecutionTimeSeconds(0.0);
            return codeResult;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load code from: " + path, e);
        }
    }

    public static void saveEnrichedGraph(Path outputDir, KnowledgeGraph graph) {
        writeJson(outputDir.resolve(MATH_ENRICHED_GRAPH_FILE), graph, "enriched graph");
    }

    public static Narrative loadNarrative(Path path) {
        return readJson(path, Narrative.class, "narrative");
    }

    public static Narrative.Storyboard loadStoryboard(Path path) {
        return readJson(path, Narrative.Storyboard.class, "storyboard");
    }

    public static void saveNarrative(Path outputDir, Narrative narrative) {
        writeJson(outputDir.resolve(VISUAL_NARRATIVE_FILE), narrative, "narrative (JSON)");
    }

    public static void saveValidatedStoryboard(Path outputDir, Narrative.Storyboard storyboard) {
        writeJson(outputDir.resolve(VALIDATED_STORYBOARD_FILE), storyboard, "validated storyboard");
    }

    public static void saveStoryboardValidation(Path outputDir,
                                                StoryboardValidationReport storyboardValidationReport) {
        writeJson(outputDir.resolve(STORYBOARD_VALIDATION_REPORT_FILE),
                storyboardValidationReport, "storyboard validation");
    }

    public static StoryboardValidationReport loadStoryboardValidation(Path path) {
        return readJson(path, StoryboardValidationReport.class, "storyboard validation");
    }

    public static void saveCodeResult(Path outputDir, CodeResult codeResult) {
        if (codeResult.hasCode()) {
            writeText(outputDir.resolve(resolveCodeFilename(codeResult)),
                    codeResult.getGeneratedCode(),
                    describeCodeArtifact(codeResult));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scene_name", codeResult.getSceneName());
        meta.put("description", codeResult.getDescription());
        meta.put("output_target", codeResult.getOutputTarget());
        meta.put("artifact_format", codeResult.getArtifactFormat());
        meta.put("code_lines", codeResult.codeLineCount());
        meta.put("tool_calls", codeResult.getToolCalls());
        meta.put("execution_time_seconds", codeResult.getExecutionTimeSeconds());
        writeJson(outputDir.resolve(CODE_RESULT_FILE), meta, "code metadata");
    }

    public static void saveCodeEvaluation(Path outputDir,
                                          CodeEvaluationResult codeEvaluationResult,
                                            CodeResult codeResult) {
        writeJson(outputDir.resolve(CODE_EVALUATION_FILE),
                codeEvaluationResult, "code evaluation");

        if (codeEvaluationResult != null
                && codeEvaluationResult.isRevisedCodeApplied()
                && codeResult != null
                && codeResult.hasCode()) {
            writeText(outputDir.resolve(resolveReviewedCodeFilename(codeResult)),
                    codeResult.getGeneratedCode(), "reviewed code");
        }
    }

    public static CodeEvaluationResult loadCodeEvaluation(Path path) {
        return readJson(path, CodeEvaluationResult.class, "code evaluation");
    }

    public static void saveRenderResult(Path outputDir, RenderResult renderResult) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("success", renderResult.isSuccess());
        meta.put("scene_name", renderResult.getSceneName());
        meta.put("video_path", renderResult.getVideoPath());
        meta.put("artifact_path", renderResult.getArtifactPath());
        meta.put("output_target", renderResult.getOutputTarget());
        meta.put("artifact_type", renderResult.getArtifactType());
        meta.put("geometry_path", renderResult.getGeometryPath());
        meta.put("attempts", renderResult.getAttempts());
        meta.put("last_error", renderResult.getLastError());
        meta.put("tool_calls", renderResult.getToolCalls());
        meta.put("execution_time_seconds", renderResult.getExecutionTimeSeconds());
        writeJson(outputDir.resolve(RENDER_RESULT_FILE), meta, "render result");

        if (renderResult.getFinalGeneratedCode() != null) {
            writeText(outputDir.resolve(resolveFinalCodeFilename(renderResult)),
                    renderResult.getFinalGeneratedCode(),
                    "final code");
        }
    }

    public static RenderResult loadRenderResult(Path path) {
        try {
            log.info("[Load] render result <- {}", path);
            JsonNode meta = mapper.readTree(path.toFile());
            RenderResult renderResult = new RenderResult();
            renderResult.setSuccess(meta.path("success").asBoolean(false));
            renderResult.setSceneName(readTextField(meta, "scene_name"));
            renderResult.setVideoPath(readTextField(meta, "video_path"));
            renderResult.setArtifactPath(readTextField(meta, "artifact_path"));
            String outputTarget = readTextField(meta, "output_target");
            if (!outputTarget.isBlank()) {
                renderResult.setOutputTarget(outputTarget);
            }
            renderResult.setArtifactType(readTextField(meta, "artifact_type"));
            renderResult.setGeometryPath(readTextField(meta, "geometry_path"));
            renderResult.setAttempts(meta.path("attempts").asInt(0));
            renderResult.setLastError(readTextField(meta, "last_error"));
            renderResult.setToolCalls(meta.path("tool_calls").asInt(0));
            renderResult.setExecutionTimeSeconds(meta.path("execution_time_seconds").asDouble(0.0));
            return renderResult;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load render result from: " + path, e);
        }
    }

    public static void saveSceneEvaluation(Path outputDir, SceneEvaluationResult sceneEvaluationResult) {
        writeJson(outputDir.resolve(SCENE_EVALUATION_FILE),
                sceneEvaluationResult, "scene evaluation");
    }

    public static SceneEvaluationResult loadSceneEvaluation(Path path) {
        return readJson(path, SceneEvaluationResult.class, "scene evaluation");
    }

    public static void saveCodeFixTrace(Path outputDir, CodeFixTraceReport codeFixTraceReport) {
        writeJson(outputDir.resolve(CODE_FIX_TRACE_FILE),
                codeFixTraceReport, "code fix trace");
    }

    public static void saveWorkflowSummary(Path outputDir, Map<String, Object> summary) {
        writeJson(outputDir.resolve(WORKFLOW_SUMMARY_FILE),
                sanitizeForJson(summary), "workflow summary");
    }

    private static void writeJson(Path path, Object data, String description) {
        try {
            mapper.writeValue(path.toFile(), data);
            log.info("[Save] {} -> {}", description, path.getFileName());
        } catch (IOException e) {
            log.error("Failed to write {}: {}", description, e.getMessage());
        }
    }

    private static <T> T readJson(Path path, Class<T> type, String description) {
        try {
            log.info("[Load] {} <- {}", description, path);
            return mapper.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + description + " from: " + path, e);
        }
    }

    private static void writeText(Path path, String text, String description) {
        try {
            Files.writeString(path, text != null ? text : "", StandardCharsets.UTF_8);
            log.info("[Save] {} -> {}", description, path.getFileName());
        } catch (IOException e) {
            log.error("Failed to write {}: {}", description, e.getMessage());
        }
    }

    private static Object sanitizeForJson(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }

        if (value instanceof Path || value instanceof TemporalAccessor || value instanceof Enum<?>) {
            return value.toString();
        }

        if (value instanceof Map<?, ?>) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                sanitized.put(String.valueOf(entry.getKey()), sanitizeForJson(entry.getValue()));
            }
            return sanitized;
        }

        if (value instanceof Collection<?>) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                sanitized.add(sanitizeForJson(item));
            }
            return sanitized;
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> sanitized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                sanitized.add(sanitizeForJson(Array.get(value, i)));
            }
            return sanitized;
        }

        String className = value.getClass().getName();
        if (className.startsWith("com.mathvision.model.")) {
            return value;
        }

        log.debug("Sanitizing non-JSON-friendly summary value of type {}", className);
        return value.toString();
    }

    private static JsonNode loadOptionalMetadata(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }

        try {
            log.info("[Load] code metadata <- {}", path);
            return mapper.readTree(path.toFile());
        } catch (IOException e) {
            log.warn("Failed to load code metadata from {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static JsonNode loadFirstExistingMetadata(Path parentDir, String... fileNames) {
        if (parentDir == null || fileNames == null) {
            return null;
        }
        for (String fileName : fileNames) {
            if (fileName == null || fileName.isBlank()) {
                continue;
            }
            Path metadataPath = parentDir.resolve(fileName);
            if (Files.exists(metadataPath)) {
                return loadOptionalMetadata(metadataPath);
            }
        }
        return null;
    }

    private static String readTextField(JsonNode node, String fieldName) {
        if (node == null || fieldName == null || fieldName.isBlank()) {
            return "";
        }

        JsonNode value = node.get(fieldName);
        return value != null && !value.isNull() ? value.asText("").trim() : "";
    }

    private static String extractSceneName(String generatedCode, String fallback) {
        if (generatedCode != null) {
            Matcher matcher = SCENE_CLASS_PATTERN.matcher(generatedCode);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return fallback != null && !fallback.isBlank() ? fallback : "MainScene";
    }

    private static String fileStem(Path path) {
        if (path == null || path.getFileName() == null) {
            return "MainScene";
        }

        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private static String resolveCodeFilename(CodeResult codeResult) {
        return codeResult != null && codeResult.isGeoGebraTarget()
                ? GEOGEBRA_COMMANDS_FILE
                : MANIM_CODE_FILE;
    }

    private static String resolveReviewedCodeFilename(CodeResult codeResult) {
        return codeResult != null && codeResult.isGeoGebraTarget()
                ? GEOGEBRA_REVIEWED_COMMANDS_FILE
                : MANIM_REVIEWED_CODE_FILE;
    }

    private static String resolveFinalCodeFilename(RenderResult renderResult) {
        return renderResult != null && renderResult.isGeoGebraTarget()
                ? GEOGEBRA_FINAL_COMMANDS_FILE
                : MANIM_FINAL_CODE_FILE;
    }

    private static String describeCodeArtifact(CodeResult codeResult) {
        return codeResult != null && codeResult.isGeoGebraTarget()
                ? "GeoGebra command script"
                : "Manim code";
    }

    private static String inferOutputTarget(Path path, JsonNode metadata) {
        String explicit = readTextField(metadata, "output_target");
        if (!explicit.isBlank()) {
            return WorkflowConfig.normalizeOutputTarget(explicit);
        }

        String fileName = path != null && path.getFileName() != null ? path.getFileName().toString().toLowerCase() : "";
        return fileName.contains("geogebra")
                ? WorkflowConfig.OUTPUT_TARGET_GEOGEBRA
                : WorkflowConfig.OUTPUT_TARGET_MANIM;
    }

    private static String resolveArtifactFormat(String outputTarget, JsonNode metadata) {
        String explicit = readTextField(metadata, "artifact_format");
        if (!explicit.isBlank()) {
            return explicit;
        }
        return WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equals(outputTarget) ? "commands" : "python";
    }

    private static String defaultFigureName(Path path) {
        String stem = fileStem(path);
        return stem == null || stem.isBlank() ? GeoGebraCodeUtils.EXPECTED_FIGURE_NAME : stem;
    }

    private static String resolveOutputTargetDirectoryName(String outputTarget) {
        String normalized = WorkflowConfig.normalizeOutputTarget(outputTarget);
        return WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equals(normalized)
                ? WorkflowConfig.OUTPUT_TARGET_GEOGEBRA
                : WorkflowConfig.OUTPUT_TARGET_MANIM;
    }
}
