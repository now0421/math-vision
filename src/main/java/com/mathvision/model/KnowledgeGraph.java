package com.mathvision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
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

    // ---- Basic node access ----

    public KnowledgeNode getRootNode() {
        return nodes.get(rootNodeId);
    }

    public KnowledgeNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    // ---- Relationship queries ----

    public List<String> getPrerequisiteIds(String nodeId) {
        List<String> prerequisites = new ArrayList<>();
        for (String prerequisiteId : prerequisiteEdges.getOrDefault(nodeId, Collections.emptyList())) {
            if (nodes.containsKey(prerequisiteId) && !prerequisites.contains(prerequisiteId)) {
                prerequisites.add(prerequisiteId);
            }
        }
        prerequisites.sort(buildPrerequisiteComparator(nodeId));
        return prerequisites;
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
                .thenComparing(KnowledgeNode::getStep, String.CASE_INSENSITIVE_ORDER));
        return prerequisites;
    }

    public List<String> getParentIds(String nodeId) {
        return getDependentIds(nodeId);
    }

    public List<KnowledgeNode> getParents(String nodeId) {
        return getDependents(nodeId);
    }

    public List<KnowledgeNode> getNearestParents(String nodeId) {
        KnowledgeNode node = nodes.get(nodeId);
        if (node == null) {
            return Collections.emptyList();
        }

        int expectedDepth = expectedParentDepth(node);
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

    public List<String> getDependentIds(String nodeId) {
        List<String> dependents = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : prerequisiteEdges.entrySet()) {
            String dependentId = entry.getKey();
            if (!nodes.containsKey(dependentId)) {
                continue;
            }
            if (entry.getValue().contains(nodeId) && !dependents.contains(dependentId)) {
                dependents.add(dependentId);
            }
        }
        dependents.sort(buildDependentComparator(nodeId));
        return dependents;
    }

    public List<KnowledgeNode> getDependents(String nodeId) {
        List<KnowledgeNode> dependents = new ArrayList<>();
        for (String dependentId : getDependentIds(nodeId)) {
            KnowledgeNode dependent = nodes.get(dependentId);
            if (dependent != null) {
                dependents.add(dependent);
            }
        }
        return dependents;
    }

    // ---- Aggregate graph info ----

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
        // Depth 0 is the final goal, so presentation order should run from
        // foundational prerequisites (deepest) back up to the goal.
        Map<Integer, List<KnowledgeNode>> groups = new TreeMap<>(Collections.reverseOrder());
        for (KnowledgeNode node : nodes.values()) {
            groups.computeIfAbsent(node.getMinDepth(), ignored -> new ArrayList<>()).add(node);
        }
        for (List<KnowledgeNode> levelNodes : groups.values()) {
            levelNodes.sort(Comparator.comparing(KnowledgeNode::getStep, String.CASE_INSENSITIVE_ORDER));
        }
        return groups;
    }

    // ---- Traversal / presentation ----

    public List<KnowledgeNode> topologicalOrder() {
        List<KnowledgeNode> ordered = new ArrayList<>();
        if (nodes.isEmpty()) {
            return ordered;
        }

        Map<String, Integer> remainingPrerequisites = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (String nodeId : nodes.keySet()) {
            remainingPrerequisites.put(nodeId, 0);
            dependents.put(nodeId, new ArrayList<>());
        }

        for (Map.Entry<String, List<String>> entry : prerequisiteEdges.entrySet()) {
            String currentNodeId = entry.getKey();
            if (!nodes.containsKey(currentNodeId)) {
                continue;
            }

            int prerequisiteCount = 0;
            for (String prerequisiteId : entry.getValue()) {
                if (!nodes.containsKey(prerequisiteId) || prerequisiteId.equals(currentNodeId)) {
                    continue;
                }
                prerequisiteCount++;
                dependents.computeIfAbsent(prerequisiteId, ignored -> new ArrayList<>()).add(currentNodeId);
            }
            remainingPrerequisites.put(currentNodeId, prerequisiteCount);
        }

        Comparator<String> readyComparator = buildTopologicalComparator();
        PriorityQueue<String> ready = new PriorityQueue<>(readyComparator);
        for (Map.Entry<String, Integer> entry : remainingPrerequisites.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        while (!ready.isEmpty()) {
            String nodeId = ready.poll();
            KnowledgeNode node = nodes.get(nodeId);
            if (node != null) {
                ordered.add(node);
            }

            List<String> nextNodes = new ArrayList<>(dependents.getOrDefault(nodeId, Collections.emptyList()));
            nextNodes.sort(buildDependentComparator(nodeId));
            for (String nextNodeId : nextNodes) {
                int updated = remainingPrerequisites.computeIfPresent(nextNodeId, (ignored, count) -> count - 1);
                if (updated == 0) {
                    ready.add(nextNodeId);
                }
            }
        }

        if (ordered.size() == nodes.size()) {
            return ordered;
        }

        List<String> missingNodeIds = new ArrayList<>();
        for (String nodeId : nodes.keySet()) {
            boolean present = false;
            for (KnowledgeNode orderedNode : ordered) {
                if (orderedNode.getId().equals(nodeId)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                missingNodeIds.add(nodeId);
            }
        }
        missingNodeIds.sort(readyComparator);
        for (String nodeId : missingNodeIds) {
            ordered.add(nodes.get(nodeId));
        }
        return ordered;
    }

    public String printGraph() {
        StringBuilder sb = new StringBuilder();
        KnowledgeNode root = getRootNode();
        sb.append("KnowledgeGraph\n");
        if (targetConcept != null && !targetConcept.isBlank()) {
            sb.append("Target: ").append(targetConcept).append("\n");
        }
        if (root != null) {
            sb.append("Root: ").append(root.getStep())
                    .append(" [depth=").append(root.getMinDepth()).append("]\n");
        }
        sb.append("Nodes: ").append(countNodes())
                .append(", Edges: ").append(countEdges())
                .append(", Max depth: ").append(getMaxDepth())
                .append("\n\n");

        for (KnowledgeNode node : topologicalOrder()) {
            sb.append("- ").append(node.getStep())
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
                    sb.append(prerequisites.get(i).getStep());
                }
                sb.append("\n");
            }

            List<KnowledgeNode> dependents = getDependents(node.getId());
            if (!dependents.isEmpty()) {
                sb.append("  dependents: ");
                for (int i = 0; i < dependents.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(dependents.get(i).getStep());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // ---- Internal helpers ----

    private int expectedPrerequisiteDepth(KnowledgeNode node) {
        return node.getMinDepth() + 1;
    }

    private int expectedParentDepth(KnowledgeNode node) {
        return node.getMinDepth() - 1;
    }

    private Comparator<String> buildTopologicalComparator() {
        return buildDepthDescendingComparator();
    }

    private Comparator<String> buildPrerequisiteComparator(String nodeId) {
        KnowledgeNode node = nodes.get(nodeId);
        Comparator<String> comparator = buildDepthDescendingComparator();
        if (node == null) {
            return comparator;
        }
        return comparator.thenComparingInt(id -> Math.abs(nodes.get(id).getMinDepth() - node.getMinDepth()));
    }

    private Comparator<String> buildDependentComparator(String nodeId) {
        KnowledgeNode node = nodes.get(nodeId);
        Comparator<String> comparator = buildDepthAscendingComparator();
        if (node == null) {
            return comparator;
        }
        return comparator.thenComparingInt(id -> Math.abs(nodes.get(id).getMinDepth() - node.getMinDepth()));
    }

    private Comparator<String> buildDepthDescendingComparator() {
        return Comparator.comparingInt((String id) -> {
            KnowledgeNode node = nodes.get(id);
            return node != null ? node.getMinDepth() : Integer.MIN_VALUE;
        }).reversed().thenComparing(id -> {
            KnowledgeNode node = nodes.get(id);
            return node != null ? node.getStep() : id;
        }, String.CASE_INSENSITIVE_ORDER);
    }

    private Comparator<String> buildDepthAscendingComparator() {
        return Comparator.comparingInt((String id) -> {
            KnowledgeNode node = nodes.get(id);
            return node != null ? node.getMinDepth() : Integer.MAX_VALUE;
        }).thenComparing(id -> {
            KnowledgeNode node = nodes.get(id);
            return node != null ? node.getStep() : id;
        }, String.CASE_INSENSITIVE_ORDER);
    }

    // ---- Bean accessors ----

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

    public boolean isProblemMode() {
        boolean hasProblemNode = nodes.values().stream()
                .anyMatch(node -> node != null
                        && KnowledgeNode.NODE_TYPE_PROBLEM.equalsIgnoreCase(node.getNodeType()));
        if (hasProblemNode) {
            return true;
        }

        String normalized = targetConcept == null ? "" : targetConcept.trim().toLowerCase(Locale.ROOT);
        int wordCount = normalized.isBlank() ? 0 : normalized.split("\\s+").length;
        return normalized.contains("?")
                || normalized.contains("problem")
                || normalized.contains("prove")
                || normalized.contains("show that")
                || normalized.contains("solve")
                || normalized.contains("find")
                || normalized.contains("determine")
                || normalized.contains("minimize")
                || normalized.contains("maximize")
                || normalized.contains("minimum")
                || normalized.contains("maximum")
                || normalized.contains("given")
                || normalized.contains("let ")
                || wordCount > 12;
    }
}
