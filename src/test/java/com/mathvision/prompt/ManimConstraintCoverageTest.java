package com.mathvision.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManimConstraintCoverageTest {

    @Test
    void matrixResourceListsAllSourceDocuments() {
        String matrix = SystemPrompts.getManimConstraintMatrix();

        assertTrue(matrix.contains("## SKILL.md"));
        assertTrue(matrix.contains("## scene-planning.md"));
        assertTrue(matrix.contains("## visual-design.md"));
        assertTrue(matrix.contains("## animation-design-thinking.md"));
        assertTrue(matrix.contains("## production-quality.md"));
        assertTrue(matrix.contains("## updaters-and-trackers.md"));
        assertTrue(matrix.contains("## animations.md"));
        assertTrue(matrix.contains("## mobjects.md"));
        assertTrue(matrix.contains("## equations.md"));
        assertTrue(matrix.contains("## graphs-and-data.md"));
        assertTrue(matrix.contains("## camera-and-3d.md"));
        assertTrue(matrix.contains("## rendering.md"));
        assertTrue(matrix.contains("## troubleshooting.md"));
        assertTrue(matrix.contains("## paper-explainer.md"));
        assertTrue(matrix.contains("## decorations.md"));
        assertTrue(matrix.contains("GeoGebra pipeline intentionally does not import Manim-only animation"));
    }

    @Test
    void manimPromptStagesExposeTeachingConstraints() {
        String exploration = ExplorationPrompts.problemGraphSystemPrompt("Target", "Demo");
        String enrichment = EnrichmentPrompts.systemPrompt("Target", "Demo");
        String visual = VisualDesignPrompts.systemPrompt("Target", "Demo", "manim");
        String narrative = NarrativePrompts.systemPrompt("Target", "Demo", "manim");
        String codegen = CodeGenerationPrompts.systemPrompt("Target", "Demo", "manim");
        String review = CodeEvaluationPrompts.reviewSystemPrompt("Target", "Demo", "manim");
        String renderFix = RenderFixPrompts.systemPrompt("Target", "Demo");
        String sceneFix = SceneEvaluationPrompts.layoutFixSystemPrompt("Target", "Demo");

        assertTrue(exploration.contains("discovery arc or problem-solution arc"));
        assertTrue(exploration.contains("hook to observation to key insight to conclusion"));

        assertTrue(enrichment.contains("narration-first teaching intent"));
        assertTrue(enrichment.contains("Explain why before how"));
        assertTrue(enrichment.contains("equations feel earned by intuition"));

        assertTrue(visual.contains("Use color semantically"));
        assertTrue(visual.contains("Preserve intentional empty space"));
        assertTrue(visual.contains("monospace fonts"));

        assertTrue(narrative.contains("Manim teaching philosophy"));
        assertTrue(narrative.contains("Every learner-visible Manim object must be explicitly represented"));
        assertTrue(narrative.contains("create a separate label object"));

        assertTrue(codegen.contains("always_redraw(...)"));
        assertTrue(codegen.contains("Use subcaptions or subtitle-ready beats"));
        assertTrue(codegen.contains("Use the right collection type"));

        assertTrue(review.contains("enough empty space"));
        assertTrue(review.contains("temporary annotations cleaned up"));

        assertTrue(renderFix.contains("always_redraw"));
        assertTrue(renderFix.contains("Use the right collection type"));

        assertTrue(sceneFix.contains("Preserve intentional empty space"));
        assertTrue(sceneFix.contains("Temporary annotations, comparison aids, and helper overlays need an exit plan"));
    }

    @Test
    void manimResourceReferencesCarryStyleAndSyntaxDestinations() {
        String styleReference = SystemPrompts.ensureManimStyleReference("");
        String syntaxManual = SystemPrompts.ensureManimSyntaxManual("");

        assertTrue(styleReference.contains("Semantic Palette"));
        assertTrue(styleReference.contains("Opacity Hierarchy"));
        assertTrue(styleReference.contains("Typography And Readability"));
        assertTrue(styleReference.contains("Empty Space And Visual Weight"));

        assertTrue(syntaxManual.contains("Group(...)"));
        assertTrue(syntaxManual.contains("VGroup(...)"));
        assertTrue(syntaxManual.contains("always_redraw"));
        assertTrue(syntaxManual.contains("self.add_subcaption"));
        assertTrue(syntaxManual.contains("Moving 2D Camera"));
    }

    @Test
    void geogebraPromptsRemainFreeOfManimOnlyMediaRules() {
        String visual = VisualDesignPrompts.systemPrompt("Target", "Demo", "geogebra");
        String narrative = NarrativePrompts.systemPrompt("Target", "Demo", "geogebra");
        String codegen = CodeGenerationPrompts.systemPrompt("Target", "Demo", "geogebra");

        assertFalse(visual.contains("Manim teaching philosophy"));
        assertFalse(visual.contains("always_redraw"));
        assertFalse(visual.contains("subcaptions"));
        assertFalse(visual.contains("monospace fonts"));

        assertFalse(narrative.contains("Manim teaching philosophy"));
        assertFalse(narrative.contains("Every learner-visible Manim object"));
        assertFalse(narrative.contains("create a separate label object"));

        assertFalse(codegen.contains("always_redraw"));
        assertFalse(codegen.contains("subcaptions"));
        assertFalse(codegen.contains("Group(...)"));
    }
}
