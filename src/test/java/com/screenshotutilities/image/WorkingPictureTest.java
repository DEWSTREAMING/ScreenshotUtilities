package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkingPictureTest {
    private static final int GREEN = 0xFF00FF00;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final int RED = 0xFF0000FF;

    @Test
    void noFilterNoCropGivesThePictureBack() {
        int[] pixels = new int[12];
        WorkingPicture.Pixels out = WorkingPicture.filterThenCrop(pixels, 4, 3, FilterSettings.NONE, null);
        assertSame(pixels, out.pixels());
        assertEquals(4, out.width());
        assertEquals(3, out.height());
    }

    @Test
    void fullImageCropIsNoCrop() {
        int[] pixels = new int[12];
        WorkingPicture.Pixels out = WorkingPicture.filterThenCrop(pixels, 4, 3, null, new CropRect(0, 0, 4, 3));
        assertSame(pixels, out.pixels());
    }

    @Test
    void keepAreaKeepsItsFullSizePlaceAfterACrop() {
        int w = 320;
        int h = 100;
        int[] pixels = new int[w * h];
        Arrays.fill(pixels, GREEN);
        FilterSettings settings = FilterSettings.NONE.withKeepAreas(List.of(new CropRect(100, 40, 60, 20)));

        // The crop cuts through the keep area, so inside the area but left of its first column is a different spot.
        CropRect crop = new CropRect(120, 30, 100, 40);
        WorkingPicture.Pixels out = WorkingPicture.filterThenCrop(pixels, w, h, settings, crop);

        assertEquals(100, out.width());
        assertEquals(40, out.height());
        assertEquals(GREEN, out.pixels()[10 * 100 + 5], "Keep area pixel at 125,40");
        assertNotEquals(GREEN, out.pixels()[10 * 100 + 90], "Past the keep area at 210,40");
        assertNotEquals(GREEN, out.pixels()[0], "Above the keep area at 120,30");
    }

    @Test
    void blurReachesPastTheCropEdge() {
        int w = 400;
        int h = 40;
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = i % w < 200 ? WHITE : BLACK;
        }
        FilterSettings settings = FilterSettings.NONE
                .withKeepAreas(List.of(new CropRect(0, 0, 16, 16)))
                .withRest(FilterSettings.Rest.BLUR);

        CropRect crop = new CropRect(200, 0, 100, 40);
        int[] forCropAfterFilter = pixels.clone();
        WorkingPicture.Pixels out = WorkingPicture.filterThenCrop(forCropAfterFilter, w, h, settings, crop);

        // The cropped part is all black, so any light in it can only have come from past the crop edge.
        int firstRow = 20 * 100;
        assertTrue((out.pixels()[firstRow] & 0xFF) > 60, "White should bleed in from past the crop");

        Filters.applyInPlace(settings, pixels, w, h);
        assertArrayEquals(crop.extract(pixels, w), out.pixels());
    }

    @Test
    void cropOfRebuiltPictureWithNoFilterIsAPlainCrop() {
        int[] pixels = {RED, GREEN, WHITE, BLACK};
        // CropRect.clamp keeps the 16 pixel minimum, capped at the image size.
        WorkingPicture.Pixels out = WorkingPicture.filterThenCrop(pixels, 2, 2, FilterSettings.NONE, new CropRect(1, 0, 1, 2));
        assertEquals(2, out.width());
        assertEquals(2, out.height());
    }
}
