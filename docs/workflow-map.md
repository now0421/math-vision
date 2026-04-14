# Workflow Map

## Recommended Project Name

Recommended name: `MathScene Compiler`

Why this name fits:
- The project is no longer only `MathVision`; it now targets both `manim` and `geogebra`.
- The real core is not direct code generation, but compiling math reasoning into teachable scenes.
- The current architecture already behaves like a compiler pipeline:
  input -> graph -> enrichment -> visual design -> storyboard -> backend code -> review -> render -> repair

One-line positioning:

`MathScene Compiler` is a multi-backend pipeline that compiles math reasoning into teachable scenes, then emits Manim animations or GeoGebra constructions.

Backup options:
- `Math Storyboard Compiler`
- `MathScene Forge`

## 1. What The Project Really Is

This project is not "generate code from a prompt in one step".

It is a layered workflow:

1. Classify the input as `concept` or `problem`.
2. Build a knowledge graph or a solution-step graph.
3. Enrich each node with math content.
4. Add node-level visual plans.
5. Merge those node plans into a global storyboard.
6. Compile the storyboard into `manimCode` or `geogebraCode`.
7. Run pre-render review and post-render geometry evaluation.
8. Route failures through a shared repair node.

So the best high-level description is:

`math teaching scene planner + multi-backend scene compiler + automatic review/repair pipeline`

## 2. Unified Main Pipeline

Entry point:
[MathVisionApplication.java](/d:/myproject/math-vision/src/main/java/com/mathvision/MathVisionApplication.java:43)

Flow assembly:
[WorkflowFlow.java](/d:/myproject/math-vision/src/main/java/com/mathvision/WorkflowFlow.java:28)

Default full chain:

`Exploration -> MathEnrichment -> VisualDesign -> Narrative -> CodeGeneration -> CodeEvaluation -> Render -> SceneEvaluation`

Shared repair loop:

`CodeFixNode`

The shared context contract is defined in:
[WorkflowKeys.java](/d:/myproject/math-vision/src/main/java/com/mathvision/model/WorkflowKeys.java:12)

Core cross-stage artifacts:
- `knowledgeGraph`
- `narrative`
- `codeResult`
- `codeEvaluationResult`
- `renderResult`
- `sceneEvaluationResult`

## 3. Stage-by-Stage Understanding

### Stage 0. Exploration

Implementation:
[ExplorationNode.java](/d:/myproject/math-vision/src/main/java/com/mathvision/node/ExplorationNode.java:49)

Responsibilities:
- Decide whether the input is `concept` or `problem`
- In `concept` mode, directly build a compact teaching DAG
- In `problem` mode, directly build a compact presentation-ready solving graph

Important details:
- `auto` mode first tries LLM classification, then falls back to heuristics
- Both modes now use one-shot graph planning rather than recursive prerequisite expansion
- Graph edge direction is:
  `node -> direct dependencies required before it`

### Stage 1a. Math Enrichment

Implementation:
[MathEnrichmentNode.java](/d:/myproject/math-vision/src/main/java/com/mathvision/node/MathEnrichmentNode.java:33)

Adds to each graph node:
- `equations`
- `definitions`
- `interpretation`
- `examples`

Purpose:
- Not full derivation
- Just enough math structure to support later visual teaching

### Stage 1b. Visual Design

Implementation:
[VisualDesignNode.java](/d:/myproject/math-vision/src/main/java/com/mathvision/node/VisualDesignNode.java:39)

Adds `visual_spec` to each node:
- `layout`
- `motion_plan`
- `scene_mode`
- `camera_plan`
- `screen_overlay_plan`
- `color_scheme`
- `duration`
- `color_palette`

Important details:
- Processes nodes from deepest prerequisites toward the conclusion
- Same-depth nodes may run in parallel
- Reuses prerequisite visual specs as context
- This is the first strong `manim` vs `geogebra` divergence point

### Stage 1c. Narrative

Implementation:
[NarrativeNode.java](/d:/myproject/math-vision/src/main/java/com/mathvision/node/NarrativeNode.java:39)

This stage is the real center of the pipeline.

It converts node-level information into a global `storyboard`.

The storyboard is the actual source of truth for code generation. It contains:
- scene breakdown
- object ids
- entering / persistent / exiting objects
- actions
- layout goals
- safe-area plans
- screen-overlay plans
- geometry constraints
- codegen notes

### Stage 2. Code Generation

Implementation:
[CodeGenerationNode.java](/d:/myproject/math-vision/src/main/java/com/mathvision/node/CodeGenerationNode.java:43)

Responsibilities:
- Compile storyboard into backend code
- Emit `manimCode` for Manim
- Emit `geogebraCode` for GeoGebra

Important details:
- Tool schema is selected by output target
- Generated code is immediately validated with backend-specific local rules
- Validation failures are routed to `CodeFixNode`

### Stage 3. Code Evaluation

Implementation:
[CodeEvaluationNode.java](/d:/myproject/math-vision/src/main/java/com/mathvision/node/CodeEvaluationNode.java:56)

This stage is not runtime debugging.

It is a pre-render storyboard-to-code alignment review.

Typical concerns:
- layout risk
- continuity risk
- pacing risk
- clutter risk
- offscreen risk
- whether storyboard constraints are actually implemented

Failures are routed to `CodeFixNode`.

### Stage 4. Render

Implementation:
[RenderNode.java](/d:/myproject/math-vision/src/main/java/com/mathvision/node/RenderNode.java:38)

Responsibilities:
- `manim`: render a video through the Manim CLI
- `geogebra`: validate commands in a headless GeoGebra applet and generate an HTML preview

This stage also produces geometry artifacts for the next stage.

### Stage 5. Scene Evaluation

Implementation:
[SceneEvaluationNode.java](/d:/myproject/math-vision/src/main/java/com/mathvision/node/SceneEvaluationNode.java:47)

Responsibilities:
- Read geometry output from render
- Evaluate post-render layout quality

Typical checks:
- overlap
- offscreen overflow
- blocking layout issues

Important difference:
- `manim` checks overlap and offscreen
- `geogebra` mainly cares about overlap, because the construction is zoomable and pannable

Failures are routed back into `CodeFixNode`, then back to render.

## 4. Manim-Specific Flow

### Input side

- user input
- `output_target = manim`

### Design side

- Exploration produces a concept graph or solving graph
- MathEnrichment adds math meaning
- VisualDesign creates animation-first visual intent
- Narrative merges everything into a storyboard

### Code side

- CodeGeneration compiles the storyboard into one Python file
- The scene class is normalized around `MainScene`
- The code must follow many Manim-specific rules:
  - safe frame
  - monospace text
  - fixed overlays
  - transform-based continuity
  - object lifecycle discipline
  - subtitle-ready beats

### Review side

- CodeEvaluation reviews storyboard vs code
- It checks likely visual quality before render:
  - camera clarity
  - label attachment
  - 3D readability
  - pacing
  - clutter

### Execution side

- Render uses:
  [ManimRendererService.java](/d:/myproject/math-vision/src/main/java/com/mathvision/service/ManimRendererService.java:26)
- It writes temporary code to `scene_render.py`
- It injects a geometry export helper
- It runs `manim render`
- On success it produces:
  - video output
  - `5_mobject_geometry.json`

### Post-render evaluation side

- SceneEvaluation reads sampled geometry
- It checks:
  - text-text overlap
  - text-geometry overlap
  - offscreen overflow
- If necessary it builds a scene-evaluation fix request and re-enters `CodeFixNode`

### One-line Manim summary

`math structure -> animation storyboard -> Python/Manim implementation -> video render -> geometry inspection -> automatic repair`

## 5. GeoGebra-Specific Flow

### Input side

- user input
- `output_target = geogebra`

### Design side

The early stages are shared:
- Exploration
- MathEnrichment
- VisualDesign
- Narrative

But the design language changes:
- less animation staging
- more stable construction planning
- more label-placement awareness
- more dependency-safe geometry
- more focus on "constructible and draggable under constraints"

### Code side

- CodeGeneration emits GeoGebra commands, not Python
- The artifact field is `geogebraCode`
- Object naming follows construction identity more closely
- Scene directives are added so the preview can switch visible objects by scene

### Review side

- CodeEvaluation does not focus on camera or animation quality
- It focuses on:
  - whether required constructions really exist
  - whether dependency relations are correct
  - whether scene visibility progression matches the storyboard
  - whether text is being used as a fake substitute for actual geometry

### Execution side

- Render uses:
  [GeoGebraRenderService.java](/d:/myproject/math-vision/src/main/java/com/mathvision/service/GeoGebraRenderService.java:34)
- This does not render a video
- It:
  - writes `5_geogebra_preview.html`
  - loads a GeoGebra applet in a headless browser
  - replays the command script
  - writes a validation report
  - writes a geometry report

Main output artifacts:
- `5_geogebra_preview.html`
- `5_geogebra_validation.json`
- `5_geogebra_geometry.json`

### Post-render evaluation side

- SceneEvaluation reads GeoGebra geometry
- It focuses on text overlap and initial-view readability
- Offscreen is not treated as the same kind of blocker as in Manim

### One-line GeoGebra summary

`math structure -> interactive construction storyboard -> GeoGebra command script -> applet validation/preview -> initial-layout inspection -> automatic repair`

## 6. Core Differences Between Manim And GeoGebra

### Shared backbone

Both backends share:
- input classification
- graph building
- math enrichment
- visual design
- storyboard narrative
- code generation
- code evaluation
- render
- scene evaluation
- shared repair routing

### Real divergence points

1. `VisualDesignPrompts`
- Manim: cinematic teaching animation
- GeoGebra: interactive construction planning

2. `NarrativePrompts`
- Manim: stronger execution and animation language
- GeoGebra: stronger construction order and visibility semantics

3. `CodeGenerationPrompts`
- Manim: Python + scene class
- GeoGebra: command script + figure semantics

4. `CodeEvaluationPrompts`
- Manim: continuity / pacing / overlay / motion readability
- GeoGebra: construction fidelity / visibility progression / object existence

5. `Render`
- Manim: CLI video render
- GeoGebra: applet validation + HTML preview

6. `SceneEvaluation`
- Manim: overlap + offscreen
- GeoGebra: mostly overlap

## 7. What This Means For Prompt Tuning

Best tuning order:

1. `NarrativePrompts`
- because storyboard is the central intermediate representation
- improvements here benefit both backends

2. `VisualDesignPrompts`
- because they shape node-level visual intent
- but they only become reliable when storyboard structure is clear

3. `CodeGenerationPrompts`
- this is the backend translation layer
- best place for backend-specific hard constraints

4. `CodeEvaluationPrompts` and `SceneEvaluationPrompts`
- these are closer to the "closing loop"
- they improve safety and repair quality, but they cannot fully compensate for a weak storyboard

## 8. Final Judgment

The most accurate abstraction for this project is not:
- Auto Manim
- prompt playground
- math animation generator

It is:

`a storyboard-centered math teaching scene compiler`

That is why the best naming direction is:

`MathScene Compiler`
