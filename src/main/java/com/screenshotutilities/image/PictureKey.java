package com.screenshotutilities.image;

import com.screenshotutilities.capture.CaptureStage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Identifies a saved picture by its stage, hidden HUD groups, erase boxes, optional crop, and filter.
 *
 * <p>A crop that covers the whole image is treated the same as no crop, and hiding all available groups is treated the
 * same as the {@link CaptureStage#WORLD} view.</p>
 */
public record PictureKey(
        CaptureStage stage,
        Set<String> hiddenGroups,
        List<CropRect> eraseBoxes,
        @Nullable CropRect crop,
        FilterSettings filter) {
    public PictureKey {
        Objects.requireNonNull(stage, "stage");
        hiddenGroups = hiddenGroups != null ? Set.copyOf(hiddenGroups) : Set.of();
        eraseBoxes = eraseBoxes != null ? List.copyOf(eraseBoxes) : List.of();
        filter = filter != null ? filter.normalised() : FilterSettings.NONE;
    }

    public PictureKey(CaptureStage stage, @Nullable CropRect crop) {
        this(stage, Set.of(), List.of(), crop, FilterSettings.NONE);
    }

    public PictureKey(CaptureStage stage, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
        this(stage, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
    }

    public static PictureKey of(CaptureStage stage, @Nullable CropRect crop, int imageWidth, int imageHeight) {
        return of(stage, Set.of(), Set.of(), List.of(), crop, FilterSettings.NONE, imageWidth, imageHeight);
    }

    public static PictureKey of(
            CaptureStage stage,
            @Nullable Set<String> hiddenGroups,
            @Nullable Set<String> availableGroups,
            @Nullable List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            int imageWidth,
            int imageHeight) {
        return of(stage, hiddenGroups, availableGroups, eraseBoxes, crop, FilterSettings.NONE, imageWidth, imageHeight);
    }

    public static PictureKey of(
            CaptureStage stage,
            @Nullable Set<String> hiddenGroups,
            @Nullable Set<String> availableGroups,
            @Nullable List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            @Nullable FilterSettings filter,
            int imageWidth,
            int imageHeight) {
        Objects.requireNonNull(stage, "stage");
        CropRect normalizedCrop = crop != null && !crop.isFullImage(imageWidth, imageHeight) ? crop : null;
        FilterSettings normalizedFilter = filter != null ? filter : FilterSettings.NONE;

        Set<String> normalizedHidden;
        if (stage == CaptureStage.WORLD || hiddenGroups == null || hiddenGroups.isEmpty()) {
            normalizedHidden = Set.of();
        } else if (availableGroups != null && !availableGroups.isEmpty()) {
            Set<String> filtered = new LinkedHashSet<>();
            for (String g : hiddenGroups) {
                if (availableGroups.contains(g)) {
                    filtered.add(g);
                }
            }
            if (filtered.size() == availableGroups.size()) {
                stage = CaptureStage.WORLD;
                normalizedHidden = Set.of();
            } else {
                normalizedHidden = Set.copyOf(filtered);
            }
        } else {
            normalizedHidden = Set.copyOf(hiddenGroups);
        }

        List<CropRect> normalizedErase;
        if (stage == CaptureStage.WORLD || eraseBoxes == null || eraseBoxes.isEmpty()) {
            normalizedErase = List.of();
        } else {
            List<CropRect> unique = new ArrayList<>(eraseBoxes.size());
            for (CropRect box : eraseBoxes) {
                if (box != null && !unique.contains(box)) {
                    unique.add(box);
                }
            }
            normalizedErase = List.copyOf(unique);
        }

        return new PictureKey(stage, normalizedHidden, normalizedErase, normalizedCrop, normalizedFilter);
    }

    /**
     * Tracks which working pictures from a capture have been saved to disk and which have been added to the gallery,
     * keeping the two sets independent.
     */
    public static final class Tracker {
        private final Set<PictureKey> savedPictures = new HashSet<>();
        private final Set<PictureKey> galleryPictures = new HashSet<>();
        private Set<String> availableGroups = Set.of();
        private int imageWidth;
        private int imageHeight;

        public void setDimensions(int width, int height) {
            if (width > 0 && height > 0) {
                this.imageWidth = width;
                this.imageHeight = height;
            }
        }

        public void setAvailableGroups(@Nullable Set<String> groups) {
            this.availableGroups = groups != null ? Set.copyOf(groups) : Set.of();
            renormalize(savedPictures);
            renormalize(galleryPictures);
        }

        public boolean isSaved(
                CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
            return isSaved(view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
        }

        public boolean isSaved(
                CaptureStage view,
                Set<String> hiddenGroups,
                List<CropRect> eraseBoxes,
                @Nullable CropRect crop,
                @Nullable FilterSettings filter) {
            return savedPictures.contains(keyFor(view, hiddenGroups, eraseBoxes, crop, filter));
        }

        public void markSaved(
                CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
            markSaved(view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
        }

        public void markSaved(
                CaptureStage view,
                Set<String> hiddenGroups,
                List<CropRect> eraseBoxes,
                @Nullable CropRect crop,
                @Nullable FilterSettings filter) {
            savedPictures.add(keyFor(view, hiddenGroups, eraseBoxes, crop, filter));
        }

        public boolean isInGallery(
                CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
            return isInGallery(view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
        }

        public boolean isInGallery(
                CaptureStage view,
                Set<String> hiddenGroups,
                List<CropRect> eraseBoxes,
                @Nullable CropRect crop,
                @Nullable FilterSettings filter) {
            return galleryPictures.contains(keyFor(view, hiddenGroups, eraseBoxes, crop, filter));
        }

        public void markInGallery(
                CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
            markInGallery(view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
        }

        public void markInGallery(
                CaptureStage view,
                Set<String> hiddenGroups,
                List<CropRect> eraseBoxes,
                @Nullable CropRect crop,
                @Nullable FilterSettings filter) {
            galleryPictures.add(keyFor(view, hiddenGroups, eraseBoxes, crop, filter));
        }

        public void unmarkInGallery(
                CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
            unmarkInGallery(view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
        }

        public void unmarkInGallery(
                CaptureStage view,
                Set<String> hiddenGroups,
                List<CropRect> eraseBoxes,
                @Nullable CropRect crop,
                @Nullable FilterSettings filter) {
            galleryPictures.remove(keyFor(view, hiddenGroups, eraseBoxes, crop, filter));
        }

        private PictureKey keyFor(
                CaptureStage view,
                Set<String> hiddenGroups,
                List<CropRect> eraseBoxes,
                @Nullable CropRect crop,
                @Nullable FilterSettings filter) {
            if (imageWidth > 0 && imageHeight > 0) {
                return PictureKey.of(view, hiddenGroups, availableGroups, eraseBoxes, crop, filter, imageWidth, imageHeight);
            }
            return new PictureKey(view, hiddenGroups, eraseBoxes, crop, filter);
        }

        private void renormalize(Set<PictureKey> set) {
            if (set.isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
                return;
            }
            List<PictureKey> existing = List.copyOf(set);
            set.clear();
            for (PictureKey k : existing) {
                set.add(PictureKey.of(
                        k.stage(), k.hiddenGroups(), availableGroups, k.eraseBoxes(), k.crop(), k.filter(), imageWidth, imageHeight));
            }
        }
    }
}
