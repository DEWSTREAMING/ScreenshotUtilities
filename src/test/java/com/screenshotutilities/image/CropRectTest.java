package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.screenshotutilities.image.CropRect.Handle;
import org.junit.jupiter.api.Test;

class CropRectTest {
    @Test
    void rejectsNonPositiveSizeOrNegativeOrigin() {
        assertThrows(IllegalArgumentException.class, () -> new CropRect(-1, 0, 20, 20));
        assertThrows(IllegalArgumentException.class, () -> new CropRect(0, -1, 20, 20));
        assertThrows(IllegalArgumentException.class, () -> new CropRect(0, 0, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> new CropRect(0, 0, 20, 0));
    }

    @Test
    void clampEnforcesMinimumSizeAndImageBounds() {
        CropRect tinyAtEdge = new CropRect(1915, 1075, 4, 4).clamp(1920, 1080);
        assertEquals(new CropRect(1920 - CropRect.MIN_SIZE, 1080 - CropRect.MIN_SIZE, CropRect.MIN_SIZE, CropRect.MIN_SIZE), tinyAtEdge);

        CropRect oversized = new CropRect(500, 400, 3000, 2000).clamp(1920, 1080);
        assertEquals(new CropRect(0, 0, 1920, 1080), oversized);
    }

    @Test
    void clampAllowsImagesSmallerThanMinSize() {
        CropRect clamped = new CropRect(8, 8, 5, 5).clamp(10, 12);
        assertEquals(new CropRect(0, 0, 10, 12), clamped);
    }

    @Test
    void fromDragBuildsBoxInAnyDirectionAndEnforcesMinimumSize() {
        // Dragging down-right.
        assertEquals(new CropRect(100, 200, 300, 150), CropRect.fromDrag(100, 200, 400, 350, 1920, 1080));
        // Dragging up-left keeps the anchor at (400, 350) as the bottom-right corner.
        assertEquals(new CropRect(100, 200, 300, 150), CropRect.fromDrag(400, 350, 100, 200, 1920, 1080));
        // Tiny drag grows to MIN_SIZE from the anchor.
        assertEquals(new CropRect(100, 200, CropRect.MIN_SIZE, CropRect.MIN_SIZE),
                CropRect.fromDrag(100, 200, 102, 203, 1920, 1080));
        // Tiny drag near the bottom-right edge shifts inward so it stays inside the image.
        assertEquals(new CropRect(1920 - CropRect.MIN_SIZE, 1080 - CropRect.MIN_SIZE, CropRect.MIN_SIZE, CropRect.MIN_SIZE),
                CropRect.fromDrag(1918, 1077, 1950, 1100, 1920, 1080));
        // Tiny drag up-left near the top-left edge stays inside (0, 0).
        assertEquals(new CropRect(0, 0, CropRect.MIN_SIZE, CropRect.MIN_SIZE),
                CropRect.fromDrag(4, 3, -20, -10, 1920, 1080));
    }

    @Test
    void moveStopsAtEdgesWithoutChangingSize() {
        CropRect start = new CropRect(50, 60, 200, 100);
        assertEquals(new CropRect(0, 0, 200, 100), start.move(-500, -500, 800, 600));
        assertEquals(new CropRect(600, 500, 200, 100), start.move(2000, 2000, 800, 600));
        assertEquals(new CropRect(80, 40, 200, 100), start.move(30, -20, 800, 600));
    }

    @Test
    void resizeAtEdgesClampsToImageAndMinimumSize() {
        CropRect box = new CropRect(100, 100, 200, 150);

        // Pulling top-left past the origin clamps at (0, 0) while keeping bottom-right at (300, 250).
        assertEquals(new CropRect(0, 0, 300, 250), box.resize(Handle.TOP_LEFT, -500, -500, 800, 600));

        // Pulling top-left past the bottom-right corner stops at MIN_SIZE before (300, 250).
        assertEquals(new CropRect(300 - CropRect.MIN_SIZE, 250 - CropRect.MIN_SIZE, CropRect.MIN_SIZE, CropRect.MIN_SIZE),
                box.resize(Handle.TOP_LEFT, 500, 500, 800, 600));

        // Pulling bottom-right past the image edge clamps at (800, 600) while keeping top-left at (100, 100).
        assertEquals(new CropRect(100, 100, 700, 500), box.resize(Handle.BOTTOM_RIGHT, 900, 900, 800, 600));

        // Pulling bottom-right left/up stops at MIN_SIZE from (100, 100).
        assertEquals(new CropRect(100, 100, CropRect.MIN_SIZE, CropRect.MIN_SIZE),
                box.resize(Handle.BOTTOM_RIGHT, -500, -500, 800, 600));

        // Single-edge handles only change their own axis.
        assertEquals(new CropRect(60, 100, 240, 150), box.resize(Handle.LEFT, -40, 99, 800, 600));
        assertEquals(new CropRect(100, 100, 250, 150), box.resize(Handle.RIGHT, 50, -99, 800, 600));
        assertEquals(new CropRect(100, 70, 200, 180), box.resize(Handle.TOP, 88, -30, 800, 600));
        assertEquals(new CropRect(100, 100, 200, 190), box.resize(Handle.BOTTOM, -88, 40, 800, 600));
    }

    @Test
    void oddSizesStayExactAndContiguous() {
        int oddW = 1919;
        int oddH = 1079;
        CropRect full = new CropRect(0, 0, oddW, oddH);
        assertTrue(full.isFullImage(oddW, oddH));
        assertFalse(new CropRect(0, 0, oddW - 1, oddH).isFullImage(oddW, oddH));

        // Full odd-sized image maps to the exact full target dimensions.
        assertEquals(new CropRect(0, 0, 853, 479), full.scaleTo(oddW, oddH, 853, 479));

        // Two halves of an odd-sized image meet without a gap or overlap when scaled to an odd target.
        CropRect leftHalf = new CropRect(0, 0, 959, oddH);
        CropRect rightHalf = new CropRect(959, 0, oddW - 959, oddH);
        CropRect scaledLeft = leftHalf.scaleTo(oddW, oddH, 639, 359);
        CropRect scaledRight = rightHalf.scaleTo(oddW, oddH, 639, 359);
        assertEquals(0, scaledLeft.x());
        assertEquals(scaledLeft.right(), scaledRight.x());
        assertEquals(639, scaledRight.right());

        // Move and resize on odd bounds hit the exact odd right/bottom edge.
        CropRect moved = new CropRect(10, 10, 101, 77).move(5000, 5000, oddW, oddH);
        assertEquals(oddW, moved.right());
        assertEquals(oddH, moved.bottom());
    }

    @Test
    void extractCopiesSelectedSubRectangle() {
        // 4x3 image with values y * 10 + x.
        CropRect crop = new CropRect(1, 1, 2, 2);
        int[] out = crop.extract((x, y) -> y * 10 + x);
        assertArrayEquals(new int[]{11, 12, 21, 22}, out);

        int[] source = {
            0, 1, 2, 3,
            10, 11, 12, 13,
            20, 21, 22, 23
        };
        assertArrayEquals(new int[]{11, 12, 21, 22}, crop.extract(source, 4));
    }
}
