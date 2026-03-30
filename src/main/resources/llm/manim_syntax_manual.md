# Manim Usage Guide

Please strictly use common, stable, and highly readable **Manim Community** patterns when generating animation code. The code should prioritize clarity, stability, and ease of rendering first, and only then pursue flashy effects.

## I. Scene Types

### 1. `Scene`

Used for the vast majority of **2D instructional animations**, for example:

* title pages
* formula derivations
* plane geometry
* coordinate systems and function graphs
* flowcharts, block diagrams, and summary pages

Common pattern:

```python
class MyScene(Scene):
    def construct(self):
        ...
```

### 2. `ThreeDScene`

Used for **3D animations**, for example:

* surfaces
* solid geometry
* polyhedra
* 3D trajectories
* 3D coordinate systems
* camera-motion demonstrations

Common pattern:

```python
class MyScene(ThreeDScene):
    def construct(self):
        ...
```

If the scene contains `Surface`, `Dot3D`, `ThreeDAxes`, solid objects, camera rotation, or similar content, prefer using `ThreeDScene`.

---

## II. Basic Object System

Elements on the screen in Manim are essentially all **Mobjects**.
Always follow this way of thinking:

1. Create the object first.
2. Then set its position.
3. Then set its style.
4. Finally play the animation.

Do not cram "creation, positioning, animation, and cleanup" into one extremely complicated line.

---

## III. Common Object Classes and Their Uses

## 1. Basic Geometric Objects

### `Dot`

Used for:

* points
* nodes
* key positions
* coordinate points

```python
dot = Dot()
dot = Dot(point=ORIGIN, color=YELLOW)
```

### `Line`

Used for:

* line segments
* connecting lines
* construction helper lines

```python
line = Line(LEFT, RIGHT)
```

### `Arrow`

Used for:

* vectors
* indicating direction
* flow relationships

```python
arrow = Arrow(LEFT, RIGHT)
```

### `DashedLine`

Used for:

* dashed helper lines
* projection lines
* geometric construction lines

```python
dashed = DashedLine(LEFT, RIGHT)
```

Compatibility rule:

* If you need a dashed segment, prefer `DashedLine` directly.
* Do not assume generic mobjects support dashed styling through `set_stroke(...)`.
* In the current environment, do not invent or rely on arguments such as `dash_array`, `dash_length`, or other unlisted dash-related keyword arguments for `Line`, `Polygon`, `VMobject`, or `set_stroke(...)`.
* If you need a dashed polygonal boundary, build it from multiple `DashedLine` segments instead of styling a `Polygon` with unsupported dash arguments.

### `Circle`, `Square`, `Rectangle`, `Triangle`, `Polygon`

Used for:

* basic shapes
* geometric objects
* emphasis boxes
* plane polygons

```python
circle = Circle()
square = Square()
rect = Rectangle(width=4, height=2)
triangle = Triangle()
poly = Polygon(LEFT, RIGHT, UP)
```

### `SurroundingRectangle`

Used for:

* highlighting titles
* highlighting formulas
* highlighting conclusions
* boxing a specific object

```python
box = SurroundingRectangle(obj, color=YELLOW, buff=0.2)
```

### `Angle`

Used for:

* showing an angle
* angle markers in plane geometry

```python
angle = Angle(line1, line2, radius=0.5)
```

Angle safety rules:

* Prefer `Angle(line1, line2, ...)` over manually constructing `Arc(start_angle=..., angle=...)` for geometric angle markers.
* The two inputs should be the actual rays that define the angle and should share the same vertex.
* Build each ray so its first point is the vertex and its second point lies on the intended side of the angle. Do not pass a long line that visually continues through the vertex and creates ambiguity about which sector should be marked.
* For moving geometry, rebuild those defining rays from the shared vertex inside the same `always_redraw(...)` lambda that creates the `Angle(...)`.
* When the intended mark is the smaller interior angle, explicitly keep `other_angle=False` instead of relying on implicit defaults.
* When several sectors are possible, especially around a normal, reflection line, or crossing helper lines, explicitly set `quadrant=...` so the arc stays in the intended region as points move.
* Do not fake angle placement by drawing a free-floating arc and shifting or rotating it into place after the fact; this often puts the arc on the wrong side of the vertex.
* If the angle is meant to compare two small equal angles, do not draw a near-full circle or large exterior angle around the vertex.

Recommended pattern for a dynamic angle at point `P`:

```python
angle = always_redraw(
    lambda: Angle(
        Line(P.get_center(), A.get_center()),
        Line(P.get_center(), B.get_center()),
        radius=0.4,
        other_angle=False,
        color=PURE_CYAN,
    )
)
```

Recommended pattern when measuring against a normal or helper ray through the same point:

```python
angle_in = always_redraw(
    lambda: Angle(
        Line(P.get_center(), P.get_center() + UP),
        Line(P.get_center(), A.get_center()),
        radius=0.35,
        quadrant=(-1, 1),
        other_angle=False,
        color=PURE_CYAN,
    )
)

angle_out = always_redraw(
    lambda: Angle(
        Line(P.get_center(), P.get_center() + UP),
        Line(P.get_center(), B.get_center()),
        radius=0.35,
        quadrant=(1, 1),
        other_angle=False,
        color=PURE_CYAN,
    )
)
```

Interpretation note:

* If `A` is above-left of `P` and `B` is above-right of `P`, then the two desired equal angles with an upward normal usually live in the upper-left and upper-right sectors. In that common case, `quadrant=(-1, 1)` and `quadrant=(1, 1)` are safer than leaving the sector implicit.

### `RightAngle`

Used for:

* showing a right-angle marker

```python
ra = RightAngle(line1, line2, length=0.2)
```

---

## 2. Text and Formula Objects

### `Text`

Used for plain text:

* titles
* subtitles
* explanations
* labels
* summary statements

```python
title = Text("Dot Product", font_size=48)
```

Use `Text` only for ordinary language.
Do not put LaTeX commands such as `\approx`, `\geq`, `\frac`, superscripts, subscripts, or math-mode syntax into `Text`.

### `MarkupText`

Used for text with rich-text styling, but only when it is truly needed.

```python
txt = MarkupText('<span fgcolor="YELLOW">Important</span>')
```

### `MathTex`

Used for mathematical formulas:

* single variables
* equations
* derivation steps
* geometric labels
* parameter displays

```python
eq = MathTex(r"\vec{a}\cdot\vec{b}=|a||b|\cos\theta")
```

Recommended practice:

* Prefer `MathTex` for mathematical content.
* If partial coloring is needed, split it into multiple segments.
* If the content contains LaTeX commands or mathematical notation, use `MathTex` rather than `Tex` or `Text`.
* Labels like `A'`, `B'`, `A_1`, `x^2`, `AP + PB`, and inequalities should be written with `MathTex`.
* When writing strings containing backslashes, prefer raw strings such as `r"\geq"` to reduce escaping mistakes.

```python
eq = MathTex(r"V", r"-", r"E", r"+", r"F", r"=", r"2")
eq[0].set_color(BLUE)
eq[2].set_color(YELLOW)
eq[4].set_color(GREEN)
```

Common safe examples:

```python
label = MathTex("A'")
ineq = MathTex(r"AP + PB \geq A'B")
value = VGroup(Text("Minimum length:"), MathTex(r"A'B")).arrange(RIGHT, buff=0.2)
```

### `Tex`

Use `Tex` only for plain LaTeX text when mathematical layout is not the focus.

```python
subtitle = Tex(r"Reflect point A across line l")
```

Safety rules:

* Do not mix plain-text narration with mathematical symbols in a way that depends on implicit math mode.
* If there is any doubt whether a string is "text" or "math", prefer `MathTex` for the mathematical part or split the content into `Text(...)` plus `MathTex(...)` inside a `VGroup`.
* Avoid patterns like `Tex("AP + PB \\approx")`; this is fragile and can produce LaTeX compilation errors. Use `MathTex(r"AP + PB \\approx")` instead.

---

## 3. Grouping Objects

### `VGroup`

Used to package multiple objects into a single whole, making it easy to:

* move them together
* fade them out together
* lay them out together
* transform them together

```python
group = VGroup(title, subtitle, formula)
```

Common scenarios:

* title groups
* label groups
* list groups
* vertex groups
* edge groups
* face groups
* combinations of multiple curve segments or multiple patches

---

## 4. Coordinate Systems and Graph Objects

### `Axes`

Used for:

* function graphs
* coordinate-system explanations
* analytic geometry
* moving-point problems
* derivative/integral visualization

```python
ax = Axes(
    x_range=[-3, 3, 1],
    y_range=[-2, 4, 1],
    x_length=6,
    y_length=5,
)
```

Common companion usage:

#### `c2p`

Convert mathematical coordinates into scene coordinates:

```python
dot = Dot(ax.c2p(2, 3))
```

#### `plot`

Draw a function graph on the axes:

```python
graph = ax.plot(lambda x: x**2, color=BLUE)
```

### `NumberPlane`

Used for:

* grid backgrounds
* plane-geometry illustrations
* vector problems
* moving-point problems in the plane

```python
plane = NumberPlane()
```

Principles:

* Prefer `Axes` when discussing functions and coordinates.
* Prefer `NumberPlane` when showing planar relationships and grid backgrounds.

---

## 5. Curve Objects

### `VMobject`

Used for:

* custom paths
* smooth trajectories
* spatial trajectories
* complex curve segments

```python
curve = VMobject()
curve.set_points_smoothly([LEFT, UP, RIGHT])
curve.set_stroke(BLUE, width=3)
```

Suitable for:

* trajectory-style animations
* curves that require manually specified points
* multi-segment smooth paths

---

## 6. 3D Objects

### `Dot3D`

Used for:

* 3D points
* vertices
* starfield particles
* highlighted points in 3D

```python
dot3d = Dot3D(point=ORIGIN, color=YELLOW)
```

### `ThreeDAxes`

Used for:

* 3D coordinate systems
* reference axes for spatial curves or surfaces

```python
axes3d = ThreeDAxes()
```

### `Surface`

Used for parametric surfaces:

* spheres
* wave surfaces
* paraboloids
* various parameterized 3D surfaces

```python
surface = Surface(
    lambda u, v: np.array([u, v, np.sin(u) * np.cos(v)]),
    u_range=[-3, 3],
    v_range=[-3, 3],
    resolution=(16, 16),
)
```

Usage principles:

* `Surface` accepts a function of the form `(u, v) -> (x, y, z)`.
* It is suitable for parametric surfaces.
* Use a lower resolution while debugging, then increase it for the final version.

### Common Standard 3D Objects

Use as needed:

* `Sphere`
* `Cube`
* `Cone`
* `Torus`
* `Tetrahedron`

Use them only when a standard solid is truly needed, and avoid meaningless piling on.

---

## IV. Positioning and Layout

When generating code, the layout must be clear. Do not hardcode large numbers of arbitrary coordinates.

## 1. Absolute Positioning

### `move_to`

Move an object to a point:

```python
obj.move_to(ORIGIN)
```

### `shift`

Relative translation:

```python
obj.shift(RIGHT * 2 + UP)
```

### `to_edge`

Place against the edge of the screen:

```python
title.to_edge(UP)
```

### `to_corner`

Place in a corner of the screen:

```python
title.to_corner(UL)
```

---

## 2. Relative Layout

### `next_to`

The most commonly used method. Used to place labels, explanatory text, or formulas relative to another object:

```python
label.next_to(dot, RIGHT, buff=0.2)
```

### `align_to`

Used to align edges:

```python
obj2.align_to(obj1, LEFT)
```

### `match_height`, `match_width`

Used to make objects consistent in size.

---

## 3. Automatic Arrangement

### `arrange`

Highly recommended. Suitable for:

* multi-line text
* summary pages
* titles and subtitles
* bullet-point lists

```python
items = VGroup(
    Text("Point 1"),
    Text("Point 2"),
    Text("Point 3"),
).arrange(DOWN, buff=0.3, aligned_edge=LEFT)
```

Principles:

* For multiple parallel text objects, prefer `VGroup(...).arrange(...)`.
* Avoid manually writing `move_to` one by one.

---

## V. Style Settings

## 1. Basic Style Functions

### Whitelisted Color Constants

When generating code, use only the following known-safe Manim color constants from the current environment.

Base colors:

* `BLACK`
* `WHITE`
* `BLUE`
* `GREEN`
* `YELLOW`
* `RED`
* `PURPLE`
* `PINK`
* `ORANGE`
* `TEAL`
* `GOLD`
* `MAROON`
* `GRAY`
* `GREY`
* `DARK_BLUE`
* `DARK_BROWN`
* `DARK_GRAY`
* `DARK_GREY`
* `DARKER_GRAY`
* `DARKER_GREY`
* `LIGHT_BROWN`
* `LIGHT_GRAY`
* `LIGHT_GREY`
* `LIGHTER_GRAY`
* `LIGHTER_GREY`
* `LIGHT_PINK`
* `GRAY_BROWN`
* `GREY_BROWN`

Variant families:

* `BLUE_A`, `BLUE_B`, `BLUE_C`, `BLUE_D`, `BLUE_E`
* `GREEN_A`, `GREEN_B`, `GREEN_C`, `GREEN_D`, `GREEN_E`
* `YELLOW_A`, `YELLOW_B`, `YELLOW_C`, `YELLOW_D`, `YELLOW_E`
* `RED_A`, `RED_B`, `RED_C`, `RED_D`, `RED_E`
* `PURPLE_A`, `PURPLE_B`, `PURPLE_C`, `PURPLE_D`, `PURPLE_E`
* `TEAL_A`, `TEAL_B`, `TEAL_C`, `TEAL_D`, `TEAL_E`
* `GOLD_A`, `GOLD_B`, `GOLD_C`, `GOLD_D`, `GOLD_E`
* `MAROON_A`, `MAROON_B`, `MAROON_C`, `MAROON_D`, `MAROON_E`
* `GRAY_A`, `GRAY_B`, `GRAY_C`, `GRAY_D`, `GRAY_E`
* `GREY_A`, `GREY_B`, `GREY_C`, `GREY_D`, `GREY_E`

Pure colors:

* `PURE_RED`
* `PURE_GREEN`
* `PURE_BLUE`
* `PURE_YELLOW`
* `PURE_CYAN`
* `PURE_MAGENTA`

Logo colors:

* `LOGO_BLACK`
* `LOGO_WHITE`
* `LOGO_BLUE`
* `LOGO_GREEN`
* `LOGO_RED`

Use only names from this whitelist when assigning Manim colors.

### `set_color`

Set the overall color:

```python
obj.set_color(BLUE)
```

### `set_fill`

Set fill color and opacity:

```python
obj.set_fill(BLUE, opacity=0.6)
```

### `set_stroke`

Set stroke color, width, and opacity:

```python
obj.set_stroke(WHITE, width=2, opacity=1)
```

Compatibility rules:

* Treat `color`, `width`, and `opacity` as the default safe arguments.
* Do not assume undocumented keyword arguments are supported in the current Manim version.
* In particular, do not use `dash_array=...` with `set_stroke(...)`.
* To create dashed geometry, prefer dedicated dashed mobjects such as `DashedLine`.

### `set_opacity`

Set overall opacity:

```python
obj.set_opacity(0.5)
```

### `scale`

Scale the object's size:

```python
obj.scale(1.2)
```

---

## 2. Style Usage Suggestions

* Use **semantic color schemes** for vertices, edges, and faces whenever possible.
* Objects of the same category should use the same color whenever possible.
* Important objects should be brighter, less transparent, and have thicker lines.
* Secondary objects can use lower opacity.
* Do not use highly saturated colors everywhere in the scene.
* Do not let subtitles compete with the main figure for visual focus.

---

## VI. Animation Functions and Their Common Uses

## 1. Entrance Animations

### `Create`

Used for:

* lines
* circles
* polygons
* axes
* curves
* image outlines

```python
self.play(Create(circle))
```

### `Write`

Used for:

* titles
* text
* mathematical formulas

```python
self.play(Write(title))
```

### `FadeIn`

Used for:

* labels
* images
* text
* making an already-prepared object appear as a whole

```python
self.play(FadeIn(obj))
```

### `GrowFromCenter`

Used for:

* points
* small shapes
* local components
* a central expansion feeling of "appearing from nothing"

```python
self.play(GrowFromCenter(dot))
```

### `DrawBorderThenFill`

Used for:

* closed shapes with borders and fill
* title boxes
* highlight boxes

```python
self.play(DrawBorderThenFill(box))
```

---

## 2. Exit Animations

### `FadeOut`

Used for normal fading out:

```python
self.play(FadeOut(obj))
```

### `Uncreate`

Used for reverse "erasing-style" disappearance, suitable for lines and geometric objects:

```python
self.play(Uncreate(line))
```

---

## 3. Transform Animations

### `Transform`

Used for:

* shrinking a title and moving it to a corner
* updating a formula
* turning one object into another
* deforming a surface or geometric solid

```python
self.play(Transform(old_obj, new_obj))
```

### `ReplacementTransform`

Used for:

* one object exiting and being replaced by another
* making scene replacement feel more natural

```python
self.play(ReplacementTransform(obj1, obj2))
```

Principles:

* For simple replacements, prefer `Transform` / `ReplacementTransform`.
* If two objects differ too much in structure, you can also directly use `FadeOut` + `FadeIn`.

---

## 4. Emphasis Animations

### `Flash`

Used for:

* flashing to emphasize a point
* point-by-point counting
* emphasizing a vertex or key position

```python
self.play(Flash(dot, color=YELLOW))
```

### `Indicate`

Used for:

* highlighting a specific object

```python
self.play(Indicate(formula))
```

### `Circumscribe`

Used for:

* circling a formula
* circling a conclusion
* emphasizing a block of content

```python
self.play(Circumscribe(eq))
```

### `FocusOn`

Used for:

* a camera-like focus on a certain position

```python
self.play(FocusOn(dot))
```

---

## 5. Combined Animations

### `AnimationGroup`

Multiple animations in parallel:

```python
self.play(AnimationGroup(
    FadeIn(a),
    FadeIn(b),
    FadeIn(c),
))
```

### `LaggedStart`

Appear in a staggered sequence, suitable for lists, batches of points, or batches of lines:

```python
self.play(LaggedStart(
    *[FadeIn(obj) for obj in objs],
    lag_ratio=0.2
))
```

### `Succession`

Animations chained in sequence:

```python
self.play(Succession(
    Write(title),
    FadeIn(subtitle),
    Create(graph),
))
```

---

## VII. `.animate` Usage

This is one of the syntax patterns most recommended for LLMs to prefer.

Suitable for simple property changes:

```python
self.play(obj.animate.shift(RIGHT * 2))
self.play(obj.animate.scale(0.5))
self.play(obj.animate.set_color(RED))
self.play(obj.animate.set_opacity(0.3))
self.play(title.animate.to_corner(UL))
```

Applicable scenarios:

* translation
* scaling
* recoloring
* changing opacity
* slight position adjustments
* tucking away a title

Principles:

* Prefer `.animate` for simple changes.
* Prefer `Transform` for complex replacements.

---

## VIII. Dedicated 3D Scene Usage

If using `ThreeDScene`, be comfortable with the following methods.

## 1. Camera Settings

### `set_camera_orientation`

Set the initial viewing angle:

```python
self.set_camera_orientation(
    phi=70 * DEGREES,
    theta=-45 * DEGREES,
    zoom=0.8
)
```

### `move_camera`

Move the camera:

```python
self.move_camera(
    phi=60 * DEGREES,
    theta=30 * DEGREES,
    zoom=1.0,
    run_time=2
)
```

### `begin_ambient_camera_rotation`

Start slow automatic camera rotation:

```python
self.begin_ambient_camera_rotation(rate=0.1)
```

### `stop_ambient_camera_rotation`

Stop automatic rotation:

```python
self.stop_ambient_camera_rotation()
```

Applicable for:

* showcasing solid figures
* observing surfaces
* explaining polyhedron rotation
* displaying 3D trajectories

---

## 2. Fixed Screen Objects

### `add_fixed_in_frame_mobjects`

Used to keep subtitles, formulas, and titles fixed in a 3D scene so that they do not rotate with the camera:

```python
title = Text("Gyroid Surface")
self.add_fixed_in_frame_mobjects(title)
```

This is very important in 3D instructional animations.
Principles:

* Titles, subtitles, and formulas are usually fixed on the screen layer.
* 3D geometric objects belong on the world layer.

---

## IX. Dynamic Update Mechanisms

## 1. `always_redraw`

Used to regenerate an object every frame, suitable for:

* moving-point labels
* dynamic connecting lines
* dynamic projection lines
* dynamic tangents
* dynamic auxiliary graphics

```python
label = always_redraw(lambda: Text("A").next_to(dot, UP))
line = always_redraw(lambda: Line(dot1.get_center(), dot2.get_center()))
```

Principles:

* Prefer it for objects that "depend on position changes of other objects."
* If a point, endpoint, vertex, or marker moves, then its label must also be dynamic. Do not write a one-time `label = Text(...).next_to(dot, ...)` and then animate the dot separately.
* For moving geometry, labels should usually use `always_redraw(lambda: MathTex(...).next_to(moving_obj, ...))` or `always_redraw(lambda: Text(...).next_to(moving_obj, ...))`.
* The same rule applies to helper lines, braces, angle markers, and any annotation whose placement depends on a moving object.
* If a label should appear only after a point finishes moving, either create the label after the motion has finished, or still use `always_redraw`. Do not precompute `next_to(...)` before the move and merely `FadeIn` it later, because that keeps the old coordinates.
* Do not abuse it for large and complex static objects.

---

## 2. `ValueTracker`

Used to store numerical values that can change through animation, often in combination with `always_redraw`:

```python
x_tracker = ValueTracker(-2)
self.play(x_tracker.animate.set_value(2), run_time=4)
```

Applicable for:

* the x-coordinate of a moving point
* parameter changes
* angle changes
* length changes
* function-parameter visualization

---

## 3. `TracedPath`

Used to trace the trajectory of a point's motion:

```python
path = TracedPath(dot.get_center, stroke_color=YELLOW, stroke_width=4)
```

Applicable for:

* trajectory-style animations
* moving-point problems
* phase portraits
* particle motion

---

## X. Code Organization Suggestions

For longer animations, do not pile all logic into `construct()`.

Recommended:

```python
class MyScene(Scene):
    def construct(self):
        self.intro()
        self.main_part()
        self.summary()

    def intro(self):
        ...

    def main_part(self):
        ...

    def summary(self):
        ...
```

Applicable for:

* multi-section animations
* staged instructional explanations
* long 3D demonstrations

Principles:

* Let `construct()` keep only the overall flow.
* Split concrete content into helper functions.
* Creation of complex objects can be written separately as `create_xxx()` functions.

---

## XI. Recommended Principles When Generating Manim Code

1. Prefer common and stable objects and animations; do not use obscure and complex patterns just to be flashy.
2. Prioritize clean layout and clear information hierarchy.
3. Prefer `VGroup`, `arrange`, `next_to`, and `to_edge` for layout.
4. Use `MathTex` for mathematical formulas and `Text` for ordinary text.
5. Prefer `.animate` for simple changes, and `Transform` for complex object replacement.
6. In 3D scenes, prefer `add_fixed_in_frame_mobjects` to keep text and formulas fixed.
7. Prefer `always_redraw` for dynamic dependency relationships.
8. Use lower resolution for surfaces and 3D objects during debugging.
9. Do not hardcode large numbers of absolute coordinates; prefer relative layout.
10. Whenever possible, let colors, animation order, and object appearance order carry instructional meaning.

---
