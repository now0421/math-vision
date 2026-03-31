package com.automanim.service;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.RenderResult;
import com.automanim.model.SceneEvaluationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileOutputServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createOutputDirPlacesRunsUnderBackendSubdirectory() {
        Path manimDir = FileOutputService.createOutputDir(tempDir, "Manual Concept", WorkflowConfig.OUTPUT_TARGET_MANIM);
        Path geogebraDir = FileOutputService.createOutputDir(tempDir, "Manual Concept", WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);

        assertEquals(tempDir.resolve("manim"), manimDir.getParent());
        assertEquals(tempDir.resolve("geogebra"), geogebraDir.getParent());
        assertTrue(Files.exists(manimDir));
        assertTrue(Files.exists(geogebraDir));
    }

    @Test
    void saveRenderResultPersistsGeometryPath() throws IOException {
        RenderResult renderResult = new RenderResult();
        renderResult.setSuccess(true);
        renderResult.setSceneName("DemoScene");
        renderResult.setVideoPath("media/videos/demo.mp4");
        renderResult.setGeometryPath("5_mobject_geometry.json");
        renderResult.setAttempts(1);
        renderResult.setToolCalls(0);

        FileOutputService.saveRenderResult(tempDir, renderResult);

        String metadata = Files.readString(tempDir.resolve("5_render_result.json"));
        assertTrue(metadata.contains("\"geometry_path\""));
        assertTrue(metadata.contains("5_mobject_geometry.json"));
    }

    @Test
    void loadCodeResultRestoresMetadataWhenPresent() throws IOException {
        Files.writeString(tempDir.resolve("4_manim_code.py"), sampleCode("RecoveredScene"));
        Files.writeString(tempDir.resolve("4_code_result.json"), String.join("\n",
                "{",
                "  \"scene_name\": \"RecoveredScene\",",
                "  \"description\": \"manual resume\",",
                "  \"target_concept\": \"Manual Concept\",",
                "  \"target_description\": \"Recovered from disk\"",
                "}"));

        CodeResult codeResult = FileOutputService.loadCodeResult(tempDir.resolve("4_manim_code.py"));

        assertEquals("RecoveredScene", codeResult.getSceneName());
        assertEquals("manual resume", codeResult.getDescription());
        assertEquals("Manual Concept", codeResult.getTargetConcept());
        assertEquals("Recovered from disk", codeResult.getTargetDescription());
        assertTrue(codeResult.getCode().contains("class RecoveredScene(Scene):"));
    }

    @Test
    void loadCodeResultFallsBackToSceneNameWhenMetadataMissing() throws IOException {
        Files.writeString(tempDir.resolve("4_manim_code.py"), sampleCode("FallbackScene"));

        CodeResult codeResult = FileOutputService.loadCodeResult(tempDir.resolve("4_manim_code.py"));

        assertEquals("FallbackScene", codeResult.getSceneName());
        assertEquals("FallbackScene", codeResult.getTargetConcept());
        assertEquals("", codeResult.getTargetDescription());
    }

    @Test
    void saveAndLoadGeoGebraCodeResultUsesGeoGebraArtifactNames() throws IOException {
        CodeResult codeResult = new CodeResult(
                String.join("\n",
                        "A = (0, 0)",
                        "B = (4, 0)",
                        "lineAB = Line(A, B)"),
                "GeoGebraFigure",
                "geo demo",
                "Manual Concept",
                "Recovered from disk");
        codeResult.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        codeResult.setArtifactFormat("commands");

        FileOutputService.saveCodeResult(tempDir, codeResult);

        assertTrue(Files.exists(tempDir.resolve("4_geogebra_commands.txt")));

        CodeResult loaded = FileOutputService.loadCodeResult(tempDir.resolve("4_geogebra_commands.txt"));
        assertEquals(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA, loaded.getOutputTarget());
        assertEquals("commands", loaded.getArtifactFormat());
        assertTrue(loaded.getCode().contains("lineAB = Line(A, B)"));
    }

    @Test
    void saveSceneEvaluationPersistsStageOutputAndSummaryUsesNewName() throws IOException {
        SceneEvaluationResult result = new SceneEvaluationResult();
        result.setEvaluated(true);
        result.setApproved(false);
        result.setSceneName("DemoScene");
        result.setGeometryPath("5_mobject_geometry.json");
        result.setSampleCount(3);
        result.setIssueSampleCount(1);
        result.setTotalIssueCount(2);

        FileOutputService.saveSceneEvaluation(tempDir, result);
        FileOutputService.saveWorkflowSummary(tempDir, Map.of("scene_name", "DemoScene"));

        String sceneEvaluation = Files.readString(tempDir.resolve("6_scene_evaluation.json"));
        assertTrue(sceneEvaluation.contains("\"sceneName\""));
        assertTrue(sceneEvaluation.contains("DemoScene"));
        assertTrue(Files.exists(tempDir.resolve("7_workflow_summary.json")));
    }

    private static String sampleCode(String sceneName) {
        return String.join("\n",
                "from manim import *",
                "",
                "class " + sceneName + "(Scene):",
                "    def construct(self):",
                "        self.wait(1)");
    }
}

