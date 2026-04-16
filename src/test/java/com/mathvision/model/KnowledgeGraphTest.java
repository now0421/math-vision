package com.mathvision.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KnowledgeGraphTest {

    @Test
    void forwardEdgesSupportPrerequisiteAndDependentQueries() {
        KnowledgeNode start = node("start", "Introduce the setup", 0, KnowledgeNode.NODE_TYPE_CONCEPT);
        KnowledgeNode observe = node("observe", "Observe the invariant", 1, KnowledgeNode.NODE_TYPE_OBSERVATION);
        KnowledgeNode derive = node("derive", "Use the invariant to derive the result", 2, KnowledgeNode.NODE_TYPE_DERIVATION);
        KnowledgeNode conclusion = node("conclusion", "State the final conclusion", 3, KnowledgeNode.NODE_TYPE_CONCLUSION);

        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        nodes.put(start.getId(), start);
        nodes.put(observe.getId(), observe);
        nodes.put(derive.getId(), derive);
        nodes.put(conclusion.getId(), conclusion);

        KnowledgeGraph graph = new KnowledgeGraph(
                start.getId(),
                "Target concept",
                nodes,
                Map.of(
                        start.getId(), List.of(observe.getId(), derive.getId()),
                        observe.getId(), List.of(derive.getId()),
                        derive.getId(), List.of(conclusion.getId())
                )
        );

        assertNotNull(graph.getStartNode());
        assertEquals(List.of("observe", "start"), graph.getPrerequisiteIds("derive"));
        assertEquals(List.of("observe", "derive"), graph.getDependentIds("start"));
        assertEquals(List.of("start", "observe", "derive", "conclusion"),
                graph.topologicalOrder().stream().map(KnowledgeNode::getId).collect(Collectors.toList()));
        assertEquals(List.of(0, 1, 2, 3), new ArrayList<>(graph.groupByDepth().keySet()));
    }

    @Test
    void primaryTerminalPrefersConclusionLeaf() {
        KnowledgeNode start = node("start", "Hook the learner", 0, KnowledgeNode.NODE_TYPE_CONCEPT);
        KnowledgeNode helper = node("helper", "Build the helper construction", 1, KnowledgeNode.NODE_TYPE_CONSTRUCTION);
        KnowledgeNode conclusion = node("conclusion", "State the final theorem takeaway", 2, KnowledgeNode.NODE_TYPE_CONCLUSION);

        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        nodes.put(start.getId(), start);
        nodes.put(helper.getId(), helper);
        nodes.put(conclusion.getId(), conclusion);

        KnowledgeGraph graph = new KnowledgeGraph(
                start.getId(),
                "Target concept",
                nodes,
                Map.of(start.getId(), List.of(helper.getId(), conclusion.getId()))
        );

        assertEquals("conclusion", graph.findPrimaryTerminalNodeId());
        assertEquals("State the final theorem takeaway", graph.findPrimaryTerminalNode().getStep());
    }

    private static KnowledgeNode node(String id, String step, int minDepth, String nodeType) {
        KnowledgeNode node = new KnowledgeNode(id, step, minDepth, false);
        node.setNodeType(nodeType);
        return node;
    }
}
