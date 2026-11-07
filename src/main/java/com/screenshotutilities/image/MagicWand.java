package com.screenshotutilities.image;

import java.util.BitSet;

/**
 * Selects the connected pixels of a similar colour, like the magic wand in a paint program. It knows nothing about objects,
 * only colours, so it suits a white sheep on grass and not a green stem on green grass.
 *
 * <p>Works on packed 0xAABBGGRR pixels (NativeImage order). Takes real time on a big picture, so keep it off the render
 * thread.</p>
 */
public final class MagicWand {
    public static final int MIN_TOLERANCE = 4;
    public static final int MAX_TOLERANCE = 160;
    public static final int DEFAULT_TOLERANCE = 40;
    public static final int TOLERANCE_STEP = 12;

    /** Holes up to this share of the selection are filled, with a floor so a small pick still loses its eyes. */
    private static final int HOLE_SHARE = 20;
    private static final int MIN_HOLE_LIMIT = 32;

    private static final byte UNSEEN = 0;
    private static final byte SELECTED = 1;
    private static final byte REJECTED = 2;

    private MagicWand() {
    }

    public static int clampTolerance(int tolerance) {
        return Math.clamp(tolerance, MIN_TOLERANCE, MAX_TOLERANCE);
    }

    public static PickedArea select(int[] pixels, int width, int height, int seedX, int seedY, int tolerance) {
        return select(pixels, width, height, seedX, seedY, tolerance, PickedArea.PictureSource.FINAL_AS_IS);
    }

    /**
     * Returns the pixels joined to {@code (seedX, seedY)} by an edge (not a corner) whose colour is within {@code tolerance}
     * of the clicked one. The seed is clamped into the picture, so a click on the very edge works.
     */
    public static PickedArea select(
            int[] pixels, int width, int height, int seedX, int seedY, int tolerance, PickedArea.PictureSource source) {
        if (width <= 0 || height <= 0 || pixels.length != width * height) {
            throw new IllegalArgumentException("Pixel length " + pixels.length + " != " + width + "x" + height);
        }
        int sx = Math.clamp(seedX, 0, width - 1);
        int sy = Math.clamp(seedY, 0, height - 1);
        int tol = clampTolerance(tolerance);
        return grow(pixels, width, height, sx, sy, tol, source);
    }

    private static PickedArea grow(
            int[] pixels, int width, int height, int sx, int sy, int tol, PickedArea.PictureSource source) {
        byte[] state = new byte[width * height];
        int[] queue = new int[(width + 2) * (height + 2)];
        long limit = (long) tol * tol;
        int seed = pixels[sy * width + sx];
        long sumR = seed & 0xFF;
        long sumG = (seed >> 8) & 0xFF;
        long sumB = (seed >> 16) & 0xFF;

        int head = 0;
        int tail = 0;
        int minX = sx;
        int maxX = sx;
        int minY = sy;
        int maxY = sy;
        queue[tail++] = sy * width + sx;
        state[sy * width + sx] = SELECTED;
        int selected = 1;

        while (head < tail) {
            int index = queue[head++];
            int px = index % width;
            int py = index / width;
            if (px < minX) {
                minX = px;
            }
            if (px > maxX) {
                maxX = px;
            }
            if (py < minY) {
                minY = py;
            }
            if (py > maxY) {
                maxY = py;
            }
            for (int n = 0; n < 4; n++) {
                int nx = n == 0 ? px - 1 : n == 1 ? px + 1 : px;
                int ny = n == 2 ? py - 1 : n == 3 ? py + 1 : py;
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }
                int next = ny * width + nx;
                if (state[next] != UNSEEN) {
                    continue;
                }
                int c = pixels[next];
                long dr = (c & 0xFF) - sumR / selected;
                long dg = ((c >> 8) & 0xFF) - sumG / selected;
                long db = ((c >> 16) & 0xFF) - sumB / selected;
                if (dr * dr + dg * dg + db * db <= limit) {
                    state[next] = SELECTED;
                    queue[tail++] = next;
                    selected++;
                    sumR += c & 0xFF;
                    sumG += (c >> 8) & 0xFF;
                    sumB += (c >> 16) & 0xFF;
                } else {
                    state[next] = REJECTED;
                }
            }
        }

        int boxW = maxX - minX + 1;
        int boxH = maxY - minY + 1;
        // A grid with a one cell border, so the outside is always one piece.
        int gridW = boxW + 2;
        int gridH = boxH + 2;
        boolean[] grid = new boolean[gridW * gridH];
        for (int row = 0; row < boxH; row++) {
            int src = (minY + row) * width + minX;
            int dst = (row + 1) * gridW + 1;
            for (int col = 0; col < boxW; col++) {
                grid[dst + col] = state[src + col] == SELECTED;
            }
        }
        fillHoles(grid, gridW, gridH, Math.max(MIN_HOLE_LIMIT, selected / HOLE_SHARE), queue);
        dropSpurs(grid, gridW, gridH, sx - minX + 1, sy - minY + 1,
                minX == 0, maxX == width - 1, minY == 0, maxY == height - 1);

        BitSet mask = new BitSet(boxW * boxH);
        for (int row = 0; row < boxH; row++) {
            int src = (row + 1) * gridW + 1;
            for (int col = 0; col < boxW; col++) {
                if (grid[src + col]) {
                    mask.set(row * boxW + col);
                }
            }
        }
        return new PickedArea(sx, sy, tol, minX, minY, boxW, boxH, mask, source);
    }

    /** Fills unselected pieces that the outside can't reach, if they are small. */
    private static void fillHoles(boolean[] grid, int gridW, int gridH, int maxHole, int[] queue) {
        byte[] seen = new byte[grid.length];
        flood(grid, seen, gridW, gridH, 0, queue);
        for (int start = 0; start < grid.length; start++) {
            if (grid[start] || seen[start] != 0) {
                continue;
            }
            int size = flood(grid, seen, gridW, gridH, start, queue);
            if (size <= maxHole) {
                for (int i = 0; i < size; i++) {
                    grid[queue[i]] = true;
                }
            }
        }
    }

    /** Marks the unselected piece around {@code start} in {@code seen} and leaves its cells at the front of {@code queue}. */
    private static int flood(boolean[] grid, byte[] seen, int gridW, int gridH, int start, int[] queue) {
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        seen[start] = 1;
        while (head < tail) {
            int index = queue[head++];
            int gx = index % gridW;
            int gy = index / gridW;
            if (gx > 0 && !grid[index - 1] && seen[index - 1] == 0) {
                seen[index - 1] = 1;
                queue[tail++] = index - 1;
            }
            if (gx < gridW - 1 && !grid[index + 1] && seen[index + 1] == 0) {
                seen[index + 1] = 1;
                queue[tail++] = index + 1;
            }
            if (gy > 0 && !grid[index - gridW] && seen[index - gridW] == 0) {
                seen[index - gridW] = 1;
                queue[tail++] = index - gridW;
            }
            if (gy < gridH - 1 && !grid[index + gridW] && seen[index + gridW] == 0) {
                seen[index + gridW] = 1;
                queue[tail++] = index + gridW;
            }
        }
        return tail;
    }

    /** One pass that drops selected pixels with fewer than two selected neighbours, other than the clicked one. */
    private static void dropSpurs(
            boolean[] grid, int gridW, int gridH, int keepX, int keepY,
            boolean atLeft, boolean atRight, boolean atTop, boolean atBottom) {
        boolean[] before = grid.clone();
        for (int gy = 1; gy < gridH - 1; gy++) {
            for (int gx = 1; gx < gridW - 1; gx++) {
                int index = gy * gridW + gx;
                if (!before[index] || (gx == keepX && gy == keepY)) {
                    continue;
                }
                // Past the edge of the picture counts as selected, so a whole-picture pick isn't nibbled.
                int neighbours = (before[index - 1] || (atLeft && gx == 1) ? 1 : 0)
                        + (before[index + 1] || (atRight && gx == gridW - 2) ? 1 : 0)
                        + (before[index - gridW] || (atTop && gy == 1) ? 1 : 0)
                        + (before[index + gridW] || (atBottom && gy == gridH - 2) ? 1 : 0);
                if (neighbours < 2) {
                    grid[index] = false;
                }
            }
        }
    }
}
