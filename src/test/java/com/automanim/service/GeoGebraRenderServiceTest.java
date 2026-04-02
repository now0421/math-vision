package com.automanim.service;

import com.automanim.util.GeoGebraCodeUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoGebraRenderServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void renderPersistsPreviewAndStructuredValidationReport() throws IOException {
        GeoGebraRenderService service = new GeoGebraRenderService() {
            @Override
            protected ValidationReport validateWithHeadlessBrowser(Path previewPath,
                                                                   String figureName,
                                                                   List<String> commands,
                                                                   List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                                                   Path geometryPath) {
                ValidationReport report = new ValidationReport();
                report.figureName = figureName;
                report.validationEngine = "playwright";
                report.browserExecutable = "playwright:chromium";
                report.completed = true;
                report.appletLoaded = true;
                report.errorDialogsDisabled = true;
                report.repaintingDisabled = true;
                report.totalCommands = commands.size();
                report.successfulCommands = commands.size();
                report.failedCommands = 0;
                report.totalObjects = 3;
                report.xmlLength = 128;
                report.finalObjectNames = List.of("A", "B", "lineAB");

                CommandValidation entry = new CommandValidation();
                entry.index = 1;
                entry.command = commands.get(0);
                entry.success = true;
                ObjectSnapshot snapshot = new ObjectSnapshot();
                snapshot.name = "A";
                snapshot.exists = true;
                snapshot.defined = true;
                snapshot.type = "point";
                entry.createdObjects = List.of(snapshot);
                entry.objectNamesAfter = List.of("A");
                entry.objectCountAfter = 1;
                report.commands = List.of(entry);
                return report;
            }
        };

        GeoGebraRenderService.RenderAttemptResult result = service.render(
                String.join("\n",
                        "A = (0, 0)",
                        "B = (4, 0)",
                        "lineAB = Line(A, B)"),
                GeoGebraCodeUtils.EXPECTED_FIGURE_NAME,
                tempDir
        );

        assertTrue(result.success());
        assertTrue(Files.exists(tempDir.resolve("5_geogebra_preview.html")));
        assertTrue(Files.exists(tempDir.resolve("5_geogebra_validation.json")));

        String previewHtml = Files.readString(tempDir.resolve("5_geogebra_preview.html"));
        String validationJson = Files.readString(tempDir.resolve("5_geogebra_validation.json"));

        assertTrue(previewHtml.contains("Runtime validation is executed separately by Playwright"));
        assertTrue(validationJson.contains("\"validationEngine\""));
        assertTrue(validationJson.contains("\"playwright\""));
        assertTrue(validationJson.contains("\"finalObjectNames\""));
        assertTrue(validationJson.contains("\"createdObjects\""));
    }

    @Test
    void renderReturnsFailureForFailedValidationReport() {
        GeoGebraRenderService service = new GeoGebraRenderService() {
            @Override
            protected ValidationReport validateWithHeadlessBrowser(Path previewPath,
                                                                   String figureName,
                                                                   List<String> commands,
                                                                   List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                                                   Path geometryPath) {
                ValidationReport report = new ValidationReport();
                report.figureName = figureName;
                report.validationEngine = "playwright";
                report.browserExecutable = "playwright:chromium";
                report.completed = true;
                report.appletLoaded = true;
                report.totalCommands = commands.size();
                report.successfulCommands = 0;
                report.failedCommands = 1;
                report.error = "Command 1 returned false: Broken(Command)";
                CommandValidation entry = new CommandValidation();
                entry.index = 1;
                entry.command = commands.get(0);
                entry.success = false;
                report.commands = List.of(entry);
                return report;
            }
        };

        GeoGebraRenderService.RenderAttemptResult result = service.render(
                "Broken(Command)",
                GeoGebraCodeUtils.EXPECTED_FIGURE_NAME,
                tempDir
        );

        assertFalse(result.success());
        assertTrue(result.error().contains("Command 1 returned false"));
    }

    @Test
    void renderEmbedsCommandAndScenePayloadsSafelyInPreviewHtml() throws IOException {
        GeoGebraRenderService service = new GeoGebraRenderService() {
            @Override
            protected ValidationReport validateWithHeadlessBrowser(Path previewPath,
                                                                   String figureName,
                                                                   List<String> commands,
                                                                   List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                                                   Path geometryPath) {
                return successfulReport(figureName, commands);
            }
        };

        String script = String.join("\n",
                "A = (0, 0)",
                "label = Text(\"quoted \\\"value\\\"\")",
                "# AUTOGEN_SCENE_BUTTONS_BEGIN",
                "# @scene {\"id\":\"scene_1\",\"title\":\"Scene 1: Setup\",\"show\":[\"A\",\"label\"],\"hide\":[]}",
                "# AUTOGEN_SCENE_BUTTONS_END");

        GeoGebraRenderService.RenderAttemptResult result =
                service.render(script, GeoGebraCodeUtils.EXPECTED_FIGURE_NAME, tempDir);

        assertTrue(result.success());
        assertNotNull(result.previewPath());

        String previewHtml = Files.readString(tempDir.resolve("5_geogebra_preview.html"));
        assertTrue(previewHtml.contains("commands-data"));
        assertTrue(previewHtml.contains("scene-controls"));
        assertTrue(previewHtml.contains("scene-data"));
        assertFalse(previewHtml.contains("label = Text(\"quoted"));
    }

    @Test
    void renderGeneratesGeometryReportForSuccessfulValidation() throws IOException {
        GeoGebraRenderService service = new GeoGebraRenderService() {
            @Override
            protected ValidationReport validateWithHeadlessBrowser(Path previewPath,
                                                                   String figureName,
                                                                   List<String> commands,
                                                                   List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                                                   Path geometryPath) {
                // Simulate writing geometry file (normally done via Playwright JS)
                if (geometryPath != null) {
                    String mockGeometry = "{\n"
                            + "  \"scene_name\": \"GeoGebraFigure\",\n"
                            + "  \"report_type\": \"geogebra_element_report\",\n"
                            + "  \"report_version\": 1,\n"
                            + "  \"frame_bounds\": {\n"
                            + "    \"min\": [-7.0, -4.0, 0],\n"
                            + "    \"max\": [7.0, 4.0, 0]\n"
                            + "  },\n"
                            + "  \"sample_count\": 1,\n"
                            + "  \"samples\": [{\n"
                            + "    \"sample_id\": \"geogebra-initial\",\n"
                            + "    \"sample_role\": \"geogebra_construction\",\n"
                            + "    \"element_count\": 2,\n"
                            + "    \"elements\": [\n"
                            + "      {\"stable_id\": \"ggb-A\", \"name\": \"A\", \"class_name\": \"point\", \"visible\": true},\n"
                            + "      {\"stable_id\": \"ggb-B\", \"name\": \"B\", \"class_name\": \"point\", \"visible\": true}\n"
                            + "    ]\n"
                            + "  }]\n"
                            + "}";
                    try {
                        Files.writeString(geometryPath, mockGeometry, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                return successfulReport(figureName, commands);
            }
        };

        GeoGebraRenderService.RenderAttemptResult result = service.render(
                "A = (0, 0)\nB = (4, 0)",
                GeoGebraCodeUtils.EXPECTED_FIGURE_NAME,
                tempDir
        );

        assertTrue(result.success());
        assertNotNull(result.geometryPath());
        assertTrue(Files.exists(Path.of(result.geometryPath())));

        String geometryJson = Files.readString(Path.of(result.geometryPath()));
        assertTrue(geometryJson.contains("geogebra_element_report"));
        assertTrue(geometryJson.contains("geogebra_construction"));
        assertTrue(geometryJson.contains("ggb-A"));
    }

    private static GeoGebraRenderService.ValidationReport successfulReport(String figureName,
                                                                           List<String> commands) {
        GeoGebraRenderService.ValidationReport report = new GeoGebraRenderService.ValidationReport();
        report.figureName = figureName;
        report.validationEngine = "playwright";
        report.browserExecutable = "playwright:chromium";
        report.completed = true;
        report.appletLoaded = true;
        report.errorDialogsDisabled = true;
        report.repaintingDisabled = true;
        report.totalCommands = commands.size();
        report.successfulCommands = commands.size();
        report.failedCommands = 0;
        report.totalObjects = commands.size();
        report.xmlLength = 32;
        report.commands = List.of();
        return report;
    }
}
