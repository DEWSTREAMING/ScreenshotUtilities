package com.screenshotutilities.image;

import java.util.List;
import javax.annotation.Nullable;

/**
 * Image filters on packed 0xAABBGGRR pixels (NativeImage format).
 */
public final class Filters {
    public static final double WEIGHT_RED = 0.2126;
    public static final double WEIGHT_GREEN = 0.7152;
    public static final double WEIGHT_BLUE = 0.0722;

    public static final float MIN_SATURATION = 0.12f;
    public static final float MAX_SATURATION = 0.20f;

    /** Picked areas fade out over one pixel on a small picture and two on a large one. */
    public static final int WIDTH_PER_PICK_EDGE_PIXEL = 960;

    /** Keep areas fade out over the picture's width over this, so the soft edge looks the same on the preview and the file. */
    public static final int WIDTH_PER_EDGE_PIXEL = 320;

    private Filters() {
    }

    public static int toGrey(int r, int g, int b) {
        return Math.clamp((int) Math.round(WEIGHT_RED * r + WEIGHT_GREEN * g + WEIGHT_BLUE * b), 0, 255);
    }

    public static int pickEdgeWidthFor(int imageWidth) {
        return Math.clamp(Math.round((float) imageWidth / WIDTH_PER_PICK_EDGE_PIXEL), 1, 2);
    }

    public static int edgeWidthFor(int imageWidth) {
        return Math.max(1, Math.round((float) imageWidth / WIDTH_PER_EDGE_PIXEL));
    }

    /**
     * Filters a whole picture. Keep areas in {@code settings} must be in this picture's own pixels, see
     * {@link FilterSettings#scaledTo}. A full-size picture with Blur takes real time, so never call this on the render thread.
     */
    public static void applyInPlace(FilterSettings settings, int[] pixels, int width, int height) {
        if (settings == null || pixels == null || !settings.isActive()) {
            return;
        }
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("Pixel length " + pixels.length + " != " + width + "x" + height);
        }
        KeepColour[] colours = settings.colours().toArray(new KeepColour[0]);
        float[] areaMask = keepMask(settings.keepAreas(), settings.pickedAreas(), width, height);
        boolean blackAndWhite = settings.blackAndWhite();
        int[] blurred = settings.keepsAnything() && settings.rest() == FilterSettings.Rest.BLUR
                ? Blur.blurred(pixels, width, height, Blur.radiusFor(width))
                : null;

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int r = pixel & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = (pixel >> 16) & 0xFF;

            float keep = colours.length > 0 ? colourKeep(colours, r, g, b) : 0.0f;
            if (areaMask != null && areaMask[i] > keep) {
                keep = areaMask[i];
            }
            if (keep >= 1.0f) {
                continue;
            }

            int restR;
            int restG;
            int restB;
            if (blurred != null && !blackAndWhite) {
                int rest = blurred[i];
                restR = rest & 0xFF;
                restG = (rest >> 8) & 0xFF;
                restB = (rest >> 16) & 0xFF;
            } else {
                int rest = blurred != null ? blurred[i] : pixel;
                restR = toGrey(rest & 0xFF, (rest >> 8) & 0xFF, (rest >> 16) & 0xFF);
                restG = restR;
                restB = restR;
            }

            int outR = keep > 0.0f ? blend(restR, r, keep) : restR;
            int outG = keep > 0.0f ? blend(restG, g, keep) : restG;
            int outB = keep > 0.0f ? blend(restB, b, keep) : restB;
            pixels[i] = (pixel & 0xFF000000) | (outB << 16) | (outG << 8) | outR;
        }
    }

    private static int blend(int rest, int original, float keep) {
        return Math.clamp((int) Math.round(rest + keep * (original - rest)), 0, 255);
    }

    /** How much of this pixel's colour stays, from 0 to 1, taking the best match among {@code colours}. */
    static float colourKeep(KeepColour[] colours, int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int delta = max - min;
        if (delta == 0 || max == 0) {
            return 0.0f;
        }
        float sat = (float) delta / max;
        if (sat <= MIN_SATURATION) {
            return 0.0f;
        }

        float hue;
        if (max == r) {
            hue = 60.0f * ((g - b) / (float) delta);
            if (hue < 0.0f) {
                hue += 360.0f;
            }
        } else if (max == g) {
            hue = 60.0f * (2.0f + (b - r) / (float) delta);
        } else {
            hue = 60.0f * (4.0f + (r - g) / (float) delta);
        }

        float hueFactor = 0.0f;
        for (KeepColour colour : colours) {
            float match = colour.hueMatch(hue);
            if (match > hueFactor) {
                hueFactor = match;
            }
        }
        float satFactor = sat >= MAX_SATURATION ? 1.0f : (sat - MIN_SATURATION) / (MAX_SATURATION - MIN_SATURATION);
        return hueFactor * satFactor;
    }

    /**
     * Builds a mask where 1 is fully kept and 0 is not kept. Inside a box is always 1, and the outside fades to 0 over the soft
     * edge. Overlapping boxes take the higher value, so the order of the boxes doesn't matter.
     */
    @Nullable
    static float[] keepAreaMask(List<CropRect> areas, int width, int height) {
        return keepMask(areas, List.of(), width, height);
    }

    /** Boxes and picked areas together into one mask, taking the higher value where they overlap. */
    @Nullable
    static float[] keepMask(List<CropRect> areas, List<PickedArea> picks, int width, int height) {
        if (areas.isEmpty() && picks.isEmpty()) {
            return null;
        }
        float[] mask = new float[width * height];
        addBoxes(mask, areas, width, height);
        int pickEdge = pickEdgeWidthFor(width);
        for (PickedArea pick : picks) {
            addPick(mask, pick, pickEdge, width, height);
        }
        return mask;
    }

    /** A pick is fully kept inside, and a short soft edge outside it comes from blurring its mask a little. */
    private static void addPick(float[] mask, PickedArea pick, int edge, int width, int height) {
        int x0 = Math.max(0, pick.x() - edge);
        int y0 = Math.max(0, pick.y() - edge);
        int x1 = Math.min(width - 1, pick.x() + pick.width() - 1 + edge);
        int y1 = Math.min(height - 1, pick.y() + pick.height() - 1 + edge);
        if (x1 < x0 || y1 < y0) {
            return;
        }
        int w = x1 - x0 + 1;
        int h = y1 - y0 + 1;
        float[] cells = new float[w * h];
        pick.forEachPixel((px, py) -> {
            if (px >= x0 && px <= x1 && py >= y0 && py <= y1) {
                cells[(py - y0) * w + (px - x0)] = 1.0f;
            }
        });
        float[] soft = new float[w * h];
        float inv = 1.0f / (2 * edge + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float sum = 0.0f;
                for (int k = Math.max(0, x - edge); k <= Math.min(w - 1, x + edge); k++) {
                    sum += cells[row + k];
                }
                soft[row + x] = sum * inv;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int index = y * w + x;
                float value = cells[index] > 0.0f ? 1.0f : 0.0f;
                if (value == 0.0f) {
                    float sum = 0.0f;
                    for (int k = Math.max(0, y - edge); k <= Math.min(h - 1, y + edge); k++) {
                        sum += soft[k * w + x];
                    }
                    value = sum * inv;
                }
                if (value > 0.0f) {
                    int target = (y0 + y) * width + x0 + x;
                    if (value > mask[target]) {
                        mask[target] = value;
                    }
                }
            }
        }
    }

    private static void addBoxes(float[] mask, List<CropRect> areas, int width, int height) {
        int edge = edgeWidthFor(width);
        float fade = edge + 1.0f;
        for (CropRect box : areas) {
            int left = box.x();
            int top = box.y();
            int right = box.right() - 1;
            int bottom = box.bottom() - 1;
            int x0 = Math.max(0, left - edge);
            int x1 = Math.min(width - 1, right + edge);
            int y0 = Math.max(0, top - edge);
            int y1 = Math.min(height - 1, bottom + edge);
            for (int y = y0; y <= y1; y++) {
                int dy = Math.max(0, Math.max(top - y, y - bottom));
                int row = y * width;
                for (int x = x0; x <= x1; x++) {
                    int dx = Math.max(0, Math.max(left - x, x - right));
                    float value;
                    if (dx == 0 && dy == 0) {
                        value = 1.0f;
                    } else {
                        float t = 1.0f - (float) Math.sqrt(dx * dx + dy * dy) / fade;
                        if (t <= 0.0f) {
                            continue;
                        }
                        value = t * t * (3.0f - 2.0f * t);
                    }
                    if (value > mask[row + x]) {
                        mask[row + x] = value;
                    }
                }
            }
        }
    }
}
