# Manim Community Syntax Manual

This manual targets **Manim Community Edition (CE)**.

- Use `from manim import *`.
- Use CLI `manim`.
- Do not use ManimGL / 3Blue1Brown syntax such as `from manimlib import *` or CLI `manimgl`.
- Prefer official CE syntax, stable built-ins, and clear educational layouts over flashy tricks.

## Code Organization

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

- Keep `construct()` focused on scene flow.
- Move repeated or complex object-building logic into helper methods.

## Recommended Rules

1. Prefer common and stable objects and animations.
2. Prioritize clean layout and clear information hierarchy.
3. Prefer `VGroup`, `arrange`, `next_to`, `align_to`, and `to_edge` for layout.
4. Use `MathTex` for formulas and `Text` for plain language.
5. Prefer `.animate` for simple state changes and `Transform`/`ReplacementTransform` for structural replacement.
6. Use `TransformMatchingTex` with `{{...}}` or `substrings_to_isolate` for derivations.
7. In 3D scenes, keep overlays fixed in frame.
8. Use lower surface resolution while debugging.
9. Prefer `add_updater` for cheap dependency tracking and `always_redraw` only when structure changes.
10. Use `-pql` for development, `-qm` for text checks, and `-qh` for final output.

## Global Rules

- Create objects first, then position them, then style them, then animate them.
- Prefer relative layout (`arrange`, `next_to`, `align_to`, `to_edge`) before hardcoded coordinates.
- Use `Scene` for ordinary 2D, `MovingCameraScene` for zoom/pan, `ThreeDScene` for actual 3D, `ZoomedScene` for inset zoom, and `LinearTransformationScene` for matrix/grid transforms.
- Use `MathTex` for mathematical notation and `Text`/`MarkupText` for plain language.
- Prefer common built-ins over obscure patterns.
- In 3D scenes, keep labels and formulas readable with `add_fixed_in_frame_mobjects(...)`.

## Scene Structure

Use this section as the minimal scene-level context before reading the core syntax blocks.

### Scene Classes

```python
class MyScene(Scene): ...
class MyScene(MovingCameraScene): ...
class MyScene(ThreeDScene): ...
class MyScene(ZoomedScene): ...
class MyScene(LinearTransformationScene): ...
```

Use:

- `Scene`: standard 2D scenes.
- `MovingCameraScene`: 2D zoom/pan.
- `ThreeDScene`: surfaces, 3D axes, solid objects, 3D camera motion.
- `ZoomedScene`: inset zoom box plus zoomed display.
- `LinearTransformationScene`: matrix and basis-vector visuals.

Example:

```python
class LinearTransformDemo(LinearTransformationScene):
    def __init__(self, **kwargs):
        super().__init__(show_coordinates=True, show_basis_vectors=True, **kwargs)

    def construct(self):
        vector = self.get_vector([1, 2], color=YELLOW)
        self.add_vector(vector)
        self.apply_matrix([[2, 1], [1, 1]])
        self.wait(1)
```

### Lifecycle and Scene Methods

```python
class MyScene(Scene):
    def setup(self):
        self.camera.background_color = BLACK

    def construct(self):
        circle = Circle()
        self.add(circle)
        self.play(circle.animate.shift(RIGHT * 2))
        self.wait(1)
        self.remove(circle)
        self.clear()
```

Syntax:

```python
self.add(*mobjects)
self.remove(*mobjects)
self.clear()
self.play(*animations, run_time=..., rate_func=...)
self.wait(duration)
self.next_section("Section Name")
self.add_subcaption(text, duration=...)
```

Example:

```python
class Intro(Scene):
    def construct(self):
        title = Text("Dot Product", font_size=48)
        self.next_section("Title")
        self.play(Write(title), subcaption="Introduce the topic", subcaption_duration=2)
        self.wait(0.5)
```

### Multiple Scenes in One File

```python
class Scene1(Scene):
    def construct(self):
        self.play(Create(Circle()))

class Scene2(Scene):
    def construct(self):
        self.play(Create(Square()))
```

## Basic Elements

This block groups the stable object, layout, style, and plotting syntax you should reach for first.

### Core Mobjects

#### Geometry Primitives

Syntax:

```python
Dot(point=..., radius=..., color=...)
Circle(radius=..., color=..., fill_opacity=...)
Ellipse(width=..., height=...)
Square(side_length=...)
Rectangle(width=..., height=...)
RoundedRectangle(width=..., height=..., corner_radius=...)
Triangle()
RegularPolygon(n=...)
Polygon(*points)
Star(n=..., outer_radius=..., inner_radius=..., density=...)
RegularPolygram(n=..., radius=...)
Arc(radius=..., start_angle=..., angle=...)
ArcBetweenPoints(start, end, angle=...)
Sector(radius=..., start_angle=..., angle=...)
Annulus(inner_radius=..., outer_radius=...)
AnnularSector(inner_radius=..., outer_radius=..., start_angle=..., angle=...)
Cutout(base_shape, *holes)
Dot3D(point=..., color=...)
```

Useful methods:

```python
Circle.from_three_points(p1, p2, p3)
circle.surround(mobject, buffer_factor=..., stretch=...)
shape.get_center()
shape.get_width()
shape.get_height()
shape.get_top()
shape.get_bottom()
shape.get_left()
shape.get_right()
shape.get_corner(UL)
```

Example:

```python
circle = Circle(radius=1.2, color=BLUE, fill_opacity=0.25)
rect = RoundedRectangle(width=4, height=2, corner_radius=0.2, color=WHITE)
arc = ArcBetweenPoints(LEFT * 2, RIGHT * 2, angle=PI / 3, color=YELLOW)
ring = Annulus(inner_radius=0.8, outer_radius=1.4, color=TEAL, fill_opacity=0.4)
star = Star(n=5, outer_radius=1.2, color=GOLD, fill_opacity=0.8)
```

#### Boolean Geometry and Custom Curves

Syntax:

```python
Union(mobject1, mobject2, color=..., fill_opacity=...)
Intersection(mobject1, mobject2, color=..., fill_opacity=...)
Difference(mobject1, mobject2, color=..., fill_opacity=...)
Exclusion(mobject1, mobject2, color=..., fill_opacity=...)

VMobject()
curve.set_points_as_corners(points)
curve.set_points_smoothly(points)
curve.set_stroke(color, width=..., opacity=...)
```

Example:

```python
circle = Circle(radius=1.5, color=BLUE, fill_opacity=0.5).shift(LEFT * 0.5)
square = Square(side_length=2, color=RED, fill_opacity=0.5).shift(RIGHT * 0.5)
intersect = Intersection(circle, square, color=YELLOW, fill_opacity=0.5)

curve = VMobject()
curve.set_points_smoothly([LEFT, UP, RIGHT, DOWN])
curve.set_stroke(BLUE, width=3)
```

#### Lines, Arrows, Angles, and Connectors

Syntax:

```python
Line(start, end, color=...)
Arrow(start, end, buff=..., tip_length=...)
DoubleArrow(start, end)
Vector(direction, color=...)
DashedLine(start, end, dash_length=..., dashed_ratio=...)
DashedVMobject(vmobject, num_dashes=...)
TangentLine(vmobject, alpha=..., length=...)
CurvedArrow(start_point=..., end_point=..., angle=...)
LabeledArrow(label, start=..., end=..., color=...)
LabeledLine(label, start=..., end=...)
Brace(mobject, direction)
BraceBetweenPoints(point_a, point_b, direction=...)
BraceLabel(mobject, label, direction)
Angle(line1, line2, radius=..., quadrant=..., other_angle=False)
RightAngle(line1, line2, length=...)
Underline(mobject, color=...)
Cross(mobject, color=...)
Elbow(width=..., angle=...)
line.get_start()
line.get_end()
line.get_length()
line.get_angle()
line.put_start_and_end_on(new_start, new_end)
line.set_length(length)
```

Parameters:

- `Arrow(..., buff=...)`: gap from tip/body to target endpoints.
- `DashedLine(..., dash_length=..., dashed_ratio=...)`: dash size and dash-to-gap ratio.
- `Angle(..., quadrant=..., other_angle=False)`: disambiguates which sector to draw.
- `TangentLine(..., alpha=...)`: `alpha` is path proportion in `[0, 1]`.

Usage notes:

- `Angle(...)` and `RightAngle(...)` should use the real rays that share the same vertex.
- When the sector is ambiguous, set `quadrant=...`.
- For moving geometry, rebuild angle markers with `always_redraw(lambda: ...)`.

Example:

```python
line1 = Line(ORIGIN, RIGHT * 2)
line2 = Line(ORIGIN, UP * 2 + RIGHT)
angle = Angle(line1, line2, radius=0.4, quadrant=(1, 1), color=YELLOW)
right_angle = RightAngle(line1, Line(ORIGIN, UP * 2), length=0.25, color=WHITE)
arrow = LabeledArrow(Text("force", font_size=18), start=LEFT, end=RIGHT, color=RED)
brace = BraceBetweenPoints(LEFT * 2, RIGHT * 2, direction=DOWN)
brace_label = brace.get_text("width", font_size=20)
```

#### Highlight and Annotation Helpers

Syntax:

```python
SurroundingRectangle(mobject, color=..., buff=..., corner_radius=..., stroke_width=...)
BackgroundRectangle(mobject, fill_opacity=..., buff=..., color=...)
```

Example:

```python
box = SurroundingRectangle(eq[2:], color=YELLOW, buff=0.1, corner_radius=0.1)
card = BackgroundRectangle(label, fill_opacity=0.8, buff=0.2, color=BLACK)
```

#### Text, Math, Tables, Code, Media

Syntax:

```python
Text(text, font_size=..., font=..., t2c=..., t2f=..., t2g=..., t2s=..., t2w=..., gradient=...)
MarkupText(markup, font_size=...)
Tex(*tex_strings, tex_template=...)
MathTex(*tex_strings, substrings_to_isolate=..., tex_to_color_map=..., tex_environment=...)
DecimalNumber(value, num_decimal_places=..., include_sign=...)
Variable(value, label, num_decimal_places=...)
BulletedList(*items, font_size=...)
Code(code_file=..., language=..., tab_width=..., font_size=...)
Table(data, row_labels=..., col_labels=...)
IntegerMatrix(values, left_bracket=..., right_bracket=...)
DecimalMatrix(values, element_to_mobject_config=...)
MobjectMatrix(values)
SVGMobject(file_name, height=..., width=...)
ImageMobject(filename_or_array, scale_to_resolution=...)
mobject.set_color_by_tex(substring, color)
```

Parameters:

- `Text(..., t2c/t2f/t2g/t2s/t2w=...)`: per-substring color/font/gradient/slant/weight.
- `MathTex(..., substrings_to_isolate=...)`: split dense formulas into trackable parts.
- `MathTex(..., tex_environment=...)`: override default `align*` environment.
- `DecimalNumber(..., num_decimal_places=..., include_sign=...)`: numeric formatting.
- `Variable(value, label, ...)`: labeled number display with `variable.tracker`.
- Use `set_color_by_tex(substring, color)` to color repeated LaTeX substrings reliably.

Usage notes:

- Use raw strings for LaTeX, for example `r"\geq"`.
- `MathTex(r"{{ a^2 }} + {{ b^2 }} = {{ c^2 }}")` uses official double-brace grouping for `TransformMatchingTex`.
- `Text` is a `VMobject`, so it can live inside `VGroup`.
- Use `Group(...)` when mixing non-VMobjects such as `ImageMobject`.
- `ImageMobject` supports common Mobject transforms such as `scale`, `shift`, and `set_opacity`, but it does not use vector-style stroke/fill semantics.

Example:

```python
title = Text(
    "Gradient Descent",
    font_size=48,
    t2c={"Gradient": BLUE, "Descent": YELLOW},
)

eq = MathTex(
    r"\mathcal{L} = \bar{\psi}(i \gamma^\mu D_\mu - m)\psi - \tfrac{1}{4}F_{\mu\nu}F^{\mu\nu}",
    substrings_to_isolate=[r"\psi", r"D_\mu", r"\gamma^\mu", r"F_{\mu\nu}"],
)
eq.set_color_by_tex(r"\psi", BLUE)
eq.set_color_by_tex(r"F_{\mu\nu}", YELLOW)

value = Variable(0, Text("x", font_size=24), num_decimal_places=2)
table = Table([["A", "B"], ["C", "D"]])
matrix = IntegerMatrix([[1, 2], [3, 4]])
logo = SVGMobject("logo.svg").set_color(WHITE).scale(0.5)
image = ImageMobject("plot.png").set_height(3)
```

#### LaTeX Utilities

Syntax:

```python
TexTemplate()
template.add_to_preamble(r"\usepackage{mathrsfs}")
Tex(..., tex_template=template)
MathTex(..., tex_template=template)
index_labels(mobject)
```

Example:

```python
template = TexTemplate()
template.add_to_preamble(r"\usepackage{mathrsfs}")
symbol = Tex(r"$\mathscr{L}$", tex_template=template)

eq = MathTex(r"\frac{a}{b}")
debug = index_labels(eq[0])
```

#### Formula Patterns

Syntax:

```python
MathTex(r"\frac{a}{b}")
MathTex(r"\sum_{i=1}^{n} x_i")
MathTex(r"\int_0^\infty e^{-x} dx")
MathTex(r"\lim_{x \to \infty} f(x)")
MathTex(r"\begin{bmatrix} 1 & 0 \\ 0 & 1 \end{bmatrix}")
MathTex(r"\begin{cases} x^2 & \text{if } x \ge 0 \\ -x^2 & \text{if } x < 0 \end{cases}")
MathTex(r"\begin{aligned} a &= b + c \\ d &= e + f \end{aligned}")
```

Example:

```python
eq1 = MathTex(r"{{a}}^2", "+", r"{{b}}^2", "=", r"{{c}}^2")
eq2 = MathTex(r"{{a}}^2", "=", r"{{c}}^2", "-", r"{{b}}^2")
self.play(TransformMatchingTex(eq1, eq2, key_map={"+": "-"}))
```

### Grouping and Layout

#### Group and VGroup

Syntax:

```python
VGroup(*vmobjects)
Group(*mobjects)
```

Use:

- `VGroup`: shapes, `Text`, `MarkupText`, `Tex`, `MathTex`, `Code`, `SVGMobject`.
- `Group`: mixed collections containing non-VMobjects such as `ImageMobject`.

Example:

```python
row = VGroup(
    Text("A"),
    MathTex("x^2"),
    Circle(),
).arrange(RIGHT, buff=0.4)

mixed = Group(
    Text("Screenshot"),
    ImageMobject("plot.png"),
).arrange(DOWN, buff=0.3)
```

#### Positioning

Syntax:

```python
obj.move_to(point_or_mobject)
obj.shift(vector)
obj.next_to(other, direction, buff=..., aligned_edge=...)
obj.align_to(other, edge)
obj.to_edge(direction, buff=...)
obj.to_corner(corner, buff=...)
obj.center()
obj.match_width(other)
obj.match_height(other)
```

Example:

```python
title.to_edge(UP, buff=0.5)
label.next_to(dot, RIGHT, buff=0.2)
formula.align_to(title, LEFT)
box.move_to(ORIGIN)
box.shift(RIGHT * 2 + UP)
```

#### Arrangement

Syntax:

```python
group.arrange(direction=RIGHT, buff=..., aligned_edge=...)
group.arrange_in_grid(rows=..., cols=..., buff=...)
```

Example:

```python
items = VGroup(
    Text("Point 1"),
    Text("Point 2"),
    Text("Point 3"),
).arrange(DOWN, buff=0.3, aligned_edge=LEFT)

grid = VGroup(*[Square().scale(0.3) for _ in range(12)]).arrange_in_grid(rows=3, cols=4, buff=0.4)
```

#### Copying and Submobjects

Syntax:

```python
mob.copy()
mob.submobjects
group[0]
group[1:3]
```

Example:

```python
eq_copy = eq.copy().shift(DOWN)
for part in eq.submobjects:
    part.set_color(BLUE)
```

### Styling and Color

#### Project-Safe Color Constants

Use only the following known-safe Manim color constants by default in this project.

Base colors:

- `BLACK`
- `WHITE`
- `BLUE`
- `GREEN`
- `YELLOW`
- `RED`
- `PURPLE`
- `PINK`
- `ORANGE`
- `TEAL`
- `GOLD`
- `MAROON`
- `GRAY`
- `GREY`
- `DARK_BLUE`
- `DARK_BROWN`
- `DARK_GRAY`
- `DARK_GREY`
- `DARKER_GRAY`
- `DARKER_GREY`
- `LIGHT_BROWN`
- `LIGHT_GRAY`
- `LIGHT_GREY`
- `LIGHTER_GRAY`
- `LIGHTER_GREY`
- `LIGHT_PINK`
- `GRAY_BROWN`
- `GREY_BROWN`

Variant families:

- `BLUE_A`, `BLUE_B`, `BLUE_C`, `BLUE_D`, `BLUE_E`
- `GREEN_A`, `GREEN_B`, `GREEN_C`, `GREEN_D`, `GREEN_E`
- `YELLOW_A`, `YELLOW_B`, `YELLOW_C`, `YELLOW_D`, `YELLOW_E`
- `RED_A`, `RED_B`, `RED_C`, `RED_D`, `RED_E`
- `PURPLE_A`, `PURPLE_B`, `PURPLE_C`, `PURPLE_D`, `PURPLE_E`
- `TEAL_A`, `TEAL_B`, `TEAL_C`, `TEAL_D`, `TEAL_E`
- `GOLD_A`, `GOLD_B`, `GOLD_C`, `GOLD_D`, `GOLD_E`
- `MAROON_A`, `MAROON_B`, `MAROON_C`, `MAROON_D`, `MAROON_E`
- `GRAY_A`, `GRAY_B`, `GRAY_C`, `GRAY_D`, `GRAY_E`
- `GREY_A`, `GREY_B`, `GREY_C`, `GREY_D`, `GREY_E`

Pure colors:

- `PURE_RED`
- `PURE_GREEN`
- `PURE_BLUE`
- `PURE_YELLOW`
- `PURE_CYAN`
- `PURE_MAGENTA`

Logo colors:

- `LOGO_BLACK`
- `LOGO_WHITE`
- `LOGO_BLUE`
- `LOGO_GREEN`
- `LOGO_RED`

Use custom hex colors or `ManimColor(...)` only when the scene explicitly requires them.

#### Fill, Stroke, Opacity, Style

Syntax:

```python
mob.set_color(color)
mob.set_fill(color, opacity=...)
mob.set_fill_color(color)
mob.set_fill_opacity(opacity)
mob.set_stroke(color, width=..., opacity=...)
mob.set_stroke(color, width=..., background=True)
mob.set_stroke_color(color)
mob.set_stroke_width(width)
mob.set_stroke_opacity(opacity)
mob.set_style(fill_color=..., fill_opacity=..., stroke_color=..., stroke_width=...)
mob.set_opacity(opacity)
mob.fade(darkness)
mob.set_z_index(z)
mob.match_style(other)
BackgroundRectangle(mobject, fill_opacity=..., buff=...)
```

Usage notes:

- `set_stroke(..., background=True)` is excellent for readable text over busy geometry.
- Use `set_z_index(...)` for layering.
- `fade(0.5)` means 50% faded, not 50% opaque.

Example:

```python
label.set_stroke(BLACK, width=4, background=True)
card = BackgroundRectangle(label, fill_opacity=0.8, buff=0.2)
square.set_style(
    fill_color=BLUE,
    fill_opacity=0.5,
    stroke_color=WHITE,
    stroke_width=4,
)
```

#### Gradients and Advanced Colors

Syntax:

```python
mob.set_color_by_gradient(color1, color2, ...)
interpolate_color(color1, color2, alpha)
ManimColor("#FF0000")
rgb_to_color([r, g, b])
color.lighter()
color.darker()
color.invert()
color.opacity(alpha)
color.to_hex()
color.to_rgb()
color.to_hsv()
color.interpolate(other, alpha)
random_color()
random_bright_color()
```

Example:

```python
text = Text("GRADIENT")
text.set_color_by_gradient(RED, YELLOW, GREEN)

mid = interpolate_color(BLUE, RED, 0.5)
custom = ManimColor("#1a1a2e")
```

### Coordinate Systems and Plotting

#### Coordinate Systems

Syntax:

```python
Axes(x_range=..., y_range=..., x_length=..., y_length=..., axis_config=...)
NumberPlane()
NumberLine(x_range=..., length=..., include_numbers=True)
ComplexPlane().add_coordinates()
PolarPlane(radius_max=...).add_coordinates()
ThreeDAxes(x_range=..., y_range=..., z_range=...)
axes.get_x_axis_label(label)
axes.get_y_axis_label(label)
axes3d.get_x_axis_label(label)
axes3d.get_y_axis_label(label)
axes3d.get_z_axis_label(label)
```

Example:

```python
axes = Axes(
    x_range=[-3, 3, 1],
    y_range=[-2, 4, 1],
    x_length=6,
    y_length=5,
    axis_config={"include_numbers": True},
)

plane = NumberPlane()
number_line = NumberLine(x_range=[0, 10, 1], length=10, include_numbers=True)
complex_plane = ComplexPlane().add_coordinates()
polar = PolarPlane(radius_max=3).add_coordinates()
axes3d = ThreeDAxes()
x_label = axes.get_x_axis_label("x")
y_label = axes.get_y_axis_label("y")
```

#### Complex Plane Nonlinear Transforms

Syntax:

```python
complex_plane.prepare_for_nonlinear_transform()
complex_plane.animate.apply_complex_function(func)
ApplyComplexFunction(func, mobject)
```

Usage note:

- Call `prepare_for_nonlinear_transform()` before nonlinear plane deformation, otherwise the result may look jagged.

Example:

```python
c_plane = ComplexPlane().add_coordinates()
moving_plane = c_plane.copy()
moving_plane.prepare_for_nonlinear_transform()
self.play(moving_plane.animate.apply_complex_function(lambda z: z**2), run_time=3)
```

#### Coordinate Conversion

Syntax:

```python
axes.c2p(x, y)
axes.i2gp(x, graph)
number_line.n2p(value)
```

Example:

```python
dot = Dot(axes.c2p(2, 3), color=YELLOW)
point_on_graph = Dot(axes.i2gp(1.5, graph), color=RED)
pointer = Arrow(number_line.n2p(3) + UP * 0.5, number_line.n2p(3), buff=0)
```

#### Function Graphs and Areas

Syntax:

```python
axes.plot(func, x_range=..., color=..., stroke_width=...)
axes.plot_parametric_curve(func, t_range=..., color=...)
ParametricFunction(func, t_range=..., color=...)
axes.get_graph_label(graph, label=..., x_val=..., direction=...)
axes.get_area(graph, x_range=..., color=..., opacity=...)
axes.get_riemann_rectangles(graph, x_range=..., dx=...)
axes.get_h_line(point)
axes.get_vertical_line(point, color=...)
axes.get_secant_slope_group(x=..., graph=..., dx=..., secant_line_color=...)
```

Parameters:

- `axes.plot(..., x_range=...)`: restrict the visible domain, especially near discontinuities.
- `axes.get_graph_label(..., x_val=...)`: choose the graph point used for label placement.
- `axes.get_riemann_rectangles(..., dx=...)`: rectangle width.

Example:

```python
graph = axes.plot(lambda x: x**2, x_range=[-2, 2], color=BLUE, stroke_width=4)
label = axes.get_graph_label(graph, label=MathTex("y=x^2"), x_val=1.5, direction=UR)
area = axes.get_area(graph, x_range=[0, 2], color=BLUE, opacity=0.3)
tangent = axes.get_secant_slope_group(x=1.0, graph=graph, dx=0.01, secant_line_color=YELLOW)
```

#### Charts, Graph Theory, and Fields

Syntax:

```python
BarChart(values, bar_names=..., y_range=..., bar_colors=...)
chart.change_bar_values(values, update_colors=True)
chart.get_bar_labels(font_size=...)

Graph(vertices, edges, layout=..., labels=..., vertex_config=..., edge_config=...)
DiGraph(vertices, edges, layout=..., labels=..., edge_config=...)
graph.add_vertices(*vertices, positions=...)
graph.add_edges(*edges, edge_config=...)
graph.remove_vertices(*vertices)
graph.remove_edges(*edges)

ArrowVectorField(func, x_range=..., y_range=..., colors=...)
StreamLines(func, x_range=..., y_range=..., stroke_width=..., max_anchors_per_line=...)
stream.start_animation(warm_up=True, flow_speed=..., time_width=...)
stream.end_animation()
```

Example:

```python
chart = BarChart(
    values=[4, 6, 2, 8, 5],
    bar_names=["A", "B", "C", "D", "E"],
    y_range=[0, 10, 2],
    bar_colors=[RED, GREEN, BLUE, YELLOW, PURPLE],
)
chart.change_bar_values([6, 3, 7, 4, 9])

g = Graph(
    vertices=[1, 2, 3, 4],
    edges=[(1, 2), (2, 3), (3, 4), (4, 1)],
    layout="circular",
    labels=True,
)

field = ArrowVectorField(
    lambda pos: np.array([-pos[1], pos[0], 0]),
    x_range=[-3, 3],
    y_range=[-3, 3],
)
```

## Basic Animation Control

This block covers the common animation flow first, then dynamic dependency patterns, then timing polish.

### Animation Basics

#### `self.play` and `.animate`

Syntax:

```python
self.play(animation, run_time=..., rate_func=...)
self.play(mobject.animate.method(...))
self.play(mobject.animate(run_time=..., rate_func=...).method(...))
```

Example:

```python
self.play(square.animate.shift(RIGHT * 2))
self.play(circle.animate(run_time=2).scale(0.5).set_color(RED))
```

#### Creation and Removal Animations

Syntax:

```python
Create(mobject)
Write(mobject, reverse=False)
FadeIn(mobject)
DrawBorderThenFill(mobject)
GrowFromCenter(mobject)
GrowFromPoint(mobject, point)
GrowFromEdge(mobject, edge)
GrowArrow(arrow)
SpinInFromNothing(mobject)
SpiralIn(mobject)
AddTextLetterByLetter(text, time_per_char=...)
AddTextWordByWord(text)
ShowIncreasingSubsets(group)
ShowSubmobjectsOneByOne(group)
TypeWithCursor(text, cursor, buff=..., keep_cursor_y=True, leave_cursor_on=True, time_per_char=...)

FadeOut(mobject)
Uncreate(mobject)
Unwrite(mobject, reverse=True)
RemoveTextLetterByLetter(text, time_per_char=...)
UntypeWithCursor(text, cursor=None, buff=..., keep_cursor_y=True, leave_cursor_on=False, time_per_char=...)
```

Parameters:

- `Write(..., reverse=True)`: write from the end toward the beginning.
- `AddTextLetterByLetter(..., time_per_char=...)`: only for `Text`, not `MathTex`.
- `AddTextWordByWord(...)`: officially available, but the current official reference marks it as "currently broken"; prefer `LaggedStart` or `AddTextLetterByLetter` unless verified.
- `TypeWithCursor(..., buff=..., keep_cursor_y=..., leave_cursor_on=...)`: cursor spacing, cursor height behavior, and whether the cursor remains after typing.
- `ShowIncreasingSubsets(...)` keeps previous members visible; `ShowSubmobjectsOneByOne(...)` shows only one at a time.

Example:

```python
typed = Text("Typing effect")
cursor = Rectangle(height=0.8, width=0.08, fill_opacity=1).move_to(typed[0])

self.play(Write(Text("Hello", font_size=48)))
self.play(AddTextLetterByLetter(Text("Step 1"), time_per_char=0.08))
self.play(TypeWithCursor(typed, cursor, buff=0.05, leave_cursor_on=True))
self.play(Blink(cursor, blinks=2))
self.play(UntypeWithCursor(typed, cursor))
```

#### Transform Animations

Syntax:

```python
Transform(source, target)
ReplacementTransform(source, target)
TransformFromCopy(source, target)
FadeTransform(source, target)
FadeTransformPieces(source, target)
TransformMatchingTex(source, target, key_map=..., transform_mismatches=False, fade_transform_mismatches=False)
TransformMatchingShapes(source, target)
FadeToColor(mobject, color)
ScaleInPlace(mobject, scale_factor)
ShrinkToCenter(mobject)
MoveToTarget(mobject)
Restore(mobject)
```

Usage notes:

- After `Transform(source, target)`, the on-screen object is still `source`.
- Use `ReplacementTransform` when you want the new visible object to be `target`.
- Use `FadeTransform` when source and target are visually dissimilar.
- `MoveToTarget(...)` requires `mobject.generate_target()` and edits on `mobject.target`.
- `Restore(...)` requires `mobject.save_state()`.

Example:

```python
self.play(Transform(circle, ellipse))
self.play(ReplacementTransform(old_label, new_label))
self.play(FadeTransform(icon1, icon2))

eq1 = MathTex(r"{{a}}^2", "+", r"{{b}}^2", "=", r"{{c}}^2")
eq2 = MathTex(r"{{a}}^2", "=", r"{{c}}^2", "-", r"{{b}}^2")
self.play(TransformMatchingTex(eq1, eq2, key_map={"+": "-"}))

obj.save_state()
self.play(obj.animate.shift(UP).set_opacity(0.3))
self.play(Restore(obj))

obj.generate_target()
obj.target.shift(RIGHT * 2).scale(1.5)
self.play(MoveToTarget(obj))
```

#### Advanced Transform and Function-Based Animation

Syntax:

```python
ApplyMethod(mobject.method, *args)
ApplyFunction(func, mobject)
ApplyPointwiseFunction(func, mobject)
ApplyPointwiseFunctionToCenter(func, mobject)
ApplyComplexFunction(func, mobject)
ApplyMatrix(matrix, mobject)
ClockwiseTransform(source, target)
CounterclockwiseTransform(source, target)
Swap(mobject1, mobject2)
CyclicReplace(*mobjects)
TransformAnimations(anim1, anim2)
```

Example:

```python
self.play(ApplyMethod(title.shift, UP))
self.play(ApplyMatrix([[0, -1], [1, 0]], square))
self.play(Swap(a, b))
self.play(CyclicReplace(obj1, obj2, obj3))
```

#### Movement and Rotation

Syntax:

```python
MoveAlongPath(mobject, path)
Homotopy(func, mobject)
ComplexHomotopy(func, mobject)
SmoothedVectorizedHomotopy(func, mobject)
PhaseFlow(func, mobject)
Rotate(mobject, angle=..., about_point=...)
Rotating(mobject, angle=..., about_point=..., run_time=...)
```

Example:

```python
path = Arc(radius=2, angle=PI)
self.play(MoveAlongPath(dot, path), run_time=2)
self.play(Rotate(square, angle=PI / 2, about_point=ORIGIN))
self.play(Rotating(arrow, angle=PI, about_point=ORIGIN, run_time=2))
```

#### Emphasis and Indication

Syntax:

```python
Flash(point_or_mobject)
Indicate(mobject)
Circumscribe(mobject)
FocusOn(point_or_mobject)
ApplyWave(mobject)
Blink(mobject)
ShowPassingFlash(vmobject, time_width=...)
ShowPassingFlashWithThinningStrokeWidth(vmobject)
Wiggle(mobject)
Broadcast(mobject)
```

Example:

```python
self.play(Flash(dot, color=YELLOW))
self.play(Indicate(eq))
self.play(Circumscribe(title))
self.play(ShowPassingFlash(curve.copy().set_color(YELLOW), time_width=0.3))
self.play(Wiggle(triangle))
```

#### Animation Composition

Syntax:

```python
AnimationGroup(*animations, lag_ratio=...)
LaggedStart(*animations, lag_ratio=...)
LaggedStartMap(AnimationClass, group, arg_creator=..., lag_ratio=...)
Succession(*animations)
Add(*mobjects)
Wait(run_time=...)
```

Parameters:

- `lag_ratio`: delay ratio between child animations.
- `Succession(...)`: sequential composition inside one `self.play(...)`.

Example:

```python
self.play(AnimationGroup(FadeIn(a), FadeIn(b), FadeIn(c), lag_ratio=0.15))
self.play(LaggedStart(*[Write(line) for line in lines], lag_ratio=0.2, run_time=3))
self.play(Succession(Write(title), Wait(0.5), FadeIn(subtitle)))
self.play(Add(label))
```

#### Number and Speed Animations

Syntax:

```python
ChangeDecimalToValue(decimal_number, target_value)
ChangingDecimal(decimal_number, number_update_func)
ChangeSpeed(animation, speedinfo=..., rate_func=...)
```

Example:

```python
number = DecimalNumber(0, num_decimal_places=2)
self.add(number)
self.play(ChangeDecimalToValue(number, 10, run_time=2))
self.play(ChangingDecimal(number, lambda a: 10 + 5 * np.sin(PI * a), run_time=2))
self.play(ChangeSpeed(dot.animate.shift(RIGHT * 4), speedinfo={0.3: 1, 0.6: 0.2, 1: 1}))
```

### Dynamic Updates

#### Updaters

Syntax:

```python
mobject.add_updater(lambda m: ...)
mobject.add_updater(lambda m, dt: ...)
mobject.remove_updater(func)
mobject.clear_updaters()
mobject.suspend_updating()
mobject.resume_updating()
```

Parameters:

- Updaters may accept `mobject` only or `(mobject, dt)`.
- `dt` is elapsed time in seconds since the previous frame.

Example:

```python
label.add_updater(lambda m: m.next_to(dot, UP, buff=0.2))
line.add_updater(lambda m: m.put_start_and_end_on(dot1.get_center(), dot2.get_center()))
cloud.add_updater(lambda m, dt: m.shift(RIGHT * 0.4 * dt))
```

#### ValueTracker and Variable

Syntax:

```python
tracker = ValueTracker(value)
tracker.get_value()
tracker.set_value(value)
tracker.increment_value(delta)
tracker.animate.set_value(value)
tracker.animate.increment_value(delta)
```

Example:

```python
x_tracker = ValueTracker(-2)
dot = always_redraw(lambda: Dot(axes.c2p(x_tracker.get_value(), 0), color=YELLOW))
number = DecimalNumber(0, num_decimal_places=2)
number.add_updater(lambda m: m.set_value(x_tracker.get_value()))
self.add(dot, number)
self.play(x_tracker.animate.set_value(2), run_time=4)
```

#### `always`, `f_always`, `always_redraw`

Syntax:

```python
always(method, *args, **kwargs)
f_always(method, *arg_generators)
always_redraw(lambda: ...)
always_shift(mobject, direction, rate=...)
always_rotate(mobject, rate=...)
```

Usage notes:

- `always_redraw` officially takes a zero-argument callable: `always_redraw(lambda: Brace(square, UP))`.
- Use `add_updater(...)` when only position/color/opacity changes.
- Use `always_redraw(...)` when the mobject structure itself changes.

Example:

```python
brace = always_redraw(lambda: Brace(square, UP))
label = always_redraw(lambda: Text("A", font_size=24).next_to(dot, UP))
line = always_redraw(lambda: Line(dot1.get_center(), dot2.get_center()))

always(label.next_to, dot, UP)
always_shift(cloud, RIGHT, rate=0.5)
always_rotate(spinner, rate=PI)
```

#### Updater Helpers and Traces

Syntax:

```python
turn_animation_into_updater(animation, cycle=False, delay=...)
cycle_animation(animation, delay=...)
AnimatedBoundary(vmobject, colors=..., cycle_rate=...)
TracedPath(callable, stroke_color=..., stroke_width=..., dissipating_time=...)
UpdateFromFunc(mobject, update_function)
UpdateFromAlphaFunc(mobject, update_function)
MaintainPositionRelativeTo(mobject, tracked)
```

Example:

```python
path = TracedPath(dot.get_center, stroke_color=YELLOW, stroke_width=4)
boundary = AnimatedBoundary(title, colors=[RED, GREEN, BLUE], cycle_rate=2)

self.add(dot, path, boundary)

self.play(
    dot.animate.shift(RIGHT * 3),
    UpdateFromFunc(label, lambda m: m.next_to(dot, UP)),
)
```

### Timing and Rate Functions

#### Common Rate Functions

Syntax:

```python
smooth
linear
rush_into
rush_from
there_and_back
there_and_back_with_pause
running_start
double_smooth
wiggle
lingering
exponential_decay
not_quite_there
squish_rate_func(func, a=..., b=...)
```

Usage notes:

- The non-standard functions above are exported and can be used directly.
- Standard ease families such as `ease_in_sine` and `ease_out_bounce` are available as `rate_functions.ease_in_sine`, `rate_functions.ease_out_bounce`, etc.

Example:

```python
self.play(FadeIn(mob), rate_func=smooth)
self.play(FadeIn(mob), rate_func=linear)
self.play(mob.animate.shift(UP), rate_func=there_and_back_with_pause)
self.play(FadeIn(mob), rate_func=not_quite_there(0.7))

self.play(
    FadeIn(a, rate_func=squish_rate_func(smooth, 0.0, 0.5)),
    FadeIn(b, rate_func=squish_rate_func(smooth, 0.25, 0.75)),
    FadeIn(c, rate_func=squish_rate_func(smooth, 0.5, 1.0)),
    run_time=2,
)
```

## Advanced Usage

This block collects the more specialized scene families, camera systems, render tooling, and runtime safety notes.

### 3D Objects and 3D Plotting

#### 3D Primitives

Syntax:

```python
Sphere(radius=..., resolution=...)
Cube(side_length=...)
Prism(dimensions=[...])
Cylinder(radius=..., height=...)
Cone(base_radius=..., height=...)
Torus(major_radius=..., minor_radius=...)
Tetrahedron()
Arrow3D(start=..., end=..., color=...)
Line3D(start=..., end=..., color=...)
Dot3D(point=..., color=...)
```

Example:

```python
sphere = Sphere(radius=1).set_color(BLUE).set_opacity(0.7)
cube = Cube(side_length=2, fill_color=GREEN, fill_opacity=0.5)
arrow3d = Arrow3D(start=ORIGIN, end=[2, 1, 1], color=RED)
line3d = Line3D(ORIGIN, [-2, 1, 1], color=YELLOW)
```

#### Surfaces and 3D Curves

Syntax:

```python
Surface(func, u_range=..., v_range=..., resolution=..., fill_opacity=...)
axes.plot_surface(func, u_range=..., v_range=..., colorscale=...)
ParametricFunction(func, t_range=..., color=...)
mob.set_shade_in_3d(True)
mob.set_color_by_gradient(color1, color2, ...)
```

Parameters:

- `Surface(func, ...)`: `func(u, v)` must return `np.array([x, y, z])`.
- Use lower `resolution` while debugging.

Example:

```python
surface = Surface(
    lambda u, v: np.array([u, v, np.sin(u) * np.cos(v)]),
    u_range=[-3, 3],
    v_range=[-3, 3],
    resolution=(24, 24),
    fill_opacity=0.8,
)
surface.set_color_by_gradient(BLUE, GREEN, YELLOW)

helix = ParametricFunction(
    lambda t: np.array([np.cos(t), np.sin(t), t / (2 * PI)]),
    t_range=[0, 4 * PI],
    color=YELLOW,
)
helix.set_shade_in_3d(True)
```

### Camera and 3D View Control

#### Moving Camera

Syntax:

```python
self.camera.frame.animate.scale(factor)
self.camera.frame.animate.set(width=...)
self.camera.frame.animate.move_to(target)
self.camera.frame.save_state()
Restore(self.camera.frame)
self.camera.auto_zoom(mobjects, margin=..., animate=True)
```

Example:

```python
class FocusOnPoint(MovingCameraScene):
    def construct(self):
        dot = Dot(RIGHT * 3)
        self.add(dot)
        self.camera.frame.save_state()
        self.play(self.camera.frame.animate.set(width=4).move_to(dot), run_time=2)
        self.play(Restore(self.camera.frame))
```

#### Zoomed Scene

Syntax:

```python
class MyZoom(ZoomedScene):
    def __init__(self, **kwargs):
        super().__init__(
            zoom_factor=...,
            zoomed_display_height=...,
            zoomed_display_width=...,
            zoomed_camera_frame_starting_position=...,
            **kwargs,
        )

self.activate_zooming(animate=False)
self.zoomed_camera.frame.animate.move_to(target)
self.get_zoomed_display_pop_out_animation()
```

Example:

```python
class UseZoom(ZoomedScene):
    def __init__(self, **kwargs):
        super().__init__(zoom_factor=0.3, zoomed_display_height=2.5, zoomed_display_width=3, **kwargs)

    def construct(self):
        dot = Dot().set_color(GREEN)
        self.add(dot)
        self.activate_zooming(animate=False)
        self.play(self.zoomed_camera.frame.animate.move_to(dot))
```

#### ThreeDScene Camera

Syntax:

```python
self.set_camera_orientation(phi=..., theta=..., gamma=..., zoom=...)
self.move_camera(phi=..., theta=..., gamma=..., zoom=..., run_time=...)
self.begin_ambient_camera_rotation(rate=...)
self.stop_ambient_camera_rotation()
self.add_fixed_in_frame_mobjects(*mobjects)
```

Parameters:

- `phi`: angle from the positive z-axis.
- `theta`: rotation around the z-axis.
- `gamma`: roll angle.

Example:

```python
class SurfaceScene(ThreeDScene):
    def construct(self):
        title = Text("Surface", font_size=32)
        self.add_fixed_in_frame_mobjects(title)

        self.set_camera_orientation(phi=70 * DEGREES, theta=-45 * DEGREES, zoom=0.8)
        axes = ThreeDAxes()
        surface = Surface(
            lambda u, v: np.array([u, v, np.sin(u) * np.cos(v)]),
            u_range=[-3, 3],
            v_range=[-3, 3],
            resolution=(20, 20),
        )
        self.play(Create(axes), Create(surface))
        self.begin_ambient_camera_rotation(rate=0.1)
        self.wait(2)
        self.stop_ambient_camera_rotation()
```

### CLI and Configuration

#### Core CLI Usage

Syntax:

```bash
manim file.py SceneName
manim -p file.py SceneName
manim -pql file.py SceneName
manim -qm file.py SceneName
manim -qh file.py SceneName
manim -qp file.py SceneName
manim -qk file.py SceneName
manim -a file.py
manim file.py Scene1 Scene2
manim -s file.py SceneName
manim --format gif file.py SceneName
manim --format png file.py SceneName
manim --format webm file.py SceneName
manim -r 1920,1080 --fps 30 file.py SceneName
manim -n 3,7 file.py SceneName
manim -o custom_name file.py SceneName
manim --media_dir ./media file.py SceneName
manim --renderer opengl file.py SceneName
manim -t file.py SceneName
python -m manim -pql file.py SceneName
```

Quality presets:

- `-ql`: 854x480, 15fps.
- `-qm`: 1280x720, 30fps.
- `-qh`: 1920x1080, 60fps.
- `-qp`: 2560x1440, 60fps.
- `-qk`: 3840x2160, 60fps.

Usage notes:

- Use `-pql` for quick iteration.
- Use at least `-qm` to check text-heavy scenes.
- Use `-s` to save the last frame as an image.

#### Sections and Notebook Usage

Syntax:

```python
self.next_section("Introduction")
```

```bash
manim --save_sections file.py SceneName
```

```python
%%manim -qm MyScene
class MyScene(Scene):
    def construct(self):
        self.play(Create(Circle()))
```

#### Health, Config, Plugins

Syntax:

```bash
manim checkhealth
manim init
manim cfg show
manim cfg write
manim plugins -l
manim --help
manim render --help
```

#### `manim.cfg`

Example:

```ini
[CLI]
quality = medium_quality
preview = True
frame_rate = 30
format = mp4
media_dir = ./media

[renderer]
background_color = #0d1117

[tex]
tex_template_file = custom_template.tex
```

Programmatic config:

```python
config.pixel_width = 1920
config.pixel_height = 1080
config.frame_rate = 60
config.background_color = BLACK
```

### Common Render Failure Guardrails

- Do not animate conditionally empty redraw outputs.
- Avoid `always_redraw(lambda: thing if cond else VMobject())` when the result will later be passed to `Create`, `FadeOut`, or `Transform`.
- Prefer one stable mobject and control visibility or style instead of swapping to an empty placeholder.
- Before cleanup animations, ensure targets are valid, on-scene, and non-empty.
- Use `always_redraw(lambda: ...)`, not unofficial positional forms of `always_redraw`.

Unsafe:

```python
arrow = always_redraw(
    lambda: Arrow(A.get_center(), B.get_center()) if flag.get_value() > 0 else VMobject()
)
self.play(Create(arrow))
self.play(flag.animate.set_value(0))
self.play(FadeOut(arrow))
```

Safe:

```python
arrow = Arrow(A.get_center(), B.get_center())

def refresh_arrow(m):
    if flag.get_value() > 0:
        m.become(Arrow(A.get_center(), B.get_center()).set_opacity(1.0))
    else:
        m.set_opacity(0.0)

arrow.add_updater(refresh_arrow)
self.add(arrow)
self.play(flag.animate.set_value(1))
self.play(flag.animate.set_value(0))
arrow.clear_updaters()
```
