package com.mathvision.util;

/**
 * Shared helpers for problem-level scene dimensionality.
 */
public final class SceneModeUtils {

    public static final String MODE_2D = "2d";
    public static final String MODE_3D = "3d";

    private SceneModeUtils() {}

    public static String normalize(String sceneMode) {
        return MODE_3D.equalsIgnoreCase(sceneMode != null ? sceneMode.trim() : "")
                ? MODE_3D
                : MODE_2D;
    }

    public static boolean isThreeD(String sceneMode) {
        return MODE_3D.equals(normalize(sceneMode));
    }
}
