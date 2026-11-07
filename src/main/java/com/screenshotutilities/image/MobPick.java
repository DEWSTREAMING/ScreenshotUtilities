package com.screenshotutilities.image;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Picking a whole entity from an {@link EntityMask}, minus whatever covers it in the picture being picked from. */
public final class MobPick {
    private MobPick() {
    }

    /**
     * Every pixel of the entity under the click that isn't covered, or null when the click isn't on an entity that shows:
     * no entity there, the clicked pixel is covered, or nothing of it is left.
     *
     * @param covered pixels where something else is drawn over the world in that picture, may be null for none
     */
    @Nullable
    public static PickedArea select(
            EntityMask mask, int seedX, int seedY, @Nullable BitSet covered, PickedArea.PictureSource source) {
        int width = mask.width();
        if (seedX < 0 || seedY < 0 || seedX >= width || seedY >= mask.height()) {
            return null;
        }
        int seed = seedY * width + seedX;
        if (covered != null && covered.get(seed)) {
            return null;
        }
        int id = mask.idAt(seed);
        if (id == EntityMask.NONE) {
            return null;
        }
        return pixelsOf(mask, id, seedX, seedY, covered, source);
    }

    /** All uncovered pixels of one id, or null if it has none. The click is kept as the pick's seed. */
    @Nullable
    public static PickedArea pixelsOf(
            EntityMask mask, int id, int seedX, int seedY, @Nullable BitSet covered, PickedArea.PictureSource source) {
        if (id == EntityMask.NONE) {
            return null;
        }
        int width = mask.width();
        int height = mask.height();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                if (mask.idAt(i) == id && (covered == null || !covered.get(i))) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = y;
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        int boxWidth = maxX - minX + 1;
        int boxHeight = maxY - minY + 1;
        BitSet bits = new BitSet(boxWidth * boxHeight);
        for (int y = minY; y <= maxY; y++) {
            int row = y * width;
            int bitRow = (y - minY) * boxWidth - minX;
            for (int x = minX; x <= maxX; x++) {
                int i = row + x;
                if (mask.idAt(i) == id && (covered == null || !covered.get(i))) {
                    bits.set(bitRow + x);
                }
            }
        }
        return PickedArea.ofMob(seedX, seedY, id, minX, minY, boxWidth, boxHeight, bits, source);
    }

    /**
     * Pixels the HUD covers in a Final picture: every changed pixel of each group that is shown, except the groups in
     * {@code ignored}, and nothing inside an erase box, where the World stage shows through.
     */
    public static BitSet hudCover(
            List<LayerStack.Part> parts,
            Set<String> hiddenGroups,
            Set<String> ignored,
            List<CropRect> eraseBoxes,
            int width,
            int height) {
        BitSet cover = new BitSet(width * height);
        for (LayerStack.Part part : parts) {
            if (hiddenGroups.contains(part.groupId()) || ignored.contains(part.groupId())) {
                continue;
            }
            BitSet bits = part.mask();
            for (int bit = bits.nextSetBit(0); bit >= 0; bit = bits.nextSetBit(bit + 1)) {
                int row = bit / part.width();
                int x = part.x() + bit - row * part.width();
                int y = part.y() + row;
                if (x < width && y < height) {
                    cover.set(y * width + x);
                }
            }
        }
        clearBoxes(cover, eraseBoxes, width, height);
        return cover;
    }

    /** Frees the pixels inside the erase boxes. Changes {@code cover}. */
    public static void clearBoxes(BitSet cover, List<CropRect> eraseBoxes, int width, int height) {
        for (CropRect box : eraseBoxes) {
            int x0 = Math.clamp(box.x(), 0, width);
            int y0 = Math.clamp(box.y(), 0, height);
            int x1 = Math.clamp(box.right(), x0, width);
            int y1 = Math.clamp(box.bottom(), y0, height);
            if (x1 <= x0) {
                continue;
            }
            for (int y = y0; y < y1; y++) {
                cover.clear(y * width + x0, y * width + x1);
            }
        }
    }
}
