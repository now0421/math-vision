# GeoGebra Style Reference

Use this reference only for storyboard-level style planning. This stage does
not write code; all style language must stay compatible with later GeoGebra
command generation.

## Rules

* GeoGebra uses a white background by default (`#FFFFFF`).
* Write every storyboard color as a 6-digit hex string in `#RRGGBB` format.
* Do not use named colors, fixed color whitelists, CSS color names, 8-digit hex
  values, RGB strings, gradients, shadows, blur, glow, or browser-specific
  styling terms.
* Put transparency in a separate `opacity`, `fill_opacity`, or
  `stroke_opacity` field. Do not encode alpha in the color string.
* Use concise, backend-friendly style properties.
* Prefer restyling an existing object over creating a visual duplicate on the
  same geometry.
* Prefer native labels for named geometric objects instead of separate duplicate
  text objects.
* Assign colors to concepts, not random objects.

## 1. Color Format And Contrast

Use hex colors in `color_scheme`, `color_palette`, object `style`, and scene
notes.

### Allowed Color Inputs

Storyboard color inputs are not restricted to a fixed list of official
GeoGebra color inputs. Use any readable 6-digit hex color that satisfies the
contrast rules below.

This replaces the old official GeoGebra color inputs whitelist with a
format-and-contrast rule.

Valid examples:

* `#111827`
* `#1D4ED8`
* `#FFFFFF`

Invalid examples:

* `BLUE`
* `WHITE`
* `BLACK`
* `#CC1D4ED8`
* `rgba(29, 78, 216, 0.8)`

Contrast requirements:

* Ordinary non-text strokes, arrows, geometry, markers, and decorative elements
  must contrast against the default GeoGebra white background `#FFFFFF` at ratio
  >= 3.0.
* Text, titles, formulas, labels, badges, and callouts must contrast at ratio
  >= 4.5 against their own background color.
* If a text element has no explicit background color, compare its text color
  against `#FFFFFF`.

## 2. Safe Style Properties

Use concise key-value properties that can later map to GeoGebra styling
commands.

```text
color               // 6-digit hex primary color, #RRGGBB (text color for text/equation; dot color for point; stroke fallback for lines)
fill_color          // 6-digit hex fill color, #RRGGBB (shape interior fill; text_card background box fill)
stroke_color        // 6-digit hex stroke/border color, #RRGGBB (line color; shape border; text_card background box border)
highlight_color     // 6-digit hex emphasis color, #RRGGBB
stroke_width        // stronger or lighter stroke emphasis
line_style          // solid | dashed | dotted | dash_dot
point_size          // numeric point size (GeoGebra: SetPointSize)
point_style         // GeoGebra: SetPointStyle code
decoration          // GeoGebra: SetDecoration code
fill_opacity        // 0..1 (shape fill transparency; text_card background box fill transparency)
stroke_opacity      // 0..1 (line transparency; text_card background box border transparency)
opacity             // 0..1
font_size           // text size hint
padding             // text-card padding
corner_radius       // text-card corner radius
z_index             // layer/order hint
label_visible       // true | false
```

## 3. Storyboard Output Guidance

When writing storyboard JSON:

* `color_palette` must contain only 6-digit hex color strings.
* `color_scheme` should describe semantic mapping, not implementation details.
* Object `style` must be a single typed object, not an array and not a free-form
  map.
* Use only the allowed style keys such as `color`, `fill_color`,
  `stroke_color`, `stroke_width`, `line_style`, `fill_opacity`, and
  `label_visible`.
* Create separate storyboard objects for labels, badges, helper outlines,
  cards, or callouts instead of encoding sidecar layers inside another
  object's style.
* Use the same color for the same mathematical concept across scenes.
* `notes_for_codegen` is a hard downstream constraint field; use it only for
  helper visibility, grid/axes policy, label policy, palette consistency,
  emphasis hierarchy, or similar instructions that code generation and repair
  must preserve.
