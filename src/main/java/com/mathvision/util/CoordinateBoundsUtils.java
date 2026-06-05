package com.mathvision.util;

import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.StoryboardCoordinateBounds;
import com.mathvision.model.Narrative.StoryboardCoordinateBoundsAxis;

/**
 * Utilities for storyboard world-coordinate bounds.
 */
public final class CoordinateBoundsUtils {

    public static final double DEFAULT_PADDING = 1.0;

    private CoordinateBoundsUtils() {}

    public static StoryboardCoordinateBounds normalize(StoryboardCoordinateBounds bounds) {
        if (bounds == null) {
            return null;
        }
        bounds.setPadding(resolvePadding(bounds));
        bounds.setX(normalizeAxis(bounds.getX()));
        bounds.setY(normalizeAxis(bounds.getY()));
        bounds.setZ(normalizeAxis(bounds.getZ()));
        return bounds.hasData() ? bounds : null;
    }

    public static double resolvePadding(StoryboardCoordinateBounds bounds) {
        if (bounds == null || bounds.getPadding() == null || bounds.getPadding() <= 0.0) {
            return DEFAULT_PADDING;
        }
        return bounds.getPadding();
    }

    public static StoryboardCoordinateBoundsAxis normalizeAxis(StoryboardCoordinateBoundsAxis axis) {
        if (axis == null || !axis.hasData()) {
            return null;
        }
        Double min = axis.getMin();
        Double max = axis.getMax();
        if (min == null && max == null) {
            return null;
        }
        if (min == null) {
            min = max;
        }
        if (max == null) {
            max = min;
        }
        if (min > max) {
            double tmp = min;
            min = max;
            max = tmp;
        }
        axis.setMin(round(min));
        axis.setMax(round(max));
        return axis;
    }

    public static StoryboardCoordinateBounds withPadding(StoryboardCoordinateBounds rawBounds) {
        StoryboardCoordinateBounds normalized = normalize(rawBounds);
        if (normalized == null) {
            return null;
        }
        double padding = resolvePadding(normalized);
        StoryboardCoordinateBounds padded = new StoryboardCoordinateBounds();
        padded.setPadding(padding);
        padded.setX(expandAxis(normalized.getX(), padding));
        padded.setY(expandAxis(normalized.getY(), padding));
        padded.setZ(expandZAxis(normalized.getZ(), padding));
        return normalize(padded);
    }

    public static StoryboardCoordinateBoundsAxis expandAxis(StoryboardCoordinateBoundsAxis axis,
                                                            double padding) {
        StoryboardCoordinateBoundsAxis normalized = normalizeAxis(axis);
        if (normalized == null) {
            return null;
        }
        return new StoryboardCoordinateBoundsAxis(
                round(normalized.getMin() - padding),
                round(normalized.getMax() + padding));
    }

    private static StoryboardCoordinateBoundsAxis expandZAxis(StoryboardCoordinateBoundsAxis axis,
                                                              double padding) {
        StoryboardCoordinateBoundsAxis normalized = normalizeAxis(axis);
        if (normalized == null) {
            return null;
        }
        if (normalized.getMin() != null
                && normalized.getMax() != null
                && normalized.getMin() == 0.0
                && normalized.getMax() == 0.0) {
            return new StoryboardCoordinateBoundsAxis(0.0, 0.0);
        }
        return expandAxis(normalized, padding);
    }

    public static double[] frameMin(Narrative.Storyboard storyboard, double[] fallback) {
        StoryboardCoordinateBounds bounds = storyboard != null
                ? normalize(storyboard.getCoordinateBounds()) : null;
        if (bounds == null || bounds.getX() == null || bounds.getY() == null) {
            return fallback;
        }
        return new double[] {
                bounds.getX().getMin(),
                bounds.getY().getMin(),
                bounds.getZ() != null && bounds.getZ().getMin() != null
                        ? bounds.getZ().getMin() : 0.0
        };
    }

    public static double[] frameMax(Narrative.Storyboard storyboard, double[] fallback) {
        StoryboardCoordinateBounds bounds = storyboard != null
                ? normalize(storyboard.getCoordinateBounds()) : null;
        if (bounds == null || bounds.getX() == null || bounds.getY() == null) {
            return fallback;
        }
        return new double[] {
                bounds.getX().getMax(),
                bounds.getY().getMax(),
                bounds.getZ() != null && bounds.getZ().getMax() != null
                        ? bounds.getZ().getMax() : 0.0
        };
    }

    public static String toGeoGebraSetCoordSystem(StoryboardCoordinateBounds bounds) {
        StoryboardCoordinateBounds normalized = normalize(bounds);
        if (normalized == null || normalized.getX() == null || normalized.getY() == null) {
            return GeoGebraCodeUtils.DEFAULT_VIEW_COMMAND;
        }
        return "SetCoordSystem("
                + format(normalized.getX().getMin()) + ", "
                + format(normalized.getX().getMax()) + ", "
                + format(normalized.getY().getMin()) + ", "
                + format(normalized.getY().getMax()) + ")";
    }

    public static String format(StoryboardCoordinateBounds bounds) {
        StoryboardCoordinateBounds normalized = normalize(bounds);
        if (normalized == null) {
            return "coordinate_bounds unavailable";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("coordinate_bounds x=")
                .append(formatAxis(normalized.getX()))
                .append(", y=").append(formatAxis(normalized.getY()));
        if (normalized.getZ() != null) {
            sb.append(", z=").append(formatAxis(normalized.getZ()));
        }
        sb.append(", padding=").append(format(resolvePadding(normalized)));
        return sb.toString();
    }

    private static String formatAxis(StoryboardCoordinateBoundsAxis axis) {
        StoryboardCoordinateBoundsAxis normalized = normalizeAxis(axis);
        if (normalized == null) {
            return "[]";
        }
        return "[" + format(normalized.getMin()) + ", " + format(normalized.getMax()) + "]";
    }

    private static String format(double value) {
        double rounded = round(value);
        if (Math.rint(rounded) == rounded) {
            return Long.toString((long) rounded);
        }
        return Double.toString(rounded);
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
