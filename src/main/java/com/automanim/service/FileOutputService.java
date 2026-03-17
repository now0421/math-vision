package com.automanim.service;

import com.automanim.model.CodeResult;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.Narrative;
import com.automanim.model.RenderResult;
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

/**
 * Handles persisting intermediate workflow results to disk.
 */
public class FileOutputService {

    private static final Logger log = LoggerFactory.getLogger(FileOutputService.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static Path createOutputDir(Path baseDir, String concept) {
        String safeName = concept.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
        if (safeName.length() > 50) {
            safeName = safeName.substring(0, 50);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path dir = baseDir.resolve(safeName + "_" + timestamp);

        try {
            Files.createDirectories(dir);
            log.info("Output directory: {}", dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory: " + dir, e);
        }
        return dir;
    }

    public static void saveKnowledgeGraph(Path outputDir, KnowledgeGraph graph) {
        writeJson(outputDir.resolve("1_knowledge_graph.json"), graph, "knowledge graph");
        writeText(outputDir.resolve("1_knowledge_graph_pretty.txt"), graph.printGraph(), "knowledge graph (pretty)");
    }

    public static KnowledgeGraph loadKnowledgeGraph(Path path) {
        try {
            log.info("[Load] knowledge graph <- {}", path);
            return mapper.readValue(path.toFile(), KnowledgeGraph.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load knowledge graph from: " + path, e);
        }
    }

    public static void saveEnrichedGraph(Path outputDir, KnowledgeGraph graph) {
        writeJson(outputDir.resolve("2_enriched_graph.json"), graph, "enriched graph");
    }

    public static void saveNarrative(Path outputDir, Narrative narrative) {
        writeJson(outputDir.resolve("3_narrative.json"), narrative, "narrative (JSON)");
        writeText(outputDir.resolve("3_narrative_prompt.txt"), narrative.getVerbosePrompt(), "narrative prompt");
    }

    public static void saveCodeResult(Path outputDir, CodeResult codeResult) {
        if (codeResult.hasCode()) {
            writeText(outputDir.resolve("4_manim_code.py"), codeResult.getManimCode(), "Manim code");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scene_name", codeResult.getSceneName());
        meta.put("description", codeResult.getDescription());
        meta.put("target_concept", codeResult.getTargetConcept());
        meta.put("target_description", codeResult.getTargetDescription());
        meta.put("code_lines", codeResult.codeLineCount());
        meta.put("tool_calls", codeResult.getToolCalls());
        meta.put("execution_time_seconds", codeResult.getExecutionTimeSeconds());
        writeJson(outputDir.resolve("4_code_result.json"), meta, "code metadata");
    }

    public static void saveRenderResult(Path outputDir, RenderResult renderResult) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("success", renderResult.isSuccess());
        meta.put("scene_name", renderResult.getSceneName());
        meta.put("video_path", renderResult.getVideoPath());
        meta.put("attempts", renderResult.getAttempts());
        meta.put("last_error", renderResult.getLastError());
        meta.put("tool_calls", renderResult.getToolCalls());
        meta.put("execution_time_seconds", renderResult.getExecutionTimeSeconds());
        writeJson(outputDir.resolve("5_render_result.json"), meta, "render result");

        if (renderResult.getFinalCode() != null) {
            writeText(outputDir.resolve("5_manim_code_final.py"), renderResult.getFinalCode(), "final Manim code");
        }
    }

    public static void saveWorkflowSummary(Path outputDir, Map<String, Object> summary) {
        writeJson(outputDir.resolve("6_workflow_summary.json"),
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
        if (className.startsWith("com.automanim.model.")) {
            return value;
        }

        log.debug("Sanitizing non-JSON-friendly summary value of type {}", className);
        return value.toString();
    }
}
