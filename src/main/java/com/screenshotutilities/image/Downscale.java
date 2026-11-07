package com.screenshotutilities.image;

/**
 * Shrinks a picture for previews. Pixels are packed ints and each 8-bit channel is averaged on its own, so the channel order
 * (ABGR from {@code NativeImage} or ARGB) doesn't matter.
 */
public final class Downscale {
    /** Cap on samples per axis for each output pixel, so a 4K source doesn't cost a full read per preview. */
    static final int MAX_SAMPLES = 4;

    private Downscale() {
    }

    @FunctionalInterface
    public interface PixelSource {
        int get(int x, int y);
    }

    /**
     * Largest size with the same aspect ratio that fits in {@code maxWidth} x {@code maxHeight}. Never scales up, and never
     * returns less than 1 x 1.
     */
    public static int[] fitWithin(int width, int height, int maxWidth, int maxHeight) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Empty image " + width + "x" + height);
        }
        double scale = Math.min(1.0, Math.min((double) maxWidth / width, (double) maxHeight / height));
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        return new int[]{Math.min(w, width), Math.min(h, height)};
    }

    /**
     * Box filter from a {@code srcWidth} x {@code srcHeight} source down to {@code dstWidth} x {@code dstHeight}. Returns
     * pixels row by row, top to bottom.
     */
    public static int[] box(PixelSource src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        int[] out = new int[dstWidth * dstHeight];
        for (int dy = 0; dy < dstHeight; dy++) {
            int y0 = (int) ((long) dy * srcHeight / dstHeight);
            int y1 = Math.max(y0 + 1, (int) ((long) (dy + 1) * srcHeight / dstHeight));
            for (int dx = 0; dx < dstWidth; dx++) {
                int x0 = (int) ((long) dx * srcWidth / dstWidth);
                int x1 = Math.max(x0 + 1, (int) ((long) (dx + 1) * srcWidth / dstWidth));
                out[dy * dstWidth + dx] = averageRegion(src, x0, x1, y0, y1);
            }
        }
        return out;
    }

    static int averageRegion(PixelSource src, int x0, int x1, int y0, int y1) {
        int stepX = Math.max(1, (x1 - x0) / MAX_SAMPLES);
        int stepY = Math.max(1, (y1 - y0) / MAX_SAMPLES);
        long c0 = 0, c1 = 0, c2 = 0, c3 = 0;
        int n = 0;
        for (int y = y0; y < y1; y += stepY) {
            for (int x = x0; x < x1; x += stepX) {
                int p = src.get(x, y);
                c0 += p & 0xFF;
                c1 += (p >>> 8) & 0xFF;
                c2 += (p >>> 16) & 0xFF;
                c3 += (p >>> 24) & 0xFF;
                n++;
            }
        }
        int half = n / 2;
        return (int) ((c0 + half) / n)
                | (int) ((c1 + half) / n) << 8
                | (int) ((c2 + half) / n) << 16
                | (int) ((c3 + half) / n) << 24;
    }
}
