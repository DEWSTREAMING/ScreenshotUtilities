package com.screenshotutilities.image;

import com.screenshotutilities.capture.CaptureStage;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Something the player clicked with the wand: the connected pixels of a similar colour, or a whole entity. Immutable.
 *
 * <p>The mask is a {@link BitSet} inside the bounding box, in full-size picture pixels, the same way {@link LayerStack.Part}
 * stores its pixels. The click, the tolerance (or the entity's id) and the picture it was made from are what the player
 * chose, so those decide equality. The mask follows from them.</p>
 */
public final class PickedArea {
    /** Which working picture a pick was made from: stage, hidden HUD groups and erase boxes, before any filter or crop. */
    public record PictureSource(CaptureStage stage, Set<String> hiddenGroups, List<CropRect> eraseBoxes) {
        public static final PictureSource FINAL_AS_IS = new PictureSource(CaptureStage.FINAL, Set.of(), List.of());

        public PictureSource {
            Objects.requireNonNull(stage, "stage");
            // World has no HUD and nothing to erase, so those can't make two sources differ.
            boolean world = stage == CaptureStage.WORLD;
            hiddenGroups = world || hiddenGroups == null ? Set.of() : Set.copyOf(hiddenGroups);
            eraseBoxes = world || eraseBoxes == null ? List.of() : List.copyOf(eraseBoxes);
        }
    }

    /** Called for each set pixel of a pick, in row order. */
    @FunctionalInterface
    public interface PixelVisitor {
        void visit(int x, int y);
    }

    private final int seedX;
    private final int seedY;
    private final int tolerance;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final BitSet mask;
    private final PictureSource source;
    /** The entity's id in the shot's mask for a whole entity pick, 0 for a colour pick. */
    private final int mobId;
    /** The HUD group the click landed on, or null. While that group is hidden the pick has nothing left to mark. */
    @Nullable
    private final String hudGroup;

    public PickedArea(
            int seedX, int seedY, int tolerance, int x, int y, int width, int height, BitSet mask, PictureSource source) {
        this(seedX, seedY, tolerance, x, y, width, height, mask, source, 0, null);
    }

    private PickedArea(
            int seedX,
            int seedY,
            int tolerance,
            int x,
            int y,
            int width,
            int height,
            BitSet mask,
            PictureSource source,
            int mobId,
            @Nullable String hudGroup) {
        if (mobId < 0) {
            throw new IllegalArgumentException("Invalid mob id: " + mobId);
        }
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid picked area bounds: " + x + "," + y + " " + width + "x" + height);
        }
        this.seedX = seedX;
        this.seedY = seedY;
        this.tolerance = tolerance;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.mask = (BitSet) Objects.requireNonNull(mask, "mask").clone();
        this.source = Objects.requireNonNull(source, "source");
        this.mobId = mobId;
        this.hudGroup = hudGroup;
    }

    /** A whole entity, picked by its id in the shot's mask. Tolerance means nothing here and is 0. */
    public static PickedArea ofMob(
            int seedX, int seedY, int mobId, int x, int y, int width, int height, BitSet mask, PictureSource source) {
        if (mobId <= 0) {
            throw new IllegalArgumentException("Invalid mob id: " + mobId);
        }
        return new PickedArea(seedX, seedY, 0, x, y, width, height, mask, source, mobId, null);
    }

    public boolean isMob() {
        return mobId > 0;
    }

    public int mobId() {
        return mobId;
    }

    @Nullable
    public String hudGroup() {
        return hudGroup;
    }

    /** The same pick, tied to the HUD group its click landed on. */
    public PickedArea onHudGroup(@Nullable String group) {
        return new PickedArea(seedX, seedY, tolerance, x, y, width, height, mask, source, mobId, group);
    }

    public int seedX() {
        return seedX;
    }

    public int seedY() {
        return seedY;
    }

    public int tolerance() {
        return tolerance;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public PictureSource source() {
        return source;
    }

    /** How many pixels are selected. */
    public int pixelCount() {
        return mask.cardinality();
    }

    /** True if the pixel at picture coordinates {@code (px, py)} is selected. */
    public boolean contains(int px, int py) {
        int col = px - x;
        int row = py - y;
        return col >= 0 && row >= 0 && col < width && row < height && mask.get(row * width + col);
    }

    public void forEachPixel(PixelVisitor visitor) {
        for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1)) {
            int row = bit / width;
            visitor.visit(x + (bit - row * width), y + row);
        }
    }

    /** The same pick redone from another picture, for example the erase boxes mapped onto a retake of another size. */
    public PickedArea withSource(PictureSource newSource) {
        return new PickedArea(seedX, seedY, tolerance, x, y, width, height, mask, newSource, mobId, hudGroup);
    }

    /** The same pick with another tolerance and mask, for redoing it. */
    public PickedArea withSelection(int newTolerance, PickedArea selected) {
        return new PickedArea(seedX, seedY, newTolerance, selected.x, selected.y, selected.width, selected.height,
                selected.mask, source, mobId, hudGroup);
    }

    /**
     * The mask mapped into a picture of another size, such as the preview. A target pixel is set if any part of it was
     * selected, so a thin shape doesn't vanish when the picture gets smaller. The click moves with it.
     */
    public PickedArea scaledTo(int imageWidth, int imageHeight, int targetWidth, int targetHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("Empty image");
        }
        if (imageWidth == targetWidth && imageHeight == targetHeight) {
            return this;
        }
        int left = scaleDown(x, targetWidth, imageWidth);
        int top = scaleDown(y, targetHeight, imageHeight);
        int right = Math.min(targetWidth - 1, scaleLastCovered(x + width - 1, targetWidth, imageWidth));
        int bottom = Math.min(targetHeight - 1, scaleLastCovered(y + height - 1, targetHeight, imageHeight));
        int newWidth = right - left + 1;
        int newHeight = bottom - top + 1;
        BitSet scaled = new BitSet(newWidth * newHeight);
        forEachPixel((px, py) -> {
            int x0 = scaleDown(px, targetWidth, imageWidth);
            int x1 = Math.min(targetWidth - 1, scaleLastCovered(px, targetWidth, imageWidth));
            int y0 = scaleDown(py, targetHeight, imageHeight);
            int y1 = Math.min(targetHeight - 1, scaleLastCovered(py, targetHeight, imageHeight));
            for (int ty = y0; ty <= y1; ty++) {
                int row = (ty - top) * newWidth - left;
                scaled.set(row + x0, row + x1 + 1);
            }
        });
        int newSeedX = Math.clamp((long) seedX * targetWidth / imageWidth, 0, targetWidth - 1);
        int newSeedY = Math.clamp((long) seedY * targetHeight / imageHeight, 0, targetHeight - 1);
        return new PickedArea(newSeedX, newSeedY, tolerance, left, top, newWidth, newHeight, scaled, source, mobId, hudGroup);
    }

    /** First target pixel that the source pixel {@code p} touches. */
    private static int scaleDown(int p, int targetSize, int imageSize) {
        return (int) ((long) p * targetSize / imageSize);
    }

    /** Last target pixel that the source pixel {@code p} touches. */
    private static int scaleLastCovered(int p, int targetSize, int imageSize) {
        long end = ((long) (p + 1) * targetSize + imageSize - 1) / imageSize;
        return (int) Math.max(end - 1, scaleDown(p, targetSize, imageSize));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PickedArea that
                && seedX == that.seedX
                && seedY == that.seedY
                && tolerance == that.tolerance
                && mobId == that.mobId
                && source.equals(that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seedX, seedY, tolerance, mobId, source);
    }

    @Override
    public String toString() {
        return "PickedArea[seed=" + seedX + "," + seedY + ", tolerance=" + tolerance + ", mob=" + mobId + ", box=" + x + "," + y + " "
                + width + "x" + height + "]";
    }
}
