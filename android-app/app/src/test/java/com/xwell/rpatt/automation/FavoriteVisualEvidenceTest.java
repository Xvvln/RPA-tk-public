package com.xwell.rpatt.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class FavoriteVisualEvidenceTest {
    @Test
    public void whiteBookmarkIsNotSelected() {
        int[] pixels = filled(400, 0xffffffff);
        assertFalse(FavoriteVisualEvidence.analyze(pixels).selected());
    }

    @Test
    public void yellowBookmarkIsSelected() {
        int[] pixels = filled(400, 0xfffac515);
        assertTrue(FavoriteVisualEvidence.analyze(pixels).selected());
    }

    @Test
    public void requiresAnActualUnselectedToSelectedTransition() {
        int[] beforePixels = filled(400, 0xffffffff);
        int[] afterPixels = filled(400, 0xffffffff);
        Arrays.fill(afterPixels, 0, 100, 0xfffac515);

        FavoriteVisualEvidence.Profile before = FavoriteVisualEvidence.analyze(beforePixels);
        FavoriteVisualEvidence.Profile after = FavoriteVisualEvidence.analyze(afterPixels);

        assertTrue(FavoriteVisualEvidence.transitionedToSelected(before, after));
        assertFalse(FavoriteVisualEvidence.transitionedToSelected(after, after));
    }

    @Test
    public void backgroundNoiseCannotVerifyFavorite() {
        int[] beforePixels = filled(400, 0xffffffff);
        int[] afterPixels = filled(400, 0xffffffff);
        Arrays.fill(afterPixels, 0, 20, 0xfffac515);

        assertFalse(FavoriteVisualEvidence.transitionedToSelected(
                FavoriteVisualEvidence.analyze(beforePixels),
                FavoriteVisualEvidence.analyze(afterPixels)));
    }

    private static int[] filled(int size, int color) {
        int[] pixels = new int[size];
        Arrays.fill(pixels, color);
        return pixels;
    }
}
