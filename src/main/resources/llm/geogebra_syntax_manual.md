# GeoGebra Syntax for LLM Output

## Rules

- Use `Point(path)` or `PointIn(region)` for draggable points constrained by existing geometry.
- Use `Slider(...)` when motion or rotation must stay within an explicit numeric range.
- Use `RigidPolygon(...)` for fixed-size shapes that must remain draggable and rotatable.
- Use `Polygon(...)` to create triangles, rectangles, and regular polygons.

## Point

```text
Point( <Object> )
Point( <Object>, <Parameter> )
Point( <Point>, <Vector> )
Point( <List> )
PointIn( <Region> )
```

```geogebra
fixedPoint = Point({0, 0})
SetFixed(fixedPoint, true)

freePoint = Point({3, 1})

pointOnLine = Point(L)
pointOnCircle = Point(circle1)
pointOnSegmentAtParameter = Point(S, 0.25)
pointInTriangle = PointIn(triangle)
```

## Vector

```text
Vector( <Point> )
Vector( <Start Point>, <End Point> )
```

```geogebra
vectorAB = Vector(A, B)
vector1 = Vector((3, 2))
```

## Line

```text
Line( <Point>, <Point> )
Line( <Point>, <Parallel Line> )
Line( <Point>, <Direction Vector> )
```

```geogebra
L = Line(A, B)
```

## Segment

```text
Segment( <Point>, <Point> )
Segment( <Point>, <Length> )
```

```geogebra
S = Segment(A, B)
```

## Ray

```text
Ray( <Start Point>, <Point> )
Ray( <Start Point>, <Direction Vector> )
```

```geogebra
rayAB = Ray(A, B)
```

## Circle

```text
Circle( <Point>, <Radius Number> )
Circle( <Point>, <Segment> )
Circle( <Point>, <Point> )
Circle( <Point>, <Point>, <Point> )
```

```geogebra
circle1 = Circle(A, B)
```

## Polygon

```text
Polygon( <Point>, ..., <Point> )
Polygon( <Point>, <Point>, <Number of Vertices> )
Polygon( <List of Points> )
RigidPolygon( <Polygon> )
RigidPolygon( <Polygon>, <Offset x>, <Offset y> )
RigidPolygon( <Free Point>, ..., <Free Point> )
```

```geogebra
triangle = Polygon(A, B, C)
rectangle = Polygon(A, B, C, D)
polygon1 = Polygon(A, B, C, D, E)
regularPolygon = Polygon(A, B, n)

rigidRectangle = RigidPolygon(A, B, C, D)
rigidPolygon = RigidPolygon(polygon1)
```

## Slider

```text
Slider( <Min>, <Max>, <Increment>, <Speed>, <Width>, <Is Angle>, <Horizontal>, <Animating>, <Boolean Random> )
```

```geogebra
position = Slider(min, max, increment, speed, width, isAngle, horizontal, animating, random)
rotationAngle = Slider(-45, 45, 1, 1, 140, true, true, false, false)

pointOnXAxis = Point({position, 0})
rotatedRectangle = Rotate(rectangle, rotationAngle, O)
```

## Transformation

```text
Translate( <Object>, <Vector> )
Translate( <Vector>, <Start Point> )
Rotate( <Object>, <Angle> )
Rotate( <Object>, <Angle>, <Point> )
Reflect( <Object>, <Point> )
Reflect( <Object>, <Line> )
Reflect( <Object>, <Circle> )
Dilate( <Object>, <Dilation Factor> )
Dilate( <Object>, <Dilation Factor>, <Dilation Center Point> )
```

```geogebra
translatedShape = Translate(polygon1, vectorAB)
rotatedShape = Rotate(polygon1, rotationAngle, O)
reflectedShape = Reflect(polygon1, L)
dilatedShape = Dilate(polygon1, scaleFactor, O)
```

## Dependency

```text
Midpoint( <Segment> )
Midpoint( <Conic> )
Midpoint( <Point>, <Point> )
Intersect( <Object>, <Object> )
Center( <Conic> )
Distance( <Point>, <Object> )
Distance( <Line>, <Line> )
Length( <Object> )
Length( <Function>, <Start x-Value>, <End x-Value> )
Area( <Point>, ..., <Point> )
Area( <Conic> )
Area( <Polygon> )
```

```geogebra
midpointAB = Midpoint(A, B)
intersection1 = Intersect(L, circle1)
center1 = Center(circle1)

distanceAB = Distance(A, B)
lengthS = Length(S)
areaTriangle = Area(triangle)
```

## Style

```text
SetColor( <Object>, <Red>, <Green>, <Blue> )
SetColor( <Object>, <"Color"> )
SetLineThickness( <Object>, <Number> )
SetPointSize( <Point>, <Number> )
SetPointSize( <Object>, <Number> )
SetFilling( <Object>, <Number> )
ShowLabel( <Object>, <Boolean> )
SetFixed( <Object>, <true | false> )
SetFixed( <Object>, <true | false>, <true | false> )
```

```geogebra
SetColor(L, "Blue")
SetLineThickness(L, 6)
SetPointSize(A, 5)
SetFilling(triangle, 0.2)
ShowLabel(L, true)
SetFixed(A, true)
```

## Forbidden Syntax

```text
Triangle(A, B, C)
Rectangle(A, B, C, D)
RegularPolygon(A, B, 5)
v = (1, 2)
A = (1, 2)
blue thick segment AB
Polygons#sym:Polygons
```
