package com.screenshotutilities.image;

import javax.annotation.Nullable;

/**
 * The last two steps of building an edited picture. The filter comes before the crop, because keep areas are in full-size
 * pixels and blur has to reach past the crop edge.
 */
public final class WorkingPicture {
    /** Row-major packed pixels and their size. */
    public record Pixels(int[] pixels, int width, int height) {
    }

    private WorkingPicture() {
    }

    /** Filters {@code fullPixels} in place, so pass a copy of anything that must stay as it is. */
    public static Pixels filterThenCrop(
            int[] fullPixels, int width, int height, @Nullable FilterSettings filter, @Nullable CropRect crop) {
        Filters.applyInPlace(filter, fullPixels, width, height);
        if (crop == null || crop.isFullImage(width, height)) {
            return new Pixels(fullPixels, width, height);
        }
        CropRect clamped = crop.clamp(width, height);
        return new Pixels(clamped.extract(fullPixels, width), clamped.width(), clamped.height());
    }
}
