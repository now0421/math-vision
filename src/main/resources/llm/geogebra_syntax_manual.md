# GeoGebra LLM Reference Manual (Revised Against the Official Manual)

This manual is intended as a **reference specification for LLMs** that generate GeoGebra Classic constructions, object definitions, or step-by-step geometry instructions.

It prioritizes:

1. **official command correctness**
2. **stable dependency structure**
3. **clear distinction between construction commands and styling / scripting commands**
4. **readable, maintainable, classroom-friendly output**

---

## 1. Scope and Output Discipline

When generating GeoGebra content, separate the output into these layers:

### A. Construction layer
Use official object-creating commands such as:

- `Point(...)`
- `Line(...)`
- `Segment(...)`
- `Ray(...)`
- `Circle(...)`
- `Polygon(...)`
- `Midpoint(...)`
- `Center(...)`
- `Intersect(...)`
- `PerpendicularLine(...)`
- `ParallelLine(...)`
- `PerpendicularBisector(...)`
- `AngularBisector(...)`
- `Reflect(...)`
- `Rotate(...)`
- `Translate(...)`
- `Dilate(...)`
- `Text(...)`

### B. Measurement / analytic layer
Use commands that compute values or derived analytic objects:

- `Distance(...)`
- `Length(...)`
- `Area(...)`
- `Perimeter(...)`
- `Slope(...)`
- `Derivative(...)`
- `Integral(...)`
- `Tangent(...)`
- `Angle(...)`

### C. Styling / visibility / scripting layer
Use scripting commands **only if needed** for appearance or interaction:

- `SetColor(...)`
- `SetLineThickness(...)`
- `SetLineStyle(...)`
- `SetPointStyle(...)`
- `SetPointSize(...)`
- `SetFilling(...)`
- `ShowLabel(...)`
- `SetConditionToShowObject(...)`
- `SetCaption(...)`
- `SetFixed(...)`
- `StartAnimation(...)`

Important:

- Scripting commands **do not return objects and cannot be nested inside object-creating commands**.
- Do not mix natural-language styling instructions into a command list unless the downstream system explicitly expects prose.
- If you need a pure executable command sequence, output only valid GeoGebra commands.

---

## 2. Recommended Generation Order

Generate in this order:

1. Define the base objects first.
2. create dependent geometric objects
3. create measurements / dynamic text
4. apply style / visibility adjustments
5. add sliders or animation controls only if necessary

This preserves a clean dependency graph and reduces fragile constructions.

---

## 3. Core Object Patterns

## 3.1 Points

### Free point
```geogebra
A = (0, 0)
B = (4, 0)
```
Use for anchors, vertices, and draggable references.

### Point on object
```geogebra
P = Point(c)
Q = Point(lineAB)
R = Point(f)
```
Official behavior: `Point(<Object>)` creates a point on a path/object; the point stays constrained to that object.

### Midpoint and center
```geogebra
M = Midpoint(A, B)
N = Midpoint(s)
O = Center(c)
```
Notes:

- `Midpoint(A, B)` is correct for two points.
- `Midpoint(s)` is correct for a segment.
- For a conic center, prefer `Center(c)` when you mean the geometric center explicitly.
- `Midpoint(c)` may also return the center for a conic, but for LLM clarity `Center(c)` is usually less ambiguous.

Do not manually compute midpoint coordinates unless coordinate derivation is the teaching objective.

---

## 3.2 Linear objects

```geogebra
s = Segment(A, B)
lineAB = Line(A, B)
r = Ray(A, B)
u = Vector(A, B)
```

Use:

- `Segment` for finite edges
- `Line` for infinite supporting lines
- `Ray` for one-sided directed geometry
- `Vector` for displacement or translation input

---

## 3.3 Circles and arcs

### Circle
```geogebra
c1 = Circle(O, A)
c2 = Circle(O, 3)
c3 = Circle(A, B, C)
```
Official forms include:

- `Circle(<Point>, <Point>)`
- `Circle(<Point>, <Radius Number>)`
- `Circle(<Point>, <Segment>)`
- `Circle(<Point>, <Point>, <Point>)`

### Arc commands
Use the correct arc command depending on the construction intent.

```geogebra
arc1 = CircularArc(O, A, B)
arc2 = Arc(c1, A, B)
arc3 = CircumcircularArc(A, B, C)
```

Guidance:

- `CircularArc(O, A, B)` uses the center / midpoint object as first input.
- `Arc(c1, A, B)` returns the directed arc on an existing circle from `A` to `B`.
- `CircumcircularArc(A, B, C)` creates an arc through three points.
- Do not assume all 鈥渁rc-like鈥?constructions are interchangeable.

### Sector commands
```geogebra
sec1 = CircularSector(O, A, B)
```
Use a sector command only when you truly need a filled angular region.

---

## 3.4 Polygons and regions

```geogebra
tri = Polygon(A, B, C)
poly = Polygon(A, B, C, D)
sq = Polygon(A, B, 4)
```

Important correction:

- For a **regular polygon command form**, the official documented command is `Polygon(A, B, n)`.
- 鈥淩egular Polygon鈥?is primarily the **tool name**, not the preferred command name to teach an LLM as canonical syntax.

Also valid:

```geogebra
poly2 = Polygon({A, B, C, D})
```

### Semicircle
```geogebra
sc = Semicircle(A, B)
```
Use the dedicated command when the object is specifically a semicircle.

---

## 3.5 Angles

```geogebra
alpha = Angle(B, A, C)
beta = Angle(line1, line2)
```

Guidance:

- In `Angle(B, A, C)`, the **middle argument is the vertex**.
- Prefer `Angle(...)` over hardcoded text labels like `"30 deg"` unless the angle is intentionally fixed.
- For dynamic geometry, define the angle from the actual objects so it updates automatically.

---

## 3.6 Text and dynamic text

### Basic text
```geogebra
t1 = Text("Construct the perpendicular bisector")
```

### Dynamic text
```geogebra
d1 = Distance(A, B)
t2 = Text("Length AB = " + d1)
t3 = Text("alpha = " + Round(alpha, 1) + " deg")
```

### Positioned text
```geogebra
t4 = Text("A note", (2, 1))
t5 = Text(alpha, (2, 1), true, true)
```

Guidance:

- `Text(...)` supports static, dynamic, mixed, and LaTeX text.
- Prefer dynamic text when a displayed value should update with the construction.
- Keep text concise and instructional.

---

## 4. Standard Construction Commands

## 4.1 Intersection and incidence

```geogebra
X = Intersect(lineAB, c)
Y = Intersect(c, d)
P = Point(lineAB)
Q = Point(c)
```

Guidance:

- Prefer `Intersect(...)` over solving coordinates manually.
- Prefer `Point(...)` over fake coordinate-based attachment.

---

## 4.2 Parallel / perpendicular / bisector constructions

```geogebra
perp = PerpendicularLine(A, lineAB)
para = ParallelLine(C, lineAB)
pb = PerpendicularBisector(A, B)
bis = AngularBisector(B, A, C)
```

Use these high-level commands instead of slope-based hand constructions unless the lesson is explicitly about coordinate methods.

---

## 4.3 Measurements

```geogebra
d1 = Distance(A, B)
len1 = Length(s)
area1 = Area(poly)
per1 = Perimeter(poly)
m = Slope(lineAB)
```

Guidance:

- Use measurement commands for display, validation, or dynamic text.
- Do not replace exact geometry with numerical approximations unless the task is explicitly numerical.

---

## 4.4 Transformations

```geogebra
obj2 = Translate(obj1, u)
obj3 = Rotate(obj1, 60 deg, A)
obj4 = Reflect(obj1, lineAB)
obj5 = Reflect(obj1, A)
obj6 = Reflect(obj1, c)
obj7 = Dilate(obj1, 1.5, A)
```

Officially supported reflection targets include:

- point
- line
- circle

Guidance:

- Prefer transformation commands over manually entering transformed coordinates.
- If the transformed object should remain dynamically linked to the source, always build it with the transformation command.

---

## 4.5 Functions and calculus objects

```geogebra
f(x) = x^2 - 2x + 1
g(x) = sin(x)
P = Point(f)
t = Tangent(P, f)
fp(x) = Derivative(f)
areaF = Integral(f, 0, 2)
```

Guidance:

- Use named functions for readability.
- Prefer direct commands like `Derivative(...)` and `Tangent(...)`.
- Keep algebraic expressions simple when teaching.

---

## 5. Sliders, Variables, and Dynamic Control

### Numeric variables
```geogebra
a = 3
r = 2.5
```

### Slider-driven point
```geogebra
t = 0
P = (t, t^2)
```

Guidance:

- A variable can later be turned into or replaced by a slider in GeoGebra.
- Keep slider count low.
- Prefer one meaningful parameter over many unrelated controls.
- If the point is supposed to move on a specific object, prefer `Point(path)` or a parameterized definition that truly respects that path.

---

## 6. Styling and Visibility: What Is Actually Official Syntax

If the downstream consumer expects executable commands, use official scripting commands such as:

```text
SetColor(s, "blue")
SetLineThickness(s, 6)
SetPointStyle(A, 0)
SetFilling(poly, 0.25)
ShowLabel(A, true)
SetConditionToShowObject(helperLine, showHelpers)
```

Important corrections:

- Natural-language phrases like 鈥渂lue segment with medium thickness鈥?are **guidance**, not executable GeoGebra syntax.
- If you want a command-level manual for an LLM, distinguish clearly between:
  - executable GeoGebra commands
  - non-executable visual recommendations
- Scripting commands cannot be nested inside object constructors.

Recommended visual policy:

- main objects: stronger emphasis
- helper objects: lighter / dashed / thinner
- temporary scaffolding: hidden when not instructionally important
- labels: only on important objects

---

## 7. Dependency Rules for LLM Output

## 7.1 Prefer dependency over hardcoding

Good:
```geogebra
M = Midpoint(A, B)
pb = PerpendicularBisector(A, B)
X = Intersect(line1, line2)
A1 = Reflect(A, lineAB)
```

Avoid unless coordinate derivation is the lesson goal:
```geogebra
M = ((x(A) + x(B)) / 2, (y(A) + y(B)) / 2)
```

## 7.2 Preserve semantic attachment

If an object depends on another object, define it from that source object directly.

Examples:

- intersection 鈫?`Intersect(...)`
- point on circle / line / function 鈫?`Point(...)`
- reflected object 鈫?`Reflect(...)`
- midpoint 鈫?`Midpoint(...)`
- center 鈫?`Center(...)`

## 7.3 Keep dynamic objects truly dynamic

If a base point is dragged or a slider changes:

- dependent objects should recompute automatically
- measurements should update
- dynamic text should update
- transformed objects should stay attached

Do not freeze dependent geometry into static coordinates.

---

## 8. Layout Guidance

Use moderate coordinates when possible:

```geogebra
A = (0, 0)
B = (4, 0)
C = (1.5, 3)
```

Guidance:

- avoid extreme coordinates unless mathematically necessary
- leave room for labels and helper lines
- separate major anchors enough so circles, bisectors, and text remain readable
- visually distinguish final objects from scaffolding

---

## 9. Recommended Canonical Patterns

## 9.1 Triangle geometry
```geogebra
A = (0, 0)
B = (4, 0)
C = (1.5, 3)
tri = Polygon(A, B, C)
M = Midpoint(B, C)
med = Line(A, M)
```

## 9.2 Circle and chord geometry
```geogebra
O = (0, 0)
A = (3, 0)
c = Circle(O, A)
B = Point(c)
chord = Segment(A, B)
pb = PerpendicularBisector(A, B)
```

## 9.3 Reflection construction
```geogebra
A = (1, 2)
l = Line((0, 0), (4, 1))
A1 = Reflect(A, l)
seg = Segment(A, A1)
midAA1 = Midpoint(A, A1)
```

## 9.4 Function and tangent
```geogebra
f(x) = x^2
P = Point(f)
t = Tangent(P, f)
```

These are short, official, dependency-safe patterns.

---

## 10. Common Corrections to the Original Draft

These are the main places where the original draft should be corrected or tightened.

### Correction 1: 鈥淩egularPolygon鈥?should not be taught as the canonical command
Use:
```geogebra
sq = Polygon(A, B, 4)
```
not a non-canonical `RegularPolygon(...)` command form.

### Correction 2: distinguish tool names from command names
Examples:

- **Regular Polygon** is a tool name.
- the command form is documented under `Polygon(...)`.
- **Midpoint or Center** is a tool name.
- the command names are `Midpoint(...)` and `Center(...)`.

### Correction 3: style prose is not executable syntax
Phrases like:

- 鈥渂lue segment with medium thickness鈥?- 鈥渄ashed gray helper line鈥?- 鈥渓ightly filled polygon鈥?
are good visual guidance, but not command syntax.
If executable syntax is required, use commands such as:

```text
SetColor(s, "blue")
SetLineThickness(s, 6)
SetFilling(poly, 0.2)
```

### Correction 4: for conic centers, `Center(...)` is clearer than `Midpoint(...)`
While `Midpoint(<Conic>)` can return the center for a conic, an LLM reference should prefer:
```geogebra
O = Center(c)
```
for semantic clarity.

### Correction 5: arc commands are not interchangeable
Do not collapse `CircularArc`, `Arc`, and `CircumcircularArc` into a single generic 鈥渁rc鈥?pattern.

### Correction 6: scripting commands are non-nesting side-effect commands
These commands change properties but do not create returnable objects, so they should be generated in a separate styling / control phase.

---

## 11. Recommended Rules for LLMs

1. Prefer official documented commands over guessed aliases.
2. Distinguish clearly between **tool names** and **command names**.
3. Build geometry from base objects to dependent objects.
4. Prefer semantic dependency commands (`Midpoint`, `Intersect`, `Reflect`, `Center`) over manually computed coordinates.
5. Use `Polygon(A, B, n)` as the canonical regular polygon command form.
6. Use `Center(c)` when you explicitly want the center of a conic.
7. Use the correct arc command for the intended geometric meaning.
8. Treat style commands as optional scripting commands, not as part of object creation.
9. Keep coordinates moderate and layout readable.
10. Keep dynamic objects truly dynamic.
11. When in doubt, prefer the shortest official command that preserves dependency correctly.

---

## 12. Minimal Output Template for LLM Generation

When generating GeoGebra content, prefer this structure:

### Construction
```geogebra
A = (0, 0)
B = (4, 0)
lineAB = Line(A, B)
P = Point(lineAB)
M = Midpoint(A, B)
pb = PerpendicularBisector(A, B)
```

### Measurements / text
```geogebra
dAB = Distance(A, B)
t1 = Text("AB = " + dAB)
```

### Optional styling
```text
SetColor(lineAB, "blue")
SetLineThickness(lineAB, 6)
SetFilling(poly, 0.2)
ShowLabel(M, true)
```

This separation is robust, readable, and easy for downstream systems to validate.
