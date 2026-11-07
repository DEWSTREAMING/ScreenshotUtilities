package com.screenshotutilities.image;

/**
 * A soft blur on packed 0xAABBGGRR pixels: three box blurs in a row, which look close to a gaussian and cost the same at any
 * radius. Pixels past the picture's edge count as the nearest edge pixel. Alpha is left as it is.
 */
public final class Blur {
    /** Radius is the picture's width over this, so the preview and the saved file look the same. */
    public static final int WIDTH_PER_RADIUS = 100;
    private static final int PASSES = 3;

    private Blur() {
    }

    public static int radiusFor(int imageWidth) {
        return Math.max(1, Math.round((float) imageWidth / WIDTH_PER_RADIUS));
    }

    /** Returns a new blurred array and leaves {@code pixels} alone. */
    public static int[] blurred(int[] pixels, int width, int height, int radius) {
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("Pixel length " + pixels.length + " != " + width + "x" + height);
        }
        int[] out = pixels.clone();
        if (radius <= 0) {
            return out;
        }
        int[] scratch = new int[out.length];
        for (int pass = 0; pass < PASSES; pass++) {
            boxHorizontal(out, scratch, width, height, radius);
            boxVertical(scratch, out, width, height, radius);
        }
        // The passes only write colour, so alpha comes back from the source.
        for (int i = 0; i < out.length; i++) {
            out[i] = (out[i] & 0x00FFFFFF) | (pixels[i] & 0xFF000000);
        }
        return out;
    }

    private static void boxHorizontal(int[] src, int[] dst, int width, int height, int radius) {
        int window = 2 * radius + 1;
        int half = window / 2;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int sumR = 0;
            int sumG = 0;
            int sumB = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = src[row + Math.clamp(i, 0, width - 1)];
                sumR += p & 0xFF;
                sumG += (p >> 8) & 0xFF;
                sumB += (p >> 16) & 0xFF;
            }
            for (int x = 0; x < width; x++) {
                dst[row + x] = ((sumB + half) / window) << 16 | ((sumG + half) / window) << 8 | ((sumR + half) / window);
                int out = src[row + Math.max(x - radius, 0)];
                int in = src[row + Math.min(x + radius + 1, width - 1)];
                sumR += (in & 0xFF) - (out & 0xFF);
                sumG += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sumB += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
            }
        }
    }

    /** Keeps one running sum per column and walks row by row, which is much kinder to the cache than column by column. */
    private static void boxVertical(int[] src, int[] dst, int width, int height, int radius) {
        int window = 2 * radius + 1;
        int half = window / 2;
        int[] sumR = new int[width];
        int[] sumG = new int[width];
        int[] sumB = new int[width];
        for (int i = -radius; i <= radius; i++) {
            int row = Math.clamp(i, 0, height - 1) * width;
            for (int x = 0; x < width; x++) {
                int p = src[row + x];
                sumR[x] += p & 0xFF;
                sumG[x] += (p >> 8) & 0xFF;
                sumB[x] += (p >> 16) & 0xFF;
            }
        }
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int outRow = Math.max(y - radius, 0) * width;
            int inRow = Math.min(y + radius + 1, height - 1) * width;
            for (int x = 0; x < width; x++) {
                dst[row + x] = ((sumB[x] + half) / window) << 16
                        | ((sumG[x] + half) / window) << 8
                        | ((sumR[x] + half) / window);
                int out = src[outRow + x];
                int in = src[inRow + x];
                sumR[x] += (in & 0xFF) - (out & 0xFF);
                sumG[x] += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sumB[x] += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
            }
        }
    }
}
