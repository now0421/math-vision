package com.mathvision.util;

import java.util.Locale;

/**
 * Shared concept-string normalization helpers.
 */
public final class ConceptUtils {

    private ConceptUtils() {}

    public static String normalizeConcept(String concept) {
        return concept == null ? "" : concept.toLowerCase(Locale.ROOT).trim();
    }
}
