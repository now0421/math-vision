package com.mathvision.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared catalog for Manim instance methods documented in the syntax manual.
 * Mirrors the GeoGebra pattern in {@link GeoGebraValidationSupport}.
 *
 * <p>The authoritative source is {@code llm/manim_syntax_manual.md}. A test
 * ({@code ManimValidationSupportTest}) parses the manual and verifies that
 * this catalog stays in sync.</p>
 */
public final class ManimValidationSupport {

    /**
     * Documented instance methods that appear in the syntax manual code blocks
     * as {@code obj.method_name(...)}.  Only methods whose name contains at
     * least one underscore are listed, because those are the ones checked by
     * the undocumented-API validation rule (single-word methods like
     * {@code scale}, {@code shift}, {@code copy} are too generic to validate
     * reliably against Python builtins).
     */
    private static final Set<String> DOCUMENTED_INSTANCE_METHODS = orderedSet(
            // Positioning
            "move_to",
            "next_to",
            "to_edge",
            "to_corner",
            "align_to",
            // Arrangement
            "arrange_in_grid",
            // Sizing
            "set_height",
            "set_length",
            "match_height",
            "match_width",
            "match_style",
            // Stroke and fill styling
            "set_color",
            "set_fill",
            "set_stroke",
            "set_opacity",
            "set_style",
            "set_fill_color",
            "set_fill_opacity",
            "set_stroke_color",
            "set_stroke_opacity",
            "set_stroke_width",
            "set_z_index",
            "set_color_by_gradient",
            "set_color_by_tex",
            "set_shade_in_3d",
            // VMobject curve construction
            "set_points_as_corners",
            "set_points_smoothly",
            // Updaters
            "add_updater",
            "remove_updater",
            "clear_updaters",
            "suspend_updating",
            "resume_updating",
            // ValueTracker / Variable
            "set_value",
            "get_value",
            "increment_value",
            // Getters
            "get_center",
            "get_start",
            "get_end",
            "get_top",
            "get_bottom",
            "get_left",
            "get_right",
            "get_corner",
            "get_height",
            "get_width",
            "get_length",
            "get_angle",
            "get_text",
            // Graph / chart methods
            "get_graph_label",
            "get_x_axis_label",
            "get_y_axis_label",
            "get_z_axis_label",
            "get_bar_labels",
            "change_bar_values",
            "plot_parametric_curve",
            "plot_surface",
            "add_coordinates",
            // Axes area and line helpers
            "get_area",
            "get_riemann_rectangles",
            "get_h_line",
            "get_vertical_line",
            "get_secant_slope_group",
            // Graph theory
            "add_edges",
            "add_vertices",
            "remove_edges",
            "remove_vertices",
            // Line / Arrow
            "put_start_and_end_on",
            // Circle special constructor
            "from_three_points",
            // State and target
            "save_state",
            "generate_target",
            // TexTemplate
            "add_to_preamble",
            // Complex plane animation
            "apply_complex_function",
            // Stream lines
            "start_animation",
            "end_animation",
            // Camera / scene
            "auto_zoom",
            // Non-linear transforms
            "prepare_for_nonlinear_transform"
    );

    private ManimValidationSupport() {}

    /** Returns the set of documented instance method names (snake_case only). */
    public static Set<String> documentedInstanceMethodNames() {
        return DOCUMENTED_INSTANCE_METHODS;
    }

    /** Returns true if the given method name is in the documented catalog. */
    public static boolean isDocumentedInstanceMethod(String methodName) {
        return methodName != null && DOCUMENTED_INSTANCE_METHODS.contains(methodName.trim());
    }

    private static Set<String> orderedSet(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }
}
