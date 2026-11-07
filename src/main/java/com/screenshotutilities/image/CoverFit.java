package com.screenshotutilities.image;

/** Works out which part of a picture to draw so it covers a whole screen, cut evenly at the edges. */
public final class CoverFit {
    private CoverFit() {
    }

    /**
     * Returns {@code {u, v, width, height}} of the source rectangle, in picture pixels, with the same shape as the screen
     * and centred in the picture.
     */
    public static float[] sourceRect(int imageWidth, int imageHeight, int screenWidth, int screenHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalArgumentException("Empty size");
        }
        double imageAspect = (double) imageWidth / imageHeight;
        double screenAspect = (double) screenWidth / screenHeight;
        if (imageAspect > screenAspect) {
            float w = (float) (imageHeight * screenAspect);
            return new float[]{(imageWidth - w) / 2f, 0f, w, imageHeight};
        }
        float h = (float) (imageWidth / screenAspect);
        return new float[]{0f, (imageHeight - h) / 2f, imageWidth, h};
    }
}
