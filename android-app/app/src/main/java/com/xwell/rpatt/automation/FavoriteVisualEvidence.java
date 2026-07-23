package com.xwell.rpatt.automation;

/** Pure colour evidence used to verify a bookmark transition without trusting click ACKs. */
public final class FavoriteVisualEvidence {
    private static final double SELECTED_RATIO = 0.12d;
    private static final double MIN_TRANSITION_DELTA = 0.06d;
    private static final int MIN_SELECTED_PIXELS = 36;

    private FavoriteVisualEvidence() {}

    public static Profile unavailable() { return new Profile(false, 0, 0); }

    public static Profile analyze(int[] pixels) {
        if (pixels == null || pixels.length == 0) return unavailable();
        int yellow = 0;
        for (int pixel : pixels) {
            int red = (pixel >>> 16) & 0xff;
            int green = (pixel >>> 8) & 0xff;
            int blue = pixel & 0xff;
            if (red >= 185 && green >= 125 && blue <= 125
                    && red >= green + 15 && green >= blue + 35) yellow++;
        }
        return new Profile(true, pixels.length, yellow);
    }

    public static boolean transitionedToSelected(Profile before, Profile after) {
        if (after == null || !after.selected()) return false;
        if (before == null || !before.available) return after.yellowRatio >= 0.20d;
        return !before.selected() && after.yellowRatio - before.yellowRatio >= MIN_TRANSITION_DELTA;
    }

    public static final class Profile {
        public final boolean available;
        public final int samples;
        public final int yellowPixels;
        public final double yellowRatio;

        Profile(boolean available, int samples, int yellowPixels) {
            this.available = available;
            this.samples = samples;
            this.yellowPixels = yellowPixels;
            this.yellowRatio = samples <= 0 ? 0d : yellowPixels / (double) samples;
        }

        public boolean selected() {
            return available && yellowPixels >= MIN_SELECTED_PIXELS
                    && yellowRatio >= SELECTED_RATIO;
        }
    }
}
