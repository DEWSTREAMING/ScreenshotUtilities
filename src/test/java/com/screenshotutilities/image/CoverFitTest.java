package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CoverFitTest {
    @Test
    void sameShapeUsesWholePicture() {
        assertArrayEquals(new float[]{0f, 0f, 1920f, 1080f}, CoverFit.sourceRect(1920, 1080, 960, 540), 0.01f);
    }

    @Test
    void widePictureOnTallScreenCutsSidesEvenly() {
        assertArrayEquals(new float[]{600f, 0f, 720f, 1080f}, CoverFit.sourceRect(1920, 1080, 600, 900), 0.01f);
    }

    @Test
    void tallPictureOnWideScreenCutsTopAndBottomEvenly() {
        assertArrayEquals(new float[]{0f, 600f, 1000f, 500f}, CoverFit.sourceRect(1000, 1700, 1600, 800), 0.01f);
    }

    @Test
    void emptySizeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CoverFit.sourceRect(0, 10, 10, 10));
    }
}
