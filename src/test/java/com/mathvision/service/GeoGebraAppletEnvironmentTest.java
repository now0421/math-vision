package com.mathvision.service;

import com.mathvision.util.GeoGebraCodeUtils;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoGebraAppletEnvironmentTest {

    @Test
    void geogebraAppletBootstrapsAndExecutesMinimalCommands() {
        ProbeGeoGebraRenderService service = new ProbeGeoGebraRenderService();
        List<String> commands = List.of(
                "A = (0, 0)",
                "B = (4, 0)",
                "lineAB = Line(A, B)"
        );

        GeoGebraRenderService.ValidationReport report =
                service.probeEnvironment(GeoGebraCodeUtils.EXPECTED_FIGURE_NAME, commands);

        assertNotNull(report, "Validation report should not be null");
        assertTrue(report.appletLoaded, diagnosticMessage(report));
        assertTrue(report.completed, diagnosticMessage(report));
        assertEquals(commands.size(), report.successfulCommands, diagnosticMessage(report));
        assertEquals(0, report.failedCommands, diagnosticMessage(report));
        assertTrue(report.error == null || report.error.isBlank(), diagnosticMessage(report));
    }

    @Test
    void geogebraAppletExecutesStylingCommandsViaValidationRuntime() {
        ProbeGeoGebraRenderService service = new ProbeGeoGebraRenderService();
        List<String> commands = List.of(
                "A = (0, 0)",
                "B = (4, 0)",
                "lineAB = Line(A, B)",
                "segmentAB = Segment(A, B)",
                "triangleABO = Polygon(A, B, (2, 2))",
                "SetColor(lineAB, \"gray\")",
                "SetLineThickness(segmentAB, 6)",
                "SetLineStyle(segmentAB, 2)",
                "SetLabelMode(lineAB, 3)",
                "SetLayer(segmentAB, 2)",
                "SetTrace(A, false)",
                "SetFilling(triangleABO, 0.4)",
                "ShowLabel(A, true)",
                "ShowGrid(1, false)"
        );

        GeoGebraRenderService.ValidationReport report =
                service.probeEnvironment(GeoGebraCodeUtils.EXPECTED_FIGURE_NAME, commands);

        assertNotNull(report, "Validation report should not be null");
        assertTrue(report.appletLoaded, diagnosticMessage(report));
        assertTrue(report.completed, diagnosticMessage(report));
        assertEquals(commands.size(), report.successfulCommands, diagnosticMessage(report));
        assertEquals(0, report.failedCommands, diagnosticMessage(report));
        assertTrue(report.error == null || report.error.isBlank(), diagnosticMessage(report));
    }

    @Test
    void geogebraAppletExecutesDeferredScriptingCommandsViaValidationRuntime() {
        ProbeGeoGebraRenderService service = new ProbeGeoGebraRenderService();
        List<String> commands = List.of(
                "A = (0, 0)",
                "t = Slider(0, 10, 1, 1, 120, false, true, false, false)",
                "helperText = Text(\"helper\", (1, 1), false, false)",
                "SetFixed(A, true)",
                "SetConditionToShowObject(helperText, t > 2)"
        );

        GeoGebraRenderService.ValidationReport report =
                service.probeEnvironment(GeoGebraCodeUtils.EXPECTED_FIGURE_NAME, commands);

        assertNotNull(report, "Validation report should not be null");
        assertTrue(report.appletLoaded, diagnosticMessage(report));
        assertTrue(report.completed, diagnosticMessage(report));
        assertEquals(commands.size(), report.successfulCommands, diagnosticMessage(report));
        assertEquals(0, report.failedCommands, diagnosticMessage(report));
        assertTrue(report.error == null || report.error.isBlank(), diagnosticMessage(report));
    }

    @Test
    void geogebraAppletExecutesManualStyleScriptingCommandsViaValidationRuntime() {
        ProbeGeoGebraRenderService service = new ProbeGeoGebraRenderService();
        List<String> commands = List.of(
                "A = (0, 0)",
                "B = (4, 0)",
                "segmentAB = Segment(A, B)",
                "SetBackgroundColor(\"#F7F7F5\")",
                "SetDynamicColor(A, 1, 0, 0, 0.7)",
                "SetLineOpacity(segmentAB, 128)",
                "SetDecoration(segmentAB, 1)",
                "SetTooltipMode(A, 3)",
                "SetVisibleInView(segmentAB, 1, true)"
        );

        GeoGebraRenderService.ValidationReport report =
                service.probeEnvironment(GeoGebraCodeUtils.EXPECTED_FIGURE_NAME, commands);

        assertNotNull(report, "Validation report should not be null");
        assertTrue(report.appletLoaded, diagnosticMessage(report));
        assertTrue(report.completed, diagnosticMessage(report));
        assertEquals(commands.size(), report.successfulCommands, diagnosticMessage(report));
        assertEquals(0, report.failedCommands, diagnosticMessage(report));
        assertTrue(report.error == null || report.error.isBlank(), diagnosticMessage(report));
    }

    private static String diagnosticMessage(GeoGebraRenderService.ValidationReport report) {
        String lineSeparator = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("GeoGebra applet environment check failed.").append(lineSeparator);
        sb.append("browserExecutable=").append(report.browserExecutable).append(lineSeparator);
        sb.append("completed=").append(report.completed).append(lineSeparator);
        sb.append("appletLoaded=").append(report.appletLoaded).append(lineSeparator);
        sb.append("successfulCommands=").append(report.successfulCommands).append("/")
                .append(report.totalCommands).append(lineSeparator);
        sb.append("failedCommands=").append(report.failedCommands).append(lineSeparator);
        sb.append("error=").append(report.error).append(lineSeparator);
        sb.append("consoleMessages=").append(report.consoleMessages).append(lineSeparator);
        sb.append("pageErrors=").append(report.pageErrors).append(lineSeparator);
        sb.append("requestFailures=").append(report.requestFailures);
        return sb.toString();
    }

    private static final class ProbeGeoGebraRenderService extends GeoGebraRenderService {
        private ValidationReport probeEnvironment(String figureName, List<String> commands) {
            return validateWithHeadlessBrowser(Path.of("geogebra-applet-environment-check.html"),
                    figureName,
                    commands,
                    List.of(),
                    null);
        }
    }
}
