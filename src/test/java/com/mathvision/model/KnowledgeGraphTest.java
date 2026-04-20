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
                ),
                List.of(start.getId(), observe.getId(), derive.getId(), conclusion.getId())
        );

        assertNotNull(graph.getStartNode());
        assertEquals(List.of("observe", "start"), graph.getPrerequisiteIds("derive"));
        assertEquals(List.of("observe", "derive"), graph.getDependentIds("start"));
        assertEquals(List.of("start", "observe", "derive", "conclusion"),
                graph.teachingOrderNodes().stream().map(KnowledgeNode::getId).collect(Collectors.toList()));
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
                Map.of(start.getId(), List.of(helper.getId(), conclusion.getId())),
                List.of(start.getId(), helper.getId(), conclusion.getId())
        );

        assertEquals("conclusion", graph.findPrimaryTerminalNodeId());
        assertEquals("State the final theorem takeaway", graph.findPrimaryTerminalNode().getStep());
    }

    @Test
    void executionBatchesFollowTeachingOrderForLinearChain() {
        KnowledgeGraph graph = graph(
                List.of(
                        node("start", "Start", 0, KnowledgeNode.NODE_TYPE_CONCEPT),
                        node("middle", "Middle", 1, KnowledgeNode.NODE_TYPE_OBSERVATION),
                        node("end", "End", 2, KnowledgeNode.NODE_TYPE_CONCLUSION)
                ),
                Map.of(
                        "start", List.of("middle"),
                        "middle", List.of("end")
                ),
                List.of("start", "middle", "end")
        );

        assertEquals(
                List.of(
                        List.of("start"),
                        List.of("middle"),
                        List.of("end")
                ),
                executionBatchIds(graph)
        );
    }

    @Test
    void executionBatchesKeepDiamondMergeInLaterBatch() {
        KnowledgeGraph graph = graph(
                List.of(
                        node("start", "Start", 0, KnowledgeNode.NODE_TYPE_CONCEPT),
                        node("left", "Left branch", 1, KnowledgeNode.NODE_TYPE_OBSERVATION),
                        node("right", "Right branch", 1, KnowledgeNode.NODE_TYPE_OBSERVATION),
                        node("merge", "Merge", 2, KnowledgeNode.NODE_TYPE_DERIVATION)
                ),
                Map.of(
                        "start", List.of("left", "right"),
                        "left", List.of("merge"),
                        "right", List.of("merge")
                ),
                List.of("start", "left", "right", "merge")
        );

        assertEquals(
                List.of(
                        List.of("start"),
                        List.of("left", "right"),
                        List.of("merge")
                ),
                executionBatchIds(graph)
        );
    }

    @Test
    void executionBatchesDoNotReleaseUnevenMergeEarly() {
        KnowledgeGraph graph = graph(
                List.of(
                        node("a", "A", 0, KnowledgeNode.NODE_TYPE_CONCEPT),
                        node("b1", "B1", 0, KnowledgeNode.NODE_TYPE_CONCEPT),
                        node("b2", "B2", 1, KnowledgeNode.NODE_TYPE_OBSERVATION),
                        node("merge", "Merge", 1, KnowledgeNode.NODE_TYPE_DERIVATION)
                ),
                Map.of(
                        "a", List.of("merge"),
                        "b1", List.of("b2"),
                        "b2", List.of("merge")
                ),
                List.of("a", "b1", "b2", "merge")
        );

        assertEquals(
                List.of(
                        List.of("a", "b1"),
                        List.of("b2"),
                        List.of("merge")
                ),
                executionBatchIds(graph)
        );
    }

    @Test
    void executionBatchesContinueSingleBranchAfterMerge() {
        KnowledgeGraph graph = graph(
                List.of(
                        node("start", "Start", 0, KnowledgeNode.NODE_TYPE_CONCEPT),
                        node("left", "Left", 1, KnowledgeNode.NODE_TYPE_OBSERVATION),
                        node("right", "Right", 1, KnowledgeNode.NODE_TYPE_OBSERVATION),
                        node("merge", "Merge", 2, KnowledgeNode.NODE_TYPE_DERIVATION),
                        node("tail", "Tail", 3, KnowledgeNode.NODE_TYPE_CONCLUSION)
                ),
                Map.of(
                        "start", List.of("left", "right"),
                        "left", List.of("merge"),
                        "right", List.of("merge"),
                        "merge", List.of("tail")
                ),
                List.of("start", "left", "right", "merge", "tail")
        );

        assertEquals(
                List.of(
                        List.of("start"),
                        List.of("left", "right"),
                        List.of("merge"),
                        List.of("tail")
                ),
                executionBatchIds(graph)
        );
    }

    private static KnowledgeGraph graph(List<KnowledgeNode> nodeList,
                                        Map<String, List<String>> nextEdges,
                                        List<String> teachingOrder) {
        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        for (KnowledgeNode node : nodeList) {
            nodes.put(node.getId(), node);
        }
        return new KnowledgeGraph(
                teachingOrder.get(0),
                "Target concept",
                nodes,
                nextEdges,
                teachingOrder
        );
    }

    private static List<List<String>> executionBatchIds(KnowledgeGraph graph) {
        return graph.executionBatches().stream()
                .map(batch -> batch.stream().map(KnowledgeNode::getId).collect(Collectors.toList()))
                .collect(Collectors.toList());
    }

    private static KnowledgeNode node(String id, String step, int minDepth, String nodeType) {
        KnowledgeNode node = new KnowledgeNode(id, step, minDepth, false);
        node.setNodeType(nodeType);
        return node;
    }
}
