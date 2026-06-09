# MathVision

MathVision is a Java/PocketFlow pipeline for turning a math concept or problem into a rendered teaching artifact.

The current workflow is problem-bundle first: raw text, Markdown files, and local image assets are normalized into a structured `ProblemBundle`, then the rest of the pipeline plans the teaching graph, enriches the math, designs and validates the storyboard, generates backend code, evaluates it, renders or validates the artifact, and optionally repairs code/layout issues through a shared code-fix node.

## Workflow

```text
Stage 0  ProblemNormalizationNode
Stage 1  ExplorationNode
Stage 2  MathEnrichmentNode
Stage 3  VisualDesignNode
Stage 4  StoryboardValidationNode
Stage 5  CodeGenerationNode
Stage 6  CodeEvaluationNode
Stage 7  RenderNode
Stage 8  SceneEvaluationNode
```

Stages 5-8 can route through `CodeFixNode` for iterative repair. Resume and partial-run modes slice the same PocketFlow graph, so node `prep -> exec -> post` behavior and artifact writes are preserved.

## Requirements

- Java 11+ and Maven 3.8+
- An API key for the selected model provider in `src/main/resources/model-config.json`
- For Manim output: Manim CLI available on `PATH`, or `MATHVISION_MANIM_EXECUTABLE` / `MATHVISION_MANIM_PYTHON`
- For GeoGebra output: Playwright Chromium installed for GeoGebra command validation

Install Playwright Chromium when using the GeoGebra target:

```bash
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

## Build

```bash
mvn clean package
```

The fat JAR is written to:

```text
target/mathvision-1.0.0-SNAPSHOT.jar
```

## Quick Start

Run a Markdown problem file. Local image links such as `![](diagram.png)` are attached automatically and resolved relative to the Markdown file.

```bash
java -jar target/mathvision-1.0.0-SNAPSHOT.jar problem-bank/geometry_aq_min_problem.md
```

Run a plain concept or problem string:

```bash
java -jar target/mathvision-1.0.0-SNAPSHOT.jar "Fourier Transform"
```

Run only normalization, useful for checking Markdown/image ingestion and `ProblemBundle` quality:

```bash
java -jar target/mathvision-1.0.0-SNAPSHOT.jar problem-bank/geometry_aq_min_problem.md --normalize-only
```

## CLI

```text
mathvision PROBLEM.md [options]
mathvision --problem-file PROBLEM.md [options]
mathvision [target-input] [options]
```

| Option | Description |
| --- | --- |
| `--problem-file FILE` | Read a Markdown problem file. Local Markdown image refs are attached automatically. |
| `--image FILE` | Add a local image asset. Can be repeated. |
| `--asset FILE` | Alias for `--image`. |
| `--from-graph FILE or DIR` | Resume from stage 2 by loading `01_knowledge_graph.json` or its parent directory. Writes later outputs beside the graph. |
| `--from-code FILE or DIR` | Resume from stage 6 by loading generated Manim/GeoGebra code or its parent directory. Writes later outputs beside the code. |
| `--normalize-only` | Run stage 0 only. Aliases: `--normalization-only`, `--problem-normalization-only`. |
| `--exploration-only` | Run stages 0-1 and stop after `01_knowledge_graph.json`. |
| `--to-visual-design` | Run stages 0-3 and stop after visual design. |
| `--to-storyboard-validation` | Run stages 0-4 and stop after storyboard validation. |
| `--workflow-config FILE` | Load workflow settings from a JSON file instead of the classpath default. |
| `--model-config FILE` | Load model catalog/provider settings from a JSON file instead of the classpath default. |
| `--output DIR` | Write outputs to a specific directory. Ignored with `--from-graph` and `--from-code`. |
| `-h`, `--help` | Show CLI help. |

Only one partial-run option can be used at a time. `--from-graph` and `--from-code` are mutually exclusive.

## Configuration

Runtime behavior is configured through JSON, not CLI flags such as `--provider`, `--quality`, or `--no-render`.

- `src/main/resources/workflow-config.json` selects the active `model`, `input_mode`, `output_target`, render settings, concurrency, and retry budgets.
- `src/main/resources/model-config.json` defines available model entries and provider defaults.
- The `model` field in `workflow-config.json` must match a key under `model-config.json.models`.

Current default workflow highlights:

```json
{
  "input_mode": "auto",
  "output_target": "manim",
  "model": "GLM-5V-Turbo",
  "render_enabled": true,
  "render_quality": "low",
  "render_max_retries": 10
}
```

Supported output targets are `manim` and `geogebra`. Supported input modes are `auto`, `concept`, and `problem`.

Provider API keys are read from the environment variables referenced by the selected model config. The bundled catalog includes providers for Moonshot, Zhipu, DeepSeek, Gemini, Aliyun, Anthropic, and OpenAI-compatible endpoints.

Common provider variables include:

| Provider | API key variable | Optional base URL variable |
| --- | --- | --- |
| Moonshot | `MOONSHOT_API_KEY` | `MOONSHOT_BASE_URL` |
| Zhipu | `ZHIPU_API_KEY` | `ZHIPU_BASE_URL` |
| DeepSeek | `DEEPSEEK_API_KEY` | `DEEPSEEK_BASE_URL` |
| Gemini | `GEMINI_API_KEY` | |
| Aliyun | `ALIYUN_API_KEY` | `ALIYUN_BASE_URL` |
| Anthropic | `ANTHROPIC_API_KEY` | `ANTHROPIC_BASE_URL` |
| OpenAI-compatible | `OPENAI_API_KEY` | `OPENAI_BASE_URL` |

## Outputs

New runs create timestamped directories under `output/<target>/`, for example:

```text
output/manim/geometry_aq_min_problem_20260609_133816/
```

Typical Manim artifacts:

```text
00_problem_source.json
00_problem_bundle.json
01_knowledge_graph.json
01_knowledge_graph_pretty.txt
02_math_enriched_graph.json
03_visual_narrative.json
04_storyboard_validated.json
04_storyboard_validation_report.json
05_manim_code.py
05_code_result.json
06_code_evaluation.json
06_manim_code_reviewed.py
07_render_result.json
07_manim_code_final.py
07_manim_geometry.json
08_scene_evaluation.json
09_workflow_summary.json
09_code_fix_trace.json
media/
```

For GeoGebra output, code and render artifacts use GeoGebra names such as:

```text
05_geogebra_commands.txt
06_geogebra_commands_reviewed.txt
07_geogebra_commands_final.txt
07_geogebra_preview.html
07_geogebra_validation.json
07_geogebra_geometry.json
```

Partial runs only write artifacts for the stages they execute, plus the workflow summary and code-fix trace.

## Project Layout

```text
src/main/java/com/mathvision/
|- MathVisionApplication.java        # CLI, input loading, config/client wiring
|- WorkflowFlow.java                 # PocketFlow graph assembly and slice entrypoints
|- config/                           # workflow/model config loading
|- model/                            # ProblemBundle, KnowledgeGraph, Narrative, code/render results
|- node/
|  |- ProblemNormalizationNode.java  # Stage 0
|  |- ExplorationNode.java           # Stage 1
|  |- MathEnrichmentNode.java        # Stage 2
|  |- VisualDesignNode.java          # Stage 3
|  |- StoryboardValidationNode.java  # Stage 4
|  |- CodeGenerationNode.java        # Stage 5
|  |- CodeEvaluationNode.java        # Stage 6
|  |- RenderNode.java                # Stage 7
|  |- SceneEvaluationNode.java       # Stage 8
|  |- CodeFixNode.java               # Shared repair route
|- prompt/                           # system prompts and tool schemas
|- service/                          # AI clients, output, Manim/GeoGebra rendering
|- util/                             # validation, geometry, storyboard, text helpers
src/main/resources/
|- workflow-config.json
|- model-config.json
|- llm/
|- render/
```
