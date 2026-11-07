package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BlurTest {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    private static int pack(int a, int b, int g, int r) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int[] noise(int count) {
        int[] pixels = new int[count];
        for (int i = 0; i < count; i++) {
            pixels[i] = pack(255, (i * 31) & 0xFF, (i * 57) & 0xFF, (i * 91) & 0xFF);
        }
        return pixels;
    }

    @Test
    void flatStaysFlat() {
        int colour = pack(255, 12, 200, 77);
        int[] pixels = new int[64 * 48];
        Arrays.fill(pixels, colour);
        assertArrayEquals(pixels, Blur.blurred(pixels, 64, 48, 5));
    }

    @Test
    void alphaIsUnchanged() {
        int[] pixels = noise(40 * 30);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (pixels[i] & 0x00FFFFFF) | ((i * 13 & 0xFF) << 24);
        }
        int[] out = Blur.blurred(pixels, 40, 30, 4);
        for (int i = 0; i < pixels.length; i++) {
            assertEquals(pixels[i] >>> 24, out[i] >>> 24);
        }
    }

    @Test
    void sourceIsLeftAlone() {
        int[] pixels = noise(20 * 20);
        int[] before = pixels.clone();
        Blur.blurred(pixels, 20, 20, 3);
        assertArrayEquals(before, pixels);
    }

    @Test
    void channelsStayApart() {
        int[] pixels = new int[30 * 30];
        Arrays.fill(pixels, pack(255, 0, 0, 200));
        int[] out = Blur.blurred(pixels, 30, 30, 3);
        assertEquals(0, (out[100] >> 8) & 0xFF);
        assertEquals(0, (out[100] >> 16) & 0xFF);
        assertEquals(200, out[100] & 0xFF);
    }

    @Test
    void hardEdgeBecomesSoft() {
        int w = 100;
        int h = 10;
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = i % w < 50 ? BLACK : WHITE;
        }
        int[] out = Blur.blurred(pixels, w, h, 4);
        int previous = -1;
        for (int x = 40; x < 60; x++) {
            int value = out[5 * w + x] & 0xFF;
            assertTrue(value >= previous, "Should rise steadily at " + x);
            previous = value;
        }
        int middle = out[5 * w + 50] & 0xFF;
        assertTrue(middle > 90 && middle < 170, "Got " + middle);
    }

    @Test
    void edgesDontGoDark() {
        int[] pixels = new int[50 * 50];
        Arrays.fill(pixels, WHITE);
        int[] out = Blur.blurred(pixels, 50, 50, 20);
        assertEquals(WHITE, out[0]);
        assertEquals(WHITE, out[49]);
        assertEquals(WHITE, out[49 * 50]);
        assertEquals(WHITE, out[50 * 50 - 1]);
    }

    @Test
    void oddSizesAndRadiusBiggerThanThePicture() {
        for (int[] size : new int[][] {{1, 1}, {1, 9}, {9, 1}, {3, 7}, {7, 3}, {5, 5}}) {
            int count = size[0] * size[1];
            int[] pixels = noise(count);
            int[] out = Blur.blurred(pixels, size[0], size[1], 25);
            assertEquals(count, out.length);
            for (int i = 0; i < count; i++) {
                assertEquals(255, out[i] >>> 24);
            }
        }
    }

    @Test
    void onePixelStaysAsItIs() {
        int[] pixels = {pack(255, 10, 20, 30)};
        assertArrayEquals(pixels, Blur.blurred(pixels, 1, 1, 5));
    }

    @Test
    void radiusFollowsThePictureWidth() {
        assertEquals(19, Blur.radiusFor(1920));
        assertEquals(13, Blur.radiusFor(1280));
        assertEquals(1, Blur.radiusFor(16));
    }

    @Test
    void previewAndFullSizeLookTheSame() {
        // The same gradient at two sizes, blurred with each size's radius, should match at the same spot.
        int fullW = 1000;
        int smallW = 500;
        int[] full = gradientWithStep(fullW);
        int[] small = gradientWithStep(smallW);
        int[] fullOut = Blur.blurred(full, fullW, 1, Blur.radiusFor(fullW));
        int[] smallOut = Blur.blurred(small, smallW, 1, Blur.radiusFor(smallW));
        for (int x = 0; x < smallW; x += 25) {
            int a = smallOut[x] & 0xFF;
            int b = fullOut[x * 2] & 0xFF;
            assertTrue(Math.abs(a - b) <= 12, "At " + x + ": " + a + " and " + b);
        }
    }

    private static int[] gradientWithStep(int width) {
        int[] pixels = new int[width];
        for (int x = 0; x < width; x++) {
            pixels[x] = x < width / 2 ? BLACK : WHITE;
        }
        return pixels;
    }

    @Test
    void wrongPixelCountIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Blur.blurred(new int[3], 2, 2, 1));
    }
}
