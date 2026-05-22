package com.mathvision.util;

import com.mathvision.model.Narrative.Storyboard;

import java.util.List;
import java.util.Map;

/**
 * Placeholder for optional deterministic storyboard constraint hints.
 * Backend semantic constraint correctness is reviewed by CodeEvaluation LLM.
 */
public final class StoryboardConstraintComplianceAnalyzer {

    public static final String RULE_ID = "constraint_compliance";

    public List<Violation> analyze(Storyboard storyboard, String outputTarget, String generatedCode) {
        return List.of();
    }

    public static final class Violation {
        private final String relation;
        private final Map<String, Object> refs;
        private final String owner;
        private final String evidence;
        private final String repairHint;
        private final String severity;
        private final String strength;

        public Violation(String relation,
                         Map<String, Object> refs,
                         String owner,
                         String evidence,
                         String repairHint,
                         String severity,
                         String strength) {
            this.relation = relation;
            this.refs = refs;
            this.owner = owner;
            this.evidence = evidence;
            this.repairHint = repairHint;
            this.severity = severity;
            this.strength = strength;
        }

        public String getRelation() { return relation; }
        public Map<String, Object> getRefs() { return refs; }
        public String getOwner() { return owner; }
        public String getEvidence() { return evidence; }
        public String getRepairHint() { return repairHint; }
        public String getSeverity() { return severity; }
        public String getStrength() { return strength; }

        public String summary() {
            return "Constraint compliance failed for relation '" + relation + "'"
                    + (owner != null && !owner.isBlank() ? " on '" + owner + "'" : "")
                    + ": " + evidence + ". Repair: " + repairHint;
        }

        public String evidenceText() {
            return "relation=" + relation
                    + ", owner=" + owner
                    + ", strength=" + strength
                    + ", refs=" + JsonUtils.toJson(refs)
                    + ", evidence=" + evidence
                    + ", repair_hint=" + repairHint;
        }
    }
}
