package com.mathvision.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared catalog for GeoGebra commands documented in the syntax manual and
 * used by render-time validation.
 */
public final class GeoGebraValidationSupport {

    private static final Set<String> DOCUMENTED_CONSTRUCTION_COMMANDS = orderedSet(
            "Point",
            "PointIn",
            "Vector",
            "Line",
            "Segment",
            "Ray",
            "Circle",
            "Polygon",
            "RigidPolygon",
            "Slider",
            "Translate",
            "Rotate",
            "Reflect",
            "Dilate",
            "Midpoint",
            "Intersect",
            "Center",
            "Distance",
            "Length",
            "Area"
    );

    private static final Set<String> DOCUMENTED_SCRIPTING_COMMANDS = orderedSet(
            "SetBackgroundColor",
            "SetColor",
            "SetDynamicColor",
            "SetLineThickness",
            "SetLineOpacity",
            "SetLineStyle",
            "SetPointStyle",
            "SetPointSize",
            "SetFilling",
            "SetDecoration",
            "ShowLabel",
            "SetLabelMode",
            "SetCaption",
            "SetFixed",
            "SetTooltipMode",
            "SetTrace",
            "SetLayer",
            "ShowLayer",
            "HideLayer",
            "SetConditionToShowObject",
            "SetVisibleInView",
            "ShowAxes",
            "ShowGrid",
            "SetLevelOfDetail"
    );

    private static final Set<String> DOCUMENTED_COMMANDS;

    static {
        LinkedHashSet<String> commands = new LinkedHashSet<>(DOCUMENTED_CONSTRUCTION_COMMANDS);
        commands.addAll(DOCUMENTED_SCRIPTING_COMMANDS);
        DOCUMENTED_COMMANDS = Collections.unmodifiableSet(commands);
    }

    private GeoGebraValidationSupport() {}

    public static Set<String> documentedCommandNames() {
        return DOCUMENTED_COMMANDS;
    }

    public static Set<String> documentedConstructionCommandNames() {
        return DOCUMENTED_CONSTRUCTION_COMMANDS;
    }

    public static Set<String> documentedScriptingCommandNames() {
        return DOCUMENTED_SCRIPTING_COMMANDS;
    }

    public static boolean isDocumentedCommandName(String commandName) {
        return commandName != null && DOCUMENTED_COMMANDS.contains(commandName.trim());
    }

    public static boolean isDocumentedScriptingCommandName(String commandName) {
        return commandName != null && DOCUMENTED_SCRIPTING_COMMANDS.contains(commandName.trim());
    }

    private static Set<String> orderedSet(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }
}
