package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.screenshotutilities.capture.CaptureStage;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PickedFilterTest {
    private static final int RED = 0xFF0000FF;
    private static final int BLUE = 0xFFFF0000;

    private static PickedArea pick(int seedX, int seedY, int tol, int x, int y, int w, int h) {
        BitSet mask = new BitSet();
        mask.set(0, w * h);
        return new PickedArea(seedX, seedY, tol, x, y, w, h, mask, PickedArea.PictureSource.FINAL_AS_IS);
    }

    private static int[] solid(int w, int h, int colour) {
        int[] pixels = new int[w * h];
        Arrays.fill(pixels, colour);
        return pixels;
    }

    private static boolean isGrey(int pixel) {
        int r = pixel & 0xFF;
        return r == ((pixel >> 8) & 0xFF) && r == ((pixel >> 16) & 0xFF);
    }

    @Test
    void aPickCountsAsKeepingSomething() {
        FilterSettings settings = FilterSettings.NONE.withPickedAreas(List.of(pick(1, 1, 40, 0, 0, 4, 4)));
        assertTrue(settings.keepsAnything());
        assertTrue(settings.isActive());
        assertTrue(settings.isNoneChosen());
    }

    @Test
    void picksAndBoxesAndColoursAreKeptTogether() {
        int w = 200;
        int h = 100;
        int[] pixels = solid(w, h, BLUE);
        // A red patch kept by colour, a box, and a pick, on a blue picture.
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                pixels[y * w + x] = RED;
            }
        }
        FilterSettings settings = FilterSettings.NONE
                .toggleColour(KeepColour.RED)
                .withKeepAreas(List.of(new CropRect(50, 20, 20, 20)))
                .withPickedAreas(List.of(pick(120, 50, 40, 100, 50, 30, 30)));
        Filters.applyInPlace(settings, pixels, w, h);
        assertEquals(RED, pixels[0]);
        assertEquals(BLUE, pixels[25 * w + 55]);
        assertEquals(BLUE, pixels[60 * w + 115]);
        assertTrue(isGrey(pixels[90 * w + 190]));
    }

    @Test
    void pickedPixelsStayAndTheRestGoesGrey() {
        int w = 100;
        int h = 100;
        int[] pixels = solid(w, h, BLUE);
        FilterSettings settings = FilterSettings.NONE.withPickedAreas(List.of(pick(20, 20, 40, 10, 10, 20, 20)));
        Filters.applyInPlace(settings, pixels, w, h);
        assertEquals(BLUE, pixels[20 * w + 20]);
        assertEquals(BLUE, pixels[10 * w + 10]);
        assertEquals(BLUE, pixels[29 * w + 29]);
        assertTrue(isGrey(pixels[60 * w + 60]));
    }

    @Test
    void pickEdgeIsSoftAndShort() {
        int w = 1920;
        int h = 40;
        int[] pixels = solid(w, h, BLUE);
        FilterSettings settings = FilterSettings.NONE.withPickedAreas(List.of(pick(100, 20, 40, 100, 5, 100, 30)));
        Filters.applyInPlace(settings, pixels, w, h);
        int edge = Filters.pickEdgeWidthFor(w);
        assertEquals(2, edge);
        int justOutside = pixels[20 * w + 99];
        assertFalse(isGrey(justOutside), "first pixel outside should be partly kept");
        assertNotEquals(BLUE, justOutside);
        assertTrue(isGrey(pixels[20 * w + 99 - edge - 1]));
    }

    @Test
    void pickEdgeWidthFollowsPictureWidth() {
        assertEquals(1, Filters.pickEdgeWidthFor(300));
        assertEquals(1, Filters.pickEdgeWidthFor(960));
        assertEquals(2, Filters.pickEdgeWidthFor(1920));
        assertEquals(2, Filters.pickEdgeWidthFor(7680));
    }

    @Test
    void pickMakesBlurRestWork() {
        int w = 100;
        int h = 100;
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (i % 2 == 0) ? RED : BLUE;
        }
        FilterSettings settings = FilterSettings.NONE
                .withPickedAreas(List.of(pick(10, 10, 40, 0, 0, 20, 20)))
                .withRest(FilterSettings.Rest.BLUR);
        Filters.applyInPlace(settings, pixels, w, h);
        assertEquals(RED, pixels[0]);
        assertNotEquals(RED, pixels[60 * w + 60]);
        assertNotEquals(BLUE, pixels[60 * w + 60]);
    }

    @Test
    void scalingMapsPicksToo() {
        FilterSettings settings = FilterSettings.NONE
                .withPickedAreas(List.of(pick(500, 250, 40, 400, 200, 200, 100)))
                .scaledTo(1000, 500, 100, 50);
        PickedArea scaled = settings.pickedAreas().get(0);
        assertEquals(40, scaled.x());
        assertEquals(20, scaled.y());
        assertEquals(20, scaled.width());
        assertEquals(10, scaled.height());
        assertEquals(40, scaled.tolerance());
    }

    @Test
    void normalisingDedupesAndSortsPicks() {
        PickedArea a = pick(50, 50, 40, 0, 0, 4, 4);
        PickedArea b = pick(10, 10, 40, 0, 0, 4, 4);
        PickedArea aAgain = pick(50, 50, 40, 1, 1, 9, 9);
        FilterSettings normal = FilterSettings.NONE.withPickedAreas(List.of(a, b, aAgain)).normalised();
        assertEquals(2, normal.pickedAreas().size());
        assertEquals(10, normal.pickedAreas().get(0).seedX());
    }

    @Test
    void restIsKeptWhenOnlyAPickIsChosen() {
        FilterSettings s = FilterSettings.NONE
                .withPickedAreas(List.of(pick(1, 1, 40, 0, 0, 4, 4)))
                .withRest(FilterSettings.Rest.BLUR)
                .normalised();
        assertEquals(FilterSettings.Rest.BLUR, s.rest());
    }

    @Test
    void sameClickAndToleranceGiveTheSameKey() {
        FilterSettings one = FilterSettings.NONE.withPickedAreas(List.of(pick(50, 50, 40, 0, 0, 4, 4)));
        FilterSettings two = FilterSettings.NONE.withPickedAreas(List.of(pick(50, 50, 40, 5, 5, 8, 8)));
        FilterSettings other = FilterSettings.NONE.withPickedAreas(List.of(pick(50, 50, 52, 0, 0, 4, 4)));
        PictureKey a = PictureKey.of(CaptureStage.FINAL, Set.of(), Set.of(), List.of(), null, one, 100, 100);
        PictureKey b = PictureKey.of(CaptureStage.FINAL, Set.of(), Set.of(), List.of(), null, two, 100, 100);
        PictureKey c = PictureKey.of(CaptureStage.FINAL, Set.of(), Set.of(), List.of(), null, other, 100, 100);
        assertEquals(a, b);
        assertNotEquals(a, c);

        PictureKey.Tracker tracker = new PictureKey.Tracker();
        tracker.setDimensions(100, 100);
        tracker.markSaved(CaptureStage.FINAL, Set.of(), List.of(), null, one);
        assertTrue(tracker.isSaved(CaptureStage.FINAL, Set.of(), List.of(), null, two));
        assertFalse(tracker.isSaved(CaptureStage.FINAL, Set.of(), List.of(), null, other));
    }

    @Test
    void settingsKeepPicksAcrossColourChanges() {
        FilterSettings s = FilterSettings.NONE.withPickedAreas(List.of(pick(1, 1, 40, 0, 0, 4, 4)));
        assertEquals(1, s.toggleColour(KeepColour.RED).pickedAreas().size());
        assertEquals(1, s.pickBlackAndWhite().pickedAreas().size());
        assertEquals(1, s.pickNone().pickedAreas().size());
        assertEquals(1, s.withRest(FilterSettings.Rest.BLUR).pickedAreas().size());
        assertEquals(1, s.withKeepAreas(List.of(new CropRect(0, 0, 20, 20))).pickedAreas().size());
    }
}
