package com.automanim.node;

import com.automanim.model.CodeResult;
import com.automanim.model.Narrative;
import com.automanim.model.PipelineKeys;
import com.automanim.service.AiClient;
import com.automanim.service.FileOutputService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 2: Code Generation - generates executable Manim Python code
 * from the narrative prompt.
 */
public class CodeGenerationNode extends PocketFlow.Node<Narrative, CodeResult, String> {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationNode.class);
    private static final Pattern SCENE_CLASS = Pattern.compile("class\\s+(\\w+)\\s*\\(.*?Scene.*?\\)");

    private static final String MANIM_CODE_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_manim_code\","
            + "    \"description\": \"返回完整的 Manim Python 动画代码。\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"code\": { \"type\": \"string\", \"description\": \"完整的 Manim Python 代码\" },"
            + "        \"scene_name\": { \"type\": \"string\", \"description\": \"主场景类名\" },"
            + "        \"description\": { \"type\": \"string\", \"description\": \"简短说明\" }"
            + "      },"
            + "      \"required\": [\"code\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    // Validation patterns for 3 mandatory rules
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
        this.aiClient = (AiClient) ctx.get(PipelineKeys.AI_CLIENT);
        return (Narrative) ctx.get(PipelineKeys.NARRATIVE);
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
                + "\n\nScene 类名：" + expectedSceneName;

        String code = null;
        String sceneName = expectedSceneName;

        try {
            JsonNode rawResponse = aiClient.chatWithToolsRaw(
                    userPrompt, PromptTemplates.CODE_GENERATION_SYSTEM, MANIM_CODE_TOOL);
            toolCalls++;

            JsonNode toolData = JsonUtils.extractToolCallPayload(rawResponse);
            if (toolData != null && toolData.has("code")) {
                code = toolData.get("code").asText();
                if (toolData.has("scene_name")) {
                    sceneName = toolData.get("scene_name").asText();
                }
            }

            // Fallback: extract from text content
            if (code == null || code.isBlank()) {
                String textContent = JsonUtils.extractTextFromResponse(rawResponse);
                if (textContent != null) {
                    code = JsonUtils.extractCodeBlock(textContent);
                }
            }
        } catch (Exception e) {
            log.debug("  Tool calling failed, falling back to plain chat: {}", e.getMessage());
        }

        // Final fallback: plain chat
        if (code == null || code.isBlank()) {
            try {
                String response = aiClient.chat(userPrompt, PromptTemplates.CODE_GENERATION_SYSTEM);
                toolCalls++;
                code = JsonUtils.extractCodeBlock(response);
            } catch (Exception e) {
                log.error("  Code generation failed: {}", e.getMessage());
                code = "";
            }
        }

        // Extract actual scene name from code
        sceneName = extractSceneName(code, sceneName);

        // Validate and potentially fix code
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

    @Override
    public String post(Map<String, Object> ctx, Narrative narrative, CodeResult codeResult) {
        ctx.put(PipelineKeys.CODE_RESULT, codeResult);

        Path outputDir = (Path) ctx.get(PipelineKeys.OUTPUT_DIR);
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
            violations.add("代码为空");
            return violations;
        }

        if (!code.contains("from manim import")) {
            violations.add("缺少 'from manim import' 语句");
        }
        if (!code.contains("class ") || !code.contains("Scene")) {
            violations.add("缺少 Scene 类定义");
        }
        if (!code.contains("def construct(")) {
            violations.add("缺少 construct() 方法");
        }

        if (RULE1_VIOLATION.matcher(code).find()) {
            violations.add("规则 1 违规：通过实例属性跨场景存储 mobject");
        }

        if (RULE3_VIOLATION.matcher(code).find()) {
            violations.add("规则 3 违规：硬编码 MathTex 子对象下标");
        }

        return violations;
    }

    private String attemptAiFix(String code, List<String> violations) {
        try {
            String violationList = String.join("\n- ", violations);
            String fixPrompt = String.format(
                    "下面的代码存在校验错误：\n\n"
                    + "```python\n%s\n```\n\n"
                    + "发现的问题：\n- %s",
                    code, violationList);

            String response = aiClient.chat(fixPrompt, PromptTemplates.RENDER_FIX_SYSTEM);
            toolCalls++;
            return JsonUtils.extractCodeBlock(response);
        } catch (Exception e) {
            log.debug("  AI fix attempt failed: {}", e.getMessage());
            return null;
        }
    }
}
