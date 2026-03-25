# Manim Style Reference

Use this compact reference when writing storyboard-level style instructions. This stage does not write code, but all style language must stay compatible with later Manim code generation.

## 1. Allowed Color Constants

If you mention Manim color names in `color_scheme`, `color_palette`, object `style`, or scene notes, use only names from this whitelist.

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

Never invent color constants outside this whitelist. For example, do not use `LIGHT_BLUE`.

## 2. Safe Style Language

Describe style in terms that can later map cleanly to Manim:

* overall color
* fill color and fill opacity
* stroke color and stroke width
* overall opacity
* scale or relative visual weight

Prefer short phrases such as:

* `BLUE primary outline with medium stroke`
* `YELLOW highlight accent`
* `WHITE text, secondary opacity`
* `GREEN fill with light opacity`

Avoid CSS-style syntax, hex colors, gradients, shadows, blur, or unsupported rendering language.

## 3. Style Planning Rules

* Use semantic color mapping consistently across scenes.
* Keep the same object category in the same color whenever possible.
* Important objects should be brighter, less transparent, or have thicker strokes.
* Secondary objects can use lower opacity or neutral colors.
* Do not overload a single scene with too many saturated highlight colors.
* Text and subtitles should not visually overpower the main geometry.

## 4. Storyboard Output Guidance

When writing storyboard JSON:

* `color_palette` should contain only whitelisted Manim color constants.
* `color_scheme` should describe the semantic mapping of those safe colors.
* Object `style` should stay concise and implementation-friendly.
* `notes_for_codegen` may remind later stages to preserve palette consistency and emphasis hierarchy.
