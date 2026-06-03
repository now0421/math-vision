package com.mathvision.service;

import com.mathvision.util.GeoGebraCodeUtils;
import com.mathvision.util.GeoGebraValidationSupport;
import com.mathvision.util.JsonUtils;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Creates a GeoGebra preview artifact and validates commands by replaying them
 * through an embedded GeoGebra applet driven by Playwright.
 */
public class GeoGebraRenderService {

    private static final Logger log = LoggerFactory.getLogger(GeoGebraRenderService.class);
    public static final String PREVIEW_FILE = "07_geogebra_preview.html";
    public static final String VALIDATION_FILE = "07_geogebra_validation.json";
    public static final String GEOMETRY_FILE = "07_geogebra_geometry.json";
    private static final String DEPLOY_GGB_URL = "https://www.geogebra.org/apps/deployggb.js";
    private static final String LOCAL_DEPLOY_GGB_PATH = "/deployggb.js";
    private static final String GEOGEBRA_APPS_URL_PREFIX = "https://www.geogebra.org/apps/";
    private static final String LOCAL_GEOGEBRA_APPS_PATH = "/apps/";
    private static final String VALIDATOR_HTML5_CODEBASE = "/apps/5.4.920.0/web3d/";
    private static final String GEOGEBRA_DEPLOY_URL_ENV = "MATHVISION_GEOGEBRA_DEPLOY_URL";
    private static final String GEOGEBRA_CACHE_DIR_ENV = "MATHVISION_GEOGEBRA_CACHE_DIR";
    private static final String GEOGEBRA_CACHE_DIR_NAME = ".mathvision/geogebra";
    private static final String DEPLOY_GGB_CACHE_FILE = "deployggb.js";
    private static final String PLAYWRIGHT_ENGINE = "playwright";
    private static final String PLAYWRIGHT_BUNDLED_CHROMIUM = "playwright:chromium";
    private static final String PLAYWRIGHT_INSTALL_HINT =
            "Install Playwright Chromium with "
                    + "mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI "
                    + "-Dexec.args=\"install chromium\"";
    private static final int VALIDATION_TIMEOUT_SECONDS = 45;
    private static final int VALIDATION_TIMEOUT_MS = VALIDATION_TIMEOUT_SECONDS * 1000;
    private static final int APPLET_BOOTSTRAP_TIMEOUT_MS = VALIDATION_TIMEOUT_MS * 2;
    private static final int APPLET_WIDTH = 960;
    private static final int APPLET_HEIGHT = 540;
    private static final String VALIDATOR_APPLET_ID = "ggbValidatorApplet";
    private static final String INTERNAL_HELPER_PREFIX = "__ggb_cond_";
    private static final double DEFAULT_FRAME_X_MIN = -7.0;
    private static final double DEFAULT_FRAME_X_MAX = 7.0;
    private static final double DEFAULT_FRAME_Y_MIN = -4.0;
    private static final double DEFAULT_FRAME_Y_MAX = 4.0;
    private static final Pattern SET_COORD_SYSTEM_COMMAND = Pattern.compile(
            "^SetCoordSystem\\s*\\(([^)]*)\\)\\s*$", Pattern.CASE_INSENSITIVE);
    private ViewBounds activeViewBounds = ViewBounds.defaults();

    private static final class ViewBounds {
        private final double xMin;
        private final double xMax;
        private final double yMin;
        private final double yMax;

        private ViewBounds(double xMin, double xMax, double yMin, double yMax) {
            this.xMin = xMin;
            this.xMax = xMax;
            this.yMin = yMin;
            this.yMax = yMax;
        }

        private double xMin() { return xMin; }
        private double xMax() { return xMax; }
        private double yMin() { return yMin; }
        private double yMax() { return yMax; }

        static ViewBounds defaults() {
            return new ViewBounds(
                    DEFAULT_FRAME_X_MIN,
                    DEFAULT_FRAME_X_MAX,
                    DEFAULT_FRAME_Y_MIN,
                    DEFAULT_FRAME_Y_MAX);
        }
    }

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
            ViewBounds viewBounds = resolveViewBounds(commands);
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
                ViewBounds previousViewBounds = activeViewBounds;
                activeViewBounds = viewBounds;
                try {
                    report = validateWithHeadlessBrowser(previewPath, safeFigureName, commands,
                            sceneDirectives, geometryPath);
                } finally {
                    activeViewBounds = previousViewBounds;
                }
            }
            if (report != null) {
                normalizeParsedReport(
                        report,
                        safeFigureName,
                        commands,
                        report.browserExecutable != null ? report.browserExecutable : PLAYWRIGHT_BUNDLED_CHROMIUM
                );
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

    private ViewBounds resolveViewBounds(List<String> commands) {
        ViewBounds bounds = ViewBounds.defaults();
        if (commands == null) {
            return bounds;
        }
        for (String command : commands) {
            if (command == null) {
                continue;
            }
            java.util.regex.Matcher matcher = SET_COORD_SYSTEM_COMMAND.matcher(command.trim());
            if (!matcher.matches()) {
                continue;
            }
            String[] parts = matcher.group(1).split(",");
            if (parts.length != 4) {
                continue;
            }
            try {
                double xMin = Double.parseDouble(parts[0].trim());
                double xMax = Double.parseDouble(parts[1].trim());
                double yMin = Double.parseDouble(parts[2].trim());
                double yMax = Double.parseDouble(parts[3].trim());
                if (Double.isFinite(xMin) && Double.isFinite(xMax)
                        && Double.isFinite(yMin) && Double.isFinite(yMax)
                        && xMin < xMax && yMin < yMax) {
                    bounds = new ViewBounds(xMin, xMax, yMin, yMax);
                }
            } catch (NumberFormatException ignored) {
                // Keep the most recent valid viewport command, or the default.
            }
        }
        return bounds;
    }

    protected ValidationReport validateWithHeadlessBrowser(Path previewPath,
                                                           String figureName,
                                                           List<String> commands,
                                                           List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                                           Path geometryPath) {
        return validateWithHeadlessBrowser(
                previewPath,
                figureName,
                commands,
                sceneDirectives,
                geometryPath,
                activeViewBounds != null ? activeViewBounds : ViewBounds.defaults());
    }

    private ValidationReport validateWithHeadlessBrowser(Path previewPath,
                                                           String figureName,
                                                           List<String> commands,
                                                           List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                                           Path geometryPath,
                                                           ViewBounds viewBounds) {
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
                    String geometryJson = extractGeometry(page, figureName, sceneDirectives, viewBounds);
                    if (geometryJson != null && !geometryJson.isBlank()) {
                        // Check for extraction errors embedded in the JSON
                        if (geometryJson.contains("\"error\"")) {
                            log.warn("GeoGebra geometry extraction reported error: {}",
                                    geometryJson.length() > 300 ? geometryJson.substring(0, 300) : geometryJson);
                            Files.writeString(geometryPath, buildFallbackElementGeometryReport(figureName, sceneDirectives, viewBounds), StandardCharsets.UTF_8);
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
                        Files.writeString(geometryPath, buildFallbackElementGeometryReport(figureName, sceneDirectives, viewBounds), StandardCharsets.UTF_8);
                        log.warn("GeoGebra geometry extraction returned empty; wrote minimal fallback");
                    }
                } catch (Exception e) {
                    log.warn("Failed to extract GeoGebra geometry: {}", e.getMessage());
                    try {
                        Files.writeString(geometryPath, buildFallbackElementGeometryReport(figureName, sceneDirectives, viewBounds), StandardCharsets.UTF_8);
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
                                    List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                    ViewBounds viewBounds) {
        ViewBounds bounds = viewBounds != null ? viewBounds : ViewBounds.defaults();
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
                        + " const internalHelperPrefix = " + JsonUtils.toJson(INTERNAL_HELPER_PREFIX) + ";"
                        + " const allNames = (typeof rawNames === 'string' ? rawNames.split(',').filter(s => s.trim()) : Array.from(rawNames))"
                        + "   .filter(name => typeof name === 'string' && !name.startsWith(internalHelperPrefix));"
                        + " const TEXT_CHAR_WIDTH = 0.15;"
                        + " const TEXT_HEIGHT = 0.4;"
                        + " const POINT_RADIUS = 0.15;"
                        + " const LINE_PADDING = 0.05;"
                        + " const VIEW_MIN = [" + bounds.xMin() + ", " + bounds.yMin() + ", 0];"
                        + " const VIEW_MAX = [" + bounds.xMax() + ", " + bounds.yMax() + ", 0];"
                        + " const point3 = (x, y) => [safeNumber(x), safeNumber(y), 0];"
                        + " const validPoint = (p) => Array.isArray(p) && p.length >= 2 && p[0] !== null && p[1] !== null;"
                        + " const objectPoint = (name) => {"
                        + "   const px = safeNumber(safeCall(() => api.getXcoord(name), null));"
                        + "   const py = safeNumber(safeCall(() => api.getYcoord(name), null));"
                        + "   return px !== null && py !== null ? [px, py, 0] : null;"
                        + " };"
                        + " const boundsFromPoints = (points, pad) => {"
                        + "   const usable = (points || []).filter(validPoint);"
                        + "   if (!usable.length) return null;"
                        + "   let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;"
                        + "   for (const p of usable) { minX = Math.min(minX, p[0]); minY = Math.min(minY, p[1]); maxX = Math.max(maxX, p[0]); maxY = Math.max(maxY, p[1]); }"
                        + "   const padding = pad || 0;"
                        + "   return { min: [safeNumber(minX - padding), safeNumber(minY - padding), 0], max: [safeNumber(maxX + padding), safeNumber(maxY + padding), 0] };"
                        + " };"
                        + " const namesFromText = (text, selfName) => {"
                        + "   const seen = new Set();"
                        + "   const refs = [];"
                        + "   const source = String(text || '');"
                        + "   const re = /[A-Za-z][A-Za-z0-9_']*/g;"
                        + "   let match;"
                        + "   while ((match = re.exec(source)) !== null) {"
                        + "     const token = match[0];"
                        + "     if (token === selfName || seen.has(token) || !allNames.includes(token)) continue;"
                        + "     seen.add(token);"
                        + "     refs.push(token);"
                        + "   }"
                        + "   return refs;"
                        + " };"
                        + " const referencedPoints = (selfName, ...sources) => {"
                        + "   const result = [];"
                        + "   const seen = new Set();"
                        + "   for (const source of sources) {"
                        + "     for (const ref of namesFromText(source, selfName)) {"
                        + "       if (seen.has(ref)) continue;"
                        + "       const refType = String(safeCall(() => api.getObjectType(ref), '') || '').toLowerCase();"
                        + "       if (refType !== 'point') continue;"
                        + "       const p = objectPoint(ref);"
                        + "       if (validPoint(p)) { seen.add(ref); result.push({ name: ref, point: p }); }"
                        + "     }"
                        + "   }"
                        + "   return result;"
                        + " };"
                        + " const distance = (a, b) => validPoint(a) && validPoint(b) ? Math.hypot(a[0] - b[0], a[1] - b[1]) : null;"
                        + " const clipLineToView = (a, b, typeLower) => {"
                        + "   if (!validPoint(a) || !validPoint(b)) return null;"
                        + "   const dx = b[0] - a[0], dy = b[1] - a[1];"
                        + "   if (Math.hypot(dx, dy) < 1e-9) return null;"
                        + "   const ts = [];"
                        + "   const pushIfInside = (t) => { const x = a[0] + t * dx, y = a[1] + t * dy; if (x >= VIEW_MIN[0] - 1e-9 && x <= VIEW_MAX[0] + 1e-9 && y >= VIEW_MIN[1] - 1e-9 && y <= VIEW_MAX[1] + 1e-9) ts.push(t); };"
                        + "   if (Math.abs(dx) > 1e-9) { pushIfInside((VIEW_MIN[0] - a[0]) / dx); pushIfInside((VIEW_MAX[0] - a[0]) / dx); }"
                        + "   if (Math.abs(dy) > 1e-9) { pushIfInside((VIEW_MIN[1] - a[1]) / dy); pushIfInside((VIEW_MAX[1] - a[1]) / dy); }"
                        + "   if (typeLower === 'ray') { ts.push(0); ts.push(Math.max(1, ...ts.filter(t => t >= 0))); }"
                        + "   else { ts.push(-1000); ts.push(1000); }"
                        + "   const usable = ts.filter(t => Number.isFinite(t) && (typeLower !== 'ray' || t >= -1e-9)).sort((x, y) => x - y);"
                        + "   if (usable.length < 2) return null;"
                        + "   const t0 = usable[0], t1 = usable[usable.length - 1];"
                        + "   return [[safeNumber(a[0] + t0 * dx), safeNumber(a[1] + t0 * dy), 0], [safeNumber(a[0] + t1 * dx), safeNumber(a[1] + t1 * dy), 0]];"
                        + " };"
                        + " const sampleCircle = (center, radius) => {"
                        + "   if (!validPoint(center) || radius === null || !Number.isFinite(radius) || radius <= 0) return [];"
                        + "   const pts = [];"
                        + "   for (let i = 0; i < 16; i++) { const t = Math.PI * 2 * i / 16; pts.push([safeNumber(center[0] + radius * Math.cos(t)), safeNumber(center[1] + radius * Math.sin(t)), 0]); }"
                        + "   return pts;"
                        + " };"
                        + " const numericValue = (text) => { const m = String(text || '').match(/-?\\d+(?:\\.\\d+)?/); return m ? safeNumber(Number(m[0])) : null; };"
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
                        + "     let geometryType = null;"
                        + "     let geometryPoints = [];"
                        + "     let geometryMeasurements = {};"
                        + "     let viewportClipped = false;"
                        + "     let evaluationShape = null;"
                        + "     const typeLower = (type || '').toLowerCase();"
                        + "     if (typeLower === 'point') {"
                        + "       semanticClass = 'point';"
                        + "       if (x !== null && y !== null) {"
                        + "         center = [x, y, 0];"
                        + "         geometryType = 'point';"
                        + "         geometryPoints = [center];"
                        + "         evaluationShape = { type: 'point', center: center, radius: POINT_RADIUS };"
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
                        + "         geometryType = 'text';"
                        + "         geometryPoints = [[x, y, 0], center];"
                        + "         geometryMeasurements = { width: safeNumber(width), height: TEXT_HEIGHT };"
                        + "         evaluationShape = { type: 'bbox', min: [x, y, 0], max: [x + width, y + TEXT_HEIGHT, 0] };"
                        + "         bounds = {"
                        + "           min: [x, y, 0],"
                        + "           max: [x + width, y + TEXT_HEIGHT, 0]"
                        + "         };"
                        + "       }"
                        + "     } else if (typeLower === 'line' || typeLower === 'ray' || typeLower === 'segment') {"
                        + "       semanticClass = 'line';"
                        + "       const refs = referencedPoints(name, defString, valueString);"
                        + "       const endpoints = refs.map(r => r.point).filter(validPoint).slice(0, 2);"
                        + "       if (endpoints.length >= 2) {"
                        + "         let visibleSegment = endpoints;"
                        + "         if (typeLower === 'line' || typeLower === 'ray') {"
                        + "           const clipped = clipLineToView(endpoints[0], endpoints[1], typeLower);"
                        + "           if (clipped) { visibleSegment = clipped; viewportClipped = true; }"
                        + "         }"
                        + "         geometryType = typeLower;"
                        + "         geometryPoints = visibleSegment;"
                        + "         const len = distance(visibleSegment[0], visibleSegment[1]);"
                        + "         geometryMeasurements = { length: len };"
                        + "         evaluationShape = { type: 'segment', points: visibleSegment, source_type: typeLower, defining_points: refs.map(r => r.name) };"
                        + "         bounds = boundsFromPoints(visibleSegment, LINE_PADDING);"
                        + "         center = [(visibleSegment[0][0] + visibleSegment[1][0]) / 2, (visibleSegment[0][1] + visibleSegment[1][1]) / 2, 0];"
                        + "       }"
                        + "     } else if (typeLower === 'circle' || typeLower === 'ellipse' || typeLower === 'conic') {"
                        + "       semanticClass = 'shape';"
                        + "       const refs = referencedPoints(name, defString, valueString);"
                        + "       const centerPoint = validPoint(objectPoint(name)) ? objectPoint(name) : (refs.length ? refs[0].point : null);"
                        + "       let radius = null;"
                        + "       if (refs.length >= 2 && validPoint(centerPoint)) { radius = distance(centerPoint, refs[1].point); }"
                        + "       if ((radius === null || !Number.isFinite(radius) || radius <= 0) && valueString) { radius = numericValue(valueString); }"
                        + "       geometryType = typeLower === 'circle' ? 'circle' : typeLower;"
                        + "       if (validPoint(centerPoint) && radius !== null && Number.isFinite(radius) && radius > 0) {"
                        + "         center = centerPoint;"
                        + "         const samples = sampleCircle(centerPoint, radius);"
                        + "         geometryPoints = [centerPoint, ...samples];"
                        + "         geometryMeasurements = { radius: radius, diameter: safeNumber(radius * 2) };"
                        + "         evaluationShape = { type: 'circle', center: centerPoint, radius: radius, sample_points: samples };"
                        + "         bounds = { min: [safeNumber(centerPoint[0] - radius), safeNumber(centerPoint[1] - radius), 0], max: [safeNumber(centerPoint[0] + radius), safeNumber(centerPoint[1] + radius), 0] };"
                        + "       }"
                        + "     } else if (typeLower === 'polygon') {"
                        + "       semanticClass = 'shape';"
                        + "       const refs = referencedPoints(name, defString, valueString);"
                        + "       const vertices = refs.map(r => r.point).filter(validPoint);"
                        + "       geometryType = 'polygon';"
                        + "       if (vertices.length >= 3) {"
                        + "         geometryPoints = vertices;"
                        + "         let area = 0;"
                        + "         for (let i = 0; i < vertices.length; i++) { const a = vertices[i], b = vertices[(i + 1) % vertices.length]; area += a[0] * b[1] - b[0] * a[1]; }"
                        + "         geometryMeasurements = { area: safeNumber(Math.abs(area) / 2) };"
                        + "         evaluationShape = { type: 'polygon', points: vertices, vertex_names: refs.map(r => r.name) };"
                        + "         bounds = boundsFromPoints(vertices, LINE_PADDING);"
                        + "         center = [safeNumber(vertices.reduce((s, p) => s + p[0], 0) / vertices.length), safeNumber(vertices.reduce((s, p) => s + p[1], 0) / vertices.length), 0];"
                        + "       }"
                        + "     } else if (typeLower === 'numeric' || typeLower === 'angle') {"
                        + "       semanticClass = 'formula';"
                        + "       displayText = valueString;"
                        + "       geometryType = typeLower === 'angle' ? 'angle' : 'formula';"
                        + "       const angleValue = numericValue(valueString);"
                        + "       if (angleValue !== null) { geometryMeasurements = { value: angleValue }; }"
                        + "       if (x !== null && y !== null) {"
                        + "         const textLen = (displayText || name).length || 1;"
                        + "         const width = textLen * TEXT_CHAR_WIDTH;"
                        + "         center = [x + width / 2, y + TEXT_HEIGHT / 2, 0];"
                        + "         geometryPoints = [[x, y, 0], center];"
                        + "         evaluationShape = { type: 'bbox', min: [x, y, 0], max: [x + width, y + TEXT_HEIGHT, 0] };"
                        + "         bounds = { min: [x, y, 0], max: [x + width, y + TEXT_HEIGHT, 0] };"
                        + "       }"
                        + "     }"
                        + "     if (!geometryType && bounds) { geometryType = semanticClass; evaluationShape = { type: 'bbox', min: bounds.min, max: bounds.max }; }"
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
                        + "       geometry_type: geometryType,"
                        + "       geometry_points: geometryPoints,"
                        + "       geometry_measurements: geometryMeasurements,"
                        + "       viewport_clipped: viewportClipped,"
                        + "       evaluation_shape: evaluationShape,"
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
                        + "     min: [" + bounds.xMin() + ", " + bounds.yMin() + ", 0],"
                        + "     max: [" + bounds.xMax() + ", " + bounds.yMax() + ", 0],"
                        + "     width: " + (bounds.xMax() - bounds.xMin()) + ","
                        + "     height: " + (bounds.yMax() - bounds.yMin())
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
                                                       List<GeoGebraCodeUtils.SceneDirective> sceneDirectives,
                                                       ViewBounds viewBounds) {
        ViewBounds bounds = viewBounds != null ? viewBounds : ViewBounds.defaults();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n")
                .append("  \"scene_name\": ").append(JsonUtils.toJson(figureName)).append(",\n")
                .append("  \"report_type\": \"geogebra_element_report\",\n")
                .append("  \"report_version\": 1,\n")
                .append("  \"frame_bounds\": {\n")
                .append("    \"min\": [").append(bounds.xMin()).append(", ").append(bounds.yMin()).append(", 0],\n")
                .append("    \"max\": [").append(bounds.xMax()).append(", ").append(bounds.yMax()).append(", 0],\n")
                .append("    \"width\": ").append(bounds.xMax() - bounds.xMin()).append(",\n")
                .append("    \"height\": ").append(bounds.yMax() - bounds.yMin()).append("\n")
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
        String localOrigin = "http://127.0.0.1:" + port;
        page.navigate(localOrigin + "/");
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
                        + "   window.__ggbInjectedApplet = new GGBApplet(params);"
                        + "   if (typeof window.__ggbInjectedApplet.setHTML5Codebase === 'function') {"
                        + "     window.__ggbInjectedApplet.setHTML5Codebase(payload.codebase, false);"
                        + "   }"
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
                        "appletId", VALIDATOR_APPLET_ID,
                        "codebase", localOrigin + VALIDATOR_HTML5_CODEBASE
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
        byte[] deployScript = loadDeployGgbScript();
        server.createContext("/", exchange -> writeHtmlResponse(exchange, body));
        server.createContext(LOCAL_DEPLOY_GGB_PATH, exchange -> writeJavaScriptResponse(exchange, deployScript));
        server.createContext(LOCAL_GEOGEBRA_APPS_PATH, this::writeCachedGeoGebraAssetResponse);
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

    private void writeJavaScriptResponse(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/javascript; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private void writeCachedGeoGebraAssetResponse(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        try {
            byte[] body = loadCachedGeoGebraAsset(requestPath, query);
            exchange.getResponseHeaders().set("Content-Type", contentTypeForPath(requestPath));
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } catch (IOException e) {
            byte[] body = ("GeoGebra asset unavailable: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(502, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private byte[] loadCachedGeoGebraAsset(String requestPath, String query) throws IOException {
        if (requestPath == null || !requestPath.startsWith(LOCAL_GEOGEBRA_APPS_PATH)) {
            throw new IOException("unexpected asset path: " + requestPath);
        }
        Path cachePath = resolveGeoGebraCacheDir()
                .resolve(requestPath.substring(1).replace('/', java.io.File.separatorChar))
                .toAbsolutePath()
                .normalize();
        Path cacheRoot = resolveGeoGebraCacheDir().toAbsolutePath().normalize();
        if (!cachePath.startsWith(cacheRoot)) {
            throw new IOException("refusing unsafe asset path: " + requestPath);
        }
        if (Files.exists(cachePath) && Files.size(cachePath) > 0) {
            return Files.readAllBytes(cachePath);
        }

        String relativePath = requestPath.substring(LOCAL_GEOGEBRA_APPS_PATH.length());
        String remoteUrl = GEOGEBRA_APPS_URL_PREFIX + relativePath + (query == null || query.isBlank() ? "" : "?" + query);
        byte[] downloaded = downloadBytes(remoteUrl, "GeoGebra asset");
        Files.createDirectories(cachePath.getParent());
        Files.write(cachePath, downloaded);
        log.info("Cached GeoGebra asset at {}", cachePath);
        return downloaded;
    }

    private String contentTypeForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (lower.endsWith(".woff")) {
            return "font/woff";
        }
        if (lower.endsWith(".ttf")) {
            return "font/ttf";
        }
        return "application/octet-stream";
    }

    private byte[] loadDeployGgbScript() throws IOException {
        Path cachePath = resolveDeployGgbCachePath();
        if (Files.exists(cachePath) && Files.size(cachePath) > 0) {
            return Files.readAllBytes(cachePath);
        }

        String deployUrl = resolveDeployGgbUrl();
        try {
            byte[] downloaded = downloadBytes(deployUrl, "GeoGebra deploy script");
            Files.createDirectories(cachePath.getParent());
            Files.write(cachePath, downloaded);
            log.info("Cached GeoGebra deploy script at {}", cachePath);
            return downloaded;
        } catch (IOException e) {
            throw new IOException("GeoGebra deploy script is not cached and could not be downloaded from "
                    + deployUrl + ": " + e.getMessage(), e);
        }
    }

    private byte[] downloadBytes(String url, String description) throws IOException {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "MathVision GeoGebra validator")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("HTTP " + statusCode);
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IOException("empty response body");
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + description, e);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid URL for " + description + ": " + url, e);
        }
    }

    private String resolveDeployGgbUrl() {
        String configured = System.getenv(GEOGEBRA_DEPLOY_URL_ENV);
        if (configured == null || configured.trim().isEmpty()) {
            return DEPLOY_GGB_URL;
        }
        return configured.trim();
    }

    private Path resolveDeployGgbCachePath() {
        return resolveGeoGebraCacheDir().resolve(DEPLOY_GGB_CACHE_FILE).toAbsolutePath().normalize();
    }

    private Path resolveGeoGebraCacheDir() {
        String configured = System.getenv(GEOGEBRA_CACHE_DIR_ENV);
        Path cacheDir;
        if (configured == null || configured.trim().isEmpty()) {
            cacheDir = Path.of(System.getProperty("user.home"), GEOGEBRA_CACHE_DIR_NAME);
        } else {
            cacheDir = Path.of(configured.trim());
        }
        return cacheDir;
    }

    private String executeValidationScript(Page page, List<String> commands) {
        return asString(page.evaluate(
                "(async ([commands, scriptingCommands]) => {"
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
                        + " const scriptingCommandNames = new Set(scriptingCommands || []);"
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
                        + " const parseIntegerLiteral = (value) => {"
                        + "   const parsed = parseNumberLiteral(value);"
                        + "   return parsed === null ? null : Math.round(parsed);"
                        + " };"
                        + " const parsePointLiteral = (value) => {"
                        + "   if (typeof value !== 'string') { return null; }"
                        + "   let expression = value.trim();"
                        + "   let match = expression.match(/^Point\\s*\\(\\s*\\{(.*)\\}\\s*\\)$/);"
                        + "   if (match) { expression = match[1]; }"
                        + "   else {"
                        + "     match = expression.match(/^\\{(.*)\\}$/) || expression.match(/^\\((.*)\\)$/);"
                        + "     if (!match) { return null; }"
                        + "     expression = match[1];"
                        + "   }"
                        + "   const coords = splitArgs(expression);"
                        + "   if (coords.length < 2 || coords.length > 3) { return null; }"
                        + "   const x = parseNumberLiteral(coords[0]);"
                        + "   const y = parseNumberLiteral(coords[1]);"
                        + "   const z = coords.length === 3 ? parseNumberLiteral(coords[2]) : 0;"
                        + "   if (x === null || y === null || z === null) { return null; }"
                        + "   return { x, y, z };"
                        + " };"
                        + " const normalizeColorChannel = (value) => {"
                        + "   const parsed = parseNumberLiteral(value);"
                        + "   if (parsed === null) { return null; }"
                        + "   const normalized = parsed >= 0 && parsed <= 1 ? parsed * 255 : parsed;"
                        + "   return Math.max(0, Math.min(255, Math.round(normalized)));"
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
                        + " const evalGeoGebraCommand = (api, command) => {"
                        + "   try {"
                        + "     return { success: !!api.evalCommand(command), error: null };"
                        + "   } catch (error) {"
                        + "     return { success: false, error: error && error.message ? error.message : String(error) };"
                        + "   }"
                        + " };"
                        + " const isPendingScriptingCommand = (error) => typeof error === 'string'"
                        + "   && error.toLowerCase().includes('scripting commands not loaded yet');"
                        + " const waitForScriptCommand = (ms) => new Promise(resolve => setTimeout(resolve, ms));"
                        + " const retryPendingScriptingCommand = async (api, command, result) => {"
                        + "   if (!result || result.success || result.error === null || !isPendingScriptingCommand(result.error)) {"
                        + "     return result;"
                        + "   }"
                        + "   let current = result;"
                        + "   for (let attempt = 0; attempt < 40; attempt += 1) {"
                        + "     await waitForScriptCommand(150);"
                        + "     current = evalGeoGebraCommand(api, command);"
                        + "     if (current.success) { refreshConditionBindings(api); return current; }"
                        + "     if (current.error !== null && !isPendingScriptingCommand(current.error)) { return current; }"
                        + "   }"
                        + "   return current;"
                        + " };"
                        + " const conditionalVisibilityBindings = [];"
                        + " let generatedConditionCounter = 0;"
                        + " const internalHelperPrefix = " + JsonUtils.toJson(INTERNAL_HELPER_PREFIX) + ";"
                        + " const toObjectName = (value) => {"
                        + "   const raw = unquote(value);"
                        + "   return typeof raw === 'string' ? raw.trim() : '';"
                        + " };"
                        + " const objectExists = (api, value) => {"
                        + "   const name = toObjectName(value);"
                        + "   return !!name && !!safeCall(() => api.exists(name), false);"
                        + " };"
                        + " const objectOrPointLiteralExists = (api, value) => objectExists(api, value) || parsePointLiteral(value) !== null;"
                        + " const parseViewNumber = (value) => {"
                        + "   const parsed = parseIntegerLiteral(value);"
                        + "   return (parsed === 1 || parsed === 2 || parsed === 3 || parsed === -1) ? parsed : null;"
                        + " };"
                        + " const rgbToHex = (red, green, blue) => '#'"
                        + "   + [red, green, blue]"
                        + "     .map(channel => Math.max(0, Math.min(255, Math.round(channel))).toString(16).padStart(2, '0'))"
                        + "     .join('')"
                        + "     .toUpperCase();"
                        + " const resolveCssColor = (args, startIndex) => {"
                        + "   if (args.length === startIndex + 1) {"
                        + "     const namedColor = toObjectName(args[startIndex]);"
                        + "     const rgb = colorToRgb(args[startIndex]);"
                        + "     if (rgb) { return rgbToHex(rgb.red, rgb.green, rgb.blue); }"
                        + "     return namedColor || null;"
                        + "   }"
                        + "   if (args.length === startIndex + 3) {"
                        + "     const red = normalizeColorChannel(args[startIndex]);"
                        + "     const green = normalizeColorChannel(args[startIndex + 1]);"
                        + "     const blue = normalizeColorChannel(args[startIndex + 2]);"
                        + "     if (red !== null && green !== null && blue !== null) {"
                        + "       return rgbToHex(red, green, blue);"
                        + "     }"
                        + "   }"
                        + "   return null;"
                        + " };"
                        + " const applyGraphicsViewBackground = (api, viewNumber, cssColor) => {"
                        + "   if (!cssColor) { return false; }"
                        + "   if (typeof api.setGraphicsOptions === 'function') {"
                        + "     try { api.setGraphicsOptions(viewNumber, { bgColor: cssColor }); } catch (error) {}"
                        + "   }"
                        + "   return true;"
                        + " };"
                        + " const createConditionHelper = (api, expression) => {"
                        + "   const helperName = internalHelperPrefix + (++generatedConditionCounter);"
                        + "   const helperResult = evalGeoGebraCommand(api, helperName + ' = ' + expression);"
                        + "   if (!helperResult.success) {"
                        + "     return { name: null, error: helperResult.error || ('Could not evaluate visibility condition: ' + expression) };"
                        + "   }"
                        + "   safeCall(() => api.setAuxiliary(helperName, true), null);"
                        + "   safeCall(() => api.setVisible(helperName, false), null);"
                        + "   safeCall(() => api.setLabelVisible(helperName, false), null);"
                        + "   return { name: helperName, error: null };"
                        + " };"
                        + " const resolveConditionObjectName = (api, rawCondition) => {"
                        + "   const existingName = toObjectName(rawCondition);"
                        + "   if (existingName && safeCall(() => api.exists(existingName), false)) {"
                        + "     return { name: existingName, error: null };"
                        + "   }"
                        + "   if (typeof rawCondition !== 'string' || rawCondition.trim() === '') {"
                        + "     return { name: null, error: 'Missing visibility condition' };"
                        + "   }"
                        + "   return createConditionHelper(api, rawCondition.trim());"
                        + " };"
                        + " const applyConditionBinding = (api, binding) => {"
                        + "   if (!binding || !binding.targetName || !binding.conditionObjectName) { return false; }"
                        + "   const value = safeCall(() => api.getValue(binding.conditionObjectName), null);"
                        + "   if (typeof value === 'boolean') {"
                        + "     safeCall(() => api.setVisible(binding.targetName, value), null);"
                        + "     return true;"
                        + "   }"
                        + "   if (value === null || value === undefined || !Number.isFinite(value)) { return false; }"
                        + "   if (binding.viewNumber === null || binding.viewNumber === 1) {"
                        + "     safeCall(() => api.setVisible(binding.targetName, value !== 0), null);"
                        + "   }"
                        + "   return true;"
                        + " };"
                        + " const registerConditionBinding = (api, targetName, conditionObjectName, viewNumber) => {"
                        + "   const binding = { targetName, conditionObjectName, viewNumber };"
                        + "   conditionalVisibilityBindings.push(binding);"
                        + "   return applyConditionBinding(api, binding);"
                        + " };"
                        + " const refreshConditionBindings = (api) => {"
                        + "   for (const binding of conditionalVisibilityBindings) {"
                        + "     applyConditionBinding(api, binding);"
                        + "   }"
                        + " };"
                        + " const executeCommand = async (api, command) => {"
                        + "   const trimmed = typeof command === 'string' ? command.trim() : '';"
                        + "   const match = trimmed.match(/^([A-Za-z][A-Za-z0-9_]*)\\((.*)\\)$/);"
                        + "   if (!match) {"
                        + "     return retryPendingScriptingCommand(api, command, evalGeoGebraCommand(api, command));"
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
                        + "           const red = normalizeColorChannel(args[1]);"
                        + "           const green = normalizeColorChannel(args[2]);"
                        + "           const blue = normalizeColorChannel(args[3]);"
                        + "           if (red !== null && green !== null && blue !== null) {"
                        + "             api.setColor(args[0].trim(), red, green, blue);"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetBackgroundColor': {"
                        + "         if (args.length === 1 || args.length === 3) {"
                        + "           const cssColor = resolveCssColor(args, 0);"
                        + "           if (cssColor && applyGraphicsViewBackground(api, 1, cssColor)) {"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         if ((args.length === 2 || args.length === 4) && objectExists(api, args[0])) {"
                        + "           const cssColor = resolveCssColor(args, 1);"
                        + "           if (cssColor) { return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetDynamicColor': {"
                        + "         if ((args.length === 4 || args.length === 5) && objectExists(api, args[0])"
                        + "             && args.slice(1).every(arg => typeof arg === 'string' && arg.trim() !== '')) {"
                        + "           return { success: true, error: null };"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetLineOpacity': {"
                        + "         if (args.length === 2 && objectExists(api, args[0]) && parseNumberLiteral(args[1]) !== null) {"
                        + "           return { success: true, error: null };"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetDecoration': {"
                        + "         if ((args.length === 2 || args.length === 3) && objectExists(api, args[0])"
                        + "             && args.slice(1).every(arg => parseIntegerLiteral(arg) !== null)) {"
                        + "           return { success: true, error: null };"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetTooltipMode': {"
                        + "         if (args.length === 2 && objectExists(api, args[0]) && parseIntegerLiteral(args[1]) !== null) {"
                        + "           return { success: true, error: null };"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetConditionToShowObject': {"
                        + "         if (args.length === 2 && objectExists(api, args[0])) {"
                        + "           const targetName = toObjectName(args[0]);"
                        + "           if (typeof api.setConditionToShowObject === 'function') {"
                        + "             try { api.setConditionToShowObject(targetName, args[1].trim()); return { success: true, error: null }; } catch (error) {}"
                        + "           }"
                        + "           const resolved = resolveConditionObjectName(api, args[1]);"
                        + "           if (resolved.name && registerConditionBinding(api, targetName, resolved.name, null)) {"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "           if (typeof args[1] === 'string' && args[1].trim() !== '') {"
                        + "             console.warn('Could not bind GeoGebra visibility condition in validator; accepting command:', targetName, args[1]);"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "           return { success: false, error: resolved.error || ('Could not bind visibility condition for ' + targetName) };"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetVisibleInView': {"
                        + "         if (args.length === 3 && objectExists(api, args[0])) {"
                        + "           const viewNumber = parseViewNumber(args[1]);"
                        + "           const visible = parseBooleanLiteral(args[2]);"
                        + "           if (viewNumber !== null && visible !== null) {"
                        + "             if (viewNumber === 1) {"
                        + "               safeCall(() => api.setVisible(toObjectName(args[0]), visible), null);"
                        + "             }"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetLevelOfDetail': {"
                        + "         if (args.length === 2 && objectExists(api, args[0]) && parseIntegerLiteral(args[1]) !== null) {"
                        + "           return { success: true, error: null };"
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
                        + "       case 'SetLabelMode': {"
                        + "         if (args.length === 2) {"
                        + "           const style = parseIntegerLiteral(args[1]);"
                        + "           if (style !== null) { api.setLabelStyle(args[0].trim(), style); return { success: true, error: null }; }"
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
                        + "       case 'SetFixed': {"
                        + "         if ((args.length === 2 || args.length === 3) && objectExists(api, args[0])) {"
                        + "           const fixed = parseBooleanLiteral(args[1]);"
                        + "           const selectionAllowed = args.length === 3 ? parseBooleanLiteral(args[2]) : true;"
                        + "           if (fixed !== null && selectionAllowed !== null) {"
                        + "             api.setFixed(toObjectName(args[0]), fixed, selectionAllowed);"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetTrace': {"
                        + "         if (args.length === 2) {"
                        + "           const trace = parseBooleanLiteral(args[1]);"
                        + "           if (trace !== null) { api.setTrace(args[0].trim(), trace); return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetLayer': {"
                        + "         if (args.length === 2) {"
                        + "           const layer = parseIntegerLiteral(args[1]);"
                        + "           if (layer !== null) { api.setLayer(args[0].trim(), layer); return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetValue': {"
                        + "         if (args.length === 2 && objectExists(api, args[0])) {"
                        + "           const targetName = toObjectName(args[0]);"
                        + "           const point = parsePointLiteral(args[1]);"
                        + "           if (point) {"
                        + "             const objectType = safeString(safeCall(() => api.getObjectType(targetName), '')).toLowerCase();"
                        + "             if ((objectType === 'point' || objectType === 'vector') && typeof api.setCoords === 'function') {"
                        + "               api.setCoords(targetName, point.x, point.y, point.z);"
                        + "               return { success: true, error: null };"
                        + "             }"
                        + "             return { success: false, error: 'SetValue coordinate assignment target is not a point or vector: ' + targetName };"
                        + "           }"
                        + "           const numericValue = parseNumberLiteral(args[1]);"
                        + "           if (numericValue !== null && typeof api.setValue === 'function') {"
                        + "             api.setValue(targetName, numericValue);"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "           const booleanValue = parseBooleanLiteral(args[1]);"
                        + "           if (booleanValue !== null && typeof api.setValue === 'function') {"
                        + "             api.setValue(targetName, booleanValue);"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'CenterView': {"
                        + "         if (args.length === 1 && objectOrPointLiteralExists(api, args[0])) {"
                        + "           return { success: true, error: null };"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'StartAnimation': {"
                        + "         if (args.length === 0) {"
                        + "           safeCall(() => api.startAnimation(), null);"
                        + "           return { success: true, error: null };"
                        + "         }"
                        + "         if (args.length === 1) {"
                        + "           const running = parseBooleanLiteral(args[0]);"
                        + "           if (running !== null) {"
                        + "             safeCall(() => running ? api.startAnimation() : api.stopAnimation(), null);"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         if (args.length >= 2) {"
                        + "           const running = parseBooleanLiteral(args[args.length - 1]);"
                        + "           if (running !== null && args.slice(0, -1).every(arg => objectExists(api, arg))) {"
                        + "             for (const target of args.slice(0, -1)) {"
                        + "               safeCall(() => api.setAnimating(toObjectName(target), running), null);"
                        + "             }"
                        + "             safeCall(() => running ? api.startAnimation() : api.stopAnimation(), null);"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'ZoomIn':"
                        + "       case 'ZoomOut': {"
                        + "         if (args.length === 0) {"
                        + "           return { success: true, error: null };"
                        + "         }"
                        + "         if (args.length === 1) {"
                        + "           const factor = parseNumberLiteral(args[0]);"
                        + "           if (factor !== null && factor > 0) { return { success: true, error: null }; }"
                        + "         }"
                        + "         if (args.length === 2) {"
                        + "           const factor = parseNumberLiteral(args[0]);"
                        + "           if (factor !== null && factor > 0 && objectOrPointLiteralExists(api, args[1])) {"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         if (args.length === 4) {"
                        + "           const xMin = parseNumberLiteral(args[0]);"
                        + "           const yMin = parseNumberLiteral(args[1]);"
                        + "           const xMax = parseNumberLiteral(args[2]);"
                        + "           const yMax = parseNumberLiteral(args[3]);"
                        + "           if (xMin !== null && yMin !== null && xMax !== null && yMax !== null && xMin < xMax && yMin < yMax) {"
                        + "             if (fnName === 'ZoomIn') { safeCall(() => api.setCoordSystem(xMin, xMax, yMin, yMax), null); }"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'ShowLayer': {"
                        + "         if (args.length === 1) {"
                        + "           const layer = parseIntegerLiteral(args[0]);"
                        + "           if (layer !== null) { api.setLayerVisible(layer, true); return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'HideLayer': {"
                        + "         if (args.length === 1) {"
                        + "           const layer = parseIntegerLiteral(args[0]);"
                        + "           if (layer !== null) { api.setLayerVisible(layer, false); return { success: true, error: null }; }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'SetCoordSystem': {"
                        + "         if (args.length === 4) {"
                        + "           const xMin = parseNumberLiteral(args[0]);"
                        + "           const xMax = parseNumberLiteral(args[1]);"
                        + "           const yMin = parseNumberLiteral(args[2]);"
                        + "           const yMax = parseNumberLiteral(args[3]);"
                        + "           if (xMin !== null && xMax !== null && yMin !== null && yMax !== null && xMin < xMax && yMin < yMax) {"
                        + "             api.setCoordSystem(xMin, xMax, yMin, yMax);"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'ShowAxes': {"
                        + "         if (args.length === 0) {"
                        + "           api.setAxesVisible(true, true);"
                        + "           return { success: true, error: null };"
                        + "         }"
                        + "         if (args.length === 1) {"
                        + "           const visible = parseBooleanLiteral(args[0]);"
                        + "           if (visible !== null) { api.setAxesVisible(visible, visible); return { success: true, error: null }; }"
                        + "         }"
                        + "         if (args.length === 2) {"
                        + "           const view = parseIntegerLiteral(args[0]);"
                        + "           const visible = parseBooleanLiteral(args[1]);"
                        + "           if (view !== null && visible !== null) {"
                        + "             api.setAxesVisible(view, visible, visible, visible);"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       case 'ShowGrid': {"
                        + "         if (args.length === 0) {"
                        + "           api.setGridVisible(true);"
                        + "           return { success: true, error: null };"
                        + "         }"
                        + "         if (args.length === 1) {"
                        + "           const visible = parseBooleanLiteral(args[0]);"
                        + "           if (visible !== null) { api.setGridVisible(visible); return { success: true, error: null }; }"
                        + "         }"
                        + "         if (args.length === 2) {"
                        + "           const view = parseIntegerLiteral(args[0]);"
                        + "           const visible = parseBooleanLiteral(args[1]);"
                        + "           if (view !== null && visible !== null) {"
                        + "             api.setGridVisible(view, visible);"
                        + "             return { success: true, error: null };"
                        + "           }"
                        + "         }"
                        + "         break;"
                        + "       }"
                        + "       default:"
                        + "         break;"
                        + "     }"
                        + "     let result = evalGeoGebraCommand(api, command);"
                        + "     if (result.success || !scriptingCommandNames.has(fnName)) {"
                        + "       return result;"
                        + "     }"
                        + "     if (result.error !== null && !isPendingScriptingCommand(result.error)) {"
                        + "       return result;"
                        + "     }"
                        + "     return retryPendingScriptingCommand(api, command, result);"
                        + "   } catch (error) {"
                        + "     return { success: false, error: error && error.message ? error.message : String(error) };"
                        + "   }"
                        + " };"
                        + " const allObjectNames = () => {"
                        + "   const names = safeCall(() => Array.from(ggbApplet.getAllObjectNames()), []);"
                        + "   return Array.from(new Set((names || []).filter(name => typeof name === 'string'"
                        + "     && name.trim() !== ''"
                        + "     && !name.startsWith(internalHelperPrefix))));"
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
                        + "   const result = await executeCommand(ggbApplet, command);"
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
                        + "})",
                List.of(
                        commands,
                        new ArrayList<>(GeoGebraValidationSupport.documentedScriptingCommandNames())
                )
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
        if (report.failedCommands > 0) {
            report.error = buildCommandFailureSummary(report);
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

    private String buildCommandFailureSummary(ValidationReport report) {
        if (report == null || report.commands == null || report.commands.isEmpty()) {
            return null;
        }

        List<String> failures = new ArrayList<>();
        for (CommandValidation entry : report.commands) {
            if (entry == null || !Boolean.FALSE.equals(entry.success)) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append("Command ").append(entry.index).append(" returned false: ");
            line.append(entry.command != null && !entry.command.isBlank()
                    ? entry.command
                    : "<missing command>");
            if (entry.error != null && !entry.error.isBlank()) {
                line.append(" (").append(entry.error).append(")");
            }
            failures.add(line.toString());
        }

        if (failures.isEmpty()) {
            return null;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("GeoGebra validation found ")
                .append(failures.size())
                .append(failures.size() == 1 ? " failing command" : " failing commands");
        if (report.totalCommands > 0) {
            summary.append(" out of ").append(report.totalCommands);
        }
        summary.append(" after replaying the full script:");

        for (String failure : failures) {
            summary.append("\n- ").append(failure);
        }
        return summary.toString();
    }

    private String summarizeValidationFailure(ValidationReport report) {
        if (report == null) {
            return "GeoGebra validation failed for an unknown reason";
        }
        String commandFailureSummary = buildCommandFailureSummary(report);
        if (commandFailureSummary != null && !commandFailureSummary.isBlank()) {
            return commandFailureSummary;
        }
        if (report.error != null && !report.error.isBlank()) {
            return report.error;
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
        String scriptingCommandsJson =
                JsonUtils.toJson(new ArrayList<>(GeoGebraValidationSupport.documentedScriptingCommandNames()));
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
                + "    const scriptingCommandNames = new Set(" + scriptingCommandsJson + ");\n"
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
                + "    function parseIntegerLiteral(value) {\n"
                + "      const parsed = parseNumberLiteral(value);\n"
                + "      return parsed === null ? null : Math.round(parsed);\n"
                + "    }\n"
                + "    function parsePointLiteral(value) {\n"
                + "      if (typeof value !== 'string') {\n"
                + "        return null;\n"
                + "      }\n"
                + "      let expression = value.trim();\n"
                + "      let match = expression.match(/^Point\\s*\\(\\s*\\{(.*)\\}\\s*\\)$/);\n"
                + "      if (match) {\n"
                + "        expression = match[1];\n"
                + "      } else {\n"
                + "        match = expression.match(/^\\{(.*)\\}$/) || expression.match(/^\\((.*)\\)$/);\n"
                + "        if (!match) {\n"
                + "          return null;\n"
                + "        }\n"
                + "        expression = match[1];\n"
                + "      }\n"
                + "      const coords = splitArgs(expression);\n"
                + "      if (coords.length < 2 || coords.length > 3) {\n"
                + "        return null;\n"
                + "      }\n"
                + "      const x = parseNumberLiteral(coords[0]);\n"
                + "      const y = parseNumberLiteral(coords[1]);\n"
                + "      const z = coords.length === 3 ? parseNumberLiteral(coords[2]) : 0;\n"
                + "      if (x === null || y === null || z === null) {\n"
                + "        return null;\n"
                + "      }\n"
                + "      return { x, y, z };\n"
                + "    }\n"
                + "    function normalizeColorChannel(value) {\n"
                + "      const parsed = parseNumberLiteral(value);\n"
                + "      if (parsed === null) {\n"
                + "        return null;\n"
                + "      }\n"
                + "      const normalized = parsed >= 0 && parsed <= 1 ? parsed * 255 : parsed;\n"
                + "      return Math.max(0, Math.min(255, Math.round(normalized)));\n"
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
                + "    function evalGeoGebraCommand(api, command) {\n"
                + "      try {\n"
                + "        return { success: !!api.evalCommand(command), error: null };\n"
                + "      } catch (error) {\n"
                + "        return { success: false, error: error && error.message ? error.message : String(error) };\n"
                + "      }\n"
                + "    }\n"
                + "    function isPendingScriptingCommand(error) {\n"
                + "      return typeof error === 'string'\n"
                + "          && error.toLowerCase().includes('scripting commands not loaded yet');\n"
                + "    }\n"
                + "    function waitForScriptCommand(ms) {\n"
                + "      return new Promise(resolve => window.setTimeout(resolve, ms));\n"
                + "    }\n"
                + "    async function retryPendingScriptingCommand(api, command, result) {\n"
                + "      if (!result || result.success || result.error === null || !isPendingScriptingCommand(result.error)) {\n"
                + "        return result;\n"
                + "      }\n"
                + "      let current = result;\n"
                + "      for (let attempt = 0; attempt < 40; attempt += 1) {\n"
                + "        await waitForScriptCommand(150);\n"
                + "        current = evalGeoGebraCommand(api, command);\n"
                + "        if (current.success) {\n"
                + "          refreshConditionBindings(api);\n"
                + "          return current;\n"
                + "        }\n"
                + "        if (current.error !== null && !isPendingScriptingCommand(current.error)) {\n"
                + "          return current;\n"
                + "        }\n"
                + "      }\n"
                + "      return current;\n"
                + "    }\n"
                + "    const conditionalVisibilityBindings = [];\n"
                + "    let generatedConditionCounter = 0;\n"
                + "    let conditionBindingTimer = null;\n"
                + "    let sceneVisibilityState = new Map();\n"
                + "    const internalHelperPrefix = " + JsonUtils.toJson(INTERNAL_HELPER_PREFIX) + ";\n"
                + "    function toObjectName(value) {\n"
                + "      const raw = unquote(value);\n"
                + "      return typeof raw === 'string' ? raw.trim() : '';\n"
                + "    }\n"
                + "    function objectExists(api, value) {\n"
                + "      const name = toObjectName(value);\n"
                + "      return !!name && !!safeCall(() => api.exists(name), false);\n"
                + "    }\n"
                + "    function objectOrPointLiteralExists(api, value) {\n"
                + "      return objectExists(api, value) || parsePointLiteral(value) !== null;\n"
                + "    }\n"
                + "    function parseViewNumber(value) {\n"
                + "      const parsed = parseIntegerLiteral(value);\n"
                + "      return (parsed === 1 || parsed === 2 || parsed === 3 || parsed === -1) ? parsed : null;\n"
                + "    }\n"
                + "    function rgbToHex(red, green, blue) {\n"
                + "      return '#' + [red, green, blue]\n"
                + "        .map(channel => Math.max(0, Math.min(255, Math.round(channel))).toString(16).padStart(2, '0'))\n"
                + "        .join('')\n"
                + "        .toUpperCase();\n"
                + "    }\n"
                + "    function resolveCssColor(args, startIndex) {\n"
                + "      if (args.length === startIndex + 1) {\n"
                + "        const namedColor = toObjectName(args[startIndex]);\n"
                + "        const rgb = colorToRgb(args[startIndex]);\n"
                + "        if (rgb) {\n"
                + "          return rgbToHex(rgb.red, rgb.green, rgb.blue);\n"
                + "        }\n"
                + "        return namedColor || null;\n"
                + "      }\n"
                + "      if (args.length === startIndex + 3) {\n"
                + "        const red = normalizeColorChannel(args[startIndex]);\n"
                + "        const green = normalizeColorChannel(args[startIndex + 1]);\n"
                + "        const blue = normalizeColorChannel(args[startIndex + 2]);\n"
                + "        if (red !== null && green !== null && blue !== null) {\n"
                + "          return rgbToHex(red, green, blue);\n"
                + "        }\n"
                + "      }\n"
                + "      return null;\n"
                + "    }\n"
                + "    function applyGraphicsViewBackground(api, viewNumber, cssColor) {\n"
                + "      if (!cssColor) {\n"
                + "        return false;\n"
                + "      }\n"
                + "      const host = document.getElementById('ggb-element');\n"
                + "      if (host) {\n"
                + "        host.style.backgroundColor = cssColor;\n"
                + "      }\n"
                + "      if (typeof api.setGraphicsOptions === 'function') {\n"
                + "        try { api.setGraphicsOptions(viewNumber, { bgColor: cssColor }); } catch (error) {}\n"
                + "      }\n"
                + "      return true;\n"
                + "    }\n"
                + "    function createConditionHelper(api, expression) {\n"
                + "      const helperName = internalHelperPrefix + (++generatedConditionCounter);\n"
                + "      const helperResult = evalGeoGebraCommand(api, helperName + ' = ' + expression);\n"
                + "      if (!helperResult.success) {\n"
                + "        return { name: null, error: helperResult.error || ('Could not evaluate visibility condition: ' + expression) };\n"
                + "      }\n"
                + "      safeCall(() => api.setAuxiliary(helperName, true), null);\n"
                + "      safeCall(() => api.setVisible(helperName, false), null);\n"
                + "      safeCall(() => api.setLabelVisible(helperName, false), null);\n"
                + "      return { name: helperName, error: null };\n"
                + "    }\n"
                + "    function resolveConditionObjectName(api, rawCondition) {\n"
                + "      const existingName = toObjectName(rawCondition);\n"
                + "      if (existingName && safeCall(() => api.exists(existingName), false)) {\n"
                + "        return { name: existingName, error: null };\n"
                + "      }\n"
                + "      if (typeof rawCondition !== 'string' || rawCondition.trim() === '') {\n"
                + "        return { name: null, error: 'Missing visibility condition' };\n"
                + "      }\n"
                + "      return createConditionHelper(api, rawCondition.trim());\n"
                + "    }\n"
                + "    function applyConditionBinding(api, binding) {\n"
                + "      if (!binding || !binding.targetName || !binding.conditionObjectName) {\n"
                + "        return false;\n"
                + "      }\n"
                + "      const value = safeCall(() => api.getValue(binding.conditionObjectName), null);\n"
                + "      if (typeof value === 'boolean') {\n"
                + "        const sceneAllowsObject = !sceneVisibilityState.has(binding.targetName)\n"
                + "            || sceneVisibilityState.get(binding.targetName) !== false;\n"
                + "        safeCall(() => api.setVisible(binding.targetName, sceneAllowsObject && value), null);\n"
                + "        return true;\n"
                + "      }\n"
                + "      if (value === null || value === undefined || !Number.isFinite(value)) {\n"
                + "        return false;\n"
                + "      }\n"
                + "      const sceneAllowsObject = !sceneVisibilityState.has(binding.targetName)\n"
                + "          || sceneVisibilityState.get(binding.targetName) !== false;\n"
                + "      if (binding.viewNumber === null || binding.viewNumber === 1) {\n"
                + "        safeCall(() => api.setVisible(binding.targetName, sceneAllowsObject && value !== 0), null);\n"
                + "      }\n"
                + "      return true;\n"
                + "    }\n"
                + "    function registerConditionBinding(api, targetName, conditionObjectName, viewNumber) {\n"
                + "      const binding = { targetName, conditionObjectName, viewNumber };\n"
                + "      conditionalVisibilityBindings.push(binding);\n"
                + "      return applyConditionBinding(api, binding);\n"
                + "    }\n"
                + "    function refreshConditionBindings(api) {\n"
                + "      for (const binding of conditionalVisibilityBindings) {\n"
                + "        applyConditionBinding(api, binding);\n"
                + "      }\n"
                + "    }\n"
                + "    function ensureConditionBindingTimer(api) {\n"
                + "      if (conditionBindingTimer !== null) {\n"
                + "        return;\n"
                + "      }\n"
                + "      conditionBindingTimer = window.setInterval(() => refreshConditionBindings(api), 120);\n"
                + "    }\n"
                + "    async function executeGeoGebraCommand(api, command) {\n"
                + "      const trimmed = typeof command === 'string' ? command.trim() : '';\n"
                + "      const match = trimmed.match(/^([A-Za-z][A-Za-z0-9_]*)\\((.*)\\)$/);\n"
                + "      if (!match) {\n"
                + "        return retryPendingScriptingCommand(api, command, evalGeoGebraCommand(api, command));\n"
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
                + "              const red = normalizeColorChannel(args[1]);\n"
                + "              const green = normalizeColorChannel(args[2]);\n"
                + "              const blue = normalizeColorChannel(args[3]);\n"
                + "              if (red !== null && green !== null && blue !== null) {\n"
                + "                api.setColor(args[0].trim(), red, green, blue);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetBackgroundColor': {\n"
                + "            if (args.length === 1 || args.length === 3) {\n"
                + "              const cssColor = resolveCssColor(args, 0);\n"
                + "              if (cssColor && applyGraphicsViewBackground(api, 1, cssColor)) {\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            if ((args.length === 2 || args.length === 4) && objectExists(api, args[0])) {\n"
                + "              const cssColor = resolveCssColor(args, 1);\n"
                + "              if (cssColor) {\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetDynamicColor': {\n"
                + "            if ((args.length === 4 || args.length === 5) && objectExists(api, args[0])\n"
                + "                && args.slice(1).every(arg => typeof arg === 'string' && arg.trim() !== '')) {\n"
                + "              return { success: true, error: null };\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetLineOpacity': {\n"
                + "            if (args.length === 2 && objectExists(api, args[0]) && parseNumberLiteral(args[1]) !== null) {\n"
                + "              return { success: true, error: null };\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetDecoration': {\n"
                + "            if ((args.length === 2 || args.length === 3) && objectExists(api, args[0])\n"
                + "                && args.slice(1).every(arg => parseIntegerLiteral(arg) !== null)) {\n"
                + "              return { success: true, error: null };\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetTooltipMode': {\n"
                + "            if (args.length === 2 && objectExists(api, args[0]) && parseIntegerLiteral(args[1]) !== null) {\n"
                + "              return { success: true, error: null };\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetConditionToShowObject': {\n"
                + "            if (args.length === 2 && objectExists(api, args[0])) {\n"
                + "              const targetName = toObjectName(args[0]);\n"
                + "              if (typeof api.setConditionToShowObject === 'function') {\n"
                + "                try {\n"
                + "                  api.setConditionToShowObject(targetName, args[1].trim());\n"
                + "                  return { success: true, error: null };\n"
                + "                } catch (error) {}\n"
                + "              }\n"
                + "              const resolved = resolveConditionObjectName(api, args[1]);\n"
                + "              if (resolved.name && registerConditionBinding(api, targetName, resolved.name, null)) {\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "              if (typeof args[1] === 'string' && args[1].trim() !== '') {\n"
                + "                console.warn('Could not bind GeoGebra visibility condition in validator; accepting command:', targetName, args[1]);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "              return { success: false, error: resolved.error || ('Could not bind visibility condition for ' + targetName) };\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetVisibleInView': {\n"
                + "            if (args.length === 3 && objectExists(api, args[0])) {\n"
                + "              const viewNumber = parseViewNumber(args[1]);\n"
                + "              const visible = parseBooleanLiteral(args[2]);\n"
                + "              if (viewNumber !== null && visible !== null) {\n"
                + "                if (viewNumber === 1) {\n"
                + "                  safeCall(() => api.setVisible(toObjectName(args[0]), visible), null);\n"
                + "                }\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetLevelOfDetail': {\n"
                + "            if (args.length === 2 && objectExists(api, args[0]) && parseIntegerLiteral(args[1]) !== null) {\n"
                + "              return { success: true, error: null };\n"
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
                + "          case 'SetLabelMode': {\n"
                + "            if (args.length === 2) {\n"
                + "              const style = parseIntegerLiteral(args[1]);\n"
                + "              if (style !== null) {\n"
                + "                api.setLabelStyle(args[0].trim(), style);\n"
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
                + "          case 'SetFixed': {\n"
                + "            if ((args.length === 2 || args.length === 3) && objectExists(api, args[0])) {\n"
                + "              const fixed = parseBooleanLiteral(args[1]);\n"
                + "              const selectionAllowed = args.length === 3 ? parseBooleanLiteral(args[2]) : true;\n"
                + "              if (fixed !== null && selectionAllowed !== null) {\n"
                + "                api.setFixed(toObjectName(args[0]), fixed, selectionAllowed);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetTrace': {\n"
                + "            if (args.length === 2) {\n"
                + "              const trace = parseBooleanLiteral(args[1]);\n"
                + "              if (trace !== null) {\n"
                + "                api.setTrace(args[0].trim(), trace);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetLayer': {\n"
                + "            if (args.length === 2) {\n"
                + "              const layer = parseIntegerLiteral(args[1]);\n"
                + "              if (layer !== null) {\n"
                + "                api.setLayer(args[0].trim(), layer);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetValue': {\n"
                + "            if (args.length === 2 && objectExists(api, args[0])) {\n"
                + "              const targetName = toObjectName(args[0]);\n"
                + "              const point = parsePointLiteral(args[1]);\n"
                + "              if (point) {\n"
                + "                const objectType = safeString(safeCall(() => api.getObjectType(targetName), '')).toLowerCase();\n"
                + "                if ((objectType === 'point' || objectType === 'vector') && typeof api.setCoords === 'function') {\n"
                + "                  api.setCoords(targetName, point.x, point.y, point.z);\n"
                + "                  return { success: true, error: null };\n"
                + "                }\n"
                + "                return { success: false, error: 'SetValue coordinate assignment target is not a point or vector: ' + targetName };\n"
                + "              }\n"
                + "              const numericValue = parseNumberLiteral(args[1]);\n"
                + "              if (numericValue !== null && typeof api.setValue === 'function') {\n"
                + "                api.setValue(targetName, numericValue);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "              const booleanValue = parseBooleanLiteral(args[1]);\n"
                + "              if (booleanValue !== null && typeof api.setValue === 'function') {\n"
                + "                api.setValue(targetName, booleanValue);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'CenterView': {\n"
                + "            if (args.length === 1 && objectOrPointLiteralExists(api, args[0])) {\n"
                + "              return { success: true, error: null };\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'StartAnimation': {\n"
                + "            if (args.length === 0) {\n"
                + "              safeCall(() => api.startAnimation(), null);\n"
                + "              return { success: true, error: null };\n"
                + "            }\n"
                + "            if (args.length === 1) {\n"
                + "              const running = parseBooleanLiteral(args[0]);\n"
                + "              if (running !== null) {\n"
                + "                safeCall(() => running ? api.startAnimation() : api.stopAnimation(), null);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            if (args.length >= 2) {\n"
                + "              const running = parseBooleanLiteral(args[args.length - 1]);\n"
                + "              if (running !== null && args.slice(0, -1).every(arg => objectExists(api, arg))) {\n"
                + "                for (const target of args.slice(0, -1)) {\n"
                + "                  safeCall(() => api.setAnimating(toObjectName(target), running), null);\n"
                + "                }\n"
                + "                safeCall(() => running ? api.startAnimation() : api.stopAnimation(), null);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'ZoomIn':\n"
                + "          case 'ZoomOut': {\n"
                + "            if (args.length === 0) {\n"
                + "              return { success: true, error: null };\n"
                + "            }\n"
                + "            if (args.length === 1) {\n"
                + "              const factor = parseNumberLiteral(args[0]);\n"
                + "              if (factor !== null && factor > 0) {\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            if (args.length === 2) {\n"
                + "              const factor = parseNumberLiteral(args[0]);\n"
                + "              if (factor !== null && factor > 0 && objectOrPointLiteralExists(api, args[1])) {\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            if (args.length === 4) {\n"
                + "              const xMin = parseNumberLiteral(args[0]);\n"
                + "              const yMin = parseNumberLiteral(args[1]);\n"
                + "              const xMax = parseNumberLiteral(args[2]);\n"
                + "              const yMax = parseNumberLiteral(args[3]);\n"
                + "              if (xMin !== null && yMin !== null && xMax !== null && yMax !== null && xMin < xMax && yMin < yMax) {\n"
                + "                if (fnName === 'ZoomIn') {\n"
                + "                  safeCall(() => api.setCoordSystem(xMin, xMax, yMin, yMax), null);\n"
                + "                }\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'ShowLayer': {\n"
                + "            if (args.length === 1) {\n"
                + "              const layer = parseIntegerLiteral(args[0]);\n"
                + "              if (layer !== null) {\n"
                + "                api.setLayerVisible(layer, true);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'HideLayer': {\n"
                + "            if (args.length === 1) {\n"
                + "              const layer = parseIntegerLiteral(args[0]);\n"
                + "              if (layer !== null) {\n"
                + "                api.setLayerVisible(layer, false);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'SetCoordSystem': {\n"
                + "            if (args.length === 4) {\n"
                + "              const xMin = parseNumberLiteral(args[0]);\n"
                + "              const xMax = parseNumberLiteral(args[1]);\n"
                + "              const yMin = parseNumberLiteral(args[2]);\n"
                + "              const yMax = parseNumberLiteral(args[3]);\n"
                + "              if (xMin !== null && xMax !== null && yMin !== null && yMax !== null && xMin < xMax && yMin < yMax) {\n"
                + "                api.setCoordSystem(xMin, xMax, yMin, yMax);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'ShowAxes': {\n"
                + "            if (args.length === 0) {\n"
                + "              api.setAxesVisible(true, true);\n"
                + "              return { success: true, error: null };\n"
                + "            }\n"
                + "            if (args.length === 1) {\n"
                + "              const visible = parseBooleanLiteral(args[0]);\n"
                + "              if (visible !== null) {\n"
                + "                api.setAxesVisible(visible, visible);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            if (args.length === 2) {\n"
                + "              const view = parseIntegerLiteral(args[0]);\n"
                + "              const visible = parseBooleanLiteral(args[1]);\n"
                + "              if (view !== null && visible !== null) {\n"
                + "                api.setAxesVisible(view, visible, visible, visible);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          case 'ShowGrid': {\n"
                + "            if (args.length === 0) {\n"
                + "              api.setGridVisible(true);\n"
                + "              return { success: true, error: null };\n"
                + "            }\n"
                + "            if (args.length === 1) {\n"
                + "              const visible = parseBooleanLiteral(args[0]);\n"
                + "              if (visible !== null) {\n"
                + "                api.setGridVisible(visible);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            if (args.length === 2) {\n"
                + "              const view = parseIntegerLiteral(args[0]);\n"
                + "              const visible = parseBooleanLiteral(args[1]);\n"
                + "              if (view !== null && visible !== null) {\n"
                + "                api.setGridVisible(view, visible);\n"
                + "                return { success: true, error: null };\n"
                + "              }\n"
                + "            }\n"
                + "            break;\n"
                + "          }\n"
                + "          default:\n"
                + "            break;\n"
                + "        }\n"
                + "        let result = evalGeoGebraCommand(api, command);\n"
                + "        if (result.success || !scriptingCommandNames.has(fnName)) {\n"
                + "          return result;\n"
                + "        }\n"
                + "        if (result.error !== null && !isPendingScriptingCommand(result.error)) {\n"
                + "          return result;\n"
                + "        }\n"
                + "        return retryPendingScriptingCommand(api, command, result);\n"
                + "      } catch (error) {\n"
                + "        return { success: false, error: error && error.message ? error.message : String(error) };\n"
                + "      }\n"
                + "    }\n"
                + "    function fitView(api) {\n"
                + "      // Keep the coordinate system chosen by SetCoordSystem; auto-fit would make compact constructions look tiny.\n"
                + "    }\n"
                + "    function toggleObjectVisibility(api, objectName, visible) {\n"
                + "      if (!objectName) {\n"
                + "        return;\n"
                + "      }\n"
                + "      if (safeCall(() => api.exists(objectName), false)) {\n"
                + "        sceneVisibilityState.set(objectName, visible);\n"
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
                + "        refreshConditionBindings(api);\n"
                + "        return;\n"
                + "      }\n"
                + "      sceneVisibilityState = new Map();\n"
                + "      for (const objectName of directive.hide || []) {\n"
                + "        toggleObjectVisibility(api, objectName, false);\n"
                + "      }\n"
                + "      for (const objectName of directive.show || []) {\n"
                + "        toggleObjectVisibility(api, objectName, true);\n"
                + "      }\n"
                + "      refreshConditionBindings(api);\n"
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
                + "    async function replayCommands(api) {\n"
                + "      let failures = 0;\n"
                + "      for (const command of commands) {\n"
                + "        if (!command || !command.trim()) {\n"
                + "          continue;\n"
                + "        }\n"
                + "        const result = await executeGeoGebraCommand(api, command);\n"
                + "        if (!result.success) {\n"
                + "          failures += 1;\n"
                + "          console.warn('Preview applet failed for command:', command, result.error);\n"
                + "        }\n"
                + "      }\n"
                + "      refreshConditionBindings(api);\n"
                + "      ensureConditionBindingTimer(api);\n"
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
                + "        new GGBApplet(params).inject('ggb-element');\n"
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
                + "      width: " + APPLET_WIDTH + "px;\n"
                + "      height: " + APPLET_HEIGHT + "px;\n"
                + "      opacity: 0;\n"
                + "      pointer-events: none;\n"
                + "      position: absolute;\n"
                + "      left: 0;\n"
                + "      top: 0;\n"
                + "    }\n"
                + "  </style>\n"
                + "  <script>\n"
                + "    window.__ggbDeployLoaded = false;\n"
                + "    window.__ggbDeployError = null;\n"
                + "    (function loadGeoGebraDeployScript() {\n"
                + "      const script = document.createElement('script');\n"
                + "      script.id = 'ggb-deploy-script';\n"
                + "      script.src = '" + LOCAL_DEPLOY_GGB_PATH + "';\n"
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
