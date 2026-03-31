package com.automanim.service;

import com.automanim.util.GeoGebraCodeUtils;
import com.automanim.util.JsonUtils;
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
    private static final String BROWSER_EXECUTABLE_ENV = "AUTOMANIM_GEOGEBRA_BROWSER_EXECUTABLE";
    private static final String DEPLOY_GGB_URL = "https://www.geogebra.org/apps/deployggb.js";
    private static final String PLAYWRIGHT_ENGINE = "playwright";
    private static final String PLAYWRIGHT_INSTALL_HINT =
            "Install Playwright Chromium with "
                    + "mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI "
                    + "-Dexec.args=\"install chromium\"";
    private static final int VALIDATION_TIMEOUT_SECONDS = 45;
    private static final int VALIDATION_TIMEOUT_MS = VALIDATION_TIMEOUT_SECONDS * 1000;
    private static final int APPLET_BOOTSTRAP_TIMEOUT_MS = VALIDATION_TIMEOUT_MS;
    private static final int APPLET_WIDTH = 960;
    private static final int APPLET_HEIGHT = 540;

    public RenderAttemptResult render(String commandScript,
                                      String figureName,
                                      Path outputDir) {
        if (outputDir == null) {
            return new RenderAttemptResult(false, null, "Output directory is unavailable");
        }

        try {
            Files.createDirectories(outputDir);
            String safeFigureName = normalizeFigureName(figureName);
            List<String> commands = GeoGebraCodeUtils.extractCommands(commandScript);
            List<GeoGebraCodeUtils.SceneDirective> sceneDirectives =
                    GeoGebraCodeUtils.extractSceneDirectives(commandScript);
            Path previewPath = outputDir.resolve(PREVIEW_FILE);
            Path validationPath = outputDir.resolve(VALIDATION_FILE);
            Files.writeString(previewPath,
                    buildPreviewHtml(commands, sceneDirectives, safeFigureName),
                    StandardCharsets.UTF_8);

            ValidationReport report;
            if (commands.isEmpty()) {
                report = newValidationReport(safeFigureName, commands);
                report.completed = true;
                report.error = "No executable GeoGebra commands were found after stripping comments.";
            } else {
                report = validateWithHeadlessBrowser(previewPath, safeFigureName, commands);
            }

            Files.writeString(validationPath, JsonUtils.toPrettyJson(report), StandardCharsets.UTF_8);
            log.info("GeoGebra preview generated: {}", previewPath);
            log.info("GeoGebra validation report generated: {}", validationPath);

            String normalizedPreviewPath = previewPath.toAbsolutePath().normalize().toString();
            if (report != null && report.allSuccessful()) {
                return new RenderAttemptResult(true, normalizedPreviewPath, null);
            }

            String error = summarizeValidationFailure(report);
            log.warn("GeoGebra command validation failed: {}", error);
            return new RenderAttemptResult(false, normalizedPreviewPath, error);
        } catch (IOException e) {
            log.warn("GeoGebra preview generation failed: {}", e.getMessage());
            return new RenderAttemptResult(false, null, e.getMessage());
        }
    }

    protected ValidationReport validateWithHeadlessBrowser(Path previewPath,
                                                           String figureName,
                                                           List<String> commands) {
        ValidationReport fallback = newValidationReport(figureName, commands);
        String configuredBrowser = resolveBrowserExecutable();
        fallback.browserExecutable = configuredBrowser != null && !configuredBrowser.isBlank()
                ? configuredBrowser
                : "playwright:chromium";

        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;
        List<String> consoleMessages = new ArrayList<>();
        List<String> pageErrors = new ArrayList<>();
        List<String> requestFailures = new ArrayList<>();

        try {
            playwright = Playwright.create();
            BrowserType chromium = playwright.chromium();
            String browserDescriptor = resolveBrowserDescriptor(chromium, configuredBrowser);
            fallback.browserExecutable = browserDescriptor;

            browser = launchBrowser(chromium, configuredBrowser);
            context = browser.newContext();
            page = context.newPage();
            page.setDefaultTimeout(VALIDATION_TIMEOUT_MS);
            page.onConsoleMessage(message -> consoleMessages.add(formatConsoleMessage(message)));
            page.onPageError(error -> pageErrors.add(error != null ? error : "Unknown page error"));
            page.onRequestFailed(request -> requestFailures.add(
                    request.url() + " :: " + (request.failure() != null ? request.failure() : "request failed")));

            initializeValidationApplet(page, figureName);
            String validationJson = executeValidationScript(page, commands);
            ValidationReport parsed = parseValidationReport(validationJson);
            if (parsed == null) {
                fallback.error = "Playwright finished, but no GeoGebra validation report was returned.";
                appendDiagnostics(fallback, consoleMessages, pageErrors, requestFailures);
                return fallback;
            }

            normalizeParsedReport(parsed, figureName, commands, browserDescriptor);
            appendDiagnostics(parsed, consoleMessages, pageErrors, requestFailures);
            return parsed;
        } catch (PlaywrightException e) {
            fallback.error = formatPlaywrightError(e, configuredBrowser);
            appendDiagnostics(fallback, consoleMessages, pageErrors, requestFailures);
            return fallback;
        } catch (IOException e) {
            fallback.error = "GeoGebra Playwright validation failed: " + e.getMessage();
            appendDiagnostics(fallback, consoleMessages, pageErrors, requestFailures);
            return fallback;
        } finally {
            safeClose(page);
            safeClose(context);
            safeClose(browser);
            safeClose(playwright);
        }
    }

    protected String resolveBrowserExecutable() {
        String configured = System.getenv(BROWSER_EXECUTABLE_ENV);
        if (configured == null) {
            return null;
        }
        String trimmed = configured.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Browser launchBrowser(BrowserType chromium, String configuredBrowser) throws IOException {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setTimeout((double) VALIDATION_TIMEOUT_MS);

        if (configuredBrowser != null && !configuredBrowser.isBlank()) {
            if (looksLikePath(configuredBrowser)) {
                Path executablePath = Path.of(configuredBrowser);
                if (!Files.exists(executablePath)) {
                    throw new IOException("Configured browser executable does not exist: " + configuredBrowser);
                }
                options.setExecutablePath(executablePath);
            } else {
                options.setChannel(configuredBrowser);
            }
        }

        return chromium.launch(options);
    }

    private String resolveBrowserDescriptor(BrowserType chromium, String configuredBrowser) {
        if (configuredBrowser != null && !configuredBrowser.isBlank()) {
            return configuredBrowser;
        }
        String executablePath = chromium.executablePath();
        return executablePath != null && !executablePath.isBlank()
                ? executablePath
                : "playwright:chromium";
    }

    private void initializeValidationApplet(Page page, String figureName) throws IOException {
        page.setContent(buildValidatorHtml(figureName));
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
                        "figureName", figureName
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
                        + "   try { success = !!ggbApplet.evalCommand(command); }"
                        + "   catch (error) { success = false; errorMessage = error && error.message ? error.message : String(error); }"
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

    private String formatPlaywrightError(PlaywrightException error, String configuredBrowser) {
        String message = error != null && error.getMessage() != null
                ? error.getMessage().trim()
                : "Unknown Playwright error";
        String normalized = message.toLowerCase();
        if (normalized.contains("executable doesn't exist")
                || normalized.contains("failed to launch")
                || normalized.contains("browsertype.launch")) {
            String browserHint = configuredBrowser != null && !configuredBrowser.isBlank()
                    ? "Configured browser: " + configuredBrowser + ". "
                    : "";
            return "GeoGebra Playwright validation failed to launch Chromium. "
                    + browserHint
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
        return (figureName == null || figureName.isBlank()) ? "GeoGebraFigure" : figureName.trim();
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
                + "        try {\n"
                + "          const ok = !!api.evalCommand(command);\n"
                + "          if (!ok) {\n"
                + "            failures += 1;\n"
                + "            console.warn('Preview applet returned false for command:', command);\n"
                + "          }\n"
                + "        } catch (error) {\n"
                + "          failures += 1;\n"
                + "          console.warn('Preview applet threw for command:', command, error);\n"
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

    private boolean looksLikePath(String value) {
        return value.contains("\\") || value.contains("/") || value.contains(":");
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
        private final String error;

        public RenderAttemptResult(boolean success, String previewPath, String error) {
            this.success = success;
            this.previewPath = previewPath;
            this.error = error;
        }

        public boolean success() { return success; }
        public String previewPath() { return previewPath; }
        public String error() { return error; }
    }
}
