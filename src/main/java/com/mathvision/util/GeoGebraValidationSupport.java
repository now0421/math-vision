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
            "SetLevelOfDetail",
            "CenterView",
            "SetValue",
            "StartAnimation",
            "ZoomIn",
            "ZoomOut"
    );

    private static final Set<String> DOCUMENTED_RUNTIME_SCRIPTING_COMMANDS = orderedSet(
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
            "SetLevelOfDetail"
    );

    private static final Set<String> DOCUMENTED_TEXT_COMMANDS = orderedSet(
            "Text",
            "FormulaText",
            "FractionText",
            "ScientificText",
            "SurdText",
            "TableText",
            "VerticalText",
            "RotateText",
            "IndexOf",
            "ReplaceAll",
            "Split",
            "LetterToUnicode",
            "UnicodeToLetter",
            "TextToUnicode",
            "UnicodeToText"
    );

    private static final Set<String> DOCUMENTED_FUNCTION_AND_ANALYSIS_COMMANDS = orderedSet(
            "Function",
            "Curve",
            "Derivative",
            "Integral",
            "IntegralBetween",
            "Limit",
            "LimitAbove",
            "LimitBelow",
            "Root",
            "Roots",
            "Extremum",
            "InflectionPoint",
            "Tangent",
            "TaylorPolynomial",
            "Spline",
            "ImplicitCurve",
            "SlopeField"
    );

    private static final Set<String> DOCUMENTED_CONIC_COMMANDS = orderedSet(
            "Conic",
            "Ellipse",
            "Hyperbola",
            "Parabola",
            "Focus",
            "Directrix",
            "Eccentricity",
            "MajorAxis",
            "MinorAxis",
            "SemiMajorAxisLength",
            "SemiMinorAxisLength",
            "Vertex",
            "Polar",
            "Sector",
            "Semicircle",
            "OsculatingCircle"
    );

    private static final Set<String> DOCUMENTED_GEOMETRY_EXTENSIONS = orderedSet(
            "AngleBisector",
            "Angle",
            "Polyline",
            "PerpendicularLine",
            "PerpendicularBisector",
            "Incircle",
            "Locus",
            "LocusEquation",
            "TriangleCenter",
            "IntersectPath",
            "Perimeter",
            "Plane",
            "PerpendicularPlane"
    );

    private static final Set<String> DOCUMENTED_3D_COMMANDS = orderedSet(
            "Sphere",
            "Cylinder",
            "Cone",
            "Prism",
            "Pyramid",
            "Cube",
            "Tetrahedron",
            "Octahedron",
            "Dodecahedron",
            "Icosahedron",
            "Surface",
            "Net",
            "Top",
            "Bottom",
            "Ends",
            "Volume",
            "Height"
    );

    private static final Set<String> DOCUMENTED_PROBABILITY_COMMANDS = orderedSet(
            "Normal",
            "BinomialDist",
            "Poisson",
            "Uniform",
            "RandomBetween",
            "RandomNormal",
            "RandomUniform",
            "RandomBinomial",
            "RandomPoisson",
            "InverseNormal",
            "InverseBinomial",
            "TDistribution",
            "ChiSquared",
            "FDistribution"
    );

    private static final Set<String> DOCUMENTED_CAS_COMMANDS = orderedSet(
            "Solve",
            "Solutions",
            "NSolve",
            "NSolutions",
            "CSolve",
            "CSolutions",
            "Factor",
            "Expand",
            "Simplify",
            "Substitute",
            "Eliminate",
            "Numeric",
            "CompleteSquare",
            "Polynomial",
            "Coefficients",
            "Degree",
            "Numerator",
            "Denominator",
            "LeftSide",
            "RightSide",
            "GCD",
            "LCM",
            "Mod",
            "IsPrime",
            "PrimeFactors",
            "Rationalize",
            "If",
            "PartialFractions"
    );

    private static final Set<String> DOCUMENTED_STATISTICS_COMMANDS = orderedSet(
            "Mean",
            "Median",
            "Mode",
            "SD",
            "SampleSD",
            "Variance",
            "SampleVariance",
            "Quartile1",
            "Quartile3",
            "Percentile",
            "MAD",
            "CorrelationCoefficient",
            "Covariance",
            "Spearman",
            "Fit",
            "FitLine",
            "FitLineX",
            "FitPoly",
            "FitExp",
            "FitLog",
            "FitPow",
            "FitSin",
            "RSquare",
            "SumSquaredErrors",
            "TTest",
            "ZMeanTest",
            "ChiSquaredTest"
    );

    private static final Set<String> DOCUMENTED_CHART_COMMANDS = orderedSet(
            "BarChart",
            "BoxPlot",
            "DotPlot",
            "FrequencyPolygon",
            "FrequencyTable",
            "Histogram",
            "HistogramRight",
            "LineGraph",
            "NormalQuantilePlot",
            "PieChart",
            "ResidualPlot",
            "StemPlot",
            "StepGraph",
            "StickGraph"
    );

    private static final Set<String> DOCUMENTED_LIST_COMMANDS = orderedSet(
            "Sequence",
            "Element",
            "First",
            "Last",
            "Take",
            "Append",
            "Insert",
            "Remove",
            "RemoveUndefined",
            "Reverse",
            "Sort",
            "Shuffle",
            "Unique",
            "Join",
            "Flatten",
            "KeepIf",
            "CountIf",
            "Zip",
            "Sample",
            "RandomElement",
            "Union",
            "Intersection",
            "PointList",
            "RootList"
    );

    private static final Set<String> DOCUMENTED_COMMANDS;

    static {
        LinkedHashSet<String> commands = new LinkedHashSet<>(DOCUMENTED_CONSTRUCTION_COMMANDS);
        commands.addAll(DOCUMENTED_SCRIPTING_COMMANDS);
        commands.addAll(DOCUMENTED_TEXT_COMMANDS);
        commands.addAll(DOCUMENTED_FUNCTION_AND_ANALYSIS_COMMANDS);
        commands.addAll(DOCUMENTED_CONIC_COMMANDS);
        commands.addAll(DOCUMENTED_GEOMETRY_EXTENSIONS);
        commands.addAll(DOCUMENTED_3D_COMMANDS);
        commands.addAll(DOCUMENTED_PROBABILITY_COMMANDS);
        commands.addAll(DOCUMENTED_CAS_COMMANDS);
        commands.addAll(DOCUMENTED_STATISTICS_COMMANDS);
        commands.addAll(DOCUMENTED_CHART_COMMANDS);
        commands.addAll(DOCUMENTED_LIST_COMMANDS);
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

    public static Set<String> documentedRuntimeScriptingCommandNames() {
        return DOCUMENTED_RUNTIME_SCRIPTING_COMMANDS;
    }

    public static Set<String> documentedTextCommandNames() {
        return DOCUMENTED_TEXT_COMMANDS;
    }

    public static Set<String> documentedFunctionAndAnalysisCommandNames() {
        return DOCUMENTED_FUNCTION_AND_ANALYSIS_COMMANDS;
    }

    public static Set<String> documentedConicCommandNames() {
        return DOCUMENTED_CONIC_COMMANDS;
    }

    public static Set<String> documentedGeometryExtensionCommandNames() {
        return DOCUMENTED_GEOMETRY_EXTENSIONS;
    }

    public static Set<String> documented3dCommandNames() {
        return DOCUMENTED_3D_COMMANDS;
    }

    public static Set<String> documentedProbabilityCommandNames() {
        return DOCUMENTED_PROBABILITY_COMMANDS;
    }

    public static Set<String> documentedCasCommandNames() {
        return DOCUMENTED_CAS_COMMANDS;
    }

    public static Set<String> documentedStatisticsCommandNames() {
        return DOCUMENTED_STATISTICS_COMMANDS;
    }

    public static Set<String> documentedChartCommandNames() {
        return DOCUMENTED_CHART_COMMANDS;
    }

    public static Set<String> documentedListCommandNames() {
        return DOCUMENTED_LIST_COMMANDS;
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
