package com.automanim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
                                                                   List<String> commands) {
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
                "GeoGebraFigure",
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
                                                                   List<String> commands) {
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
                "GeoGebraFigure",
                tempDir
        );

        assertFalse(result.success());
        assertTrue(result.error().contains("Command 1 returned false"));
    }
}
