package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MagicWandTest {
    private static final int GREEN = rgb(40, 140, 40);
    private static final int WHITE = rgb(240, 240, 240);

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    private static int[] filled(int w, int h, int colour) {
        int[] pixels = new int[w * h];
        Arrays.fill(pixels, colour);
        return pixels;
    }

    private static void disc(int[] pixels, int w, int cx, int cy, int radius, int colour) {
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (x >= 0 && y >= 0 && x < w && y < pixels.length / w
                        && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius) {
                    pixels[y * w + x] = colour;
                }
            }
        }
    }

    private static int discSize(int w, int h, int cx, int cy, int radius) {
        int[] probe = new int[w * h];
        disc(probe, w, cx, cy, radius, 1);
        int n = 0;
        for (int p : probe) {
            n += p;
        }
        return n;
    }

    /** The four one pixel tips of a disc count as spurs and are trimmed. */
    private static void assertNear(int expected, int actual) {
        assertTrue(actual <= expected && actual >= expected - 4, "expected about " + expected + " but was " + actual);
    }

    @Test
    void whiteBlobOnGreenIsSelectedExactly() {
        int[] pixels = filled(100, 100, GREEN);
        disc(pixels, 100, 50, 50, 20, WHITE);
        PickedArea area = MagicWand.select(pixels, 100, 100, 50, 50, MagicWand.DEFAULT_TOLERANCE);
        assertNear(discSize(100, 100, 50, 50, 20), area.pixelCount());
        assertTrue(area.contains(50, 50));
        assertTrue(area.contains(31, 50));
        assertFalse(area.contains(29, 50));
        assertFalse(area.contains(5, 5));
    }

    @Test
    void texturedBlobGetsMostOfItselfWithNoSpeckle() {
        Random random = new Random(7);
        int[] pixels = filled(200, 200, GREEN);
        disc(pixels, 200, 100, 100, 60, WHITE);
        // Wool texture and shading: noise on every pixel, plus a slow darkening across the blob.
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                if (pixels[y * 200 + x] == WHITE) {
                    int shade = (x - 40) / 4;
                    int v = 240 - shade + random.nextInt(21) - 10;
                    pixels[y * 200 + x] = rgb(v, v, v);
                }
            }
        }
        PickedArea area = MagicWand.select(pixels, 200, 200, 100, 100, MagicWand.DEFAULT_TOLERANCE);
        int blob = discSize(200, 200, 100, 100, 60);
        int[] inside = {0};
        int[] outside = {0};
        area.forEachPixel((x, y) -> {
            if ((x - 100) * (x - 100) + (y - 100) * (y - 100) <= 60 * 60) {
                inside[0]++;
            } else {
                outside[0]++;
            }
        });
        assertTrue(inside[0] >= blob * 0.97, "covered " + inside[0] + " of " + blob);
        assertEquals(0, outside[0]);
    }

    @Test
    void blobTouchingTheEdgeIsSelected() {
        int[] pixels = filled(80, 60, GREEN);
        disc(pixels, 80, 0, 30, 15, WHITE);
        PickedArea area = MagicWand.select(pixels, 80, 60, 0, 30, MagicWand.DEFAULT_TOLERANCE);
        assertEquals(0, area.x());
        assertNear(discSize(80, 60, 0, 30, 15), area.pixelCount());
    }

    @Test
    void clickOnTheCornerWorks() {
        int[] pixels = filled(10, 10, GREEN);
        PickedArea area = MagicWand.select(pixels, 10, 10, 0, 0, MagicWand.DEFAULT_TOLERANCE);
        assertEquals(100, area.pixelCount());
        PickedArea past = MagicWand.select(pixels, 10, 10, 99, 99, MagicWand.DEFAULT_TOLERANCE);
        assertEquals(100, past.pixelCount());
    }

    @Test
    void oneColourPictureIsAllSelected() {
        int[] pixels = filled(64, 37, WHITE);
        PickedArea area = MagicWand.select(pixels, 64, 37, 10, 10, MagicWand.DEFAULT_TOLERANCE);
        assertEquals(64 * 37, area.pixelCount());
    }

    @Test
    void oddSizesWork() {
        for (int[] size : new int[][] {{1, 1}, {1, 9}, {9, 1}, {13, 7}}) {
            int[] pixels = filled(size[0], size[1], GREEN);
            PickedArea area = MagicWand.select(pixels, size[0], size[1], size[0] / 2, size[1] / 2, 20);
            assertEquals(size[0] * size[1], area.pixelCount());
        }
    }

    @Test
    void twoBlobsOfTheSameColourStaySeparate() {
        int[] pixels = filled(120, 60, GREEN);
        disc(pixels, 120, 25, 30, 15, WHITE);
        disc(pixels, 120, 95, 30, 15, WHITE);
        PickedArea area = MagicWand.select(pixels, 120, 60, 25, 30, MagicWand.DEFAULT_TOLERANCE);
        assertTrue(area.contains(25, 30));
        assertFalse(area.contains(95, 30));
        assertNear(discSize(120, 60, 25, 30, 15), area.pixelCount());
    }

    @Test
    void diagonalGapDoesNotLeak() {
        int w = 40;
        int[] pixels = filled(w, w, GREEN);
        // A white box on the left and one on the right that only touch at a corner.
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                pixels[y * w + x] = WHITE;
            }
        }
        for (int y = 20; y < 40; y++) {
            for (int x = 20; x < 40; x++) {
                pixels[y * w + x] = WHITE;
            }
        }
        PickedArea area = MagicWand.select(pixels, w, w, 5, 5, MagicWand.DEFAULT_TOLERANCE);
        assertEquals(400, area.pixelCount());
        assertFalse(area.contains(30, 30));
    }

    @Test
    void smallHoleInsideIsFilled() {
        int[] pixels = filled(100, 100, GREEN);
        disc(pixels, 100, 50, 50, 30, WHITE);
        disc(pixels, 100, 45, 45, 2, rgb(0, 0, 0));
        PickedArea area = MagicWand.select(pixels, 100, 100, 50, 50, MagicWand.DEFAULT_TOLERANCE);
        assertTrue(area.contains(45, 45));
        assertNear(discSize(100, 100, 50, 50, 30), area.pixelCount());
    }

    @Test
    void bigHoleStaysOut() {
        int[] pixels = filled(100, 100, GREEN);
        disc(pixels, 100, 50, 50, 40, WHITE);
        disc(pixels, 100, 50, 50, 20, GREEN);
        PickedArea area = MagicWand.select(pixels, 100, 100, 50, 15, MagicWand.DEFAULT_TOLERANCE);
        assertFalse(area.contains(50, 50));
    }

    @Test
    void thinSpurIsTrimmedButTheBodyStays() {
        int[] pixels = filled(60, 60, GREEN);
        for (int y = 10; y < 40; y++) {
            for (int x = 10; x < 40; x++) {
                pixels[y * 60 + x] = WHITE;
            }
        }
        // A one pixel hair sticking out of the corner of the body.
        for (int x = 40; x < 50; x++) {
            pixels[10 * 60 + x] = WHITE;
        }
        PickedArea area = MagicWand.select(pixels, 60, 60, 20, 20, MagicWand.DEFAULT_TOLERANCE);
        assertTrue(area.contains(39, 10));
        assertTrue(area.contains(39, 39));
        assertFalse(area.contains(49, 10));
    }

    @Test
    void tolerancePicksTheClickedColourAndGrowsTheSelection() {
        // A strip that darkens by 6 per column.
        int w = 40;
        int[] pixels = new int[w * 3];
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < w; x++) {
                int v = 250 - x * 6;
                pixels[y * w + x] = rgb(v, v, v);
            }
        }
        int tight = MagicWand.select(pixels, w, 3, 0, 1, 20).pixelCount();
        int normal = MagicWand.select(pixels, w, 3, 0, 1, 60).pixelCount();
        int loose = MagicWand.select(pixels, w, 3, 0, 1, 120).pixelCount();
        assertTrue(tight < normal, tight + " < " + normal);
        assertTrue(normal < loose, normal + " < " + loose);
    }

    @Test
    void toleranceIsClamped() {
        int[] pixels = filled(10, 10, GREEN);
        assertEquals(MagicWand.MIN_TOLERANCE, MagicWand.select(pixels, 10, 10, 1, 1, -5).tolerance());
        assertEquals(MagicWand.MAX_TOLERANCE, MagicWand.select(pixels, 10, 10, 1, 1, 9999).tolerance());
    }

    @Test
    void sourceIsKept() {
        int[] pixels = filled(10, 10, GREEN);
        PickedArea.PictureSource source = new PickedArea.PictureSource(
                com.screenshotutilities.capture.CaptureStage.FINAL, java.util.Set.of("hotbar"), java.util.List.of());
        assertEquals(source, MagicWand.select(pixels, 10, 10, 1, 1, 20, source).source());
    }

    @Test
    void fullHdSelectionIsFast() {
        int w = 1920;
        int h = 1080;
        Random random = new Random(3);
        int[] pixels = filled(w, h, GREEN);
        disc(pixels, w, 960, 540, 520, WHITE);
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == WHITE) {
                int v = 235 + random.nextInt(11);
                pixels[i] = rgb(v, v, v);
            }
        }
        long best = Long.MAX_VALUE;
        PickedArea area = null;
        for (int run = 0; run < 3; run++) {
            long start = System.nanoTime();
            area = MagicWand.select(pixels, w, h, 960, 540, MagicWand.DEFAULT_TOLERANCE);
            best = Math.min(best, System.nanoTime() - start);
        }
        long wholePicture = 0;
        long start = System.nanoTime();
        PickedArea all = MagicWand.select(filled(w, h, GREEN), w, h, 10, 10, MagicWand.DEFAULT_TOLERANCE);
        wholePicture = System.nanoTime() - start;
        System.out.println("MagicWand 1080p disc " + best / 1_000_000 + " ms (" + area.pixelCount() + " px), whole picture "
                + wholePicture / 1_000_000 + " ms");
        assertEquals(w * h, all.pixelCount());
        assertTrue(best / 1_000_000 < 100, "took " + best / 1_000_000 + " ms");
    }
}
