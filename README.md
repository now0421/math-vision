# MathVision

**Automated mathematical animation pipeline** built on [PocketFlow-Java](https://github.com/The-Pocket/PocketFlow-Java).

Takes a math concept as input, explores its prerequisite tree via AI, enriches each concept with equations and visual designs, composes a narrative, generates Manim code, and renders the final video �� all automatically.

## Architecture

PocketFlow workflow pipeline:

```
ExplorationNode �� MathEnrichmentNode �� VisualDesignNode
    �� NarrativeNode �� CodeGenerationNode �� CodeEvaluationNode �� RenderNode �� SceneEvaluationNode
```

Each node follows the PocketFlow `prep �� exec �� post` pattern:
- **prep**: reads from shared context (`Map<String, Object>`)
- **exec**: performs the transformation (AI calls, rendering, etc.)
- **post**: writes results back to context, persists to disk

## Prerequisites

- **Java 17+**
- **Maven 3.8+**
- **Manim** (for rendering �� optional, use `--no-render` to skip)
- AI API key: `MOONSHOT_API_KEY` (Kimi) or `GEMINI_API_KEY` (Gemini)

## Build

```bash
mvn clean package
```

This produces a fat JAR at `target/mathvision-1.0-SNAPSHOT.jar`.

## Usage

```bash
# Basic usage (uses Kimi by default)
java -jar target/mathvision-1.0-SNAPSHOT.jar "Fourier Transform"

# With options
java -jar target/mathvision-1.0-SNAPSHOT.jar "Taylor Series" \
    --provider gemini \
    --quality medium \
    --max-depth 3 \
    --render-retries 5

# Code generation only (no rendering)
java -jar target/mathvision-1.0-SNAPSHOT.jar "Euler's Formula" --no-render
```

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `--provider kimi\|gemini` | `kimi` | AI provider |
| `--quality low\|medium\|high` | `low` | Manim render quality |
| `--max-depth N` | `4` | Prerequisite tree exploration depth |
| `--output DIR` | `./output/<concept>` | Output directory |
| `--no-render` | off | Skip the rendering stage |
| `--render-retries N` | `4` | Max render retry attempts |

### Environment Variables

| Variable | Required for |
|----------|-------------|
| `MOONSHOT_API_KEY` | Kimi provider |
| `MOONSHOT_BASE_URL` | Kimi (optional, defaults to `https://api.moonshot.cn/v1`) |
| `KIMI_K2_MODEL` | Kimi (optional, defaults to `kimi-k2-0711-preview`) |
| `GEMINI_API_KEY` | Gemini provider |
| `GEMINI_MODEL` | Gemini (optional, defaults to `gemini-2.5-flash`) |

## Output

Each run creates timestamped output files:

```
output/fourier_transform_20250101_120000/
������ 1_knowledge_tree.json     # Prerequisite tree
������ 2_enriched_tree.json      # With equations + visual specs
������ 3_narrative.json          # Composed animation script
������ 4_manim_code.py           # Generated Manim code
������ 5_render_result.json      # Render outcome + metadata
������ 6_pipeline_summary.json   # Timing and stats
```

## Project Structure

```
src/main/java/com/mathvision/
������ MathVisionApplication.java    # CLI entry point
������ WorkflowFlow.java            # Flow assembly
������ config/
��   ������ WorkflowConfig.java      # Workflow/runtime config
������ model/
��   ������ KnowledgeNode.java       # Prerequisite tree node
��   ������ Narrative.java           # Narrative composition result
��   ������ CodeResult.java          # Code generation result
��   ������ CodeEvaluationResult.java # Pre-render review result
��   ������ WorkflowKeys.java        # Shared context key constants
������ node/
��   ������ ExplorationNode.java     # Stage 0: prerequisite discovery
��   ������ MathEnrichmentNode.java  # Stage 1a: equations + definitions
��   ������ VisualDesignNode.java    # Stage 1b: visual specifications
��   ������ NarrativeNode.java       # Stage 1c: narrative composition
��   ������ CodeGenerationNode.java  # Stage 2: Manim code generation
��   ������ CodeEvaluationNode.java  # Stage 3: pre-render semantic review
��   ������ RenderNode.java          # Stage 4: render + retry loop
��   ������ SceneEvaluationNode.java # Stage 5: geometry/layout review
��   ������ CodeFixNode.java         # Shared routed code-fix node
������ service/
��   ������ AiClient.java            # AI provider interface
��   ������ OpenAiCompatibleAiClient.java # Config-driven OpenAI-compatible client
��   ������ AbstractOpenAiCompatibleAiClient.java # Shared provider base
��   ������ GeminiAiClient.java      # Google Gemini implementation
��   ������ ManimRendererService.java # Manim CLI subprocess
��   ������ FileOutputService.java   # Intermediate file persistence
������ util/
    ������ JsonUtils.java           # Jackson helpers
    ������ CodeUtils.java           # Shared Manim code extraction/validation helpers
    ������ ErrorSummarizer.java     # Shared render error summarization
    ������ TargetDescriptionBuilder.java # Shared workflow target/context builders
    ������ NodeConversationContext.java # Rolling chat context per node
```
