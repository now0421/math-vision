# GeoGebra Syntax for LLM Output

This manual is the project-local GeoGebra prompt reference. It is based on
GeoGebra command syntax and expanded from the multi-chart-draw skill guide, but
it keeps the stricter construction rules required by this renderer.

## Output Contract

- Emit one executable GeoGebra command per line.
- Prefer `name = Command(...)` for every object that will be styled, reused, or
  referenced later.
- Create coordinate points with `Point({x, y})` or `Point({x, y, z})`; do not
  use bare coordinate assignment such as `A = (1, 2)`.
- Use `Point(path)` or `PointIn(region)` for draggable points constrained by
  existing geometry.
- Use `Slider(...)` when motion, angle, scale, opacity, or animation state must
  stay inside an explicit numeric range.
- Use `RigidPolygon(...)` for fixed-size shapes that must remain draggable and
  rotatable.
- Use `Polygon(...)` for triangles, rectangles, and regular polygons; do not use
  pseudo-commands such as `Triangle(...)` or `Rectangle(...)`.
- Apply style and visibility commands after all referenced objects exist.
- Keep object names ASCII identifiers: `A`, `B`, `pointA`, `circle1`,
  `mainFunction`.
- Prefer `pi` over unicode pi and numeric radians over degree symbols in
  generated code.

## Command Line Shape

Use these safe line forms:

```geogebra
A = Point({0, 0})
B = Point({4, 0})
f(x) = x^2 - 2x + 1
lineAB = Line(A, B)
SetColor(lineAB, "Blue")
```

Use comments only outside executable command blocks. The runtime strips ordinary
commentary before replaying commands, but prompt examples should stay command
only.

## Point

```text
Point( <Object> )                 // point constrained to an existing path or object
Point( <Object>, <Parameter> )    // parameter chooses the initial position on that object
Point( <Point>, <Vector> )        // vector is a displacement from the given point
Point( <List> )                   // list of two or three coordinates
PointIn( <Region> )               // point constrained to the interior of the region
```

```geogebra
fixedPoint = Point({0, 0})
freePoint = Point({3, 1})
point3d = Point({1, 2, 3})
pointOnLine = Point(lineAB)
pointOnCircle = Point(circle1)
pointOnSegmentAtParameter = Point(segmentAB, 0.25)
pointInTriangle = PointIn(triangle)
```

To keep a constructed point fixed after creation, apply `SetFixed(fixedPoint,
true)` as a later scripting command rather than mixing it into an
assignment-style construction example.

## Vector

```text
Vector( <Point> )
Vector( <Start Point>, <End Point> )
```

```geogebra
vectorOA = Vector(A)
vectorAB = Vector(A, B)
vector1 = Vector(Point({3, 2}))
```

For displacement by numeric coordinates, create a point first and then build the
vector. This keeps syntax symmetric with the point rule.

## Line

```text
Line( <Point>, <Point> )
Line( <Point>, <Parallel Line> )    // line through the point parallel to the given line
Line( <Point>, <Direction Vector> ) // vector gives direction only
```

```geogebra
lineAB = Line(A, B)
parallelThroughC = Line(C, lineAB)
directedLine = Line(A, vectorAB)
```

## Segment

```text
Segment( <Point>, <Point> )
Segment( <Point>, <Length> )
```

```geogebra
segmentAB = Segment(A, B)
fixedLengthSegment = Segment(A, 3)
```

## Ray

```text
Ray( <Start Point>, <Point> )
Ray( <Start Point>, <Direction Vector> )
```

```geogebra
rayAB = Ray(A, B)
rayWithDirection = Ray(A, vectorAB)
```

## Circle

```text
Circle( <Point>, <Radius Number> )
Circle( <Point>, <Segment> )
Circle( <Point>, <Point> )           // center and one point on the circle
Circle( <Point>, <Point>, <Point> )  // circumcircle through three non-collinear points
```

```geogebra
circleByRadius = Circle(A, 3)
circleThroughPoint = Circle(A, B)
circumcircle = Circle(A, B, C)
circleWithSegmentRadius = Circle(A, segmentAB)
```

## Polygon

```text
Polygon( <Point>, ..., <Point> )
Polygon( <Point>, <Point>, <Number of Vertices> )  // regular polygon; last parameter is total vertices
Polygon( <List of Points> )
RigidPolygon( <Polygon> )                          // rigid draggable copy; first vertex translates, second rotates
RigidPolygon( <Polygon>, <Offset x>, <Offset y> )  // rigid copy shifted by the given offset
RigidPolygon( <Free Point>, ..., <Free Point> )     // build a rigid polygon from free vertices
```

```geogebra
triangle = Polygon(A, B, C)
rectangle = Polygon(A, B, C, D)
polygon1 = Polygon(A, B, C, D, E)
regularPentagon = Polygon(A, B, 5)
pointList = {A, B, C, D}
polygonFromList = Polygon(pointList)
rigidRectangle = RigidPolygon(A, B, C, D)
rigidPolygon = RigidPolygon(polygon1)
```

## Slider

```text
Slider( <Min>, <Max>, <Increment>, <Speed>, <Width>, <Is Angle>, <Horizontal>, <Animating>, <Boolean Random> ) // range, step, speed, width, angle-mode, orientation, auto-animation, random
```

```geogebra
position = Slider(-5, 5, 0.1, 1, 140, false, true, false, false)
rotationAngle = Slider(-0.785, 0.785, 0.01, 1, 140, true, true, false, false)
scaleFactor = Slider(0.5, 2, 0.1, 1, 140, false, true, false, false)
pointOnXAxis = Point({position, 0})
rotatedRectangle = Rotate(rectangle, rotationAngle, A)
scaledRectangle = Dilate(rectangle, scaleFactor, A)
```

## Function Plotting

Prefer named functions. Use plain assignments for ordinary functions and use
commands when a restricted domain, sampled curve, tangent, or derived object is
needed.

```geogebra
quadratic(x) = x^2 - 4
sineWave(x) = sin(x)
exponential(x) = e^x
logCurve(x) = log(x)
absoluteValue(x) = If(x < 0, -x, x)
clamped(x) = If(x < -1, -1, x > 1, 1, x)
restrictedQuadratic = Function(quadratic, -3, 3)
unitCircleCurve = Curve(cos(t), sin(t), t, 0, 2 * pi)
ellipseCurve = Curve(3 * cos(t), 2 * sin(t), t, 0, 2 * pi)
```

Useful function and calculus commands include `Function`, `Curve`,
`Derivative`, `Integral`, `IntegralBetween`, `Limit`, `Root`, `Roots`,
`Extremum`, `InflectionPoint`, `Tangent`, `TaylorPolynomial`, `Spline`,
`ImplicitCurve`, and `SlopeField`.

```geogebra
f(x) = x^3 - 3 * x^2 + 2 * x
df = Derivative(f)
secondDerivative = Derivative(f, 2)
areaUnderCurve = Integral(f, 0, 2)
tangentAtA = Tangent(Point({1, f(1)}), f)
rootsOfF = Roots(f)
turningPoints = Extremum(f)
taylorNearZero = TaylorPolynomial(sin(x), 0, 5)
```

## Geometry Patterns

Build helper objects explicitly and name them. This makes later style, labels,
visibility, and scene directives stable.

```geogebra
A = Point({0, 0})
B = Point({4, 0})
C = Point({2, 3})
triangle = Polygon(A, B, C)
sideAB = Segment(A, B)
sideBC = Segment(B, C)
sideCA = Segment(C, A)
midAB = Midpoint(A, B)
medianC = Segment(C, midAB)
circumcircle = Circle(A, B, C)
centerO = Center(circumcircle)
altitudeC = Line(C, Line(A, B))
```

For perpendicular, parallel, angle-bisector, and tangent constructions, use
official commands directly when needed:

```geogebra
base = Line(A, B)
parallelThroughC = Line(C, base)
perpendicularThroughC = PerpendicularLine(C, base)
bisectorABC = AngleBisector(A, B, C)
incircle = Incircle(A, B, C)
tangentToCircle = Tangent(A, circumcircle)
```

## Conics

Use `Circle(...)` for circles, then use conic-specific commands when the task
really needs ellipses, parabolas, hyperbolas, foci, directrices, axes, or
eccentricity.

```geogebra
F1 = Point({-2, 0})
F2 = Point({2, 0})
ellipse1 = Ellipse(F1, F2, 3)
hyperbola1 = Hyperbola(F1, F2, 1.5)
directrix = Line(Point({0, -1}), Point({1, -1}))
focusP = Point({0, 1})
parabola1 = Parabola(focusP, directrix)
ellipseCenter = Center(ellipse1)
ellipseFoci = Focus(ellipse1)
ellipseMajorAxis = MajorAxis(ellipse1)
ellipseMinorAxis = MinorAxis(ellipse1)
ellipseEccentricity = Eccentricity(ellipse1)
```

Useful conic commands include `Conic`, `Ellipse`, `Hyperbola`, `Parabola`,
`Focus`, `Directrix`, `Eccentricity`, `MajorAxis`, `MinorAxis`,
`SemiMajorAxisLength`, `SemiMinorAxisLength`, `Vertex`, `Tangent`, `Polar`,
`Sector`, `Semicircle`, and `OsculatingCircle`.

## Transformation

```text
Translate( <Object>, <Vector> )       // move the whole object by the vector
Translate( <Vector>, <Start Point> )  // relocate the vector so it starts at the point
Rotate( <Object>, <Angle> )           // rotate around the origin
Rotate( <Object>, <Angle>, <Point> )  // rotate around the given point
Reflect( <Object>, <Point> )
Reflect( <Object>, <Line> )
Reflect( <Object>, <Circle> )         // inversion with respect to the circle
Dilate( <Object>, <Dilation Factor> ) // dilate from the origin
Dilate( <Object>, <Dilation Factor>, <Dilation Center Point> ) // dilate from the given center
```

```geogebra
moveVector = Vector(Point({2, 1}))
translatedTriangle = Translate(triangle, moveVector)
rotatedTriangle = Rotate(triangle, pi / 4, A)
reflectedTriangle = Reflect(triangle, base)
dilatedTriangle = Dilate(triangle, 1.5, A)
```

Additional transformation commands include `Stretch`, `Shear`, and
`ApplyMatrix`. Use them only when the mathematical transformation is clear.

## Dependency

```text
Midpoint( <Segment> )
Midpoint( <Conic> )                     // returns the center of the conic
Midpoint( <Point>, <Point> )
Intersect( <Object>, <Object> )         // may return one or more intersection points
Center( <Conic> )
Distance( <Point>, <Object> )           // shortest distance from the point to the object
Distance( <Line>, <Line> )
Length( <Object> )
Length( <Function>, <Start x-Value>, <End x-Value> ) // graph length on the x-interval
Area( <Point>, ..., <Point> )           // points are interpreted as a polygon boundary
Area( <Conic> )
Area( <Polygon> )
```

```geogebra
midpointAB = Midpoint(A, B)
intersection1 = Intersect(lineAB, circleThroughPoint)
center1 = Center(circleThroughPoint)
distanceAB = Distance(A, B)
lengthAB = Length(segmentAB)
arcLengthF = Length(quadratic, -1, 1)
areaTriangle = Area(triangle)
```

If an intersection can return more than one point, prefer indexed intersection
objects when the downstream logic requires a specific point.

```geogebra
firstIntersection = Intersect(lineAB, circleThroughPoint, 1)
secondIntersection = Intersect(lineAB, circleThroughPoint, 2)
```

## Algebra and CAS

CAS commands are useful for symbolic annotations, but geometric drawings should
not depend on ambiguous symbolic results unless they are named and then used
explicitly.

```geogebra
expanded = Expand((x + 1)^3)
factored = Factor(x^2 - 1)
roots = Solve(x^2 - 5 * x + 6 = 0)
systemSolution = Solve({x + y = 1, x - y = 0}, {x, y})
numericRoot = NSolve(x^3 - x - 1 = 0)
simplified = Simplify((x^2 - 1) / (x - 1))
partialFractions = PartialFractions((x + 1) / (x^2 - 1))
```

Common CAS and algebra commands include `Solve`, `Solutions`, `NSolve`,
`NSolutions`, `CSolve`, `CSolutions`, `Factor`, `Expand`, `Simplify`,
`Substitute`, `Eliminate`, `Numeric`, `CompleteSquare`, `Polynomial`,
`Coefficients`, `Degree`, `Numerator`, `Denominator`, `LeftSide`, `RightSide`,
`GCD`, `LCM`, `Mod`, `IsPrime`, `PrimeFactors`, and `Rationalize`.

## Statistics

Lists use braces. Name important datasets and computed values separately.

```geogebra
data = {12, 15, 18, 22, 25, 28, 30}
dataMean = Mean(data)
dataMedian = Median(data)
dataMode = Mode(data)
dataStdDev = SD(data)
dataVariance = Variance(data)
q1 = Quartile1(data)
q3 = Quartile3(data)
```

For bivariate data, prefer a list of points.

```geogebra
points = {Point({1, 2}), Point({2, 4}), Point({3, 5}), Point({4, 7})}
fitLine = FitLine(points)
correlation = CorrelationCoefficient(points)
residuals = ResidualPlot(points, fitLine)
```

Useful statistics commands include `Mean`, `Median`, `Mode`, `SD`,
`SampleSD`, `Variance`, `SampleVariance`, `Quartile1`, `Quartile3`,
`Percentile`, `MAD`, `CorrelationCoefficient`, `Covariance`, `Spearman`,
`Fit`, `FitLine`, `FitLineX`, `FitPoly`, `FitExp`, `FitLog`, `FitPow`,
`FitSin`, `RSquare`, `SumSquaredErrors`, `TTest`, `ZMeanTest`, and
`ChiSquaredTest`.

## Chart Commands

GeoGebra charts are appropriate for simple mathematical visuals. For rich
business charts, prefer the project's ECharts path instead of GeoGebra.

```geogebra
barData = {10, 20, 15, 25}
barChart1 = BarChart(barData)
boxPlot1 = BoxPlot(1, 0.5, data)
dotPlot1 = DotPlot(data)
histogram1 = Histogram({0, 10, 20, 30, 40}, {2, 5, 4, 1})
lineGraph1 = LineGraph({1, 2, 3, 4}, {3, 5, 4, 8})
pieChart1 = PieChart({30, 20, 50})
```

Common chart commands include `BarChart`, `BoxPlot`, `DotPlot`,
`FrequencyPolygon`, `FrequencyTable`, `Histogram`, `HistogramRight`,
`LineGraph`, `NormalQuantilePlot`, `PieChart`, `ResidualPlot`, `StemPlot`,
`StepGraph`, and `StickGraph`.

## Lists

Use lists for repeated geometry, generated samples, datasets, and tables.

```geogebra
numbers = {1, 2, 3, 4, 5}
squares = Sequence(k^2, k, 1, 5)
samplePoints = Sequence(Point({k, k^2}), k, -2, 2)
thirdValue = Element(numbers, 3)
firstThree = First(numbers, 3)
lastTwo = Last(numbers, 2)
middleSlice = Take(numbers, 2, 4)
sortedNumbers = Sort(numbers)
uniqueNumbers = Unique({1, 1, 2, 3, 3})
```

Useful list commands include `Sequence`, `Element`, `First`, `Last`, `Take`,
`Append`, `Insert`, `Remove`, `RemoveUndefined`, `Reverse`, `Sort`, `Shuffle`,
`Unique`, `Join`, `Flatten`, `KeepIf`, `CountIf`, `Zip`, `Sample`,
`RandomElement`, `Union`, `Intersection`, `PointList`, and `RootList`.

## Text

Prefer native labels for ordinary named geometric objects. Use `Text(...)` only
for explanatory annotations, formulas, or tables.

```geogebra
labelA = Text("A", A)
areaLabel = Text("area = " + Area(triangle), Point({0.5, -0.5}))
formulaLabel = FormulaText(quadratic)
fractionLabel = FractionText(0.5)
tableLabel = TableText({{1, 2}, {3, 4}})
rotatedLabel = RotateText("slope", pi / 6)
```

Useful text commands include `Text`, `FormulaText`, `FractionText`,
`ScientificText`, `SurdText`, `TableText`, `VerticalText`, `RotateText`,
`Length`, `IndexOf`, `ReplaceAll`, `Split`, `Take`, `LetterToUnicode`,
`UnicodeToLetter`, `TextToUnicode`, and `UnicodeToText`.

## 3D Commands

Use 3D commands only when the requested output needs a spatial figure. A 2D
construction is usually more reliable for ordinary geometry diagrams.

```geogebra
A3 = Point({0, 0, 0})
B3 = Point({3, 0, 0})
C3 = Point({0, 3, 0})
D3 = Point({0, 0, 3})
planeABC = Plane(A3, B3, C3)
lineAD = Line(A3, D3)
tetrahedron1 = Tetrahedron(A3, B3)
sphere1 = Sphere(A3, 2)
surface1 = Surface(u * cos(v), u * sin(v), v, u, 0, 2, v, 0, 2 * pi)
volumeTetra = Volume(tetrahedron1)
```

Common 3D commands include `Plane`, `PerpendicularPlane`, `Sphere`, `Cylinder`,
`Cone`, `Prism`, `Pyramid`, `Cube`, `Tetrahedron`, `Octahedron`,
`Dodecahedron`, `Icosahedron`, `Surface`, `Net`, `Top`, `Bottom`, `Ends`,
`Volume`, and `Height`.

## Style

- Style commands do not create mathematical objects and should be applied after
  construction.
- Prefer the color tokens in `geogebra_style_reference.md` or official hex
  colors.
- Numeric RGB and opacity channels use the `0..1` scale, not `0..255`.
- Use labels and captions sparingly; prefer labels for named points and short
  captions for lines, regions, or functions.
- Use layers when helper geometry must sit behind the main object.

```text
SetBackgroundColor( <Object>, <Red>, <Green>, <Blue> )
SetBackgroundColor( <Object>, <"Color"> )
SetBackgroundColor( <Red>, <Green>, <Blue> )         // active Graphics View; numeric channels use 0..1
SetBackgroundColor( <"Color"> )                      // active Graphics View
SetColor( <Object>, <"Color"> )
SetColor( <Object>, <Red>, <Green>, <Blue> )         // numeric RGB uses the 0..1 scale
SetColor( <Object>, <"#RRGGBB"> )
SetColor( <Object>, <"#AARRGGBB"> )                  // AA + RGB
SetDynamicColor( <Object>, <Red>, <Green>, <Blue> )
SetDynamicColor( <Object>, <Red>, <Green>, <Blue>, <Opacity> ) // all numeric inputs use 0..1
SetLineThickness( <Object>, <Number> )
SetLineOpacity( <Object>, <Number> )
SetLineStyle( <Line>, <Number> )                     // 0 full, 1 dashed long, 2 dashed short, 3 dotted, 4 dash-dot
SetPointStyle( <Point>, <Number> )                   // 0 dot, 1 cross, 2 empty dot, 3 plus, 4 full diamond, 5 empty diamond, 6-9 triangles, 10 full dot without outline
SetPointSize( <Point>, <Number> )
SetPointSize( <Object>, <Number> )
SetFilling( <Object>, <Number> )
SetDecoration( <Object>, <Number> )                  // style code depends on object type
SetDecoration( <Segment>, <Number>, <Number> )       // start decoration, end decoration
ShowLabel( <Object>, <Boolean> )
SetLabelMode( <Object>, <Number> )                   // 0 name, 1 name + value, 2 value, 3 caption, 9 caption + value
SetCaption( <Object>, <Text> )
SetFixed( <Object>, <true | false> )
SetFixed( <Object>, <true | false>, <true | false> ) // third parameter controls Selection Allowed
SetTooltipMode( <Object>, <Number> )                 // 0 automatic, 1 on, 2 off, 3 caption, 4 next cell
SetTrace( <Object>, <true | false> )
SetLayer( <Object>, <Layer> )                        // layer 0..9
ShowLayer( <Number> )
HideLayer( <Number> )
SetConditionToShowObject( <Object>, <Condition> )    // boolean condition
SetVisibleInView( <Object>, <View Number 1|2|-1>, <Boolean> ) // 1 Graphics, 2 Graphics 2, -1 3D
ShowAxes( )                                          // active view
ShowAxes( <Boolean> )
ShowAxes( <View>, <Boolean> )                        // view 1, 2, or 3
ShowGrid( )                                          // active view
ShowGrid( <Boolean> )
ShowGrid( <View>, <Boolean> )                        // view 1, 2, or 3
SetLevelOfDetail( <Surface>, <Level of Detail> )     // 0 faster, 1 more accurate
```

```geogebra
SetBackgroundColor("White")
SetColor(lineAB, "Blue")
SetColor(triangle, "#4E79A7")
SetDynamicColor(A, 1, 0, 0, 0.7)
SetLineThickness(lineAB, 6)
SetLineStyle(lineAB, 2)
SetPointStyle(A, 2)
SetPointSize(A, 5)
SetFilling(triangle, 0.2)
SetDecoration(segmentAB, 1)
ShowLabel(lineAB, true)
SetLabelMode(lineAB, 3)
SetCaption(lineAB, "Line AB")
SetTooltipMode(A, 3)
SetLayer(lineAB, 2)
ShowGrid(1, false)
SetFixed(A, true)
```

## Common Complete Examples

### Function Comparison

```geogebra
f(x) = x^2
g(x) = 2^x
h(x) = e^x
SetColor(f, "Blue")
SetColor(g, "Red")
SetColor(h, "Green")
SetLineThickness(f, 4)
SetLineThickness(g, 4)
SetLineThickness(h, 4)
ShowGrid(1, true)
ShowAxes(1, true)
```

### Triangle With Measurements

```geogebra
A = Point({0, 0})
B = Point({5, 0})
C = Point({2, 3})
triangle = Polygon(A, B, C)
sideAB = Segment(A, B)
sideBC = Segment(B, C)
sideCA = Segment(C, A)
midAB = Midpoint(A, B)
medianC = Segment(C, midAB)
circumcircle = Circle(A, B, C)
centerO = Center(circumcircle)
areaValue = Area(triangle)
areaText = Text("area = " + areaValue, Point({0, -0.8}))
SetColor(triangle, "#4E79A7")
SetFilling(triangle, 0.18)
SetColor(medianC, "Orange")
SetLineThickness(medianC, 5)
SetPointSize(A, 5)
SetPointSize(B, 5)
SetPointSize(C, 5)
ShowLabel(A, true)
ShowLabel(B, true)
ShowLabel(C, true)
```

### Dynamic Rotation

```geogebra
A = Point({0, 0})
B = Point({3, 0})
C = Point({3, 2})
D = Point({0, 2})
rectangle = RigidPolygon(A, B, C, D)
angleSlider = Slider(-1.57, 1.57, 0.01, 1, 160, true, true, false, false)
rotatedRectangle = Rotate(rectangle, angleSlider, A)
SetColor(rectangle, "#A6CEE3")
SetFilling(rectangle, 0.2)
SetColor(rotatedRectangle, "#FB9A99")
SetFilling(rotatedRectangle, 0.25)
```

### Conic Comparison

```geogebra
F1 = Point({-2, 0})
F2 = Point({2, 0})
ellipse1 = Ellipse(F1, F2, 3)
hyperbola1 = Hyperbola(F1, F2, 1.2)
directrix = Line(Point({0, -2}), Point({1, -2}))
focusP = Point({0, 2})
parabola1 = Parabola(focusP, directrix)
SetColor(ellipse1, "Blue")
SetColor(hyperbola1, "Red")
SetColor(parabola1, "Green")
SetLineThickness(ellipse1, 4)
SetLineThickness(hyperbola1, 4)
SetLineThickness(parabola1, 4)
```

### Statistical Plot

```geogebra
data = {12, 15, 18, 22, 25, 28, 30}
meanValue = Mean(data)
medianValue = Median(data)
boxPlot1 = BoxPlot(1, 0.5, data)
dotPlot1 = DotPlot(data)
meanText = Text("mean = " + meanValue, Point({10, 2}))
medianText = Text("median = " + medianValue, Point({10, 1.5}))
SetColor(boxPlot1, "Blue")
SetColor(dotPlot1, "Red")
```

## Extended Command Index

The following index is a compact guide for choosing commands. It is not the
renderer's strict support list; use official GeoGebra syntax for exact overloads.

- 3D: `Plane`, `PerpendicularPlane`, `Sphere`, `Cylinder`, `Cone`, `Prism`,
  `Pyramid`, `Cube`, `Tetrahedron`, `Octahedron`, `Dodecahedron`,
  `Icosahedron`, `Surface`, `Net`, `Volume`, `Height`.
- Algebra and CAS: `Solve`, `Solutions`, `NSolve`, `NSolutions`, `CSolve`,
  `CSolutions`, `Factor`, `Expand`, `Simplify`, `Substitute`, `Eliminate`,
  `Numeric`, `CompleteSquare`, `Polynomial`, `Coefficients`, `Degree`,
  `GCD`, `LCM`, `Mod`, `IsPrime`, `PrimeFactors`, `Rationalize`.
- Charts: `BarChart`, `BoxPlot`, `DotPlot`, `FrequencyPolygon`,
  `FrequencyTable`, `Histogram`, `HistogramRight`, `LineGraph`,
  `NormalQuantilePlot`, `PieChart`, `ResidualPlot`, `StemPlot`, `StepGraph`,
  `StickGraph`.
- Conics: `Conic`, `Circle`, `Ellipse`, `Hyperbola`, `Parabola`, `Focus`,
  `Directrix`, `Eccentricity`, `MajorAxis`, `MinorAxis`,
  `SemiMajorAxisLength`, `SemiMinorAxisLength`, `Vertex`, `Tangent`,
  `Polar`, `Sector`, `Semicircle`, `OsculatingCircle`.
- Functions and calculus: `Function`, `Curve`, `Derivative`, `Integral`,
  `IntegralBetween`, `Limit`, `LimitAbove`, `LimitBelow`, `Root`, `Roots`,
  `Extremum`, `InflectionPoint`, `Tangent`, `TaylorPolynomial`, `Spline`,
  `ImplicitCurve`, `SlopeField`.
- Geometry: `Point`, `PointIn`, `Line`, `Segment`, `Ray`, `Vector`, `Circle`,
  `Polygon`, `RigidPolygon`, `Polyline`, `Midpoint`, `Intersect`,
  `IntersectPath`, `Center`, `Distance`, `Length`, `Area`, `Perimeter`,
  `Angle`, `AngleBisector`, `PerpendicularLine`, `PerpendicularBisector`,
  `Incircle`, `Locus`, `LocusEquation`, `TriangleCenter`.
- Lists: `Sequence`, `Element`, `First`, `Last`, `Take`, `Append`, `Insert`,
  `Remove`, `RemoveUndefined`, `Reverse`, `Sort`, `Shuffle`, `Unique`,
  `Join`, `Flatten`, `KeepIf`, `CountIf`, `Zip`, `Sample`, `Union`,
  `Intersection`, `PointList`, `RootList`.
- Probability: `Normal`, `BinomialDist`, `Poisson`, `Uniform`, `RandomBetween`,
  `RandomNormal`, `RandomUniform`, `RandomBinomial`, `RandomPoisson`,
  `InverseNormal`, `InverseBinomial`, `TDistribution`, `ChiSquared`,
  `FDistribution`.
- Statistics: `Mean`, `Median`, `Mode`, `SD`, `SampleSD`, `Variance`,
  `SampleVariance`, `Quartile1`, `Quartile3`, `Percentile`, `MAD`,
  `CorrelationCoefficient`, `Covariance`, `Spearman`, `Fit`, `FitLine`,
  `FitLineX`, `FitPoly`, `FitExp`, `FitLog`, `FitPow`, `FitSin`, `RSquare`.
- Text: `Text`, `FormulaText`, `FractionText`, `ScientificText`, `SurdText`,
  `TableText`, `VerticalText`, `RotateText`, `Length`, `IndexOf`,
  `ReplaceAll`, `Split`, `LetterToUnicode`, `UnicodeToLetter`,
  `TextToUnicode`, `UnicodeToText`.
- Transformations: `Translate`, `Rotate`, `Reflect`, `Dilate`, `Stretch`,
  `Shear`, `ApplyMatrix`.
- Scripting and style: `SetColor`, `SetDynamicColor`, `SetLineThickness`,
  `SetLineOpacity`, `SetLineStyle`, `SetPointStyle`, `SetPointSize`,
  `SetFilling`, `SetDecoration`, `ShowLabel`, `SetLabelMode`, `SetCaption`,
  `SetFixed`, `SetTooltipMode`, `SetTrace`, `SetLayer`, `ShowLayer`,
  `HideLayer`, `SetConditionToShowObject`, `SetVisibleInView`, `ShowAxes`,
  `ShowGrid`, `Slider`, `StartAnimation`, `SetValue`, `CenterView`,
  `ZoomIn`, `ZoomOut`.

## Forbidden Syntax

Do not emit these forms in generated GeoGebra code:

```text
Triangle(A, B, C)
Rectangle(A, B, C, D)
RegularPolygon(A, B, 5)
v = (1, 2)
A = (1, 2)
blue thick segment AB
Polygons#sym:Polygons
SetColor(f, 255, 0, 0)
Rotate(A, 45deg, B)
```

Use these replacements:

```geogebra
A = Point({1, 2})
vPoint = Point({1, 2})
v = Vector(vPoint)
triangle = Polygon(A, B, C)
rectangle = Polygon(A, B, C, D)
regularPentagon = Polygon(A, B, 5)
SetColor(f, 1, 0, 0)
Rotate(A, pi / 4, B)
```
