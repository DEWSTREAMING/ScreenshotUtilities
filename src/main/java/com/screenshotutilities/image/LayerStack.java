package com.screenshotutilities.image;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Rebuilds a screenshot from the World stage and the per-group pixel diffs captured across the HUD draw order.
 *
 * <p>A group whose layers are split across the draw order has more than one {@link Part} with the same {@code groupId}.
 * Parts are applied in their original draw order so non-adjacent runs still layer accurately when visible.</p>
 */
public final class LayerStack {
    private final int width;
    private final int height;
    private final int[] worldPixels;
    private final List<Part> parts;
    private final List<String> groups;

    /**
     * One contiguous run's changed pixels inside its tight bounding box {@code (x, y, width, height)}.
     */
    public record Part(String groupId, int x, int y, int width, int height, BitSet mask, int[] pixels) {
        public Part {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(mask, "mask");
            Objects.requireNonNull(pixels, "pixels");
            if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Invalid part bounds: " + x + "," + y + " " + width + "x" + height);
            }
        }

        public int changedCount() {
            return pixels.length;
        }

        public void applyTo(int[] canvas, int imageWidth) {
            int p = 0;
            for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1)) {
                int row = bit / width;
                int col = bit - row * width;
                canvas[(y + row) * imageWidth + (x + col)] = pixels[p++];
            }
        }
    }

    public LayerStack(int width, int height, int[] worldPixels, List<Part> parts) {
        this(width, height, worldPixels, parts, collectGroups(parts));
    }

    public LayerStack(int width, int height, int[] worldPixels, List<Part> parts, List<String> groups) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image size: " + width + "x" + height);
        }
        if (worldPixels.length != width * height) {
            throw new IllegalArgumentException("World pixel length " + worldPixels.length + " != " + (width * height));
        }
        this.width = width;
        this.height = height;
        this.worldPixels = worldPixels.clone();
        this.parts = List.copyOf(parts);
        this.groups = List.copyOf(groups);
    }

    private static List<String> collectGroups(List<Part> parts) {
        Set<String> seen = new LinkedHashSet<>();
        for (Part part : parts) {
            seen.add(part.groupId());
        }
        return List.copyOf(seen);
    }

    /**
     * The group drawn on top at {@code (px, py)}, leaving out {@code skipped} groups and pixels inside an erase box, or null
     * if only the world shows there.
     */
    @Nullable
    public String groupAt(int px, int py, Set<String> skipped, List<CropRect> eraseBoxes) {
        for (CropRect box : eraseBoxes) {
            if (px >= box.x() && py >= box.y() && px < box.right() && py < box.bottom()) {
                return null;
            }
        }
        for (int i = parts.size() - 1; i >= 0; i--) {
            Part part = parts.get(i);
            int col = px - part.x();
            int row = py - part.y();
            if (!skipped.contains(part.groupId()) && col >= 0 && row >= 0 && col < part.width() && row < part.height()
                    && part.mask().get(row * part.width() + col)) {
                return part.groupId();
            }
        }
        return null;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int[] worldPixels() {
        return worldPixels.clone();
    }

    public List<Part> parts() {
        return parts;
    }

    /** Distinct group ids that changed at least one pixel, in the order they first appeared. */
    public List<String> groups() {
        return groups;
    }

    /**
     * Extracts the tight bounding box and changed pixels between {@code before} and {@code after}, or returns {@code null}
     * if nothing changed.
     */
    @Nullable
    public static Part extractPart(String groupId, int[] before, int[] after, int imageWidth, int imageHeight) {
        return extractPartInBounds(groupId, before, after, imageWidth, 0, 0, imageWidth, imageHeight);
    }

    @Nullable
    private static Part extractPartInBounds(
            String groupId, int[] before, int[] after, int imageWidth, int x0, int y0, int x1, int y1) {
        int minX = x1;
        int minY = y1;
        int maxX = -1;
        int maxY = -1;
        int count = 0;

        for (int y = y0; y < y1; y++) {
            int rowOffset = y * imageWidth;
            for (int x = x0; x < x1; x++) {
                int idx = rowOffset + x;
                if (before[idx] != after[idx]) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                    count++;
                }
            }
        }

        if (count == 0) {
            return null;
        }

        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        BitSet mask = new BitSet(bw * bh);
        int[] pixels = new int[count];
        int p = 0;

        for (int row = 0; row < bh; row++) {
            int srcRow = (minY + row) * imageWidth + minX;
            int maskRow = row * bw;
            for (int col = 0; col < bw; col++) {
                int idx = srcRow + col;
                int val = after[idx];
                if (val != before[idx]) {
                    mask.set(maskRow + col);
                    pixels[p++] = val;
                }
            }
        }

        return new Part(groupId, minX, minY, bw, bh, mask, pixels);
    }

    /**
     * Builds a stack from {@code worldPixels} and an ordered sequence of {@code (groupId, stagePixels)} snapshots. Groups
     * that changed nothing are dropped.
     */
    public static LayerStack fromSnapshots(
            int width, int height, int[] worldPixels, List<String> stepGroups, List<int[]> stepSnapshots) {
        if (stepGroups.size() != stepSnapshots.size()) {
            throw new IllegalArgumentException("Group and snapshot counts differ");
        }
        List<Part> parts = new ArrayList<>();
        int[] prev = worldPixels;
        for (int i = 0; i < stepSnapshots.size(); i++) {
            int[] curr = stepSnapshots.get(i);
            if (curr.length != width * height) {
                throw new IllegalArgumentException("Snapshot length " + curr.length + " != " + (width * height));
            }
            Part part = extractPart(stepGroups.get(i), prev, curr, width, height);
            if (part != null) {
                parts.add(part);
            }
            prev = curr;
        }
        return new LayerStack(width, height, worldPixels, parts);
    }

    /** Rebuilds the picture with {@code hiddenGroups} left out. */
    public int[] rebuild(Set<String> hiddenGroups) {
        return rebuild(hiddenGroups, Collections.emptyList());
    }

    /** Rebuilds the picture with {@code hiddenGroups} left out and {@code eraseBoxes} restored to World. */
    public int[] rebuild(Set<String> hiddenGroups, List<CropRect> eraseBoxes) {
        Set<String> hidden = hiddenGroups != null ? hiddenGroups : Collections.emptySet();
        int[] out = worldPixels.clone();
        for (Part part : parts) {
            if (!hidden.contains(part.groupId())) {
                part.applyTo(out, width);
            }
        }
        applyEraseBoxes(out, worldPixels, width, height, eraseBoxes);
        return out;
    }

    /** Copies {@code worldPixels} onto {@code canvas} inside each box in {@code eraseBoxes}. */
    public static void applyEraseBoxes(
            int[] canvas, int[] worldPixels, int width, int height, @Nullable List<CropRect> eraseBoxes) {
        if (eraseBoxes == null || eraseBoxes.isEmpty()) {
            return;
        }
        for (CropRect box : eraseBoxes) {
            int x0 = Math.clamp(box.x(), 0, width);
            int y0 = Math.clamp(box.y(), 0, height);
            int x1 = Math.clamp(box.right(), x0, width);
            int y1 = Math.clamp(box.bottom(), y0, height);
            int span = x1 - x0;
            if (span <= 0) {
                continue;
            }
            for (int y = y0; y < y1; y++) {
                int offset = y * width + x0;
                System.arraycopy(worldPixels, offset, canvas, offset, span);
            }
        }
    }

    /**
     * Builds a preview-sized {@link LayerStack} on the worker thread so toggling groups in the popup never has to downscale
     * or walk the full-resolution image on the render thread.
     */
    public LayerStack downscale(int dstWidth, int dstHeight) {
        if (dstWidth == width && dstHeight == height) {
            return this;
        }
        int[] fullCanvas = worldPixels.clone();
        Downscale.PixelSource src = (x, y) -> fullCanvas[y * width + x];
        int[] previewWorld = Downscale.box(src, width, height, dstWidth, dstHeight);
        int[] prevPreview = previewWorld.clone();
        int[] currPreview = previewWorld.clone();
        List<Part> previewParts = new ArrayList<>(parts.size());

        for (Part part : parts) {
            part.applyTo(fullCanvas, width);
            int dx0 = Math.max(0, (int) ((long) part.x() * dstWidth / width) - 1);
            int dy0 = Math.max(0, (int) ((long) part.y() * dstHeight / height) - 1);
            int dx1 = Math.min(dstWidth, (int) ((long) (part.x() + part.width()) * dstWidth / width) + 2);
            int dy1 = Math.min(dstHeight, (int) ((long) (part.y() + part.height()) * dstHeight / height) + 2);

            for (int dy = dy0; dy < dy1; dy++) {
                int y0 = (int) ((long) dy * height / dstHeight);
                int y1 = Math.max(y0 + 1, (int) ((long) (dy + 1) * height / dstHeight));
                int rowOffset = dy * dstWidth;
                for (int dx = dx0; dx < dx1; dx++) {
                    int x0 = (int) ((long) dx * width / dstWidth);
                    int x1 = Math.max(x0 + 1, (int) ((long) (dx + 1) * width / dstWidth));
                    currPreview[rowOffset + dx] = Downscale.averageRegion(src, x0, x1, y0, y1);
                }
            }

            Part previewPart = extractPartInBounds(part.groupId(), prevPreview, currPreview, dstWidth, dx0, dy0, dx1, dy1);
            if (previewPart != null) {
                previewParts.add(previewPart);
                for (int dy = dy0; dy < dy1; dy++) {
                    int rowOffset = dy * dstWidth + dx0;
                    System.arraycopy(currPreview, rowOffset, prevPreview, rowOffset, dx1 - dx0);
                }
            }
        }

        return new LayerStack(dstWidth, dstHeight, previewWorld, previewParts, groups);
    }
}
