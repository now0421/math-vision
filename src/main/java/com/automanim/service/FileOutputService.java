package com.automanim.service;

import com.automanim.model.KnowledgeNode;
import com.automanim.model.Narrative;
import com.automanim.model.CodeResult;
import com.automanim.model.RenderResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles persisting intermediate pipeline results to disk.
 * Each stage saves its output so the pipeline can be resumed or inspected.
 */
public class FileOutputService {

    private static final Logger log = LoggerFactory.getLogger(FileOutputService.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Create a timestamped output directory for a concept.
     */
    public static Path createOutputDir(Path baseDir, String concept) {
        String safeName = concept.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
        if (safeName.length() > 50) safeName = safeName.substring(0, 50);

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

    public static void saveKnowledgeTree(Path outputDir, KnowledgeNode tree) {
        writeJson(outputDir.resolve("1_knowledge_tree.json"), tree, "knowledge tree");
        writeText(outputDir.resolve("1_knowledge_tree_pretty.txt"), tree.printTree(), "knowledge tree (pretty)");
    }

    public static void saveEnrichedTree(Path outputDir, KnowledgeNode tree) {
        writeJson(outputDir.resolve("2_enriched_tree.json"), tree, "enriched tree");
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

    public static void savePipelineSummary(Path outputDir, Map<String, Object> summary) {
        writeJson(outputDir.resolve("6_pipeline_summary.json"), summary, "pipeline summary");
    }

    // ---- Internal ----

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
            Files.writeString(path, text != null ? text : "");
            log.info("[Save] {} -> {}", description, path.getFileName());
        } catch (IOException e) {
            log.error("Failed to write {}: {}", description, e.getMessage());
        }
    }
}
