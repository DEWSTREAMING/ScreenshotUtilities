package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.screenshotutilities.capture.CaptureStage;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PickedAreaTest {
    private static PickedArea box(int seedX, int seedY, int tol, int x, int y, int w, int h, PickedArea.PictureSource source) {
        BitSet mask = new BitSet();
        mask.set(0, w * h);
        return new PickedArea(seedX, seedY, tol, x, y, w, h, mask, source);
    }

    private static PickedArea box(int seedX, int seedY, int tol, int x, int y, int w, int h) {
        return box(seedX, seedY, tol, x, y, w, h, PickedArea.PictureSource.FINAL_AS_IS);
    }

    @Test
    void equalWhenClickToleranceAndSourceMatch() {
        PickedArea a = box(5, 5, 40, 0, 0, 10, 10);
        PickedArea b = box(5, 5, 40, 2, 2, 4, 4);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, box(6, 5, 40, 0, 0, 10, 10));
        assertNotEquals(a, box(5, 5, 52, 0, 0, 10, 10));
    }

    @Test
    void differentSourcesAreDifferentPicks() {
        PickedArea.PictureSource hidden = new PickedArea.PictureSource(CaptureStage.FINAL, Set.of("hotbar"), List.of());
        assertNotEquals(box(5, 5, 40, 0, 0, 4, 4), box(5, 5, 40, 0, 0, 4, 4, hidden));
    }

    @Test
    void worldSourceIgnoresHiddenGroupsAndEraseBoxes() {
        PickedArea.PictureSource a = new PickedArea.PictureSource(
                CaptureStage.WORLD, Set.of("hotbar"), List.of(new CropRect(0, 0, 20, 20)));
        assertEquals(new PickedArea.PictureSource(CaptureStage.WORLD, Set.of(), List.of()), a);
    }

    @Test
    void maskIsCopiedSoLaterChangesDontReachIt() {
        BitSet mask = new BitSet();
        mask.set(0, 4);
        PickedArea area = new PickedArea(0, 0, 40, 0, 0, 2, 2, mask, PickedArea.PictureSource.FINAL_AS_IS);
        mask.clear();
        assertEquals(4, area.pixelCount());
    }

    @Test
    void containsUsesPictureCoordinates() {
        PickedArea area = box(12, 12, 40, 10, 20, 5, 3);
        assertTrue(area.contains(10, 20));
        assertTrue(area.contains(14, 22));
        assertFalse(area.contains(15, 22));
        assertFalse(area.contains(9, 20));
        assertFalse(area.contains(10, 23));
    }

    @Test
    void scalingToTheSameSizeGivesTheSamePick() {
        PickedArea area = box(5, 5, 40, 0, 0, 10, 10);
        assertSame(area, area.scaledTo(100, 100, 100, 100));
    }

    @Test
    void thinLineSurvivesScalingDown() {
        // One pixel wide vertical line in a 1000 x 500 picture, shown at 100 x 50.
        BitSet mask = new BitSet();
        for (int row = 0; row < 400; row++) {
            mask.set(row * 1 + 0);
        }
        PickedArea line = new PickedArea(333, 50, 40, 333, 50, 1, 400, mask, PickedArea.PictureSource.FINAL_AS_IS);
        PickedArea small = line.scaledTo(1000, 500, 100, 50);
        assertEquals(40, small.pixelCount());
        assertTrue(small.contains(33, 5));
        assertTrue(small.contains(33, 44));
        assertFalse(small.contains(32, 5));
    }

    @Test
    void aPixelIsSetIfAnyPartOfItWasSelected() {
        // A single selected pixel at (3, 3) in a 10 x 10 picture, shown at 5 x 5, lands in (1, 1).
        PickedArea one = box(3, 3, 40, 3, 3, 1, 1);
        PickedArea small = one.scaledTo(10, 10, 5, 5);
        assertEquals(1, small.pixelCount());
        assertTrue(small.contains(1, 1));
    }

    @Test
    void scalingUpCoversWholeBlocks() {
        PickedArea one = box(1, 1, 40, 1, 1, 1, 1);
        PickedArea big = one.scaledTo(4, 4, 8, 8);
        assertEquals(4, big.pixelCount());
        assertTrue(big.contains(2, 2));
        assertTrue(big.contains(3, 3));
        assertFalse(big.contains(4, 4));
    }

    @Test
    void scaledBoxStaysInsideTheTarget() {
        PickedArea edge = box(99, 99, 40, 90, 90, 10, 10);
        PickedArea small = edge.scaledTo(100, 100, 7, 7);
        assertTrue(small.x() + small.width() <= 7);
        assertTrue(small.y() + small.height() <= 7);
        assertEquals(small.source(), edge.source());
        assertEquals(small.tolerance(), edge.tolerance());
    }
}
