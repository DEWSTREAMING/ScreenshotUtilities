package com.screenshotutilities.image;

import java.util.BitSet;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Which entity each pixel of a shot belongs to. Immutable, kept with the shot in memory only.
 *
 * <p>The GPU draws every entity in a flat colour that is its id: red is the low byte, green the high byte. Blue marks pixels
 * the HUD drew on, when that was measured. Id 0 means no entity. Ids are stored as {@code short} and read back unsigned.</p>
 */
public final class EntityMask {
    public static final int NONE = 0;
    /** Ids fit red and green. */
    public static final int MAX_ID = 0xFFFF;

    private final int width;
    private final int height;
    private final short[] ids;
    @Nullable
    private final BitSet hudPixels;
    private final List<String> names;

    /**
     * @param names     name of id {@code i} at index {@code i - 1}
     * @param hudPixels pixels the HUD drew on, or null if that wasn't measured
     */
    public EntityMask(int width, int height, short[] ids, @Nullable BitSet hudPixels, List<String> names) {
        if (width <= 0 || height <= 0 || ids.length != width * height) {
            throw new IllegalArgumentException("Expected " + width + "x" + height + ", got " + ids.length + " ids");
        }
        this.width = width;
        this.height = height;
        this.ids = ids;
        this.hudPixels = hudPixels;
        this.names = List.copyOf(names);
    }

    /** The colour the GPU draws an id in, as {@code {red, green, blue}} from 0 to 255. */
    public static int[] rgbOf(int id) {
        if (id < 0 || id > MAX_ID) {
            throw new IllegalArgumentException("Id out of range: " + id);
        }
        return new int[] {id & 0xFF, (id >> 8) & 0xFF, 0};
    }

    /** The id in a pixel read as {@code 0xAABBGGRR}. Blue and alpha are ignored. */
    public static int idOfPixel(int abgr) {
        return abgr & 0xFFFF;
    }

    public static boolean isHudPixel(int abgr) {
        return ((abgr >> 16) & 0xFF) != 0;
    }

    /**
     * Builds the mask from a raw read, which has its first row at the bottom.
     *
     * @param hudMeasured whether the blue channel holds the HUD pixels
     */
    public static EntityMask fromBottomUp(int[] pixels, int width, int height, boolean hudMeasured, List<String> names) {
        if (width <= 0 || height <= 0 || pixels.length != width * height) {
            throw new IllegalArgumentException("Expected " + width + "x" + height + ", got " + pixels.length + " pixels");
        }
        short[] ids = new short[width * height];
        BitSet hud = hudMeasured ? new BitSet(width * height) : null;
        for (int y = 0; y < height; y++) {
            int src = (height - 1 - y) * width;
            int dst = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = pixels[src + x];
                ids[dst + x] = (short) idOfPixel(pixel);
                if (hud != null && isHudPixel(pixel)) {
                    hud.set(dst + x);
                }
            }
        }
        return new EntityMask(width, height, ids, hud, names);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The id at a pixel, {@link #NONE} outside the picture. */
    public int idAt(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return NONE;
        }
        return ids[y * width + x] & 0xFFFF;
    }

    int idAt(int index) {
        return ids[index] & 0xFFFF;
    }

    /** A copy of the pixels the HUD drew on, or null if that wasn't measured. */
    @Nullable
    public BitSet hudPixels() {
        return hudPixels != null ? (BitSet) hudPixels.clone() : null;
    }

    /** How many ids were handed out. Ids run from 1 to this. */
    public int entityCount() {
        return names.size();
    }

    /** The entity's name, or an empty string for an id that wasn't handed out. */
    public String nameOf(int id) {
        return id >= 1 && id <= names.size() ? names.get(id - 1) : "";
    }

    /** How many pixels each id has, index is the id. Ids that were never handed out are left out. */
    public int[] pixelCounts() {
        int[] counts = new int[names.size() + 1];
        for (short raw : ids) {
            int id = raw & 0xFFFF;
            if (id < counts.length) {
                counts[id]++;
            }
        }
        return counts;
    }

    /** Pixels holding an id that was never handed out, which would mean colours didn't survive the draw exactly. */
    public int strayPixels() {
        int stray = 0;
        for (short raw : ids) {
            if ((raw & 0xFFFF) > names.size()) {
                stray++;
            }
        }
        return stray;
    }
}
