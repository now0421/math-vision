# MathVision

Automated math teaching-scene pipeline built on [PocketFlow-Java](https://github.com/The-Pocket/PocketFlow-Java).

It takes a math concept or problem, plans a forward teaching graph with AI, enriches each beat with math content and visual design, composes a storyboard, generates backend code, and renders the final artifact.

## Architecture

PocketFlow workflow pipeline:

```text
ProblemNormalizationNode -> ExplorationNode -> MathEnrichmentNode
    -> VisualDesignNode -> StoryboardValidationNode -> CodeGenerationNode
    -> CodeEvaluationNode -> RenderNode -> SceneEvaluationNode
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
|- 00_problem_source.json                 # Raw text/assets supplied to normalization
|- 00_problem_bundle.json                 # Normalized problem/concept bundle
|- 01_knowledge_graph.json                # Forward teaching graph
|- 02_math_enriched_graph.json            # Graph with equations and definitions
|- 03_visual_narrative.json               # Visual storyboard package
|- 04_storyboard_validated.json           # Validated storyboard
|- 04_storyboard_validation_report.json   # Storyboard validation report
|- 05_manim_code.py                       # Generated Manim code
|- 05_code_result.json                    # Code-generation metadata
|- 06_code_evaluation.json                # Code review and static evaluation
|- 07_render_result.json                  # Render outcome and artifact metadata
|- 08_scene_evaluation.json               # Rendered-geometry scene evaluation
|- 09_workflow_summary.json               # Timing and workflow stats
|- 09_code_fix_trace.json                 # Shared code-fix event trace
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
|  |- ProblemNormalizationNode.java  # Stage 0: normalize text/image problem input
|  |- ExplorationNode.java           # Stage 1: forward teaching-graph planning
|  |- MathEnrichmentNode.java        # Stage 2: equations and definitions
|  |- VisualDesignNode.java          # Stage 3: visual specifications
|  |- StoryboardValidationNode.java  # Stage 4: storyboard validation
|  |- CodeGenerationNode.java        # Stage 5: backend code generation
|  |- CodeEvaluationNode.java        # Stage 6: static code review
|  |- RenderNode.java                # Stage 7: render/validate artifact
|  |- SceneEvaluationNode.java       # Stage 8: geometry scene evaluation
|  |- CodeFixNode.java
|- service/
|- util/
```
