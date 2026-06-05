package com.mathvision.util;

import com.mathvision.model.Narrative;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CoordinateBoundsUtilsTest {

    @Test
    void normalizesAxesAndPadding() {
        Narrative.StoryboardCoordinateBounds bounds = bounds(
                axis(3.0, -1.0),
                axis(null, 2.5),
                axis(7.0, null),
                -4.0);

        Narrative.StoryboardCoordinateBounds normalized = CoordinateBoundsUtils.normalize(bounds);

        assertEquals(CoordinateBoundsUtils.DEFAULT_PADDING, normalized.getPadding());
        assertEquals(-1.0, normalized.getX().getMin());
        assertEquals(3.0, normalized.getX().getMax());
        assertEquals(2.5, normalized.getY().getMin());
        assertEquals(2.5, normalized.getY().getMax());
        assertEquals(7.0, normalized.getZ().getMin());
        assertEquals(7.0, normalized.getZ().getMax());
    }

    @Test
    void returnsNullForEmptyBoundsAfterNormalization() {
        assertNull(CoordinateBoundsUtils.normalize(new Narrative.StoryboardCoordinateBounds()));
    }

    @Test
    void expandsBoundsWithResolvedPadding() {
        Narrative.StoryboardCoordinateBounds padded = CoordinateBoundsUtils.withPadding(bounds(
                axis(-2.0, 5.0),
                axis(-1.0, 3.0),
                null,
                0.5));

        assertEquals(-2.5, padded.getX().getMin());
        assertEquals(5.5, padded.getX().getMax());
        assertEquals(-1.5, padded.getY().getMin());
        assertEquals(3.5, padded.getY().getMax());
        assertEquals(0.5, padded.getPadding());
    }

    @Test
    void doesNotPadZeroDepthZRange() {
        Narrative.StoryboardCoordinateBounds padded = CoordinateBoundsUtils.withPadding(bounds(
                axis(-2.0, 5.0),
                axis(-1.0, 3.0),
                axis(0.0, 0.0),
                1.0));

        assertEquals(0.0, padded.getZ().getMin());
        assertEquals(0.0, padded.getZ().getMax());
    }

    @Test
    void formatsTwoDimensionalBoundsWithoutZ() {
        Narrative.StoryboardCoordinateBounds bounds = bounds(
                axis(-2.0, 5.0),
                axis(-1.0, 3.0),
                null,
                1.0);

        assertEquals("coordinate_bounds x=[-2, 5], y=[-1, 3], padding=1",
                CoordinateBoundsUtils.format(bounds));
    }

    @Test
    void frameBoundsRequireBothXAndYOtherwiseUseFallback() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        storyboard.setCoordinateBounds(bounds(axis(-2.0, 5.0), null, null, 1.0));
        double[] fallbackMin = {-7.0, -4.0, 0.0};
        double[] fallbackMax = {7.0, 4.0, 0.0};

        assertArrayEquals(fallbackMin, CoordinateBoundsUtils.frameMin(storyboard, fallbackMin));
        assertArrayEquals(fallbackMax, CoordinateBoundsUtils.frameMax(storyboard, fallbackMax));

        storyboard.setCoordinateBounds(bounds(axis(-2.0, 5.0), axis(-1.0, 3.0), axis(-9.0, 9.0), 1.0));

        assertArrayEquals(new double[] {-2.0, -1.0, -9.0}, CoordinateBoundsUtils.frameMin(storyboard, fallbackMin));
        assertArrayEquals(new double[] {5.0, 3.0, 9.0}, CoordinateBoundsUtils.frameMax(storyboard, fallbackMax));
    }

    @Test
    void formatsGeoGebraViewCommandFromWorldBounds() {
        Narrative.StoryboardCoordinateBounds bounds = bounds(axis(-4.0, 4.0), axis(-2.5, 3.0), null, 1.0);

        assertEquals("SetCoordSystem(-4, 4, -2.5, 3)",
                CoordinateBoundsUtils.toGeoGebraSetCoordSystem(bounds));
        assertEquals("SetCoordSystem(-7, 7, -4, 4)",
                CoordinateBoundsUtils.toGeoGebraSetCoordSystem(null));
    }

    private static Narrative.StoryboardCoordinateBounds bounds(Narrative.StoryboardCoordinateBoundsAxis x,
                                                               Narrative.StoryboardCoordinateBoundsAxis y,
                                                               Narrative.StoryboardCoordinateBoundsAxis z,
                                                               Double padding) {
        Narrative.StoryboardCoordinateBounds bounds = new Narrative.StoryboardCoordinateBounds();
        bounds.setX(x);
        bounds.setY(y);
        bounds.setZ(z);
        bounds.setPadding(padding);
        return bounds;
    }

    private static Narrative.StoryboardCoordinateBoundsAxis axis(Double min, Double max) {
        Narrative.StoryboardCoordinateBoundsAxis axis = new Narrative.StoryboardCoordinateBoundsAxis();
        axis.setMin(min);
        axis.setMax(max);
        return axis;
    }
}
