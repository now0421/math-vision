package com.mathvision.model;

/**
 * Source stage that requested a shared code-fix pass.
 */
public enum CodeFixSource {
    GENERATION_VALIDATION,
    EVALUATION_REVIEW,
    RENDER_FAILURE,
    SCENE_LAYOUT_EVALUATION
}
