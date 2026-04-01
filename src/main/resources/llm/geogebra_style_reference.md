# GeoGebra Style Reference

Use this compact reference when writing storyboard-level style properties for GeoGebra output. This stage does not write code, but all style language must stay compatible with later GeoGebra command generation.

## 1. Allowed Color Inputs

If you mention GeoGebra colors in `color_scheme`, `color_palette`, object `style`, or scene notes, use only official `SetColor`-compatible inputs:

Named colors:

* `BLACK`
* `DARKGRAY`
* `GRAY`
* `DARKBLUE`
* `BLUE`
* `DARKGREEN`
* `GREEN`
* `MAROON`
* `CRIMSON`
* `RED`
* `MAGENTA`
* `INDIGO`
* `PURPLE`
* `BROWN`
* `ORANGE`
* `GOLD`
* `LIME`
* `CYAN`
* `TURQUOISE`
* `LIGHTBLUE`
* `AQUA`
* `SILVER`
* `LIGHTGRAY`
* `PINK`
* `VIOLET`
* `YELLOW`
* `LIGHTYELLOW`
* `LIGHTORANGE`
* `LIGHTVIOLET`
* `LIGHTPURPLE`
* `LIGHTGREEN`
* `WHITE`

Additional official color names from the GeoGebra colors reference are also allowed. Keep them in English.

Hex colors:

* `#RRGGBB`
* `#AARRGGBB`

Rules:

* Use English color names only.
* Prefer named colors in storyboard output unless a very specific official hex color is necessary.
* Do not invent CSS color names, gradients, shadows, or browser-specific styling terms.

## 2. Safe Style Language

Describe style in simple GeoGebra-friendly terms:

* overall color
* line thickness
* line style
* point size
* point style
* fill opacity
* label visibility
* object visibility

Prefer short phrases such as:

* `BLUE primary segment with medium thickness`
* `RED highlight point`
* `DARKGRAY dashed helper line`
* `LIGHTGREEN lightly filled polygon`
* `hide auxiliary bisectors after construction`
* `show labels only for key points`

## 3. Style Planning Rules

* Use semantic color mapping consistently across the figure.
* Keep the same object category in the same color whenever possible.
* Important objects should be brighter, thicker, or more visible than helper objects.
* Helper objects can use dashed lines, neutral colors, or reduced emphasis.
* Do not overload one figure with too many saturated highlight colors.
* Labels and measurements should support the construction rather than crowd it.
* Keep foreground elements high-contrast against their local background or fill.
* Avoid pale-on-pale combinations such as `YELLOW` on `WHITE`, `WHITE` on `LIGHTYELLOW`, or `LIGHTGRAY` on `WHITE`.
* If a region uses a light fill, use a darker text or stroke color on top of it rather than another light accent.

## 4. Storyboard Output Guidance

When writing storyboard JSON:

* `color_palette` should contain only official GeoGebra named colors or official hex strings.
* `color_scheme` should describe semantic mapping, not implementation details.
* Object `style` should stay concise and construction-friendly.
* `notes_for_codegen` may remind later stages to preserve helper visibility, label policy, and emphasis hierarchy.
