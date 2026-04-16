# MathVision

Automated math teaching-scene pipeline built on [PocketFlow-Java](https://github.com/The-Pocket/PocketFlow-Java).

It takes a math concept or problem, plans a forward teaching graph with AI, enriches each beat with math content and visual design, composes a storyboard, generates backend code, and renders the final artifact.

## Architecture

PocketFlow workflow pipeline:

```text
ExplorationNode -> MathEnrichmentNode -> VisualDesignNode
    -> NarrativeNode -> CodeGenerationNode -> CodeEvaluationNode
    -> RenderNode -> SceneEvaluationNode
```

Each node follows the PocketFlow `prep -> exec -> post` pattern:
- `prep`: reads from shared context (`Map<String, Object>`)
- `exec`: performs the transformation
- `post`: writes results back to context and persists artifacts

## Prerequisites

- Java 17+
- Maven 3.8+
- Manim for video rendering, or GeoGebra runtime support if using the GeoGebra target
- AI API key such as `MOONSHOT_API_KEY` or `GEMINI_API_KEY`

## Build

```bash
mvn clean package
```

This produces a fat JAR at `target/mathvision-1.0-SNAPSHOT.jar`.

## Usage

```bash
java -jar target/mathvision-1.0-SNAPSHOT.jar "Fourier Transform"

java -jar target/mathvision-1.0-SNAPSHOT.jar "Taylor Series" \
    --provider gemini \
    --quality medium \
    --max-depth 3 \
    --render-retries 5

java -jar target/mathvision-1.0-SNAPSHOT.jar "Euler's Formula" --no-render
```

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `--provider kimi\|gemini` | `kimi` | AI provider |
| `--quality low\|medium\|high` | `low` | Manim render quality |
| `--max-depth N` | `4` | Forward teaching-graph depth budget |
| `--output DIR` | `./output/<concept>` | Output directory |
| `--no-render` | off | Skip the rendering stage |
| `--render-retries N` | `4` | Max render retry attempts |

### Environment Variables

| Variable | Required for |
|----------|-------------|
| `MOONSHOT_API_KEY` | Kimi provider |
| `MOONSHOT_BASE_URL` | Kimi, optional |
| `KIMI_K2_MODEL` | Kimi, optional |
| `GEMINI_API_KEY` | Gemini provider |
| `GEMINI_MODEL` | Gemini, optional |

## Output

Each run creates timestamped output files such as:

```text
output/fourier_transform_20250101_120000/
|- 1_knowledge_graph.json    # Forward teaching graph
|- 2_enriched_graph.json     # With equations and visual specs
|- 3_narrative.json          # Storyboard and prompt package
|- 4_manim_code.py           # Generated Manim code
|- 5_render_result.json      # Render outcome and metadata
|- 7_workflow_summary.json   # Timing and workflow stats
```

## Project Structure

```text
src/main/java/com/mathvision/
|- MathVisionApplication.java
|- WorkflowFlow.java
|- config/
|- model/
|  |- KnowledgeNode.java
|  |- KnowledgeGraph.java
|  |- Narrative.java
|  |- CodeResult.java
|  |- CodeEvaluationResult.java
|  |- WorkflowKeys.java
|- node/
|  |- ExplorationNode.java     # Stage 0: forward teaching-graph planning
|  |- MathEnrichmentNode.java  # Stage 1a: equations and definitions
|  |- VisualDesignNode.java    # Stage 1b: visual specifications
|  |- NarrativeNode.java       # Stage 1c: storyboard composition
|  |- CodeGenerationNode.java
|  |- CodeEvaluationNode.java
|  |- RenderNode.java
|  |- SceneEvaluationNode.java
|  |- CodeFixNode.java
|- service/
|- util/
```
