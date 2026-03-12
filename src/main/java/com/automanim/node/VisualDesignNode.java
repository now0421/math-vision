package com.automanim.node;

import com.automanim.config.PipelineConfig;
import com.automanim.model.KnowledgeNode;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 1b: Visual Design - adds visual specifications to each node.
 *
 * Depth levels processed root-first (depth 0, then 1, then 2, ...):
 *   - Parent node's visual spec is fully finalized before any child begins.
 *   - Children at the same depth may run in parallel since all share a
 *     completed parent spec (read-only at that point).
 *   - The parentMap (built once before processing) provides O(1) child-to-parent lookup.
 */
public class VisualDesignNode extends PocketFlow.Node<KnowledgeNode, KnowledgeNode, String> {

    private static final Logger log = LoggerFactory.getLogger(VisualDesignNode.class);

    private static final String VISUAL_DESIGN_TOOL = "["
            + "{"
            + "  \"type\": \"function\","
            + "  \"function\": {"
            + "    \"name\": \"write_visual_design\","
            + "    \"description\": \"返回某个概念动画场景的视觉设计规格。\","
            + "    \"parameters\": {"
            + "      \"type\": \"object\","
            + "      \"properties\": {"
            + "        \"visual_description\": { \"type\": \"string\", \"description\": \"会出现哪些视觉对象或图形\" },"
            + "        \"color_scheme\": { \"type\": \"string\", \"description\": \"颜色方案说明\" },"
            + "        \"animation_description\": { \"type\": \"string\", \"description\": \"视觉效果与转场（可选）\" },"
            + "        \"transitions\": { \"type\": \"string\", \"description\": \"场景切换方式（可选）\" },"
            + "        \"duration\": { \"type\": \"number\", \"description\": \"时长，单位秒\" },"
            + "        \"layout\": { \"type\": \"string\", \"description\": \"16:9 画布中的空间布局\" },"
            + "        \"color_palette\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"Manim 颜色名称\" }"
            + "      },"
            + "      \"required\": [\"visual_description\", \"color_scheme\", \"layout\"]"
            + "    }"
            + "  }"
            + "}"
            + "]";

    private AiClient aiClient;
    private final AtomicInteger toolCalls = new AtomicInteger(0);
    private boolean parallelEnabled = true;
    private int maxConcurrent = 4;
    private final List<String> globalColorPalette = Collections.synchronizedList(new ArrayList<>());

    // Built once per exec(), maps child-to-parent for correct visual inheritance
    private Map<KnowledgeNode, KnowledgeNode> parentMap;

    public VisualDesignNode() {
        super(1, 0);
    }

    @Override
    public KnowledgeNode prep(Map<String, Object> ctx) {
        this.aiClient = (AiClient) ctx.get(PipelineKeys.AI_CLIENT);
        PipelineConfig config = (PipelineConfig) ctx.get(PipelineKeys.CONFIG);
        if (config != null) {
            this.parallelEnabled = config.isParallelVisualDesign();
            this.maxConcurrent = config.getMaxConcurrent();
        }
        return (KnowledgeNode) ctx.get(PipelineKeys.KNOWLEDGE_TREE);
    }

    @Override
    public KnowledgeNode exec(KnowledgeNode tree) {
        log.info("=== Stage 1b: Visual Design (parallel={}) ===", parallelEnabled);
        toolCalls.set(0);
        globalColorPalette.clear();

        // Build child-to-parent map once for O(1) parent lookup
        this.parentMap = tree.buildParentMap();

        Map<Integer, List<KnowledgeNode>> levels = tree.groupByDepth();
        List<Integer> depths = new ArrayList<>(levels.keySet());
        Collections.sort(depths); // root-first: parent spec finalized before children

        ExecutorService executor = parallelEnabled
                ? Executors.newFixedThreadPool(maxConcurrent) : null;

        try {
            for (int depth : depths) {
                List<KnowledgeNode> nodes = levels.get(depth);
                log.info("  Designing depth {} ({} nodes{})", depth, nodes.size(),
                        parallelEnabled && nodes.size() > 1 ? ", parallel" : "");

                if (parallelEnabled && nodes.size() > 1 && executor != null) {
                    List<Future<?>> futures = new ArrayList<>();
                    for (KnowledgeNode node : nodes) {
                        futures.add(executor.submit(() -> designNode(node)));
                    }
                    for (Future<?> f : futures) {
                        try { f.get(); }
                        catch (Exception e) { log.warn("  Parallel visual design error: {}", e.getMessage()); }
                    }
                } else {
                    for (KnowledgeNode node : nodes) {
                        designNode(node);
                    }
                }
            }
        } finally {
            if (executor != null) executor.shutdown();
        }

        log.info("Visual design complete: {} API calls, palette: {}", toolCalls.get(), globalColorPalette);
        return tree;
    }

    @Override
    public String post(Map<String, Object> ctx, KnowledgeNode prepRes, KnowledgeNode tree) {
        ctx.put(PipelineKeys.KNOWLEDGE_TREE, tree);
        int prevCalls = (int) ctx.getOrDefault(PipelineKeys.ENRICHMENT_TOOL_CALLS, 0);
        ctx.put(PipelineKeys.ENRICHMENT_TOOL_CALLS, prevCalls + toolCalls.get());

        Path outputDir = (Path) ctx.get(PipelineKeys.OUTPUT_DIR);
        if (outputDir != null) {
            FileOutputService.saveEnrichedTree(outputDir, tree);
        }
        return null;
    }

    private void designNode(KnowledgeNode node) {
        Map<String, Object> existingSpec = node.getVisualSpec();
        if (existingSpec != null && existingSpec.containsKey("visual_description")) {
            log.debug("  Skipping already-designed node: {}", node.getConcept());
            return;
        }

        String equationsInfo = node.getEquations() != null
                ? String.join(", ", node.getEquations()) : "无";

        String parentSpecContext = buildParentSpecContext(node);

        String paletteContext = globalColorPalette.isEmpty()
                ? "当前还没有分配任何颜色。"
                : "已使用的颜色有：" + String.join(", ", globalColorPalette)
                  + "。请优先使用互补色或对比色。";

        String userPrompt = String.format(
                "概念：%s\n深度：%d\n是否基础：%s\n相关公式：%s\n\n%s\n%s",
                node.getConcept(), node.getDepth(), node.isFoundation(),
                equationsInfo, parentSpecContext, paletteContext);

        try {
            JsonNode data = null;
            try {
                JsonNode rawResponse = aiClient.chatWithToolsRaw(
                        userPrompt, PromptTemplates.VISUAL_DESIGN_SYSTEM, VISUAL_DESIGN_TOOL);
                toolCalls.incrementAndGet();
                data = JsonUtils.extractToolCallPayload(rawResponse);

                if (data == null) {
                    String textContent = JsonUtils.extractTextFromResponse(rawResponse);
                    if (textContent != null && !textContent.isBlank()) {
                        data = JsonUtils.parseTree(JsonUtils.extractJsonObject(textContent));
                    }
                }
            } catch (Exception e) {
                log.debug("  Tool calling failed for '{}', falling back to plain chat", node.getConcept());
                String response = aiClient.chat(userPrompt, PromptTemplates.VISUAL_DESIGN_SYSTEM);
                toolCalls.incrementAndGet();
                data = JsonUtils.parseTree(JsonUtils.extractJsonObject(response));
            }

            if (data != null) {
                applyVisualSpec(node, data);
                log.debug("  Visual spec set for: {}", node.getConcept());
            }
        } catch (Exception e) {
            log.warn("  Visual design failed for '{}': {}", node.getConcept(), e.getMessage());
        }
    }

    /**
     * Looks up the tree to find this node's parent spec.
     * The parentMap is built before processing begins, so the parent's spec
     * is guaranteed to be finalized (parent depth was fully processed first).
     */
    private String buildParentSpecContext(KnowledgeNode node) {
        KnowledgeNode parent = parentMap.get(node);
        if (parent == null) {
            return "这是根概念，请建立整个动画的总体视觉主题。";
        }

        Map<String, Object> parentSpec = parent.getVisualSpec();
        if (parentSpec == null || parentSpec.isEmpty()) {
            return String.format("父概念：%s（目前还没有视觉规格）。", parent.getConcept());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("父概念：%s\n", parent.getConcept()));
        sb.append("请继承父概念的视觉一致性：\n");
        if (parentSpec.containsKey("color_scheme")) {
            sb.append("  配色：").append(parentSpec.get("color_scheme")).append("\n");
        }
        if (parentSpec.containsKey("layout")) {
            sb.append("  布局风格：").append(parentSpec.get("layout")).append("\n");
        }
        if (parentSpec.containsKey("visual_description")) {
            sb.append("  视觉风格：").append(parentSpec.get("visual_description")).append("\n");
        }
        sb.append("请与父概念保持一致的风格、色彩体系、节奏和视觉密度。");
        return sb.toString();
    }

    private void applyVisualSpec(KnowledgeNode node, JsonNode data) {
        Map<String, Object> visualSpec = node.getVisualSpec();
        if (visualSpec == null) { visualSpec = new LinkedHashMap<>(); }

        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();

            if ("color_palette".equals(key) && value.isArray()) {
                List<String> nodeColors = new ArrayList<>();
                for (JsonNode color : value) {
                    String colorName = color.asText();
                    nodeColors.add(colorName);
                    if (!globalColorPalette.contains(colorName)) {
                        globalColorPalette.add(colorName);
                    }
                }
                visualSpec.put(key, nodeColors);
            } else if ("duration".equals(key) && value.isNumber()) {
                visualSpec.put(key, value.numberValue());
            } else {
                visualSpec.put(key, value.asText());
            }
        }

        node.setVisualSpec(visualSpec);
    }
}
