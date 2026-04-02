package com.automanim.service;

import com.automanim.util.GeoGebraCodeUtils;
import com.automanim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates a GeoGebra preview artifact and validates commands by replaying them
 * through an embedded GeoGebra applet driven by Playwright.
 */
public class GeoGebraRenderService {

    private static final Logger log = LoggerFactory.getLogger(GeoGebraRenderService.class);
    private static final String PREVIEW_FILE = "5_geogebra_preview.html";
    private static final String VALIDATION_FILE = "5_geogebra_validation.json";
    private static final String GEOMETRY_FILE = "5_geogebra_geometry.json";
    private static final String DEPLOY_GGB_URL = "https://www.geogebra.org/apps/deployggb.js";
    private static final String PLAYWRIGHT_ENGINE = "playwright";
    private static final String PLAYWRIGHT_BUNDLED_CHROMIUM = "playwright:chromium";
    private static final String PLAYWRIGHT_INSTALL_HINT =
            "Install Playwright Chromium with "
                    + "mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI "
                    + "-Dexec.args=\"install chromium\"";
    private static final int VALIDATION_TIMEOUT_SECONDS = 45;
    private static final int VALIDATION_TIMEOUT_MS = VALIDATION_TIMEOUT_SECONDS * 1000;
    private static final int APPLET_BOOTSTRAP_TIMEOUT_MS = VALIDATION_TIMEOUT_MS;
    private static final int APPLET_WIDTH = 960;
    private static final int APPLET_HEIGHT = 540;
    private static final String VALIDATOR_APPLET_ID = "ggbValidatorApplet";
    private static final double DEFAULT_FRAME_X_MIN = -7.0;
    private static final double DEFAULT_FRAME_X_MAX = 7.0;
    private static final double DEFAULT_FRAME_Y_MIN = -4.0;
    private static final double DEFAULT_FRAME_Y_MAX = 4.0;

    public RenderAttemptResult render(String commandScript,
                                      String figureName,
                                      Path outputDir) {
        if (outputDir == null) {
            return new RenderAttemptResult(false, null, null, "Output directory is unavailable");
        }

        try {
            Files.createDirectories(outputDir);
            String safeFigureName = normalizeFigureName(figureName);
            List<String> commands = GeoGebraCodeUtils.extractCommands(commandScript);
            List<GeoGebraCodeUtils.SceneDirective> sceneDirectives =
                    GeoGebraCodeUtils.extractSceneDirectives(commandScript);
            Path previewPath = outputDir.resolve(PREVIEW_FILE);
            Path validationPath = outputDir.resolve(VALIDATION_FILE);
            Path geometryPath = outputDir.resolve(GEOMETRY_FILE);
            Files.writeString(previewPath,
                    buildPreviewHtml(commands, sceneDirectives, safeFigureName),
                    StandardCharsets.UTF_8);

            ValidationReport report;
            if (commands.isEmpty()) {
                report = newValidationReport(safeFigureName, commands);
                report.completed = true;
                report.error = "No executable GeoGebra commands were found after stripping comments.";
            } else {
                report = validateWithHeadlessBrowser(previewPath, safeFigureName, commands,
                        sceneDirectives, geometryPath);
            }

            Files.writeString(validationPath, JsonUtils.toPrettyJson(report), StandardCharsets.UTF_8);
            log.info("GeoGebra preview generated: {}", previewPath);
            log.info("GeoGebra validation report generated: {}", validationPath);

            String normalizedPreviewPath = previewPath.toAbsolutePath().normalize().toString();
            String normalizedGeometryPath = Files.exists(geometryPath)
                    ? geometryPath.toAbsolutePath().normalize().toString()
                    : null;
            if (report != null && report.allSuccessful()) {
                return new RenderAttemptResult(true, normalizedPreviewPath, normalizedGeometryPath, null);
            }

            String error = summarizeValidationFailure(report);
            log.warn("GeoGebra command validation failed: {}", error);
            return new RenderAttemptResult(false, normalizedPreviewPath, normalizedGeometryPath, error);
        } catch (IOException e) {
            log.warn("GeoGebra preview generation failed: {}", e.getMessage());
            return new RenderAttemptResult(false, null, null, e.getMessage());
        }
    }

    protected ValidationReport validateWithHeadlessBrowser(Path previewPath,
                                                           String figureName,
                                                           List<String> commands,
                                                           List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                                           Path geometryPath) {
        ValidationReport fallback = newValidationReport(figureName, commands);
        fallback.browserExecutable = PLAYWRIGHT_BUNDLED_CHROMIUM;

        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;
        HttpServer validationServer = null;
        List<String> consoleMessages = new ArrayList<>();
        List<String> pageErrors = new ArrayList<>();
        List<String> requestFailures = new ArrayList<>();

        try {
            playwright = Playwright.create();
            BrowserType chromium = playwright.chromium();
            String browserDescriptor = resolveBrowserDescriptor(chromium);
            fallback.browserExecutable = browserDescriptor;

            browser = launchBrowser(chromium);
            context = browser.newContext();
            page = context.newPage();
            page.setDefaultTimeout(VALIDATION_TIMEOUT_MS);
            page.onConsoleMessage(message -> consoleMessages.add(formatConsoleMessage(message)));
            page.onPageError(error -> pageErrors.add(error != null ? error : "Unknown page error"));
            page.onRequestFailed(request -> requestFailures.add(
                    request.url() + " :: " + (request.failure() != null ? request.failure() : "request failed")));

            validationServer = startValidationServer(figureName);
            initializeValidationApplet(page, figureName, validationServer);
            String validationJson = executeValidationScript(page, commands);
            ValidationReport parsed = parseValidationReport(validationJson);
            if (parsed == null) {
                fallback.error = "Playwright finished, but no GeoGebra validation report was returned.";
                appendDiagnostics(fallback, consoleMessages, pageErrors, requestFailures);
                return fallback;
            }

            // Extract geometry for scene evaluation (repainting stays disabled — the
            // applet API still returns valid object data in this state, and re-enabling
            // repainting can cause a transient state where getAllObjectNames() returns empty)
            if (geometryPath != null && parsed.completed && parsed.appletLoaded) {
                try {
                    String geometryJson = extractGeometry(page, figureName, sceneDirectives);
                    if (geometryJson != null && !geometryJson.isBlank()) {
                        // Check for extraction errors embedded in the JSON
                        if (geometryJson.contains("\"error\"")) {
                            log.warn("GeoGebra geometry extraction reported error: {}",
                                    geometryJson.length() > 300 ? geometryJson.substring(0, 300) : geometryJson);
                            Files.writeString(geometryPath, buildFallbackElementGeometryReport(figureName, sceneDirectives), StandardCharsets.UTF_8);
                            log.info("Wrote fallback geometry report due to extraction error");
                        } else {
                            // Check if extraction actually found elements; log a warning if not
                            if (geometryJson.contains("\"element_count\": 0") || geometryJson.contains("\"element_count\":0")) {
                                log.warn("GeoGebra geometry extraction returned 0 elements (expected {} from validation); "
                                        + "writing report anyway", parsed.totalObjects);
                            }
                            Files.writeString(geometryPath, geometryJson, StandardCharsets.UTF_8);
                            log.info("GeoGebra geometry report generated: {}", geometryPath);
                        }
                    } else {
                        Files.writeString(geometryPath, buildFallbackElementGeometryReport(figureName, sceneDirectives), StandardCharsets.UTF_8);
                        log.warn("GeoGebra geometry extraction returned empty; wrote minimal fallback");
                    }
                } catch (Exception e) {
                    log.warn("Failed to extract GeoGebra geometry: {}", e.getMessage());
                    try {
                        Files.writeString(geometryPath, buildFallbackElementGeometryReport(figureName, sceneDirectives), StandardCharsets.UTF_8);
                        log.info("Wrote minimal fallback geometry report after extraction failure");
                    } catch (IOException writeError) {
                        log.warn("Failed to write fallback geometry report: {}", writeError.getMessage());
                    }
                }
            }

            normalizeParsedReport(parsed, figureName, commands, browserDescriptor);
            appendDiagnostics(parsed, consoleMessages, pageErrors, requestFailures);
            return parsed;
        } catch (PlaywrightException e) {
            fallback.error = formatPlaywrightError(e);
            appendDiagnostics(fallback, consoleMessages, pageErrors, requestFailures);
            return fallback;
        } catch (IOException e) {
            fallback.error = "GeoGebra Playwright validation failed: " + e.getMessage();
            appendDiagnostics(fallback, consoleMessages, pageErrors, requestFailures);
            return fallback;
        } finally {
            safeClose(validationServer);
            safeClose(page);
            safeClose(context);
            safeClose(browser);
            safeClose(playwright);
        }
    }

    private String extractGeometry(Page page, String figureName,
                                    List<GeoGebraCodeUtils.SceneDirective> sceneDirectives) {
        // Build a JSON array of scene directives for the browser-side script
        String scenesJson = "[]";
        if (sceneDirectives != null && !sceneDirectives.isEmpty()) {
            scenesJson = JsonUtils.toJson(sceneDirectives);
        }

        return asString(page.evaluate(
                "([figureName, scenesJson]) => {"
                        + " const safeCall = (fn, fallback) => {"
                        + "   try { const v = fn(); return v === undefined ? fallback : v; }"
                        + "   catch (e) { return fallback; }"
                        + " };"
                        + " const safeNumber = (v) => {"
                        + "   if (v === null || v === undefined || !Number.isFinite(v)) return null;"
                        + "   return Math.round(v * 1000000) / 1000000;"
                        + " };"
                        + " const api = window.ggbApplet;"
                        + " if (!api) { return JSON.stringify({ error: 'GeoGebra applet not available' }); }"
                        + " let rawNames = null;"
                        + " try { rawNames = api.getAllObjectNames(); } catch (e) { return JSON.stringify({ error: 'getAllObjectNames() threw: ' + e }); }"
                        + " if (!rawNames) { return JSON.stringify({ error: 'getAllObjectNames() returned null/undefined' }); }"
                        + " const allNames = typeof rawNames === 'string' ? rawNames.split(',').filter(s => s.trim()) : Array.from(rawNames);"
                        + " const TEXT_CHAR_WIDTH = 0.15;"
                        + " const TEXT_HEIGHT = 0.4;"
                        + " const POINT_RADIUS = 0.15;"
                        + ""
                        + " function snapshotElements(names) {"
                        + "   const elements = [];"
                        + "   for (const name of names) {"
                        + "     const type = safeCall(() => api.getObjectType(name), 'unknown');"
                        + "     const visible = safeCall(() => api.getVisible(name), true);"
                        + "     const x = safeNumber(safeCall(() => api.getXcoord(name), null));"
                        + "     const y = safeNumber(safeCall(() => api.getYcoord(name), null));"
                        + "     const caption = safeCall(() => api.getCaption(name), null);"
                        + "     const defString = safeCall(() => api.getDefinitionString(name), null);"
                        + "     const valueString = safeCall(() => api.getValueString(name), null);"
                        + "     let bounds = null;"
                        + "     let center = null;"
                        + "     let displayText = null;"
                        + "     let semanticClass = 'unknown';"
                        + "     const typeLower = (type || '').toLowerCase();"
                        + "     if (typeLower === 'point') {"
                        + "       semanticClass = 'point';"
                        + "       if (x !== null && y !== null) {"
                        + "         center = [x, y, 0];"
                        + "         bounds = {"
                        + "           min: [x - POINT_RADIUS, y - POINT_RADIUS, 0],"
                        + "           max: [x + POINT_RADIUS, y + POINT_RADIUS, 0]"
                        + "         };"
                        + "       }"
                        + "     } else if (typeLower === 'text') {"
                        + "       semanticClass = 'text';"
                        + "       displayText = valueString || caption || name;"
                        + "       if (x !== null && y !== null) {"
                        + "         const textLen = (displayText || '').length || 1;"
                        + "         const width = textLen * TEXT_CHAR_WIDTH;"
                        + "         center = [x + width / 2, y + TEXT_HEIGHT / 2, 0];"
                        + "         bounds = {"
                        + "           min: [x, y, 0],"
                        + "           max: [x + width, y + TEXT_HEIGHT, 0]"
                        + "         };"
                        + "       }"
                        + "     } else if (typeLower === 'line' || typeLower === 'ray' || typeLower === 'segment') {"
                        + "       semanticClass = 'line';"
                        + "     } else if (typeLower === 'circle' || typeLower === 'ellipse' || typeLower === 'conic') {"
                        + "       semanticClass = 'shape';"
                        + "     } else if (typeLower === 'polygon') {"
                        + "       semanticClass = 'shape';"
                        + "     } else if (typeLower === 'numeric' || typeLower === 'angle') {"
                        + "       semanticClass = 'formula';"
                        + "       displayText = valueString;"
                        + "     }"
                        + "     elements.push({"
                        + "       stable_id: 'ggb-' + name,"
                        + "       name: name,"
                        + "       semantic_name: name,"
                        + "       class_name: type,"
                        + "       semantic_class: semanticClass,"
                        + "       display_text: displayText,"
                        + "       visible: visible,"
                        + "       center: center,"
                        + "       bounds: bounds,"
                        + "       top_level_stable_id: 'ggb-' + name,"
                        + "       element_path: String(elements.length),"
                        + "       sample_order: elements.length"
                        + "     });"
                        + "   }"
                        + "   return elements;"
                        + " }"
                        + ""
                        + " const scenes = JSON.parse(scenesJson);"
                        + " const samples = [];"
                        + ""
                        + " if (scenes.length === 0) {"
                        + "   const elements = snapshotElements(allNames);"
                        + "   samples.push({"
                        + "     sample_id: 'geogebra-initial',"
                        + "     play_index: null,"
                        + "     play_type: 'geogebra',"
                        + "     sample_role: 'geogebra_construction',"
                        + "     scene_method: 'construct',"
                        + "     source_line: null,"
                        + "     source_code: null,"
                        + "     animation_time_seconds: null,"
                        + "     scene_time_seconds: 0,"
                        + "     trigger: 'construction_complete',"
                        + "     element_count: elements.length,"
                        + "     elements: elements"
                        + "   });"
                        + " } else {"
                        + "   for (let i = 0; i < scenes.length; i++) {"
                        + "     const scene = scenes[i];"
                        + "     const showSet = new Set(scene.show || []);"
                        + "     const hideSet = new Set(scene.hide || []);"
                        + "     for (const name of allNames) {"
                        + "       if (showSet.has(name)) {"
                        + "         try { api.setVisible(name, true); } catch(e) {}"
                        + "       } else if (hideSet.has(name)) {"
                        + "         try { api.setVisible(name, false); } catch(e) {}"
                        + "       }"
                        + "     }"
                        + "     const elements = snapshotElements(allNames).filter(e => e.visible);"
                        + "     samples.push({"
                        + "       sample_id: scene.id || ('scene-' + (i + 1)),"
                        + "       play_index: i,"
                        + "       play_type: 'geogebra',"
                        + "       sample_role: 'scene_final',"
                        + "       scene_method: 'construct',"
                        + "       source_line: null,"
                        + "       source_code: null,"
                        + "       animation_time_seconds: null,"
                        + "       scene_time_seconds: i,"
                        + "       trigger: 'scene_visible',"
                        + "       element_count: elements.length,"
                        + "       elements: elements"
                        + "     });"
                        + "   }"
                        + " }"
                        + ""
                        + " const report = {"
                        + "   scene_name: figureName,"
                        + "   report_type: 'geogebra_element_report',"
                        + "   report_version: 1,"
                        + "   frame_bounds: {"
                        + "     min: [" + DEFAULT_FRAME_X_MIN + ", " + DEFAULT_FRAME_Y_MIN + ", 0],"
                        + "     max: [" + DEFAULT_FRAME_X_MAX + ", " + DEFAULT_FRAME_Y_MAX + ", 0],"
                        + "     width: " + (DEFAULT_FRAME_X_MAX - DEFAULT_FRAME_X_MIN) + ","
                        + "     height: " + (DEFAULT_FRAME_Y_MAX - DEFAULT_FRAME_Y_MIN)
                        + "   },"
                        + "   element_bounds_space: 'world',"
                        + "   sample_count: samples.length,"
                        + "   samples: samples"
                        + " };"
                        + " return JSON.stringify(report, null, 2);"
                        + "}",
                new Object[] { figureName, scenesJson }
        ));
    }

    private String buildFallbackElementGeometryReport(String figureName,
                                                       List<GeoGebraCodeUtils.SceneDirective> sceneDirectives) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n")
                .append("  \"scene_name\": ").append(JsonUtils.toJson(figureName)).append(",\n")
                .append("  \"report_type\": \"geogebra_element_report\",\n")
                .append("  \"report_version\": 1,\n")
                .append("  \"frame_bounds\": {\n")
                .append("    \"min\": [").append(DEFAULT_FRAME_X_MIN).append(", ").append(DEFAULT_FRAME_Y_MIN).append(", 0],\n")
                .append("    \"max\": [").append(DEFAULT_FRAME_X_MAX).append(", ").append(DEFAULT_FRAME_Y_MAX).append(", 0],\n")
                .append("    \"width\": ").append(DEFAULT_FRAME_X_MAX - DEFAULT_FRAME_X_MIN).append(",\n")
                .append("    \"height\": ").append(DEFAULT_FRAME_Y_MAX - DEFAULT_FRAME_Y_MIN).append("\n")
                .append("  },\n")
                .append("  \"element_bounds_space\": \"world\",\n");

        int sceneCount = (sceneDirectives != null && !sceneDirectives.isEmpty()) ? sceneDirectives.size() : 1;
        sb.append("  \"sample_count\": ").append(sceneCount).append(",\n")
                .append("  \"samples\": [");

        for (int i = 0; i < sceneCount; i++) {
            if (i > 0) sb.append(",");
            String sampleId = (sceneDirectives != null && i < sceneDirectives.size()
                    && sceneDirectives.get(i).id != null)
                    ? sceneDirectives.get(i).id
                    : (sceneCount == 1 ? "geogebra-initial" : "scene-" + (i + 1));
            String sampleRole = sceneCount == 1 ? "geogebra_construction" : "scene_final";
            sb.append("{\n")
                    .append("    \"sample_id\": \"").append(sampleId).append("\",\n")
                    .append("    \"play_index\": ").append(sceneCount == 1 ? "null" : String.valueOf(i)).append(",\n")
                    .append("    \"play_type\": \"geogebra\",\n")
                    .append("    \"sample_role\": \"").append(sampleRole).append("\",\n")
                    .append("    \"scene_method\": \"construct\",\n")
                    .append("    \"source_line\": null,\n")
                    .append("    \"source_code\": null,\n")
                    .append("    \"animation_time_seconds\": null,\n")
                    .append("    \"scene_time_seconds\": ").append(i).append(",\n")
                    .append("    \"trigger\": \"").append(sceneCount == 1 ? "construction_complete" : "scene_visible").append("\",\n")
                    .append("    \"element_count\": 0,\n")
                    .append("    \"elements\": []\n")
                    .append("  }");
        }

        sb.append("]\n").append("}");
        return sb.toString();
    }

    private Browser launchBrowser(BrowserType chromium) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setChannel("chromium")
                .setTimeout((double) VALIDATION_TIMEOUT_MS);

        return chromium.launch(options);
    }

    private String resolveBrowserDescriptor(BrowserType chromium) {
        String executablePath = chromium.executablePath();
        return executablePath != null && !executablePath.isBlank()
                ? executablePath
                : PLAYWRIGHT_BUNDLED_CHROMIUM;
    }

    private void initializeValidationApplet(Page page,
                                            String figureName,
                                            HttpServer validationServer) throws IOException {
        int port = validationServer.getAddress().getPort();
        page.navigate("http://127.0.0.1:" + port + "/");
        page.waitForFunction(
                "() => window.__ggbDeployLoaded === true || !!window.__ggbDeployError",
                null,
                new Page.WaitForFunctionOptions().setTimeout((double) VALIDATION_TIMEOUT_MS)
        );

        String deployError = asString(page.evaluate("() => window.__ggbDeployError || ''"));
        if (deployError != null && !deployError.isBlank()) {
            throw new IOException("GeoGebra deploy script failed to load: " + deployError);
        }

        page.waitForFunction(
                "() => typeof window.GGBApplet === 'function'",
                null,
                new Page.WaitForFunctionOptions().setTimeout((double) VALIDATION_TIMEOUT_MS)
        );

        Map<String, Object> appletParams = new LinkedHashMap<>();
        appletParams.put("appName", "classic");
        appletParams.put("width", APPLET_WIDTH);
        appletParams.put("height", APPLET_HEIGHT);
        appletParams.put("showToolBar", false);
        appletParams.put("showAlgebraInput", false);
        appletParams.put("showMenuBar", false);
        appletParams.put("showResetIcon", false);
        appletParams.put("enableShiftDragZoom", false);
        appletParams.put("enableRightClick", false);
        appletParams.put("useBrowserForJS", true);
        appletParams.put("id", VALIDATOR_APPLET_ID);

        page.evaluate(
                "(payload) => {"
                        + " const params = payload.params;"
                        + " const timeoutMs = payload.timeoutMs;"
                        + " window.__ggbReady = false;"
                        + " window.__ggbInitError = null;"
                        + " window.ggbApplet = null;"
                        + " const resolveAppletApi = (candidate) => {"
                        + "   let api = candidate;"
                        + "   if (!api && window.__ggbInjectedApplet"
                        + "       && typeof window.__ggbInjectedApplet.getAppletObject === 'function') {"
                        + "     try { api = window.__ggbInjectedApplet.getAppletObject(); } catch (error) { api = null; }"
                        + "   }"
                        + "   if (!api && window[payload.appletId]) { api = window[payload.appletId]; }"
                        + "   if (!api && window.ggbApplet0) { api = window.ggbApplet0; }"
                        + "   if (!api && window[payload.figureName]) { api = window[payload.figureName]; }"
                        + "   if (api && typeof api.evalCommand === 'function') {"
                        + "     window.ggbApplet = api;"
                        + "     window.__ggbReady = true;"
                        + "     return true;"
                        + "   }"
                        + "   return false;"
                        + " };"
                        + " params.appletOnLoad = function(api) {"
                        + "   resolveAppletApi(api);"
                        + " };"
                        + " try {"
                        + "   window.__ggbInjectedApplet = new GGBApplet(params, true);"
                        + "   window.__ggbInjectedApplet.inject('ggb-validator');"
                        + " } catch (error) {"
                        + "   window.__ggbInitError = error && error.message ? error.message : String(error);"
                        + "   return;"
                        + " }"
                        + " const startedAt = Date.now();"
                        + " const poll = () => {"
                        + "   if (window.__ggbInitError || window.__ggbReady || resolveAppletApi(null)) {"
                        + "     return;"
                        + "   }"
                        + "   if (Date.now() - startedAt >= timeoutMs) {"
                        + "     window.__ggbInitError = 'GeoGebra applet did not become ready within ' + timeoutMs + 'ms';"
                        + "     return;"
                        + "   }"
                        + "   window.setTimeout(poll, 250);"
                        + " };"
                        + " poll();"
                        + "}",
                Map.of(
                        "params", appletParams,
                        "timeoutMs", APPLET_BOOTSTRAP_TIMEOUT_MS,
                        "figureName", figureName,
                        "appletId", VALIDATOR_APPLET_ID
                )
        );

        page.waitForFunction(
                "() => window.__ggbReady === true || !!window.__ggbInitError",
                null,
                new Page.WaitForFunctionOptions().setTimeout((double) APPLET_BOOTSTRAP_TIMEOUT_MS)
        );

        String initError = asString(page.evaluate("() => window.__ggbInitError || ''"));
        if (initError != null && !initError.isBlank()) {
            throw new IOException("GeoGebra validator bootstrap failed: " + initError);
        }

        page.evaluate("() => { ggbApplet.setErrorDialogsActive(false); ggbApplet.setRepaintingActive(false); }");
    }

    private HttpServer startValidationServer(String figureName) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = buildValidatorHtml(figureName).getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> writeHtmlResponse(exchange, body));
        server.start();
        return server;
    }

    private void writeHtmlResponse(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private String executeValidationScript(Page page, List<String> commands) {
        return asString(page.evaluate(
                "(commands) => {"
                        + " const report = {"
                        + "   validationEngine: 'playwright',"
                        + "   completed: false,"
                        + "   appletLoaded: !!window.ggbApplet,"
                        + "   errorDialogsDisabled: false,"
                        + "   repaintingDisabled: false,"
                        + "   totalCommands: commands.length,"
                        + "   successfulCommands: 0,"
                        + "   failedCommands: 0,"
                        + "   totalObjects: 0,"
                        + "   xmlLength: 0,"
                        + "   error: null,"
                        + "   finalObjectNames: [],"
                        + "   commands: []"
                        + " };"
                        + " const safeString = (value) => {"
                        + "   if (value === undefined || value === null) { return null; }"
                        + "   return String(value);"
                        + " };"
                        + " const safeCall = (fn, fallback) => {"
                        + "   try { const value = fn(); return value === undefined ? fallback : value; }"
                        + "   catch (error) { return fallback; }"
                        + " };"
                        + " const splitArgs = (raw) => {"
                        + "   const args = [];"
                        + "   let current = '';"
                        + "   let depth = 0;"
                        + "   let quote = null;"
                        + "   for (let i = 0; i < raw.length; i += 1) {"
                        + "     const ch = raw[i];"
                        + "     if (quote) {"
                        + "       current += ch;"
                        + "       if (ch === '\\\\' && i + 1 < raw.length) { current += raw[i + 1]; i += 1; continue; }"
                        + "       if (ch === quote) { quote = null; }"
                        + "       continue;"
                        + "     }"
                        + "     if (ch === '\"' || ch === '\\'') { quote = ch; current += ch; continue; }"
                        + "     if (ch === '(' || ch === '[' || ch === '{') { depth += 1; current += ch; continue; }"
                        + "     if (ch === ')' || ch === ']' || ch === '}') { depth = Math.max(0, depth - 1); current += ch; continue; }"
                        + "     if (ch === ',' && depth === 0) { args.push(current.trim()); current = ''; continue; }"
                        + "     current += ch;"
                        + "   }"
                        + "   if (current.trim() || raw.trim().endsWith(',')) { args.push(current.trim()); }"
                        + "   return args;"
                        + " };"
                        + " const unquote = (value) => {"
                        + "   if (typeof value !== 'string') { return value; }"
                        + "   const trimmed = value.trim();"
                        + "   if (trimmed.length >= 2 && ((trimmed.startsWith('\"') && trimmed.endsWith('\"')) || (trimmed.startsWith('\\'') && trimmed.endsWith('\\'')))) {"
                        + "     return trimmed.slice(1, -1);"
                        + "   }"
                        + "   return trimmed;"
                        + " };"
                        + " const parseBooleanLiteral = (value) => {"
                        + "   if (typeof value !== 'string') { return null; }"
                        + "   const normalized = value.trim().toLowerCase();"
                        + "   if (normalized === 'true') { return true; }"
                        + "   if (normalized === 'false') { return false; }"
                        + "   return null;"
                        + " };"
                        + " const parseNumberLiteral = (value) => {"
                        + "   if (typeof value !== 'string' || value.trim() === '') { return null; }"
                        + "   const parsed = Number(value.trim());"
                        + "   return Number.isFinite(parsed) ? parsed : null;"
                        + " };"
                        + " const colorToRgb = (() => {"
                        + "   let ctx = null;"
                        + "   return (value) => {"
                        + "     const color = unquote(value);"
                        + "     if (!color) { return null; }"
                        + "     if (!ctx && typeof document !== 'undefined' && document.createElement) {"
                        + "       const canvas = document.createElement('canvas');"
                        + "       ctx = canvas.getContext('2d');"
                        + "     }"
                        + "     if (!ctx) { return null; }"
                        + "     ctx.fillStyle = '#000000';"
                        + "     ctx.fillStyle = color;"
                        + "     const normalized = ctx.fillStyle || '';"
                        + "     if (normalized.startsWith('#')) {"
                        + "       const hex = normalized.slice(1);"
                        + "       if (hex.length === 6) {"
                        + "         return { red: parseInt(hex.slice(0, 2), 16), green: parseInt(hex.slice(2, 4), 16), blue: parseInt(hex.slice(4, 6), 16) };"
                        + "       }"
                        + "     }"
                        + "     const match = normalized.match(/^rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)/i);"
                        + "     if (match) {"
                        + "       return { red: Number(match[1]), green: Number(match[2]), blue: Number(match[3]) };"
                        + "     }"
                        + "     return null;"
                        + "   };"
                        + " })();"
                        + " const executeCommand = (api, command) => {"
                        + "   const trimmed = typeof command === 'string' ? command.trim() : '';"
                        + "   const match = trimmed.match(/^([A-Za-z][A-Za-z0-9_]*)\\((.*)\\)$/);"
                        + "   if (!match) {"
                        + "     return { success: !!api.evalCommand(command), error: null };"
                        + "   }"
                        + "   const fnName = match[1];"
                        + "   const args = splitArgs(match[2]);"
                        + "   try {"
                        + "     switch (fnName) {"
                        + "       case 'SetColor': {"
                        + "         if (args.length === 2) {"
                        + "           const rgb = colorToRgb(args[1]);"
                        + "           if (rgb) { api.setColor(args[0].trim(), rgb.red, rgb.green, rgb.blue); return { success: true, error: null }; }"
                        + "         }"
                        + "         if (args.length === 4) {"
                        + "           const red = parseNumberLiteral(args[1]);"
                        + "           const green = parseNumberLiteral(args[2]);"
                        + "           const blue = parseNumberLiteral(args[3]);"
                        + "           if (red !== null && green !== null && blue !== null) {"
                        + "             api.setColor(args[0].trim(), Math.round(red), Math.round(green), Math.round(blue));"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetLineThickness': {"
                        + "         if (args.length === 2) {"
                        + "           const thickness = parseNumberLiteral(args[1]);"
                        + "           if (thickness !== null) { api.setLineThickness(args[0].trim(), Math.round(thickness)); return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetLineStyle': {"
                        + "         if (args.length === 2) {"
                        + "           const style = parseNumberLiteral(args[1]);"
                        + "           if (style !== null) { api.setLineStyle(args[0].trim(), Math.round(style)); return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetPointStyle': {"
                        + "         if (args.length === 2) {"
                        + "           const style = parseNumberLiteral(args[1]);"
                        + "           if (style !== null) { api.setPointStyle(args[0].trim(), Math.round(style)); return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetPointSize': {"
                        + "         if (args.length === 2) {"
                        + "           const size = parseNumberLiteral(args[1]);"
                        + "           if (size !== null) { api.setPointSize(args[0].trim(), Math.round(size)); return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetFilling': {"
                        + "         if (args.length === 2) {"
                        + "           const filling = parseNumberLiteral(args[1]);"
                        + "           if (filling !== null) { api.setFilling(args[0].trim(), filling); return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'ShowLabel': {"
                        + "         if (args.length === 2) {"
                        + "           const visible = parseBooleanLiteral(args[1]);"
                        + "           if (visible !== null) { api.setLabelVisible(args[0].trim(), visible); return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetCaption': {"
                        + "         if (args.length === 2) {"
                        + "           api.setCaption(args[0].trim(), unquote(args[1]));"
                        + "           return { success: true, error: null };"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       default:"
                        + "         break;"
                        + "     }"
                        + "     return { success: !!api.evalCommand(command), error: null };"
                        + "   } catch (error) {"
                        + "     return { success: false, error: error && error.message ? error.message : String(error) };"
                        + "   }"
                        + " };"
                        + " const allObjectNames = () => {"
                        + "   const names = safeCall(() => Array.from(ggbApplet.getAllObjectNames()), []);"
                        + "   return Array.from(new Set((names || []).filter(name => typeof name === 'string' && name.trim() !== '')));"
                        + " };"
                        + " const snapshotObject = (name) => ({"
                        + "   name,"
                        + "   exists: !!safeCall(() => ggbApplet.exists(name), false),"
                        + "   defined: !!safeCall(() => ggbApplet.isDefined(name), false),"
                        + "   type: safeString(safeCall(() => ggbApplet.getObjectType(name), null))"
                        + " });"
                        + " try { ggbApplet.setErrorDialogsActive(false); report.errorDialogsDisabled = true; }"
                        + " catch (error) { report.error = report.error || ('Could not disable GeoGebra error dialogs: ' + (error && error.message ? error.message : String(error))); }"
                        + " try { ggbApplet.setRepaintingActive(false); report.repaintingDisabled = true; }"
                        + " catch (error) { report.error = report.error || ('Could not disable repainting: ' + (error && error.message ? error.message : String(error))); }"
                        + " for (let index = 0; index < commands.length; index += 1) {"
                        + "   const command = commands[index];"
                        + "   const beforeNames = allObjectNames();"
                        + "   const beforeSet = new Set(beforeNames);"
                        + "   let success = false;"
                        + "   let errorMessage = null;"
                        + "   const result = executeCommand(ggbApplet, command);"
                        + "   success = !!result.success;"
                        + "   errorMessage = result.error;"
                        + "   const afterNames = allObjectNames();"
                        + "   const createdObjects = afterNames.filter(name => !beforeSet.has(name)).map(snapshotObject);"
                        + "   report.commands.push({"
                        + "     index: index + 1,"
                        + "     command,"
                        + "     success,"
                        + "     error: errorMessage,"
                        + "     objectCountAfter: afterNames.length,"
                        + "     objectNamesAfter: afterNames,"
                        + "     createdObjects"
                        + "   });"
                        + "   if (success) {"
                        + "     report.successfulCommands += 1;"
                        + "   } else {"
                        + "     report.failedCommands += 1;"
                        + "     if (!report.error) {"
                        + "       report.error = errorMessage"
                        + "         ? ('Command ' + (index + 1) + ' threw: ' + command + ' (' + errorMessage + ')')"
                        + "         : ('Command ' + (index + 1) + ' returned false: ' + command);"
                        + "     }"
                        + "   }"
                        + " }"
                        + " report.finalObjectNames = allObjectNames();"
                        + " report.totalObjects = report.finalObjectNames.length;"
                        + " report.xmlLength = safeString(safeCall(() => ggbApplet.getXML(), '')).length;"
                        + " report.completed = true;"
                        + " return JSON.stringify(report);"
                        + "}",
                commands
        ));
    }

    private ValidationReport parseValidationReport(String validationJson) {
        if (validationJson == null || validationJson.isBlank()) {
            return null;
        }
        return JsonUtils.fromJson(validationJson, ValidationReport.class);
    }

    private void normalizeParsedReport(ValidationReport report,
                                       String figureName,
                                       List<String> commands,
                                       String browserExecutable) {
        if (report.figureName == null || report.figureName.isBlank()) {
            report.figureName = figureName;
        }
        if (report.validationEngine == null || report.validationEngine.isBlank()) {
            report.validationEngine = PLAYWRIGHT_ENGINE;
        }
        if (report.browserExecutable == null || report.browserExecutable.isBlank()) {
            report.browserExecutable = browserExecutable;
        }
        if (report.commands == null || report.commands.isEmpty()) {
            report.commands = newValidationReport(figureName, commands).commands;
        }
        if (report.consoleMessages == null) {
            report.consoleMessages = new ArrayList<>();
        }
        if (report.pageErrors == null) {
            report.pageErrors = new ArrayList<>();
        }
        if (report.requestFailures == null) {
            report.requestFailures = new ArrayList<>();
        }
        if (report.finalObjectNames == null) {
            report.finalObjectNames = new ArrayList<>();
        }

        for (int i = 0; i < report.commands.size(); i++) {
            CommandValidation commandValidation = report.commands.get(i);
            if (commandValidation == null) {
                commandValidation = new CommandValidation();
                report.commands.set(i, commandValidation);
            }
            if (commandValidation.index <= 0) {
                commandValidation.index = i + 1;
            }
            if ((commandValidation.command == null || commandValidation.command.isBlank())
                    && i < commands.size()) {
                commandValidation.command = commands.get(i);
            }
            if (commandValidation.objectNamesAfter == null) {
                commandValidation.objectNamesAfter = new ArrayList<>();
            }
            if (commandValidation.createdObjects == null) {
                commandValidation.createdObjects = new ArrayList<>();
            }
        }

        report.totalCommands = report.commands.size();
        report.successfulCommands = 0;
        report.failedCommands = 0;
        for (CommandValidation entry : report.commands) {
            if (entry == null) {
                continue;
            }
            if (Boolean.TRUE.equals(entry.success)) {
                report.successfulCommands++;
            } else if (Boolean.FALSE.equals(entry.success)) {
                report.failedCommands++;
            }
        }
        if (report.totalObjects <= 0 && report.finalObjectNames != null) {
            report.totalObjects = report.finalObjectNames.size();
        }
    }

    private void appendDiagnostics(ValidationReport report,
                                   List<String> consoleMessages,
                                   List<String> pageErrors,
                                   List<String> requestFailures) {
        if (report == null) {
            return;
        }
        if (report.consoleMessages == null) {
            report.consoleMessages = new ArrayList<>();
        }
        if (report.pageErrors == null) {
            report.pageErrors = new ArrayList<>();
        }
        if (report.requestFailures == null) {
            report.requestFailures = new ArrayList<>();
        }
        report.consoleMessages.addAll(consoleMessages);
        report.pageErrors.addAll(pageErrors);
        report.requestFailures.addAll(requestFailures);
    }

    private String formatPlaywrightError(PlaywrightException error) {
        String message = error != null && error.getMessage() != null
                ? error.getMessage().trim()
                : "Unknown Playwright error";
        String normalized = message.toLowerCase();
        if (normalized.contains("executable doesn't exist")
                || normalized.contains("failed to launch")
                || normalized.contains("browsertype.launch")) {
            return "GeoGebra Playwright validation failed to launch Chromium. "
                    + PLAYWRIGHT_INSTALL_HINT
                    + ". Raw error: "
                    + message;
        }
        return "GeoGebra Playwright validation failed: " + message;
    }

    private ValidationReport newValidationReport(String figureName, List<String> commands) {
        ValidationReport report = new ValidationReport();
        report.figureName = figureName;
        report.validationEngine = PLAYWRIGHT_ENGINE;
        report.totalCommands = commands.size();
        report.commands = new ArrayList<>();
        report.consoleMessages = new ArrayList<>();
        report.pageErrors = new ArrayList<>();
        report.requestFailures = new ArrayList<>();
        report.finalObjectNames = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            CommandValidation entry = new CommandValidation();
            entry.index = i + 1;
            entry.command = commands.get(i);
            entry.objectNamesAfter = new ArrayList<>();
            entry.createdObjects = new ArrayList<>();
            report.commands.add(entry);
        }
        return report;
    }

    private String summarizeValidationFailure(ValidationReport report) {
        if (report == null) {
            return "GeoGebra validation failed for an unknown reason";
        }
        if (report.error != null && !report.error.isBlank()) {
            return report.error;
        }
        if (report.commands != null) {
            for (CommandValidation entry : report.commands) {
                if (entry != null && Boolean.FALSE.equals(entry.success)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Command ").append(entry.index).append(" returned false: ").append(entry.command);
                    if (entry.error != null && !entry.error.isBlank()) {
                        sb.append(" (").append(entry.error).append(")");
                    }
                    return sb.toString();
                }
            }
        }
        if (!report.completed) {
            return "GeoGebra validation did not complete";
        }
        return "GeoGebra validation completed with failures";
    }

    private String normalizeFigureName(String figureName) {
        return (figureName == null || figureName.isBlank()) ? GeoGebraCodeUtils.EXPECTED_FIGURE_NAME : figureName.trim();
    }

    private String buildPreviewHtml(List<String> commands,
                                    List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                    String figureName) {
        String safeFigureName = normalizeFigureName(figureName);
        String figureNameJson = JsonUtils.toJson(safeFigureName);
        String commandsPayload = encodeJsonPayload(commands);
        String scenePayload = encodeJsonPayload(sceneDirectives != null ? sceneDirectives : List.of());

        return "<!doctype html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"utf-8\" />\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n"
                + "  <title>" + escapeHtml(safeFigureName) + "</title>\n"
                + "  <style>\n"
                + "    body { font-family: Arial, sans-serif; margin: 0; padding: 24px; background: #f7f7f5; color: #1f2933; }\n"
                + "    h1 { font-size: 20px; margin: 0 0 16px; }\n"
                + "    .layout { display: grid; grid-template-columns: minmax(320px, 960px); gap: 16px; }\n"
                + "    .panel { background: #fff; border: 1px solid #d9e2ec; padding: 16px; }\n"
                + "    #ggb-element { width: 100%; max-width: 960px; height: 540px; border: 1px solid #d9e2ec; background: #fff; }\n"
                + "    #scene-controls { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0 0; }\n"
                + "    #scene-controls button { border: 1px solid #bcccdc; background: #f8fafc; color: #102a43; padding: 8px 12px; cursor: pointer; }\n"
                + "    #scene-controls button.active { background: #d9e2ec; border-color: #829ab1; }\n"
                + "    pre { margin: 0; padding: 16px; background: #fff; border: 1px solid #d9e2ec; overflow: auto; white-space: pre-wrap; }\n"
                + "    .note { font-size: 13px; color: #52606d; }\n"
                + "    .status { font-weight: 700; }\n"
                + "    .status.ok { color: #12704a; }\n"
                + "    .status.fail { color: #b42318; }\n"
                + "    .status.pending { color: #7a5c00; }\n"
                + "  </style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div class=\"layout\">\n"
                + "    <div class=\"panel\">\n"
                + "      <h1>" + escapeHtml(safeFigureName) + "</h1>\n"
                + "      <div id=\"ggb-element\"></div>\n"
                + "      <div id=\"scene-controls\"></div>\n"
                + "      <p id=\"preview-status\" class=\"status pending\">Waiting for GeoGebra preview...</p>\n"
                + "      <p class=\"note\">Runtime validation is executed separately by Playwright. See <code>"
                + escapeHtml(VALIDATION_FILE)
                + "</code> for the structured validation report.</p>\n"
                + "    </div>\n"
                + "    <div>\n"
                + "      <pre id=\"commands\"></pre>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "  <script id=\"commands-data\" type=\"text/plain\">" + commandsPayload + "</script>\n"
                + "  <script id=\"scene-data\" type=\"text/plain\">" + scenePayload + "</script>\n"
                + "  <script>\n"
                + "    const figureName = " + figureNameJson + ";\n"
                + "    const previewStatus = document.getElementById('preview-status');\n"
                + "    const sceneControls = document.getElementById('scene-controls');\n"
                + "    function decodeEmbeddedJson(elementId, fallbackValue) {\n"
                + "      const element = document.getElementById(elementId);\n"
                + "      if (!element) {\n"
                + "        return fallbackValue;\n"
                + "      }\n"
                + "      try {\n"
                + "        const binary = window.atob((element.textContent || '').trim());\n"
                + "        const bytes = Uint8Array.from(binary, ch => ch.charCodeAt(0));\n"
                + "        return JSON.parse(new TextDecoder('utf-8').decode(bytes));\n"
                + "      } catch (error) {\n"
                + "        console.warn('Could not decode embedded GeoGebra payload', elementId, error);\n"
                + "        return fallbackValue;\n"
                + "      }\n"
                + "    }\n"
                + "    const commands = decodeEmbeddedJson('commands-data', []);\n"
                + "    const sceneDirectives = decodeEmbeddedJson('scene-data', []);\n"
                + "    document.getElementById('commands').textContent = commands.join('\\n');\n"
                + "    function setStatus(text, cssClass) {\n"
                + "      previewStatus.textContent = text;\n"
                + "      previewStatus.className = 'status ' + cssClass;\n"
                + "    }\n"
                + "    function safeCall(fn, fallbackValue) {\n"
                + "      try {\n"
                + "        const value = fn();\n"
                + "        return value === undefined ? fallbackValue : value;\n"
                + "      } catch (error) {\n"
                + "        return fallbackValue;\n"
                + "      }\n"
                + "    }\n"
                + "    function splitArgs(raw) {\n"
                + "      const args = [];\n"
                + "      let current = '';\n"
                + "      let depth = 0;\n"
                + "      let quote = null;\n"
                + "      for (let i = 0; i < raw.length; i += 1) {\n"
                + "        const ch = raw[i];\n"
                + "        if (quote) {\n"
                + "          current += ch;\n"
                + "          if (ch === '\\\\' && i + 1 < raw.length) {\n"
                + "            current += raw[i + 1];\n"
                + "            i += 1;\n"
                + "            continue;\n"
                + "          }\n"
                + "          if (ch === quote) {\n"
                + "            quote = null;\n"
                + "          }\n"
                + "          continue;\n"
                + "        }\n"
                + "        if (ch === '\"' || ch === '\\'') {\n"
                + "          quote = ch;\n"
                + "          current += ch;\n"
                + "          continue;\n"
                + "        }\n"
                + "        if (ch === '(' || ch === '[' || ch === '{') {\n"
                + "          depth += 1;\n"
                + "          current += ch;\n"
                + "          continue;\n"
                + "        }\n"
                + "        if (ch === ')' || ch === ']' || ch === '}') {\n"
                + "          depth = Math.max(0, depth - 1);\n"
                + "          current += ch;\n"
                + "          continue;\n"
                + "        }\n"
                + "        if (ch === ',' && depth === 0) {\n"
                + "          args.push(current.trim());\n"
                + "          current = '';\n"
                + "          continue;\n"
                + "        }\n"
                + "        current += ch;\n"
                + "      }\n"
                + "      if (current.trim() || raw.trim().endsWith(',')) {\n"
                + "        args.push(current.trim());\n"
                + "      }\n"
                + "      return args;\n"
                + "    }\n"
                + "    function unquote(value) {\n"
                + "      if (typeof value !== 'string') {\n"
                + "        return value;\n"
                + "      }\n"
                + "      const trimmed = value.trim();\n"
                + "      if (trimmed.length >= 2\n"
                + "          && ((trimmed.startsWith('\"') && trimmed.endsWith('\"'))\n"
                + "              || (trimmed.startsWith('\\'') && trimmed.endsWith('\\'')))) {\n"
                + "        return trimmed.slice(1, -1);\n"
                + "      }\n"
                + "      return trimmed;\n"
                + "    }\n"
                + "    function parseBooleanLiteral(value) {\n"
                + "      if (typeof value !== 'string') {\n"
                + "        return null;\n"
                + "      }\n"
                + "      const normalized = value.trim().toLowerCase();\n"
                + "      if (normalized === 'true') {\n"
                + "        return true;\n"
                + "      }\n"
                + "      if (normalized === 'false') {\n"
                + "        return false;\n"
                + "      }\n"
                + "      return null;\n"
                + "    }\n"
                + "    function parseNumberLiteral(value) {\n"
                + "      if (typeof value !== 'string' || value.trim() === '') {\n"
                + "        return null;\n"
                + "      }\n"
                + "      const parsed = Number(value.trim());\n"
                + "      return Number.isFinite(parsed) ? parsed : null;\n"
                + "    }\n"
                + "    const colorToRgb = (() => {\n"
                + "      let ctx = null;\n"
                + "      return (value) => {\n"
                + "        const color = unquote(value);\n"
                + "        if (!color) {\n"
                + "          return null;\n"
                + "        }\n"
                + "        if (!ctx) {\n"
                + "          const canvas = document.createElement('canvas');\n"
                + "          ctx = canvas.getContext('2d');\n"
                + "        }\n"
                + "        if (!ctx) {\n"
                + "          return null;\n"
                + "        }\n"
                + "        ctx.fillStyle = '#000000';\n"
                + "        ctx.fillStyle = color;\n"
                + "        const normalized = ctx.fillStyle || '';\n"
                + "        if (normalized.startsWith('#')) {\n"
                + "          const hex = normalized.slice(1);\n"
                + "          if (hex.length === 6) {\n"
                + "            return {\n"
                + "              red: parseInt(hex.slice(0, 2), 16),\n"
                + "              green: parseInt(hex.slice(2, 4), 16),\n"
                + "              blue: parseInt(hex.slice(4, 6), 16)\n"
                + "            };\n"
                + "          }\n"
                + "        }\n"
                + "        const match = normalized.match(/^rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)/i);\n"
                + "        if (match) {\n"
                + "          return { red: Number(match[1]), green: Number(match[2]), blue: Number(match[3]) };\n"
                + "        }\n"
                + "        return null;\n"
                + "      };\n"
                + "    })();\n"
                + "    function executeGeoGebraCommand(api, command) {\n"
                + "      const trimmed = typeof command === 'string' ? command.trim() : '';\n"
                + "      const match = trimmed.match(/^([A-Za-z][A-Za-z0-9_]*)\\((.*)\\)$/);\n"
                + "      if (!match) {\n"
                + "        return { success: !!api.evalCommand(command), error: null };\n"
                + "      }\n"
                + "      const fnName = match[1];\n"
                + "      const args = splitArgs(match[2]);\n"
                + "      try {\n"
                + "        switch (fnName) {\n"
                + "          case 'SetColor': {\n"
                + "            if (args.length === 2) {\n"
                + "              const rgb = colorToRgb(args[1]);\n"
                + "              if (rgb) {\n"
                + "                api.setColor(args[0].trim(), rgb.red, rgb.green, rgb.blue);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            if (args.length === 4) {\n"
                + "              const red = parseNumberLiteral(args[1]);\n"
                + "              const green = parseNumberLiteral(args[2]);\n"
                + "              const blue = parseNumberLiteral(args[3]);\n"
                + "              if (red !== null && green !== null && blue !== null) {\n"
                + "                api.setColor(args[0].trim(), Math.round(red), Math.round(green), Math.round(blue));\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetLineThickness': {\n"
                + "            if (args.length === 2) {\n"
                + "              const thickness = parseNumberLiteral(args[1]);\n"
                + "              if (thickness !== null) {\n"
                + "                api.setLineThickness(args[0].trim(), Math.round(thickness));\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetLineStyle': {\n"
                + "            if (args.length === 2) {\n"
                + "              const style = parseNumberLiteral(args[1]);\n"
                + "              if (style !== null) {\n"
                + "                api.setLineStyle(args[0].trim(), Math.round(style));\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetPointStyle': {\n"
                + "            if (args.length === 2) {\n"
                + "              const style = parseNumberLiteral(args[1]);\n"
                + "              if (style !== null) {\n"
                + "                api.setPointStyle(args[0].trim(), Math.round(style));\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetPointSize': {\n"
                + "            if (args.length === 2) {\n"
                + "              const size = parseNumberLiteral(args[1]);\n"
                + "              if (size !== null) {\n"
                + "                api.setPointSize(args[0].trim(), Math.round(size));\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetFilling': {\n"
                + "            if (args.length === 2) {\n"
                + "              const filling = parseNumberLiteral(args[1]);\n"
                + "              if (filling !== null) {\n"
                + "                api.setFilling(args[0].trim(), filling);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'ShowLabel': {\n"
                + "            if (args.length === 2) {\n"
                + "              const visible = parseBooleanLiteral(args[1]);\n"
                + "              if (visible !== null) {\n"
                + "                api.setLabelVisible(args[0].trim(), visible);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetCaption': {\n"
                + "            if (args.length === 2) {\n"
                + "              api.setCaption(args[0].trim(), unquote(args[1]));\n"
                + "              return { success: true, error: null };\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          default:\n"
                + "            break;\n"
                + "        }\n"
                + "        return { success: !!api.evalCommand(command), error: null };\n"
                + "      } catch (error) {\n"
                + "        return { success: false, error: error && error.message ? error.message : String(error) };\n"
                + "      }\n"
                + "    }\n"
                + "    function fitView(api) {\n"
                + "      safeCall(() => api.showAllObjects(), null);\n"
                + "    }\n"
                + "    function toggleObjectVisibility(api, objectName, visible) {\n"
                + "      if (!objectName) {\n"
                + "        return;\n"
                + "      }\n"
                + "      if (safeCall(() => api.exists(objectName), false)) {\n"
                + "        safeCall(() => api.setVisible(objectName, visible), null);\n"
                + "      }\n"
                + "    }\n"
                + "    function markActiveScene(index) {\n"
                + "      const buttons = Array.from(sceneControls.querySelectorAll('button'));\n"
                + "      buttons.forEach((button, buttonIndex) => {\n"
                + "        button.classList.toggle('active', buttonIndex === index);\n"
                + "      });\n"
                + "    }\n"
                + "    function applySceneDirective(api, directive, index) {\n"
                + "      if (!directive) {\n"
                + "        fitView(api);\n"
                + "        return;\n"
                + "      }\n"
                + "      for (const objectName of directive.hide || []) {\n"
                + "        toggleObjectVisibility(api, objectName, false);\n"
                + "      }\n"
                + "      for (const objectName of directive.show || []) {\n"
                + "        toggleObjectVisibility(api, objectName, true);\n"
                + "      }\n"
                + "      fitView(api);\n"
                + "      markActiveScene(index);\n"
                + "      setStatus('Preview loaded. Showing ' + (directive.title || ('scene ' + (index + 1))) + '.', 'ok');\n"
                + "    }\n"
                + "    function renderSceneButtons(api) {\n"
                + "      sceneControls.innerHTML = '';\n"
                + "      if (!Array.isArray(sceneDirectives) || sceneDirectives.length === 0) {\n"
                + "        return;\n"
                + "      }\n"
                + "      sceneDirectives.forEach((directive, index) => {\n"
                + "        const button = document.createElement('button');\n"
                + "        button.type = 'button';\n"
                + "        button.textContent = directive && directive.title ? directive.title : ('Scene ' + (index + 1));\n"
                + "        button.addEventListener('click', () => applySceneDirective(api, directive, index));\n"
                + "        sceneControls.appendChild(button);\n"
                + "      });\n"
                + "    }\n"
                + "    function replayCommands(api) {\n"
                + "      let failures = 0;\n"
                + "      for (const command of commands) {\n"
                + "        if (!command || !command.trim()) {\n"
                + "          continue;\n"
                + "        }\n"
                + "        const result = executeGeoGebraCommand(api, command);\n"
                + "        if (!result.success) {\n"
                + "          failures += 1;\n"
                + "          console.warn('Preview applet failed for command:', command, result.error);\n"
                + "        }\n"
                + "      }\n"
                + "      renderSceneButtons(api);\n"
                + "      if (sceneDirectives.length > 0) {\n"
                + "        applySceneDirective(api, sceneDirectives[0], 0);\n"
                + "      } else {\n"
                + "        fitView(api);\n"
                + "      }\n"
                + "      if (failures === 0) {\n"
                + "        if (sceneDirectives.length === 0) {\n"
                + "          setStatus('Preview loaded. All commands replayed in the visible applet.', 'ok');\n"
                + "        }\n"
                + "      } else {\n"
                + "        setStatus('Preview loaded with ' + failures + ' command issue(s). Check validation JSON for the authoritative report.', 'fail');\n"
                + "      }\n"
                + "    }\n"
                + "    function loadGeoGebraScript() {\n"
                + "      return new Promise((resolve, reject) => {\n"
                + "        if (typeof window.GGBApplet === 'function') {\n"
                + "          resolve();\n"
                + "          return;\n"
                + "        }\n"
                + "        const existing = document.getElementById('ggb-deploy-script');\n"
                + "        if (existing) {\n"
                + "          existing.addEventListener('load', () => resolve(), { once: true });\n"
                + "          existing.addEventListener('error', () => reject(new Error('GeoGebra deploy script failed to load.')), { once: true });\n"
                + "          return;\n"
                + "        }\n"
                + "        const script = document.createElement('script');\n"
                + "        script.id = 'ggb-deploy-script';\n"
                + "        script.src = '" + DEPLOY_GGB_URL + "';\n"
                + "        script.async = true;\n"
                + "        script.onload = () => resolve();\n"
                + "        script.onerror = () => reject(new Error('GeoGebra deploy script failed to load.'));\n"
                + "        document.head.appendChild(script);\n"
                + "      });\n"
                + "    }\n"
                + "    function injectPreviewApplet() {\n"
                + "      const params = {\n"
                + "        appName: 'classic',\n"
                + "        width: 960,\n"
                + "        height: 540,\n"
                + "        showToolBar: false,\n"
                + "        showAlgebraInput: false,\n"
                + "        showMenuBar: false,\n"
                + "        showResetIcon: false,\n"
                + "        enableShiftDragZoom: true,\n"
                + "        enableRightClick: false,\n"
                + "        useBrowserForJS: true,\n"
                + "        appletOnLoad(api) {\n"
                + "          replayCommands(api);\n"
                + "        }\n"
                + "      };\n"
                + "      try {\n"
                + "        new GGBApplet(params, true).inject('ggb-element');\n"
                + "      } catch (error) {\n"
                + "        setStatus('GeoGebra preview bootstrap failed: ' + (error && error.message ? error.message : String(error)), 'fail');\n"
                + "      }\n"
                + "    }\n"
                + "    function bootstrapGeoGebra() {\n"
                + "      loadGeoGebraScript()\n"
                + "        .then(() => injectPreviewApplet())\n"
                + "        .catch((error) => {\n"
                + "          setStatus((error && error.message) ? error.message : 'GeoGebra preview script failed to load.', 'fail');\n"
                + "        });\n"
                + "    }\n"
                + "    if (commands.length === 0) {\n"
                + "      setStatus('No executable GeoGebra commands found.', 'fail');\n"
                + "    } else {\n"
                + "      bootstrapGeoGebra();\n"
                + "    }\n"
                + "  </script>\n"
                + "</body>\n"
                + "</html>\n";
    }

    private String buildValidatorHtml(String figureName) {
        return "<!doctype html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"utf-8\" />\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n"
                + "  <title>" + escapeHtml(normalizeFigureName(figureName)) + "</title>\n"
                + "  <style>\n"
                + "    html, body { margin: 0; padding: 0; background: #fff; }\n"
                + "    #ggb-validator {\n"
                + "      width: 1px;\n"
                + "      height: 1px;\n"
                + "      opacity: 0;\n"
                + "      overflow: hidden;\n"
                + "      pointer-events: none;\n"
                + "      position: absolute;\n"
                + "      left: -9999px;\n"
                + "      top: -9999px;\n"
                + "    }\n"
                + "  </style>\n"
                + "  <script>\n"
                + "    window.__ggbDeployLoaded = false;\n"
                + "    window.__ggbDeployError = null;\n"
                + "    (function loadGeoGebraDeployScript() {\n"
                + "      const script = document.createElement('script');\n"
                + "      script.id = 'ggb-deploy-script';\n"
                + "      script.src = '" + DEPLOY_GGB_URL + "';\n"
                + "      script.async = true;\n"
                + "      script.onload = function() { window.__ggbDeployLoaded = true; };\n"
                + "      script.onerror = function() { window.__ggbDeployError = 'GeoGebra deploy script failed to load.'; };\n"
                + "      document.head.appendChild(script);\n"
                + "    })();\n"
                + "  </script>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div id=\"ggb-validator\"></div>\n"
                + "</body>\n"
                + "</html>\n";
    }

    private String encodeJsonPayload(Object value) {
        String json = JsonUtils.toJson(value);
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String formatConsoleMessage(ConsoleMessage message) {
        if (message == null) {
            return "console: <null>";
        }
        return message.type() + ": " + message.text();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void safeClose(Page page) {
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (Exception ignored) {
            // Ignore shutdown noise from already-closed pages.
        }
    }

    private void safeClose(BrowserContext context) {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (Exception ignored) {
            // Ignore shutdown noise from already-closed contexts.
        }
    }

    private void safeClose(Browser browser) {
        if (browser == null) {
            return;
        }
        try {
            browser.close();
        } catch (Exception ignored) {
            // Ignore shutdown noise from already-closed browsers.
        }
    }

    private void safeClose(HttpServer server) {
        if (server == null) {
            return;
        }
        try {
            server.stop(0);
        } catch (Exception ignored) {
            // Ignore shutdown noise from already-stopped servers.
        }
    }

    private void safeClose(Playwright playwright) {
        if (playwright == null) {
            return;
        }
        try {
            playwright.close();
        } catch (Exception ignored) {
            // Ignore shutdown noise from already-closed Playwright sessions.
        }
    }

    public static final class ValidationReport {
        public String figureName;
        public String validationEngine;
        public String browserExecutable;
        public boolean completed;
        public boolean appletLoaded;
        public boolean errorDialogsDisabled;
        public boolean repaintingDisabled;
        public int totalCommands;
        public int successfulCommands;
        public int failedCommands;
        public int totalObjects;
        public int xmlLength;
        public String error;
        public List<String> finalObjectNames = new ArrayList<>();
        public List<String> consoleMessages = new ArrayList<>();
        public List<String> pageErrors = new ArrayList<>();
        public List<String> requestFailures = new ArrayList<>();
        public List<CommandValidation> commands = new ArrayList<>();

        boolean allSuccessful() {
            return completed
                    && (error == null || error.isBlank())
                    && failedCommands == 0
                    && successfulCommands == totalCommands;
        }
    }

    public static final class CommandValidation {
        public int index;
        public String command;
        public Boolean success;
        public String error;
        public int objectCountAfter;
        public List<String> objectNamesAfter = new ArrayList<>();
        public List<ObjectSnapshot> createdObjects = new ArrayList<>();
    }

    public static final class ObjectSnapshot {
        public String name;
        public Boolean exists;
        public Boolean defined;
        public String type;
    }

    public static final class RenderAttemptResult {
        private final boolean success;
        private final String previewPath;
        private final String geometryPath;
        private final String error;

        public RenderAttemptResult(boolean success, String previewPath, String geometryPath, String error) {
            this.success = success;
            this.previewPath = previewPath;
            this.geometryPath = geometryPath;
            this.error = error;
        }

        public boolean success() { return success; }
        public String previewPath() { return previewPath; }
        public String geometryPath() { return geometryPath; }
        public String error() { return error; }
    }
}
