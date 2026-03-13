package com.automanim.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@JsonIgnoreProperties(ignoreUnknown = true)
public class KnowledgeGraph {

    @JsonProperty("root_node_id")
    private String rootNodeId;

    @JsonProperty("target_concept")
    private String targetConcept;

    private Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();

    @JsonProperty("prerequisite_edges")
    private Map<String, List<String>> prerequisiteEdges = new LinkedHashMap<>();

    public KnowledgeGraph() {}

    public KnowledgeGraph(String rootNodeId,
                          String targetConcept,
                          Map<String, KnowledgeNode> nodes,
                          Map<String, List<String>> prerequisiteEdges) {
        this.rootNodeId = rootNodeId;
        this.targetConcept = targetConcept;
        this.nodes = new LinkedHashMap<>(nodes);
        this.prerequisiteEdges = new LinkedHashMap<>(prerequisiteEdges);
    }

    public KnowledgeNode getRootNode() {
        return nodes.get(rootNodeId);
    }

    public KnowledgeNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public List<String> getPrerequisiteIds(String nodeId) {
        return prerequisiteEdges.getOrDefault(nodeId, Collections.emptyList());
    }

    public List<KnowledgeNode> getPrerequisites(String nodeId) {
        List<KnowledgeNode> prerequisites = new ArrayList<>();
        for (String prereqId : getPrerequisiteIds(nodeId)) {
            KnowledgeNode node = nodes.get(prereqId);
            if (node != null) {
                prerequisites.add(node);
            }
        }
        prerequisites.sort(Comparator.comparingInt(KnowledgeNode::getMinDepth)
                .thenComparing(KnowledgeNode::getConcept, String.CASE_INSENSITIVE_ORDER));
        return prerequisites;
    }

    public List<String> getParentIds(String nodeId) {
        List<String> parents = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : prerequisiteEdges.entrySet()) {
            if (entry.getValue().contains(nodeId)) {
                parents.add(entry.getKey());
            }
        }
        parents.sort(Comparator.comparingInt((String id) -> nodes.get(id).getMinDepth())
                .thenComparing(id -> nodes.get(id).getConcept(), String.CASE_INSENSITIVE_ORDER));
        return parents;
    }

    public List<KnowledgeNode> getParents(String nodeId) {
        List<KnowledgeNode> parents = new ArrayList<>();
        for (String parentId : getParentIds(nodeId)) {
            KnowledgeNode parent = nodes.get(parentId);
            if (parent != null) {
                parents.add(parent);
            }
        }
        return parents;
    }

    public List<KnowledgeNode> getNearestParents(String nodeId) {
        KnowledgeNode node = nodes.get(nodeId);
        if (node == null) {
            return Collections.emptyList();
        }

        int expectedDepth = node.getMinDepth() - 1;
        List<KnowledgeNode> nearest = new ArrayList<>();
        for (KnowledgeNode parent : getParents(nodeId)) {
            if (parent.getMinDepth() == expectedDepth) {
                nearest.add(parent);
            }
        }

        if (!nearest.isEmpty()) {
            return nearest;
        }
        return getParents(nodeId);
    }

    public int countNodes() {
        return nodes.size();
    }

    public int countEdges() {
        int count = 0;
        for (List<String> edges : prerequisiteEdges.values()) {
            count += edges.size();
        }
        return count;
    }

    public int getMaxDepth() {
        int maxDepth = 0;
        for (KnowledgeNode node : nodes.values()) {
            maxDepth = Math.max(maxDepth, node.getMinDepth());
        }
        return maxDepth;
    }

    public Map<Integer, List<KnowledgeNode>> groupByDepth() {
        Map<Integer, List<KnowledgeNode>> groups = new TreeMap<>();
        for (KnowledgeNode node : nodes.values()) {
            groups.computeIfAbsent(node.getMinDepth(), ignored -> new ArrayList<>()).add(node);
        }
        for (List<KnowledgeNode> levelNodes : groups.values()) {
            levelNodes.sort(Comparator.comparing(KnowledgeNode::getConcept, String.CASE_INSENSITIVE_ORDER));
        }
        return groups;
    }

    public List<KnowledgeNode> topologicalOrder() {
        List<KnowledgeNode> ordered = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        visitPostOrder(rootNodeId, visited, ordered);
        return ordered;
    }

    public String printGraph() {
        StringBuilder sb = new StringBuilder();
        KnowledgeNode root = getRootNode();
        sb.append("KnowledgeGraph\n");
        if (root != null) {
            sb.append("Root: ").append(root.getConcept())
                    .append(" [depth=").append(root.getMinDepth()).append("]\n");
        }
        sb.append("Nodes: ").append(countNodes())
                .append(", Edges: ").append(countEdges())
                .append(", Max depth: ").append(getMaxDepth())
                .append("\n\n");

        for (KnowledgeNode node : topologicalOrder()) {
            sb.append("- ").append(node.getConcept())
                    .append(" [id=").append(node.getId())
                    .append(", depth=").append(node.getMinDepth());
            if (node.getNodeType() != null && !node.getNodeType().isBlank()) {
                sb.append(", type=").append(node.getNodeType());
            }
            if (node.isFoundation()) {
                sb.append(", foundation");
            }
            sb.append("]\n");

            List<KnowledgeNode> prerequisites = getPrerequisites(node.getId());
            if (!prerequisites.isEmpty()) {
                sb.append("  prerequisites: ");
                for (int i = 0; i < prerequisites.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(prerequisites.get(i).getConcept());
                }
                sb.append("\n");
            }

            List<KnowledgeNode> parents = getParents(node.getId());
            if (!parents.isEmpty()) {
                sb.append("  parents: ");
                for (int i = 0; i < parents.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(parents.get(i).getConcept());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private void visitPostOrder(String nodeId, Set<String> visited, List<KnowledgeNode> ordered) {
        if (nodeId == null || visited.contains(nodeId)) {
            return;
        }
        visited.add(nodeId);
        for (String prereqId : getPrerequisiteIds(nodeId)) {
            visitPostOrder(prereqId, visited, ordered);
        }
        KnowledgeNode node = nodes.get(nodeId);
        if (node != null) {
            ordered.add(node);
        }
    }

    public String getRootNodeId() { return rootNodeId; }
    public void setRootNodeId(String rootNodeId) { this.rootNodeId = rootNodeId; }

    public String getTargetConcept() { return targetConcept; }
    public void setTargetConcept(String targetConcept) { this.targetConcept = targetConcept; }

    public Map<String, KnowledgeNode> getNodes() { return nodes; }
    public void setNodes(Map<String, KnowledgeNode> nodes) { this.nodes = nodes; }

    public Map<String, List<String>> getPrerequisiteEdges() { return prerequisiteEdges; }
    public void setPrerequisiteEdges(Map<String, List<String>> prerequisiteEdges) {
        this.prerequisiteEdges = prerequisiteEdges;
    }
}
