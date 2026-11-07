package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DownscaleTest {
    @Test
    void fitKeepsAspectRatio() {
        assertArrayEquals(new int[]{1280, 720}, Downscale.fitWithin(3840, 2160, 1280, 720));
        assertArrayEquals(new int[]{640, 720}, Downscale.fitWithin(1600, 1800, 1280, 720));
    }

    @Test
    void fitNeverScalesUp() {
        assertArrayEquals(new int[]{800, 600}, Downscale.fitWithin(800, 600, 1280, 720));
    }

    @Test
    void fitNeverReturnsZero() {
        // 10000x10 at 1% would be 100x0.1.
        assertArrayEquals(new int[]{100, 1}, Downscale.fitWithin(10000, 10, 100, 100));
    }

    @Test
    void fitRejectsEmptyImage() {
        assertThrows(IllegalArgumentException.class, () -> Downscale.fitWithin(0, 10, 100, 100));
    }

    @Test
    void sameSizeCopiesPixels() {
        int[] src = {0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 0x80123456};
        int[] out = Downscale.box((x, y) -> src[y * 2 + x], 2, 2, 2, 2);
        assertArrayEquals(src, out);
    }

    @Test
    void halvingAveragesEachChannel() {
        // Black and white on a 2x2 block average to mid grey in every channel, alpha included.
        int[] src = {0xFFFFFFFF, 0x00000000, 0x00000000, 0xFFFFFFFF};
        int[] out = Downscale.box((x, y) -> src[y * 2 + x], 2, 2, 1, 1);
        assertEquals(0x80808080, out[0]);
    }

    @Test
    void channelsDontBleedIntoEachOther() {
        int[] src = {0xFF0000FF, 0xFF0000FF, 0xFF00FF00, 0xFF00FF00};
        int[] out = Downscale.box((x, y) -> src[y * 2 + x], 2, 2, 1, 1);
        assertEquals(0xFF008080, out[0]);
    }

    @Test
    void largeSourceIsSampledNotFullyRead() {
        int[] reads = {0};
        Downscale.box((x, y) -> {
            reads[0]++;
            return 0xFFFFFFFF;
        }, 400, 400, 10, 10);
        // 40x40 source pixels per output pixel, capped at MAX_SAMPLES per axis.
        assertEquals(100 * Downscale.MAX_SAMPLES * Downscale.MAX_SAMPLES, reads[0]);
    }

    @Test
    void unevenScaleCoversWholeSource() {
        int[] out = Downscale.box((x, y) -> x == 4 ? 0xFFFFFFFF : 0xFF000000, 5, 1, 2, 1);
        // The last source column lands in the second output pixel.
        assertEquals(0xFF000000, out[0]);
        assertEquals(0xFF555555, out[1]);
    }
}
