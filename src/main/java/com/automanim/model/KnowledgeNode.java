package com.automanim.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public class KnowledgeNode {

    private String concept;
    private int depth;

    @JsonProperty("is_foundation")
    private boolean foundation;

    private List<KnowledgeNode> prerequisites = new ArrayList<>();

    private List<String> equations;
    private Map<String, String> definitions;
    private String interpretation;
    private List<String> examples;

    @JsonProperty("visual_spec")
    private Map<String, Object> visualSpec;

    private String narrative;

    public KnowledgeNode() {}

    public KnowledgeNode(String concept, int depth, boolean foundation) {
        this.concept = concept;
        this.depth = depth;
        this.foundation = foundation;
    }

    public int countNodes() {
        int count = 1;
        for (KnowledgeNode prereq : prerequisites) {
            count += prereq.countNodes();
        }
        return count;
    }

    public int getMaxDepth() {
        if (prerequisites.isEmpty()) {
            return depth;
        }
        return prerequisites.stream()
                .mapToInt(KnowledgeNode::getMaxDepth)
                .max()
                .orElse(depth);
    }

    public List<String> collectAllConcepts() {
        List<String> concepts = new ArrayList<>();
        concepts.add(concept);
        for (KnowledgeNode prereq : prerequisites) {
            concepts.addAll(prereq.collectAllConcepts());
        }
        return concepts;
    }

    public Map<Integer, List<KnowledgeNode>> groupByDepth() {
        Map<Integer, List<KnowledgeNode>> groups = new TreeMap<>();
        collectByDepth(this, groups);
        return groups;
    }

    private void collectByDepth(KnowledgeNode node, Map<Integer, List<KnowledgeNode>> groups) {
        groups.computeIfAbsent(node.depth, k -> new ArrayList<>()).add(node);
        for (KnowledgeNode prereq : node.prerequisites) {
            collectByDepth(prereq, groups);
        }
    }

    public boolean isEnriched() {
        return equations != null && !equations.isEmpty()
                && definitions != null && !definitions.isEmpty();
    }

    public boolean hasVisualSpec() {
        return visualSpec != null && !visualSpec.isEmpty();
    }

    /**
     * Builds a child→parent map for the entire tree.
     * Used by VisualDesignNode to look up a node's parent for style inheritance.
     */
    public Map<KnowledgeNode, KnowledgeNode> buildParentMap() {
        Map<KnowledgeNode, KnowledgeNode> parentMap = new IdentityHashMap<>();
        buildParentMap(this, parentMap);
        return parentMap;
    }

    private void buildParentMap(KnowledgeNode node, Map<KnowledgeNode, KnowledgeNode> map) {
        for (KnowledgeNode child : node.prerequisites) {
            map.put(child, node);
            buildParentMap(child, map);
        }
    }

    public String printTree() {
        StringBuilder sb = new StringBuilder();
        printTree(sb, 0);
        return sb.toString();
    }

    private void printTree(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
        sb.append("|- ").append(concept)
                .append(" (depth ").append(depth).append(")");
        if (foundation) {
            sb.append(" [FOUNDATION]");
        }
        sb.append("\n");
        for (KnowledgeNode prereq : prerequisites) {
            prereq.printTree(sb, indent + 1);
        }
    }

    public String getConcept() { return concept; }
    public void setConcept(String concept) { this.concept = concept; }

    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }

    public boolean isFoundation() { return foundation; }
    public void setFoundation(boolean foundation) { this.foundation = foundation; }

    public List<KnowledgeNode> getPrerequisites() { return prerequisites; }
    public void setPrerequisites(List<KnowledgeNode> prerequisites) { this.prerequisites = prerequisites; }

    public List<String> getEquations() { return equations; }
    public void setEquations(List<String> equations) { this.equations = equations; }

    public Map<String, String> getDefinitions() { return definitions; }
    public void setDefinitions(Map<String, String> definitions) { this.definitions = definitions; }

    public String getInterpretation() { return interpretation; }
    public void setInterpretation(String interpretation) { this.interpretation = interpretation; }

    public List<String> getExamples() { return examples; }
    public void setExamples(List<String> examples) { this.examples = examples; }

    public Map<String, Object> getVisualSpec() { return visualSpec; }
    public void setVisualSpec(Map<String, Object> visualSpec) { this.visualSpec = visualSpec; }

    public String getNarrative() { return narrative; }
    public void setNarrative(String narrative) { this.narrative = narrative; }

    @Override
    public String toString() {
        return "KnowledgeNode{concept='" + concept + "', depth=" + depth
                + ", foundation=" + foundation
                + ", prerequisites=" + prerequisites.size() + "}";
    }
}
