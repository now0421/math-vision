package com.automanim.node;

import com.automanim.model.KnowledgeNode;
import com.automanim.util.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathEnrichmentNodeTest {

    @Test
    void acceptsExplicitlyEmptyMathFieldsAsValidEnrichment() throws Exception {
        MathEnrichmentNode enrichmentNode = new MathEnrichmentNode();
        KnowledgeNode node = new KnowledgeNode("step_1", "Describe the setup", 0, false);

        ObjectNode payload = JsonUtils.mapper().createObjectNode();
        payload.putArray("equations");
        payload.putObject("definitions");
        payload.put("interpretation", "   ");
        payload.putArray("examples");

        applyContent(enrichmentNode, node, payload);

        assertEquals(List.of(), node.getEquations());
        assertEquals(Map.of(), node.getDefinitions());
        assertNull(node.getInterpretation());
        assertEquals(List.of(), node.getExamples());
        assertTrue(node.isEnriched());
    }

    @Test
    void trimsAndFiltersBlankMathEntries() throws Exception {
        MathEnrichmentNode enrichmentNode = new MathEnrichmentNode();
        KnowledgeNode node = new KnowledgeNode("step_2", "Solve for x", 0, false);

        ObjectNode payload = JsonUtils.mapper().createObjectNode();
        ArrayNode equations = payload.putArray("equations");
        equations.add("   ");
        equations.add(" x + 1 = 2 ");
        equations.add("");

        ObjectNode definitions = payload.putObject("definitions");
        definitions.put(" ", "ignored");
        definitions.put("x", " value of the unknown ");
        definitions.put("y", "   ");

        payload.put("interpretation", " isolate the variable ");

        ArrayNode examples = payload.putArray("examples");
        examples.add("");
        examples.add(" Substitute and check the result. ");

        applyContent(enrichmentNode, node, payload);

        assertEquals(List.of("x + 1 = 2"), node.getEquations());
        assertEquals(Map.of("x", "value of the unknown"), node.getDefinitions());
        assertEquals("isolate the variable", node.getInterpretation());
        assertEquals(List.of("Substitute and check the result."), node.getExamples());
        assertTrue(node.isEnriched());
    }

    private void applyContent(MathEnrichmentNode enrichmentNode,
                              KnowledgeNode node,
                              ObjectNode payload) throws Exception {
        Method method = MathEnrichmentNode.class.getDeclaredMethod(
                "applyContent", KnowledgeNode.class, com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        method.invoke(enrichmentNode, node, payload);
    }
}
