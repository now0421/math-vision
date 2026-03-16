package com.automanim.node;

import com.automanim.model.CodeResult;
import com.automanim.model.Narrative;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
import com.automanim.util.ConcurrencyUtils;
import com.automanim.util.JsonUtils;
import com.automanim.util.PromptTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 2: Code Generation - generates executable Manim Python code
 * from the narrative prompt.
 */
public class CodeGenerationNode extends PocketFlow.Node<Narrative, CodeResult, String> {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationNode.class);
    private static final Pattern SCENE_CLASS = Pattern.compile("class\\s+(\\w+)\\s*\\(.*?Scene.*?\\)");
    private static final Pattern NON_ASCII_IDENTIFIER = Pattern.compile(
            "(?:class|def)\\s+[^\\x00-\\x7F]+|self\\.[^\\x00-\\x7F]+|\\b[^\\x00-\\x7F_][^\\s(=:,]*");

    private static final String MANIM_CODE_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_manim_code\","
            + "    \"description\": \"Return complete Manim Community Python animation code.\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"code\": { \"type\": \"string\", \"description\": \"Complete Manim Python source code\" },"
            + "        \"scene_name\": { \"type\": \"string\", \"description\": \"Primary scene class name in ASCII\" },"
            + "        \"description\": { \"type\": \"string\", \"description\": \"Short summary of the animation\" }"
            + "      },"
            + "      \"required\": [\"code\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // Validation patterns for mandatory rules
    private static final Pattern RULE1_VIOLATION = Pattern.compile(
            "self\\.\\w+\\s*=\\s*(?:MathTex|Text|VGroup|Circle|Square|Line|Dot|Arrow|Axes|NumberPlane)");
    private static final Pattern RULE3_VIOLATION = Pattern.compile(
            "\\w+\\[\\d+\\]\\[\\d+:\\d+\\]");

    private AiClient aiClient;
    private int toolCalls = 0;

    public CodeGenerationNode() {
        super(2, 2000); // 2 retries with 2-second wait
    }

    @Override
    public Narrative prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(WorkflowKeys.AI_CLIENT);
        return (Narrative) ctx.get(WorkflowKeys.NARRATIVE);
    }

    @Override
    public CodeResult exec(Narrative narrative) {
        log.info("=== Stage 2: Code Generation ===");
        toolCalls = 0;

        if (narrative == null || narrative.getVerbosePrompt() == null
                || narrative.getVerbosePrompt().isBlank()) {
            log.warn("Narrative is empty, cannot generate code");
            return new CodeResult("", "", "Empty narrative",
                    narrative != null ? narrative.getTargetConcept() : "");
        }

        String expectedSceneName = conceptToClassName(narrative.getTargetConcept());

        String userPrompt = narrative.getVerbosePrompt()
                + "\n\nScene class name: " + expectedSceneName
                + "\nAll class names, method names, and variable names must use ASCII identifiers only."
                + "\nDo not use Chinese, pinyin, mojibake, or any non-ASCII text in Python identifiers.";

        String code;
        String sceneName;
        try {
            CodeDraft draft = requestCodeAsync(userPrompt, expectedSceneName).join();
            code = draft.code;
            sceneName = draft.sceneName;
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            log.error("  Code generation failed: {}", cause.getMessage());
            code = "";
            sceneName = expectedSceneName;
        }

        sceneName = extractSceneName(code, sceneName);

        List<String> violations = validateCode(code);
        if (!violations.isEmpty()) {
            log.warn("  Code validation found {} violations, attempting AI fix", violations.size());
            String fixedCode = attemptAiFix(code, violations);
            if (fixedCode != null && !fixedCode.isBlank()) {
                List<String> postFixViolations = validateCode(fixedCode);
                if (postFixViolations.size() < violations.size()) {
                    code = fixedCode;
                    sceneName = extractSceneName(code, sceneName);
                    log.info("  AI fix reduced violations from {} to {}",
                            violations.size(), postFixViolations.size());
                }
            }
        }

        CodeResult result = new CodeResult(
                code,
                sceneName,
                "Manim animation for " + narrative.getTargetConcept(),
                narrative.getTargetConcept()
        );
        result.setToolCalls(toolCalls);

        log.info("Code generated: {} lines, scene={}", result.codeLineCount(), sceneName);
        return result;
    }

    private CompletableFuture<CodeDraft> requestCodeAsync(String userPrompt, String expectedSceneName) {
        return aiClient.chatWithToolsRawAsync(userPrompt, PromptTemplates.CODE_GENERATION_SYSTEM, MANIM_CODE_TOOL)
                .thenApply(rawResponse -> {
                    toolCalls++;

                    JsonNode toolData = JsonUtils.extractToolCallPayload(rawResponse);
                    String code = null;
                    String sceneName = expectedSceneName;

                    if (toolData != null && toolData.has("code")) {
                        code = toolData.get("code").asText();
                        if (toolData.has("scene_name")) {
                            sceneName = toolData.get("scene_name").asText();
                        }
                    }

                    if (code == null || code.isBlank()) {
                        String textContent = JsonUtils.extractTextFromResponse(rawResponse);
                        if (textContent != null) {
                            code = JsonUtils.extractCodeBlock(textContent);
                        }
                    }

                    return new CodeDraft(code, sceneName);
                })
                .exceptionally(error -> {
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    log.debug("  Tool calling failed, falling back to plain chat: {}", cause.getMessage());
                    return null;
                })
                .thenCompose(draft -> {
                    if (draft != null && draft.hasCode()) {
                        return CompletableFuture.completedFuture(draft);
                    }
                    return aiClient.chatAsync(userPrompt, PromptTemplates.CODE_GENERATION_SYSTEM)
                            .thenApply(response -> {
                                toolCalls++;
                                return new CodeDraft(JsonUtils.extractCodeBlock(response), expectedSceneName);
                            });
                });
    }

    @Override
    public String post(Map<String, Object> ctx, Narrative narrative, CodeResult codeResult) {
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);

        Path outputDir = (Path) ctx.get(WorkflowKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveCodeResult(outputDir, codeResult);
        }

        return null;
    }

    private String extractSceneName(String code, String fallback) {
        Matcher m = SCENE_CLASS.matcher(code);
        if (m.find()) {
            return m.group(1);
        }
        return fallback;
    }

    private String conceptToClassName(String concept) {
        if (concept == null || concept.isBlank()) return "MainScene";

        StringBuilder sb = new StringBuilder();
        for (String word : concept.split("[\\s\\-_]+")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    sb.append(word.substring(1).toLowerCase());
                }
            }
        }
        sb.append("Scene");
        return sb.toString();
    }

    private List<String> validateCode(String code) {
        List<String> violations = new ArrayList<>();
        if (code == null || code.isBlank()) {
            violations.add("Code is empty");
            return violations;
        }

        if (!code.contains("from manim import")) {
            violations.add("Missing 'from manim import' statement");
        }
        if (!code.contains("class ") || !code.contains("Scene")) {
            violations.add("Missing Scene class definition");
        }
        if (!code.contains("def construct(")) {
            violations.add("Missing construct() method");
        }
        if (NON_ASCII_IDENTIFIER.matcher(code).find()) {
            violations.add("Contains non-ASCII class, method, or variable identifiers");
        }

        if (RULE1_VIOLATION.matcher(code).find()) {
            violations.add("Rule 1 violation: stores mobjects on instance fields across scenes");
        }

        if (RULE3_VIOLATION.matcher(code).find()) {
            violations.add("Rule 3 violation: hardcoded MathTex subobject indexing");
        }

        return violations;
    }

    private String attemptAiFix(String code, List<String> violations) {
        try {
            String violationList = String.join("\n- ", violations);
            String fixPrompt = String.format(
                    "The following code violates validation rules:\n\n"
                    + "```python\n%s\n```\n\n"
                    + "Problems found:\n- %s\n\n"
                    + "Ensure that all class names, function names, method names, and variable names"
                    + " use ASCII English identifiers only.",
                    code, violationList);

            String response = aiClient.chatAsync(fixPrompt, PromptTemplates.RENDER_FIX_SYSTEM).join();
            toolCalls++;
            return JsonUtils.extractCodeBlock(response);
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            log.debug("  AI fix attempt failed: {}", cause.getMessage());
            return null;
        }
    }

    private static final class CodeDraft {
        private final String code;
        private final String sceneName;

        private CodeDraft(String code, String sceneName) {
            this.code = code;
            this.sceneName = sceneName;
        }

        private boolean hasCode() {
            return code != null && !code.isBlank();
        }
    }
}
