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

    @JsonProperty("start_node_id")
    private String startNodeId;

    @JsonProperty("target_concept")
    private String targetConcept;

    private Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();

    @JsonProperty("next_edges")
    private Map<String, List<String>> nextEdges = new LinkedHashMap<>();

    public KnowledgeGraph() {}

    public KnowledgeGraph(String startNodeId,
                          String targetConcept,
                          Map<String, KnowledgeNode> nodes,
                          Map<String, List<String>> nextEdges) {
        this.startNodeId = startNodeId;
        this.targetConcept = targetConcept;
        this.nodes = new LinkedHashMap<>(nodes);
        this.nextEdges = new LinkedHashMap<>(nextEdges);
    }

    public KnowledgeNode getStartNode() {
        return nodes.get(startNodeId);
    }

    public KnowledgeNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public List<String> getPrerequisiteIds(String nodeId) {
        List<String> prerequisites = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : nextEdges.entrySet()) {
            String prerequisiteId = entry.getKey();
            if (!nodes.containsKey(prerequisiteId)) {
                continue;
            }
            if (entry.getValue().contains(nodeId) && !prerequisites.contains(prerequisiteId)) {
                prerequisites.add(prerequisiteId);
            }
        }
        prerequisites.sort(buildPrerequisiteComparator(nodeId));
        return prerequisites;
    }

    public List<KnowledgeNode> getPrerequisites(String nodeId) {
        List<KnowledgeNode> prerequisites = new ArrayList<>();
        for (String prerequisiteId : getPrerequisiteIds(nodeId)) {
            KnowledgeNode prerequisite = nodes.get(prerequisiteId);
            if (prerequisite != null) {
                prerequisites.add(prerequisite);
            }
        }
        prerequisites.sort(Comparator.comparingInt(KnowledgeNode::getMinDepth)
                .reversed()
                .thenComparing(KnowledgeNode::getStep, String.CASE_INSENSITIVE_ORDER));
        return prerequisites;
    }

    public List<String> getDependentIds(String nodeId) {
        List<String> dependents = new ArrayList<>();
        for (String dependentId : nextEdges.getOrDefault(nodeId, Collections.emptyList())) {
            if (nodes.containsKey(dependentId) && !dependents.contains(dependentId)) {
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

    public int countNodes() {
        return nodes.size();
    }

    public int countEdges() {
        int count = 0;
        for (List<String> edges : nextEdges.values()) {
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
            levelNodes.sort(Comparator.comparing(KnowledgeNode::getStep, String.CASE_INSENSITIVE_ORDER));
        }
        return groups;
    }

    public List<KnowledgeNode> topologicalOrder() {
        List<KnowledgeNode> ordered = new ArrayList<>();
        if (nodes.isEmpty()) {
            return ordered;
        }

        Map<String, Integer> remainingPrerequisites = new HashMap<>();
        for (String nodeId : nodes.keySet()) {
            remainingPrerequisites.put(nodeId, 0);
        }

        for (Map.Entry<String, List<String>> entry : nextEdges.entrySet()) {
            String currentNodeId = entry.getKey();
            if (!nodes.containsKey(currentNodeId)) {
                continue;
            }

            for (String nextNodeId : entry.getValue()) {
                if (!nodes.containsKey(nextNodeId) || nextNodeId.equals(currentNodeId)) {
                    continue;
                }
                remainingPrerequisites.computeIfPresent(nextNodeId, (ignored, count) -> count + 1);
            }
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

            List<String> nextNodeIds = new ArrayList<>(nextEdges.getOrDefault(nodeId, Collections.emptyList()));
            nextNodeIds.sort(buildDependentComparator(nodeId));
            for (String nextNodeId : nextNodeIds) {
                if (!remainingPrerequisites.containsKey(nextNodeId)) {
                    continue;
                }
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
        KnowledgeNode start = getStartNode();
        KnowledgeNode terminal = findPrimaryTerminalNode();
        sb.append("KnowledgeGraph\n");
        if (targetConcept != null && !targetConcept.isBlank()) {
            sb.append("Target: ").append(targetConcept).append("\n");
        }
        if (start != null) {
            sb.append("Start: ").append(start.getStep())
                    .append(" [depth=").append(start.getMinDepth()).append("]\n");
        }
        if (terminal != null) {
            sb.append("Terminal: ").append(terminal.getStep())
                    .append(" [depth=").append(terminal.getMinDepth()).append("]\n");
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
                sb.append("  next steps: ");
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

    public KnowledgeNode findPrimaryTerminalNode() {
        String terminalId = findPrimaryTerminalNodeId();
        return terminalId == null ? null : nodes.get(terminalId);
    }

    public String findPrimaryTerminalNodeId() {
        List<String> terminalCandidates = new ArrayList<>();
        for (String nodeId : nodes.keySet()) {
            if (getDependentIds(nodeId).isEmpty()) {
                terminalCandidates.add(nodeId);
            }
        }

        if (terminalCandidates.isEmpty()) {
            return startNodeId;
        }

        terminalCandidates.sort(primaryTerminalComparator());
        return terminalCandidates.get(0);
    }

    private Comparator<String> primaryTerminalComparator() {
        return Comparator.comparingInt((String id) -> {
                    KnowledgeNode node = nodes.get(id);
                    return terminalTypeRank(node != null ? node.getNodeType() : "");
                })
                .thenComparing(Comparator.comparingInt((String id) -> {
                    KnowledgeNode node = nodes.get(id);
                    return node != null ? node.getMinDepth() : Integer.MIN_VALUE;
                }).reversed())
                .thenComparing(id -> {
                    KnowledgeNode node = nodes.get(id);
                    return node != null ? node.getStep() : id;
                }, String.CASE_INSENSITIVE_ORDER);
    }

    private int terminalTypeRank(String nodeType) {
        String normalized = nodeType == null ? "" : nodeType.trim().toLowerCase(Locale.ROOT);
        if (KnowledgeNode.NODE_TYPE_CONCLUSION.equals(normalized)) {
            return 0;
        }
        if (KnowledgeNode.NODE_TYPE_DERIVATION.equals(normalized)) {
            return 1;
        }
        if (KnowledgeNode.NODE_TYPE_CONSTRUCTION.equals(normalized)) {
            return 2;
        }
        if (KnowledgeNode.NODE_TYPE_OBSERVATION.equals(normalized)) {
            return 3;
        }
        if (KnowledgeNode.NODE_TYPE_CONCEPT.equals(normalized)
                || KnowledgeNode.NODE_TYPE_PROBLEM.equals(normalized)) {
            return 4;
        }
        return 5;
    }

    private Comparator<String> buildTopologicalComparator() {
        return buildDepthAscendingComparator();
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

    public String getStartNodeId() { return startNodeId; }
    public void setStartNodeId(String startNodeId) { this.startNodeId = startNodeId; }

    public String getTargetConcept() { return targetConcept; }
    public void setTargetConcept(String targetConcept) { this.targetConcept = targetConcept; }

    public Map<String, KnowledgeNode> getNodes() { return nodes; }
    public void setNodes(Map<String, KnowledgeNode> nodes) { this.nodes = nodes; }

    public Map<String, List<String>> getNextEdges() { return nextEdges; }
    public void setNextEdges(Map<String, List<String>> nextEdges) {
        this.nextEdges = nextEdges;
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
