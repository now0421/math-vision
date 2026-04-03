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
- `AngleBisector(...)`
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

## 2.5 Object Naming Conventions (from Official Manual)

GeoGebra enforces strict naming rules. The case of the first letter determines the object type for coordinate-pair definitions, and many built-in names are reserved.

### Points
Point names **must start with an uppercase letter**.
```geogebra
A = (0, 0)
P = (3, 2)
C_1 = (1, 4)
```
A coordinate pair assigned to a lowercase name creates a **vector**, not a point.

### Vectors
Vector names **must start with a lowercase letter**.
```geogebra
v = (1, 3)
u = (3; 90°)
```
A coordinate pair assigned to an uppercase name creates a **point**, not a vector.

### Lines, circles, and conics via implicit equations
When defining objects through implicit equations in the input bar, use the **colon syntax**:
```geogebra
g: y = x + 3
c: (x-1)^2 + (y - 2)^2 = 4
hyp: x^2 - y^2 = 2
```
When using construction commands, use normal assignment:
```geogebra
g = Line(A, B)
c = Circle(O, 3)
```

### Functions
Name functions with their variable in parentheses:
```geogebra
f(x) = x^2 - 2x + 1
h(x) = 2 x + 4
trig(x) = sin(x)
```

### Subscripts
Use an underscore to create subscripts:
- Single character subscript: `A_1` renders as A₁
- Multi-character subscript: `s_{AB}` renders as s_AB (use curly braces)

### Reserved labels (CANNOT be used as object names)
The following names are reserved by GeoGebra and must never be used as variable or object names:

**Coordinates and axes:** `x`, `y`, `z`, `xAxis`, `yAxis`, `zAxis`

**Math functions:** `abs`, `sgn`, `sqrt`, `exp`, `log`, `ln`, `ld`, `lg`, `cos`, `sin`, `tan`, `acos`, `arcos`, `arccos`, `asin`, `arcsin`, `atan`, `arctan`, `cosh`, `sinh`, `tanh`, `acosh`, `arcosh`, `arccosh`, `asinh`, `arcsinh`, `atanh`, `arctanh`, `atan2`, `erf`, `floor`, `ceil`, `round`, `random`, `conjugate`, `arg`, `gamma`, `gammaRegularized`, `beta`, `betaRegularized`, `sec`, `csc`, `cosec`, `cot`, `sech`, `csch`, `coth`

### Special constants
- **π** (pi): the circle constant
- **ℯ** (Euler's number): used for exponential functions like ℯ^x
- **ί** (imaginary unit): for complex numbers like z = 3 + ί

When `e` and `i` are not already assigned to existing objects, GeoGebra automatically reads them as ℯ and ί respectively. Avoid using bare `e` or `i` as object names.

### Auto-naming
If you do not manually assign a name, GeoGebra assigns new object names in alphabetical order.

### Practical guidance for LLM generation
- Always use uppercase-starting names for points, even helper points.
- Always use lowercase-starting names for vectors.
- If you need a numeric variable, choose a name that is not reserved: prefer `dist`, `len`, `rad`, `val` over `d`, `l`, `r` (which are unambiguous but short), and absolutely avoid `x`, `y`, `z`, `e`, `i`.
- For lines defined by equation, use the colon syntax. For lines defined by command, use assignment.

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

### Fixed anchor vs constrained moving point
Use these as distinct semantic patterns:

Independent fixed anchor:
```geogebra
A = (-4, 2)
B = (4, 2)
```

Constrained moving point on a path:
```geogebra
l = Line((-7, 0), (7, 0))
P = Point(l)
```

Guidance:

- A fixed anchor is an independently defined point with no upstream geometric dependency.
- A constrained moving point is not a free point. It must be created from the path or from an equivalent dependency-safe parameterization.
- If the storyboard says a point can move on `l`, do not write `P = (-2, 0)` unless the teaching goal is explicitly to start with a free point instead of a constrained one.
- If the storyboard requires a specific starting area on the path, choose a dependency-safe initialization near that location instead of breaking the constraint.

### Bounded motion
If a point may move only on a finite part of a locus, encode the bound in the construction:

```geogebra
s = Segment(( -5, 0), (5, 0))
P = Point(s)
```

or use a bounded parameter:

```geogebra
t = 0
P = (t, 0)
```

Guidance:

- Prefer `Point(segment)` when the storyboard describes motion on a finite visible segment.
- Prefer a bounded slider/parameter only when the motion itself is the teaching object or when no natural bounded path object exists.
- Do not leave a point free on an unbounded line if the storyboard specifies a finite interval or visible range.

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

### Additional Angle forms
```geogebra
gamma = Angle(v)              // angle between x-axis and vector v
delta = Angle(P)              // angle between x-axis and position vector of point P
angles = Angle(poly)          // all interior angles of a polygon (returned individually)
epsilon = Angle(conic1)       // twist angle of a conic's major axis
numAngle = Angle(1.2)         // converts a number into an angle (result in [0,360°] or [0,2π])
fixedAngle = Angle(A, B, 45°) // angle of size 45° drawn from A with apex B; also creates the rotated point
vecAngle = Angle(u, v)        // angle between two vectors
lineAngle = Angle(line1, line2) // angle between direction vectors of two lines
```

3D-specific angle overloads:
```geogebra
planeAngle = Angle(line1, plane1)             // angle between a line and a plane
dihedralAngle = Angle(plane1, plane2)         // angle between two planes
dirAngle = Angle(A, B, C, zAxis)             // angle with explicit direction (line or plane); bypasses 3D display restrictions
```

Guidance:

- In `Angle(B, A, C)`, the **middle argument is the vertex**.
- `Angle(B, A, C)` measures the angle **counterclockwise** from ray AB to ray AC at vertex A.
- To mark a specific small angle sector, choose point order so the counterclockwise sweep covers the intended region.
- **Concrete example**: Given point A at upper-left, vertex P on a horizontal line, and point B at upper-right, to mark the incidence angle (small angle between segment PA and the rightward horizontal):
  - `Angle((x(P)+1, 0), P, A)` — sweeps CCW from rightward ray to ray PA = small angle above the line. Correct.
  - `Angle(A, P, (x(P)+1, 0))` — sweeps CCW from ray PA to rightward ray = large angle (~330°). Wrong.
- For equal-angle markers (e.g., reflection angles), ensure both `Angle(...)` calls sweep the same-sized small sector on their respective sides.
- `Angle(<Polygon>)` creates all angles in mathematically positive (CCW) orientation. If the polygon was created CCW, you get interior angles; if CW, you get exterior angles.
- `Angle(<Number>)` converts a number into an angle in [0, 360°] or [0, 2π] depending on the angle unit setting.
- `Angle(<Vector>, <Vector>)` returns the angle between two vectors.
- `Angle(<Point>, <Apex>, <Angle>)` creates an angle of the given size and also creates a rotated point via `Rotate(<Point>, <Angle>, <Apex>)`.
- `Angle(<Point>, <Point>, <Point>, <Direction>)` (3D only) uses an explicit line or plane as direction to bypass standard 3D angle display restrictions.
- Prefer `Angle(...)` over hardcoded text labels like `"30 deg"` unless the angle is intentionally fixed.
- Use `SetFilling(alpha, 0.3)` to create a filled angle sector for visual emphasis.
- **Use `Angle(...)` as the sole method for angle markers.** Do not use `CircularArc` for angle marking — it draws a decorative arc unrelated to angle measurement and produces incorrect visual output.
- For dynamic geometry, define the angle from the actual objects so it updates automatically.

---

## 3.6 Text and dynamic text

### Basic text
```geogebra
t1 = Text("Construct the perpendicular bisector")
```

### Substitution control
```geogebra
t2a = Text(c, true)              // substitutes variable values into the formula
t2b = Text(c, false)             // shows the symbolic formula with variable names
```
`Text(<Object>, <Boolean>)` — when `true`, variable values are substituted; when `false`, variable names are shown.

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
Full form: `Text(<Object>, <Point>, <Boolean for Substitution>, <Boolean for LaTeX>)`

### Positioned text with alignment
```geogebra
t6 = Text(alpha, (2, 1), true, true, -1, 0)
```
Full form: `Text(<Object>, <Point>, <Boolean for Substitution>, <Boolean for LaTeX>, <H-alignment [-1|0|1]>, <V-alignment [-1|0|1]>)`

The last two parameters control horizontal and vertical alignment:
- `-1`: shift left / shift down
- `0`: center horizontally / vertically at the point
- `1`: shift right / shift up

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
Z = Intersect(lineAB, c, 2)     // 2nd intersection point (index-based)
W = Intersect(f, g, C)          // intersection near initial point C (iterative)
pts = Intersect(f, g, -1, 2)    // all intersections in x ∈ [-1, 2]
V = Intersect(curve1, curve2, 0, 2)  // curve intersection seeded at parameter values
P = Point(lineAB)
Q = Point(c)
```

Guidance:

- Prefer `Intersect(...)` over solving coordinates manually.
- `Intersect(<Object>, <Object>, <Index>)` returns the n-th intersection point. Useful when two objects have multiple intersections.
- `Intersect(<Object>, <Object>, <Initial Point>)` uses an iterative numerical method seeded at the initial point.
- `Intersect(<Function>, <Function>, <Start x-Value>, <End x-Value>)` returns all intersection points in the given x-interval.
- `Intersect(<Curve1>, <Curve2>, <Parameter1>, <Parameter2>)` finds one intersection point using an iterative method starting at the given parameter values for each curve.
- Prefer `Point(...)` over fake coordinate-based attachment.

---

## 4.2 Parallel / perpendicular / bisector constructions

```geogebra
perp = PerpendicularLine(A, lineAB)
perpSeg = PerpendicularLine(A, s)
perpVec = PerpendicularLine(A, u)
perpLL = PerpendicularLine(line1, line2)  // perpendicular to both lines through their intersection
para = ParallelLine(C, lineAB)
pb = PerpendicularBisector(A, B)
pbSeg = PerpendicularBisector(s)
bis = AngleBisector(B, A, C)
bisLines = AngleBisector(line1, line2)
```

Guidance:

- `PerpendicularLine(<Point>, <Line|Segment|Vector>)` creates a line through the point perpendicular to the given direction.
- `PerpendicularLine(<Line>, <Line>)` creates a perpendicular line to both lines through their intersection point.
- `PerpendicularBisector` accepts either two points or a segment.
- `AngleBisector(<Point>, <Point>, <Point>)` returns the bisector of the angle at the middle point (apex).
- `AngleBisector(<Line>, <Line>)` returns **both** angle bisectors of the two lines.
- Use these high-level commands instead of slope-based hand constructions unless the lesson is explicitly about coordinate methods.

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
obj3 = Rotate(obj1, 60°)           // rotation around the origin
obj4 = Rotate(obj1, 60°, A)        // rotation around point A
obj5 = Reflect(obj1, lineAB)       // reflection across a line
obj6 = Reflect(obj1, A)            // reflection through a point
obj7 = Reflect(obj1, c)            // inversion with respect to a circle
obj8 = Dilate(obj1, 1.5, A)        // dilation from center A by factor 1.5
obj9 = Dilate(obj1, 2)             // dilation from origin by factor 2
```

Officially supported reflection targets include:

- point
- line
- circle
- plane (3D only)

Additional `Rotate` forms:
- `Rotate(<Object>, <Angle>)` — rotates around the origin.
- `Rotate(<Object>, <Angle>, <Point>)` — rotates around a given point.
- `Rotate(<Object>, <Angle>, <Axis of Rotation>)` — 3D rotation around an axis.

Additional `Dilate` forms:
- `Dilate(<Object>, <Factor>)` — dilates from the origin.
- `Dilate(<Object>, <Factor>, <Center Point>)` — dilates from a given center point.

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
fp2(x) = Derivative(f, 2)           // second derivative
fpx(x) = Derivative(x^3 * y^2, y)   // partial derivative w.r.t. y
areaF = Integral(f, 0, 2)
```

### Tangent overloads
```geogebra
t1 = Tangent(P, f)                  // tangent to function at point P
t2 = Tangent(2, f)                  // tangent to function at x = 2
t3 = Tangent(P, conic1)             // tangent(s) through point to conic
t4 = Tangent(lineAB, conic1)        // tangent(s) to conic parallel to lineAB
t5 = Tangent(c1, c2)                // common tangents of two circles (up to 4)
t6 = Tangent(P, curve1)             // tangent to parametric curve at point P on the curve
t7 = Tangent(P, implicitCurve1)     // tangent to implicit curve at point P
```

### Derivative overloads
```geogebra
fp(x) = Derivative(f)                // first derivative
fp2(x) = Derivative(f, 2)            // second derivative
fpy = Derivative(x^3 * y^2, y)       // partial derivative w.r.t. y
fpyn = Derivative(x^3 + 3*x*y, x, 2) // n-th partial derivative w.r.t. variable
curveD = Derivative(Curve(cos(t), sin(t), t, 0, pi))  // derivative of parametric curve
curveDn = Derivative(Curve(cos(t), sin(t), t, 0, pi), 2)  // n-th derivative of parametric curve
```

Note: You can use `f'(x)` instead of `Derivative(f)`, or `f''(x)` instead of `Derivative(f, 2)`, and so on.

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
SetColor(s, 0, 0, 1)
SetColor(s, "#0000FF")
SetColor(poly, "#80FF0000")
SetLineThickness(s, 6)
SetPointStyle(A, 0)
SetFilling(poly, 0.25)
ShowLabel(A, true)
SetConditionToShowObject(helperLine, showHelpers)
```

Important corrections:

- `SetColor(<Object>, <Red>, <Green>, <Blue>)` is an official form, where each component is normally interpreted on the 0 to 1 scale. A number _t_ outside [0,1] is mapped back into the interval.
- `SetColor(<Object>, <"Color">)` is also official. In GeoGebraScript, you **must** use English color names.
- Official text color inputs also include hexadecimal strings of the form `#RRGGBB` and `#AARRGGBB`.
- `#AARRGGBB` includes alpha, red, green, and blue; `AA` controls transparency (`01` = fully transparent, `FF` = fully opaque).
- `SetLineThickness(<Object>, <Number>)` displays as _N/2_ pixels where _N_ is the given number.
- `SetFilling(<Object>, <Number>)` sets opacity; the number must be in [0,1] where 0 = transparent and 1 = fully opaque. Numbers outside this interval are ignored.
- Natural-language phrases like 鈥渂lue segment with medium thickness鈥?are **guidance**, not executable GeoGebra syntax.
- If you want a command-level manual for an LLM, distinguish clearly between:
  - executable GeoGebra commands
  - non-executable visual recommendations
- Scripting commands cannot be nested inside object constructors.

Preferred color-name discipline for LLM output:

- Use English names only.
- Use the official color names exactly as documented (with spaces where needed):
  `"Black"`, `"Dark Gray"`, `"Gray"`, `"Silver"`, `"Light Gray"`, `"White"`,
  `"Dark Blue"`, `"Blue"`, `"Light Blue"`, `"Aqua"`, `"Cyan"`, `"Turquoise"`,
  `"Dark Green"`, `"Green"`, `"Light Green"`, `"Lime"`,
  `"Maroon"`, `"Crimson"`, `"Red"`, `"Pink"`, `"Orange"`, `"Light Orange"`, `"Gold"`, `"Yellow"`, `"Light Yellow"`,
  `"Brown"`, `"Magenta"`, `"Indigo"`, `"Purple"`, `"Light Purple"`, `"Violet"`, `"Light Violet"`.
- Note: In `SetColor(<Object>, <"Color">)` inside GeoGebraScript, you **must** use English color names.
- If a very specific color is required, prefer an official hex string (`#RRGGBB` or `#AARRGGBB`) over an invented prose description.

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

## 7.4 Do not confuse independence with freedom

Good:
```geogebra
A = (-4, 2)
B = (4, 2)
l = Line((-7, 0), (7, 0))
P = Point(l)
AP = Segment(A, P)
PB = Segment(P, B)
```

Avoid:
```geogebra
A = (-4, 2)
B = (4, 2)
l = Line((-7, 0), (7, 0))
P = (-2, 0)
AP = Segment(A, P)
PB = Segment(P, B)
```

Why:

- `A` and `B` are independent anchors.
- `P` is not independent if the problem states `P` lies on `l`.
- A point can be independent-and-fixed, independent-and-draggable, or dependent-and-draggable. Do not collapse those cases into one generic “free point” pattern.

## 7.5 Avoid guessed overloads

Do not invent non-canonical syntax such as:

```geogebra
P = Point(l, -2, 0)
Bperp = Point(l, 4, 0)
```

Prefer official dependency-safe commands such as:

```geogebra
P = Point(l)
Bperp = Intersect(l, PerpendicularLine(B, l))
```

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

### Correction 7: `AngularBisector` is not the official command name
The correct official command name is `AngleBisector`, not `AngularBisector`.
```geogebra
bis = AngleBisector(B, A, C)
bisLines = AngleBisector(line1, line2)
```

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
12. Use `AngleBisector(...)`, not `AngularBisector(...)`.
13. Use official color names with correct spacing (e.g. `"Dark Blue"`, not `"DARKBLUE"`).

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
