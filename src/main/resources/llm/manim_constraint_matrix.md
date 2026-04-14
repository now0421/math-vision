# Manim Constraint Matrix

This file is the source-of-truth mapping between the upstream `manim-video`
skill reference and MathVision's Manim prompt pipeline.

Each upstream source document is mapped into one or more of these destinations:

- `prompt-stage rule`: stage-specific prompt text
- `shared backend rule`: shared Manim-only prompt fragments in `SystemPrompts`
- `manual/style-reference rule`: `manim_syntax_manual.md` or
  `manim_style_reference.md`

The GeoGebra pipeline intentionally does not import Manim-only animation
constraints from this matrix.

## SKILL.md

- Educational cinema mindset -> `shared backend rule`
- Narrative arc before code -> `shared backend rule`, Stage 0, Stage 1a, Stage 1c
- Geometry before algebra -> `shared backend rule`, Stage 1b, Stage 1c
- First-render excellence -> Stage 3, Stage 5
- Opacity layering -> `manual/style-reference rule`, Stage 1b, Stage 5
- Breathing room after reveals -> `shared backend rule`, Stage 1c, Stage 2, Stage 3
- Cohesive visual language across scenes -> Stage 1b, Stage 1c, Stage 2
- Shared color constants / palette meaning -> `manual/style-reference rule`,
  Stage 1b, Stage 2

## scene-planning.md

- Discovery arc / problem-solution arc / comparison arc / build-up arc ->
  Stage 0, Stage 1c
- Scene purpose, duration, layout, animation sequence ->
  `prompt-stage rule`, Stage 1c
- Clean break / carry-forward / transform bridge transitions ->
  `shared backend rule`, Stage 1c, Stage 2, Stage 5
- Cross-scene consistency -> Stage 1b, Stage 1c, Stage 2
- Scene checklist -> Stage 3, Stage 5
- Duration estimation -> Stage 1c

## visual-design.md

- Geometry before algebra -> `shared backend rule`, Stage 1b
- Opacity layering -> `manual/style-reference rule`, Stage 1b, Stage 5
- One new idea per scene -> Stage 1b, Stage 1c, Stage 3
- Spatial consistency -> Stage 1b, Stage 1c, Stage 5
- Color equals meaning -> `manual/style-reference rule`, Stage 1b, Stage 2
- Progressive disclosure -> Stage 1b, Stage 1c, Stage 2
- Transform, do not replace -> Stage 1b, Stage 2, Stage 3
- Breathing room -> Stage 1c, Stage 2, Stage 3
- Visual weight balance -> Stage 1b, Stage 5
- Intentional empty space -> `manual/style-reference rule`, Stage 1b, Stage 5
- Layout templates -> Stage 1b, Stage 1c
- Font policy / minimum readable size -> `manual/style-reference rule`,
  Stage 1b, Stage 2, Stage 3
- Visual hierarchy checklist -> Stage 3, Stage 5

## animation-design-thinking.md

- Animate only when motion clarifies -> Stage 1b, Stage 1c
- Show static when motion distracts -> Stage 1b, Stage 1c
- Narration first -> Stage 1a, Stage 1c
- Narration sentences map to visual beats -> Stage 1c
- One beat is one play group -> Stage 1c, Stage 2
- Tool-per-beat taxonomy -> Stage 2
- Pacing rules and tempo variation -> Stage 1c, Stage 2, Stage 3
- See-then-hear synchronization -> Stage 1c, Stage 2
- Equation dim-and-reveal pattern -> Stage 1a, Stage 1c, Stage 2
- Common design mistakes -> Stage 3

## production-quality.md

- Narration script before code -> Stage 1a, Stage 1c
- Scene list with purpose, duration, layout -> Stage 1c
- Color palette and font constants -> Stage 1b, Stage 2
- Text overlap prevention -> Stage 1b, Stage 2, Stage 3, Stage 5
- Width enforcement / readable text bounds -> Stage 2, Stage 3, Stage 5
- Font consistency -> `manual/style-reference rule`, Stage 1b, Stage 2, Stage 3
- Coordinate budget / safe zones -> Stage 1b, Stage 1c, Stage 5
- Max simultaneous elements -> Stage 1b, Stage 1c, Stage 3
- Transition quality / clean exits -> Stage 1c, Stage 2, Stage 5
- Color meaning consistency -> Stage 1b, Stage 2
- Pre-render / post-render checklist -> Stage 3, Stage 5

## updaters-and-trackers.md

- Declare continuous relationships once -> `shared backend rule`, Stage 2
- ValueTracker three-step pattern -> `manual/style-reference rule`, Stage 2
- `add_updater` vs `always_redraw` boundary -> `manual/style-reference rule`,
  Stage 2, Stage 3
- DecimalNumber and Variable patterns -> Stage 2
- Remove / suspend updaters when needed -> Stage 2, Stage 4
- Common updater mistakes -> Stage 3, Stage 4

## animations.md

- Explicit animation taxonomy -> Stage 2
- `Transform` / `ReplacementTransform` / `FadeTransform` semantics -> Stage 2, Stage 3
- `.animate` chaining rules -> `manual/style-reference rule`, Stage 2, Stage 4
- Movement / emphasis / composition patterns -> Stage 2
- Subcaptions -> Stage 2, Stage 3
- Timing patterns and clean exits -> Stage 2, Stage 3, Stage 5
- `always_redraw`, `TracedPath`, `ShowPassingFlash` patterns -> Stage 2

## mobjects.md

- Text / MathTex / MarkupText roles -> Stage 2
- Shapes, polygons, sectors, arcs -> `manual/style-reference rule`, Stage 2
- Positioning helpers -> Stage 2, Stage 5
- `Group` versus `VGroup` -> `manual/style-reference rule`, Stage 2, Stage 3, Stage 4
- Specialized mobjects and chart helpers -> Stage 2
- Backstroke / readability over busy backgrounds -> Stage 2, Stage 3, Stage 5

## equations.md

- Raw LaTeX strings -> `manual/style-reference rule`, Stage 2, Stage 4
- Step-by-step derivation pacing -> Stage 1a, Stage 2
- Selective color / highlighting -> Stage 2
- `TransformMatchingTex` patterns -> Stage 2, Stage 3
- Matrix / cases / aligned environments -> `manual/style-reference rule`, Stage 2
- `substrings_to_isolate`, `matched_keys`, `key_map` -> Stage 2

## graphs-and-data.md

- Axes and graph styling -> Stage 2
- Dynamic plotting and trackers -> Stage 2
- Bar charts, number lines, counters -> Stage 2
- Algorithm/data story patterns -> Stage 1b, Stage 2
- Graph / DiGraph / vector field / complex plane patterns -> Stage 2

## camera-and-3d.md

- `MovingCameraScene` guidance -> Stage 2
- `ThreeDScene` guidance -> `manual/style-reference rule`, Stage 2, Stage 3
- Fixed-in-frame overlays in 3D -> Stage 2, Stage 3
- When to use or avoid 3D -> Stage 1b, Stage 1c
- ZoomedScene and linear transformation patterns -> Stage 2

## rendering.md

- Quality presets and text-preview guidance -> Stage 3
- Render workflow discipline -> Stage 3, Stage 5
- Section markers and voiceover are optional workflow techniques ->
  mapped as "not mandatory in current pipeline"
- Subtitle generation support -> Stage 2, Stage 3

## troubleshooting.md

- Raw string errors -> Stage 4, Stage 3
- VGroup TypeError and Group limitations -> Stage 3, Stage 4
- Transform confusion -> Stage 3, Stage 4
- Duplicate animation and updater conflicts -> Stage 3, Stage 4
- Layout crowding / no breathing room / missing background -> Stage 3, Stage 5
- Debugging strategies are "not mandatory in current pipeline"

## paper-explainer.md

- Video explains why, not just what -> Stage 0, Stage 1a, Stage 1c
- Audience controls pacing and notation depth -> Stage 0, Stage 1b, Stage 1c
- Hook / problem / key insight / method / evidence / implication template ->
  Stage 0, Stage 1c
- What to skip versus expand -> Stage 1a, Stage 1c
- Equation reveal after intuition -> Stage 1a, Stage 1c, Stage 2
- Architecture build-up and data-flow patterns -> Stage 1b, Stage 2
- Common paper-explainer mistakes -> Stage 3

## decorations.md

- SurroundingRectangle / brace / arrow / dashed-line roles -> Stage 2
- BackgroundRectangle / text backstroke -> Stage 2, Stage 3, Stage 5
- Annotation lifecycle: appear, hold, disappear -> Stage 1c, Stage 2, Stage 5
- Cross / underline / layered highlighting -> Stage 2
