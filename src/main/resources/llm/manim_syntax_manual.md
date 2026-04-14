# Manim Usage Guide

Please strictly use common, stable, and highly readable **Manim Community** patterns when generating animation code. Prioritize clarity, stability, and ease of rendering before flashy effects.

## Rules

- Create objects first, then position them, then style them, then animate them.
- Prefer one clear step per line.
- Use `ThreeDScene` only for actual 3D content or camera motion.

## Scene

```python
class MyScene(Scene): ...
class MyScene(MovingCameraScene): ...
class MyScene(ThreeDScene): ...
```

- Use `Scene` for ordinary 2D scenes.
- Use `MovingCameraScene` only when camera zoom or pan is part of the teaching.
- Use `ThreeDScene` when the scene contains `Surface`, `Dot3D`, `ThreeDAxes`, solid objects, or camera motion.

```python
class MyScene(Scene):
    def construct(self):
        ...

class MyScene(MovingCameraScene):
    def construct(self):
        ...

class MyScene(ThreeDScene):
    def construct(self):
        ...
```

## Basic Geometric Objects

```python
Dot()
Line(start, end)
Arrow(start, end)
DashedLine(start, end)
Circle()
Square()
Rectangle(width=..., height=...)
Triangle()
Polygon(*points)
SurroundingRectangle(obj, buff=...)
Angle(line1, line2, radius=..., quadrant=..., other_angle=False)
RightAngle(line1, line2, length=...)
```

- Use `DashedLine` for dashed segments instead of undocumented dash arguments on other mobjects.
- `Angle(...)` and `RightAngle(...)` should use the actual rays sharing the same vertex.
- When the angle sector is ambiguous, set `quadrant=...`; for the smaller interior angle, keep `other_angle=False`.
- For moving geometry, rebuild the full angle inside `always_redraw(...)`.

```python
dot = Dot()
dot = Dot(point=ORIGIN, color=YELLOW)

line = Line(LEFT, RIGHT)
arrow = Arrow(LEFT, RIGHT)
dashed = DashedLine(LEFT, RIGHT)

circle = Circle()
square = Square()
rect = Rectangle(width=4, height=2)
triangle = Triangle()
poly = Polygon(LEFT, RIGHT, UP)

box = SurroundingRectangle(obj, color=YELLOW, buff=0.2)
angle = Angle(line1, line2, radius=0.5)
ra = RightAngle(line1, line2, length=0.2)
```

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

- In the common "upward normal" case, `quadrant=(-1, 1)` and `quadrant=(1, 1)` are often the correct left and right sectors.

## Text and Formula Objects

```python
Text(text)
MarkupText(markup)
MathTex(*tex_strings)
Tex(*tex_strings)
DecimalNumber(value, num_decimal_places=...)
```

- Use `Text` for plain language and `MathTex` for mathematical notation.
- Prefer monospace fonts for `Text(...)` and `MarkupText(...)` when readable text must stay stable across renders.
- If the string contains LaTeX commands or expressions such as `A'`, `A_1`, `x^2`, or inequalities, use `MathTex`.
- Split `MathTex` into segments when you need partial coloring or matching transforms.
- Prefer raw strings for LaTeX, for example `r"\geq"`.

```python
title = Text("Dot Product", font_size=48, font="Menlo")
txt = MarkupText('<span fgcolor="YELLOW">Important</span>')

eq = MathTex(r"\vec{a}\cdot\vec{b}=|a||b|\cos\theta")

eq = MathTex(r"V", r"-", r"E", r"+", r"F", r"=", r"2")
eq[0].set_color(BLUE)
eq[2].set_color(YELLOW)
eq[4].set_color(GREEN)

label = MathTex("A'")
ineq = MathTex(r"AP + PB \geq A'B")
value = VGroup(Text("Minimum length:"), MathTex(r"A'B")).arrange(RIGHT, buff=0.2)
number = DecimalNumber(3.14, num_decimal_places=2)

subtitle = Tex(r"Reflect point A across line l")
```

## Grouping

```python
Group(*mobjects)
VGroup(*mobjects)
```

- Use `Group(...)` for mixed `Mobject` collections, especially when `Text(...)` is combined with shapes.
- Use `VGroup(...)` when all members are vectorized mobjects and should move, lay out, or animate together.

```python
group = Group(circle, Text("Label", font="Menlo"))
formula_group = VGroup(formula1, formula2, formula3)
```

## Coordinate Systems and Graphs

```python
Axes(x_range=..., y_range=..., x_length=..., y_length=...)
ax.c2p(x, y)
ax.plot(func, ...)
NumberPlane()
```

- Use `Axes` for coordinate-based content and `NumberPlane` for grid backgrounds.

```python
ax = Axes(
    x_range=[-3, 3, 1],
    y_range=[-2, 4, 1],
    x_length=6,
    y_length=5,
)

dot = Dot(ax.c2p(2, 3))
graph = ax.plot(lambda x: x**2, color=BLUE)

plane = NumberPlane()
```

## Curve Objects

```python
VMobject()
curve.set_points_smoothly(points)
curve.set_stroke(color, width=..., opacity=...)
```

- Use `VMobject` for custom curves and paths.

```python
curve = VMobject()
curve.set_points_smoothly([LEFT, UP, RIGHT])
curve.set_stroke(BLUE, width=3)
```

## 3D Objects

```python
Dot3D(point=..., color=...)
ThreeDAxes()
Surface(func, u_range=..., v_range=..., resolution=...)
Sphere()
Cube()
Cone()
Torus()
Tetrahedron()
```

- `Surface` expects `func(u, v) -> np.array([x, y, z])`.
- Use a lower `resolution` while debugging.

```python
dot3d = Dot3D(point=ORIGIN, color=YELLOW)
axes3d = ThreeDAxes()

surface = Surface(
    lambda u, v: np.array([u, v, np.sin(u) * np.cos(v)]),
    u_range=[-3, 3],
    v_range=[-3, 3],
    resolution=(16, 16),
)
```

## Positioning

```python
obj.move_to(point)
obj.shift(vector)
obj.to_edge(direction)
obj.to_corner(corner)
```

- Prefer relative layout before hardcoded coordinates.

```python
obj.move_to(ORIGIN)
obj.shift(RIGHT * 2 + UP)
title.to_edge(UP)
title.to_corner(UL)
```

## Relative Layout

```python
obj.next_to(other, direction, buff=...)
obj.align_to(other, edge)
obj.match_height(other)
obj.match_width(other)
```

- `next_to(...)` is the default choice for labels and nearby annotations.

```python
label.next_to(dot, RIGHT, buff=0.2)
obj2.align_to(obj1, LEFT)
```

## Arrangement

```python
VGroup(...).arrange(direction, buff=..., aligned_edge=...)
```

- Use `arrange(...)` for repeated linear layout.

```python
items = VGroup(
    Text("Point 1"),
    Text("Point 2"),
    Text("Point 3"),
).arrange(DOWN, buff=0.3, aligned_edge=LEFT)
```

## Style

### Whitelisted Color Constants

Use only the following known-safe Manim color constants from the current environment.

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

```python
obj.set_color(color)
obj.set_fill(color, opacity=...)
obj.set_stroke(color, width=..., opacity=...)
obj.set_stroke(color, width=..., background=True)
obj.set_opacity(opacity)
obj.scale(factor)
BackgroundRectangle(obj, fill_opacity=..., buff=...)
```

- For `set_stroke(...)`, keep to `color`, `width`, and `opacity`.
- Use `background=True` on `set_stroke(...)` only for readable text backstrokes over busy geometry.
- Do not use undocumented arguments such as `dash_array=...`; use `DashedLine` for dashed geometry.
- Keep color usage consistent and restrained.

```python
obj.set_color(BLUE)
obj.set_fill(BLUE, opacity=0.6)
obj.set_stroke(WHITE, width=2, opacity=1)
label.set_stroke(BLACK, width=4, background=True)
obj.set_opacity(0.5)
obj.scale(1.2)
card = BackgroundRectangle(label, fill_opacity=0.8, buff=0.2)
```

## Timing and Utility Animation

```python
Add(*mobjects)
Wait(run_time=...)
self.add_subcaption(text, duration=...)
self.wait(duration)
```

- Prefer `self.add(...)` and `self.wait(...)` in ordinary scenes.
- Use `self.add_subcaption(...)` or `subcaption=...` on major animations when the scene includes subtitle-worthy narration beats.
- Use `Add(...)` when the insertion must happen inside another animation.

```python
self.camera.background_color = BLACK
self.add(title, graph)
self.add_subcaption("Introduce the graph", duration=2)
self.wait(0.5)
self.play(Add(label))
self.play(Write(title), subcaption="State the key idea", subcaption_duration=2)
self.play(Succession(
    Write(title),
    Wait(0.5),
    FadeIn(subtitle),
))
```

## Entrance Animation

```python
Create(mobject)
Write(mobject)
FadeIn(mobject)
GrowFromCenter(mobject)
GrowFromPoint(mobject, point)
GrowFromEdge(mobject, edge)
GrowArrow(arrow)
SpinInFromNothing(mobject)
DrawBorderThenFill(mobject)
SpiralIn(mobject)
AddTextLetterByLetter(text)
AddTextWordByWord(text)
ShowIncreasingSubsets(group)
ShowSubmobjectsOneByOne(group)
TypeWithCursor(text, cursor)
```

- `Create`, `Write`, and `DrawBorderThenFill` are the default entrance choices.
- `TypeWithCursor(...)` is for `Text`.
- `ShowIncreasingSubsets(...)` keeps previous submobjects visible; `ShowSubmobjectsOneByOne(...)` shows only one at a time.

```python
self.play(ShowIncreasingSubsets(VGroup(a, b, c)))
self.play(ShowSubmobjectsOneByOne(VGroup(step1, step2, step3)))
self.play(Create(circle))
self.play(Write(title))
self.play(FadeIn(obj))
self.play(GrowFromCenter(dot))
self.play(GrowFromPoint(square, ORIGIN))
self.play(GrowFromEdge(rect, LEFT))
self.play(GrowArrow(arrow))
self.play(SpinInFromNothing(poly))
self.play(DrawBorderThenFill(box))
self.play(AddTextLetterByLetter(Text("Step 1")))
self.play(AddTextWordByWord(Text("Now compare the two sides")))
typed = Text("Typing effect")
cursor = Rectangle(height=0.8, width=0.08, fill_opacity=1).move_to(typed[0])
self.play(TypeWithCursor(typed, cursor))
```

## Exit Animation

```python
FadeOut(mobject)
Uncreate(mobject)
Unwrite(mobject)
RemoveTextLetterByLetter(text)
UntypeWithCursor(text, cursor=None)
```

```python
self.play(FadeOut(obj))
self.play(Uncreate(line))
self.play(Unwrite(formula))
self.play(RemoveTextLetterByLetter(Text("Temporary note")))
typed = Text("Typing effect")
cursor = Rectangle(height=0.8, width=0.08, fill_opacity=1).move_to(typed[0])
self.play(UntypeWithCursor(typed, cursor))
```

## Transform Animation

```python
Transform(old_obj, new_obj)
ReplacementTransform(old_obj, new_obj)
TransformFromCopy(source, target)
FadeTransform(source, target)
FadeTransformPieces(source, target)
FadeToColor(mobject, color)
ScaleInPlace(mobject, scale_factor)
ShrinkToCenter(mobject)
MoveToTarget(mobject)
Restore(mobject)
```

- Use `Transform(...)` or `ReplacementTransform(...)` as the default replacement animations.
- `MoveToTarget(...)` requires `mobject.generate_target()` and edits on `mobject.target`.
- `Restore(...)` requires `mobject.save_state()`.

```python
self.play(Transform(old_obj, new_obj))
self.play(ReplacementTransform(obj1, obj2))
self.play(TransformFromCopy(label, formula))
self.play(FadeTransform(shape1, shape2))
self.play(FadeTransformPieces(eq1, eq2))
self.play(FadeToColor(obj, YELLOW))
self.play(ScaleInPlace(obj, 1.2))
self.play(ShrinkToCenter(obj))

obj.generate_target()
obj.target.shift(RIGHT * 2).set_color(GREEN)
self.play(MoveToTarget(obj))

obj.save_state()
self.play(obj.animate.shift(UP).set_opacity(0.3))
self.play(Restore(obj))
```

## Matching Transform Animation

```python
TransformMatchingTex(source, target)
TransformMatchingShapes(source, target)
```

- `TransformMatchingTex(...)` works best when reusable parts are split consistently, for example with `{{...}}`.
- Use `TransformMatchingShapes(...)` for text or shape rearrangements.

```python
eq1 = MathTex("{{a}}^2", "+", "{{b}}^2", "=", "{{c}}^2")
eq2 = MathTex("{{a}}^2", "=", "{{c}}^2", "-", "{{b}}^2")
self.play(TransformMatchingTex(eq1, eq2))

src = Text("the morse code")
tar = Text("here come dots")
self.play(TransformMatchingShapes(src, tar))
```

## Advanced Transform Animation

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

- Prefer `.animate` or ordinary `Transform` unless you need function-based deformation or cyclic replacement.

```python
self.play(ApplyMethod(title.shift, UP))
self.play(ApplyMatrix([[0, -1], [1, 0]], square))
self.play(ClockwiseTransform(circle, square))
self.play(Swap(a, b))
self.play(CyclicReplace(obj1, obj2, obj3))
```

## Movement Animation

```python
MoveAlongPath(mobject, path)
Homotopy(function, mobject)
ComplexHomotopy(function, mobject)
SmoothedVectorizedHomotopy(function, mobject)
PhaseFlow(function, mobject)
```

- Use `MoveAlongPath(...)` for ordinary path-following motion; use the homotopy family when the object itself must deform.

```python
path = Circle(radius=2)
self.play(MoveAlongPath(dot, path))

self.play(Homotopy(
    lambda x, y, z, t: (x + 0.5 * np.sin(PI * t), y, z),
    square,
))
```

## Rotation Animation

```python
Rotate(mobject, angle=..., about_point=...)
Rotating(mobject, angle=..., about_point=..., run_time=...)
```

- `Rotate(...)` is the common choice; `Rotating(...)` is the standalone animation form.

```python
self.play(Rotate(square, angle=PI / 2))
self.play(Rotating(arrow, angle=PI, about_point=ORIGIN, run_time=2))
```

## Emphasis Animation

```python
Flash(mobject)
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

- `ShowPassingFlash(...)` and `ShowPassingFlashWithThinningStrokeWidth(...)` are for stroke-based `VMobject` content.

```python
self.play(Flash(dot, color=YELLOW))
self.play(Indicate(formula))
self.play(Circumscribe(eq))
self.play(FocusOn(dot))
self.play(ApplyWave(title))
self.play(Blink(title, blinks=2))
self.play(ShowPassingFlash(line.copy().set_color(YELLOW)))
self.play(ShowPassingFlashWithThinningStrokeWidth(curve.copy().set_color(BLUE)))
self.play(Wiggle(triangle))
self.play(Broadcast(circle))
```

## Combined Animation

```python
AnimationGroup(*animations)
LaggedStart(*animations, lag_ratio=...)
LaggedStartMap(AnimationClass, group, arg_creator=..., lag_ratio=...)
Succession(*animations)
```

```python
self.play(AnimationGroup(
    FadeIn(a),
    FadeIn(b),
    FadeIn(c),
))

self.play(LaggedStart(
    *[FadeIn(obj) for obj in objs],
    lag_ratio=0.2
))

self.play(LaggedStartMap(
    FadeIn,
    VGroup(a, b, c),
    lag_ratio=0.2,
))

self.play(Succession(
    Write(title),
    FadeIn(subtitle),
    Create(graph),
))
```

## `.animate`

```python
mobject.animate.method(...)
mobject.animate(run_time=..., rate_func=...).method(...)
```

- Use `.animate` for simple property changes and `Transform` for structural replacement.

```python
self.play(obj.animate.shift(RIGHT * 2))
self.play(obj.animate(run_time=2).shift(RIGHT * 2))
self.play(obj.animate.scale(0.5))
self.play(obj.animate.set_color(RED))
self.play(obj.animate.set_opacity(0.3))
self.play(title.animate.to_corner(UL))
```

## Number Animation

```python
ChangeDecimalToValue(decimal_number, target_value)
ChangingDecimal(decimal_number, number_update_func)
```

- Use these animations with `DecimalNumber(...)`.

```python
number = DecimalNumber(0, num_decimal_places=2)
self.add(number)
self.play(ChangeDecimalToValue(number, 10, run_time=2))
self.play(ChangingDecimal(number, lambda a: 10 + 5 * np.sin(PI * a), run_time=2))
```

## Speed Modifier Animation

```python
ChangeSpeed(animation, speedinfo=..., rate_func=...)
```

- Use `ChangeSpeed(...)` only when ordinary `run_time` and `rate_func` are not enough.

```python
self.play(ChangeSpeed(
    dot.animate.shift(RIGHT * 4),
    speedinfo={0.3: 1, 0.6: 0.2, 1: 1},
    rate_func=linear,
))
```

## 3D Camera

```python
self.set_camera_orientation(phi=..., theta=..., zoom=...)
self.move_camera(phi=..., theta=..., zoom=..., run_time=...)
self.begin_ambient_camera_rotation(rate=...)
self.stop_ambient_camera_rotation()
```

## Moving 2D Camera

```python
self.camera.frame.animate.set(width=...)
self.camera.frame.animate.move_to(target)
self.camera.frame.save_state()
Restore(self.camera.frame)
```

- Use these methods inside `MovingCameraScene`.
- Prefer camera motion only when it teaches scale, detail, or comparison better than a static frame.

```python
self.camera.frame.save_state()
self.play(self.camera.frame.animate.set(width=6).move_to(dot), run_time=2)
self.play(Restore(self.camera.frame))
```
- Use these methods inside `ThreeDScene`.

```python
self.set_camera_orientation(
    phi=70 * DEGREES,
    theta=-45 * DEGREES,
    zoom=0.8
)

self.move_camera(
    phi=60 * DEGREES,
    theta=30 * DEGREES,
    zoom=1.0,
    run_time=2
)

self.begin_ambient_camera_rotation(rate=0.1)
self.stop_ambient_camera_rotation()
```

## Fixed Screen Objects

```python
self.add_fixed_in_frame_mobjects(*mobjects)
```

- Use this to keep text or formulas fixed on screen in a `ThreeDScene`.

```python
title = Text("Gyroid Surface")
self.add_fixed_in_frame_mobjects(title)
```

## Dynamic Update

```python
always(method, *args, **kwargs)
f_always(method, *arg_generators)
always_redraw(lambda: ...)
always_shift(mobject, direction, rate=...)
always_rotate(mobject, rate=...)
turn_animation_into_updater(animation, ...)
cycle_animation(animation, ...)
ValueTracker(value)
AnimatedBoundary(vmobject, ...)
TracedPath(callable, ...)
mobject.add_updater(lambda m: ...)          # updater without dt
mobject.add_updater(lambda m, dt: ...)      # updater with elapsed-time dt
mobject.clear_updaters()
```

- Use `always_redraw(...)` when the object itself must be rebuilt from moving dependencies.
- Use `always(...)` or `f_always(...)` when updating an existing object is enough.
- `always_shift(...)` and `always_rotate(...)` are for continuous background motion.
- `ValueTracker(...)` stores changing values; `TracedPath(...)` traces a moving point.
- `turn_animation_into_updater(...)` and `cycle_animation(...)` are advanced patterns.
- Use `add_updater(...)` (or its shorthand `always(...)`) when only position, color, or non-structural properties change — it mutates the object in-place and is cheap. Use `always_redraw(...)` only when the object's structure or content must be rebuilt each frame — it destroys and recreates the object every frame and is expensive. Updaters attached via `add_updater` remain active across `self.play(...)` calls until `clear_updaters()` is called; never call `self.play(...)` inside an updater.

```python
label = always_redraw(lambda: Text("A").next_to(dot, UP))
line = always_redraw(lambda: Line(dot1.get_center(), dot2.get_center()))
always(label.next_to, dot, UP)
always_shift(cloud, RIGHT, rate=0.5)
always_rotate(spinner, rate=PI)

x_tracker = ValueTracker(-2)
self.play(x_tracker.animate.set_value(2), run_time=4)

boundary = AnimatedBoundary(title, colors=[RED, GREEN, BLUE], cycle_rate=2)
path = TracedPath(dot.get_center, stroke_color=YELLOW, stroke_width=4)

# follow anchor
label.add_updater(lambda m: m.next_to(dot, UP))
self.play(dot.animate.shift(RIGHT * 3))
label.clear_updaters()

# dt-based drift
cloud.add_updater(lambda m, dt: m.shift(RIGHT * 0.4 * dt))
self.wait(3)
cloud.clear_updaters()
```

## Common Render Failure Guardrails

- Do not animate conditionally empty redraw outputs.
- Avoid `always_redraw(lambda: thing if cond else VMobject())` when that result will later be passed to `Create`, `FadeOut`, or `Transform`.
- Prefer one stable mobject and control visibility/style instead of swapping to an empty placeholder.
- Before cleanup animations, ensure targets are still valid, on-scene, and non-empty.

Unsafe pattern (can crash at runtime when cleanup starts on an empty target):

```python
arrow = always_redraw(
    lambda: Arrow(A.get_center(), B.get_center()) if flag.get_value() > 0 else VMobject()
)
self.play(Create(arrow))
self.play(flag.animate.set_value(0))
self.play(FadeOut(arrow))
```

Safe pattern (stable identity + visibility control):

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

## Update Animation

```python
UpdateFromFunc(mobject, update_function)
UpdateFromAlphaFunc(mobject, update_function)
MaintainPositionRelativeTo(mobject, tracked)
```

- Use these when the update is local to a single `self.play(...)`.
- For long-lived dependencies, prefer `always_redraw(...)`.

```python
self.play(
    dot.animate.shift(RIGHT * 3),
    UpdateFromFunc(label, lambda m: m.next_to(dot, UP)),
)

self.play(UpdateFromAlphaFunc(
    square,
    lambda m, a: m.set_opacity(0.2 + 0.8 * a),
))

self.play(
    dot.animate.shift(UP * 2),
    MaintainPositionRelativeTo(label, dot),
)
```

## Code Organization

```python
class MyScene(Scene):
    def construct(self):
        self.intro()       # opening section
        self.main_part()   # main teaching section
        self.summary()     # closing section

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
