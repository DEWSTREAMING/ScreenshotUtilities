package com.screenshotutilities.capture;

import com.mojang.blaze3d.platform.NativeImage;
import com.screenshotutilities.image.CropRect;
import com.screenshotutilities.image.EntityMask;
import com.screenshotutilities.image.FilterSettings;
import com.screenshotutilities.image.LayerStack;
import com.screenshotutilities.image.PictureKey;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.GuiMessage;

/**
 * One F2 press: the stage images, optional HUD layer diffs, and where the shot was saved. Images are native memory, so
 * they are closed as soon as the last lease is released, whichever thread that happens on.
 */
public final class Capture {
    /** A raw intermediate framebuffer read awaiting diff extraction on the worker thread. */
    public static final class RawLayerRead {
        private final String groupId;
        @Nullable
        private NativeImage image;
        private final boolean unflipped;

        public RawLayerRead(String groupId, NativeImage image, boolean unflipped) {
            this.groupId = groupId;
            this.image = image;
            this.unflipped = unflipped;
        }

        public String groupId() {
            return groupId;
        }

        public boolean unflipped() {
            return unflipped;
        }

        @Nullable
        public synchronized NativeImage image() {
            return image;
        }

        public synchronized void closeImage() {
            if (image != null) {
                image.close();
                image = null;
            }
        }
    }

    private final Map<CaptureStage, NativeImage> stages = new EnumMap<>(CaptureStage.class);
    private final List<RawLayerRead> rawLayerReads = new ArrayList<>();
    private final PictureKey.Tracker pictures = new PictureKey.Tracker();
    private final File file;
    private final boolean screenWasOpen;
    private int leases = 1;
    private boolean closed;
    private boolean layersPending;
    @Nullable
    private LayerStack layerStack;
    @Nullable
    private LayerStack previewLayerStack;
    private Set<String> initialHiddenGroups = Set.of();
    @Nullable
    private CaptureStage savedStage;
    private boolean entityMaskPending;
    @Nullable
    private EntityMask entityMask;
    /** The first shot a shader retake came from, so a retake of a retake is still named after it. */
    @Nullable
    private volatile File retakeOf;
    /** The newest chat line when the frame was captured. Render thread only. */
    @Nullable
    private GuiMessage chatMarker;
    private boolean chatMarkerKnown;

    /** The creator holds the first lease. */
    Capture(File file, boolean screenWasOpen) {
        this.file = file;
        this.screenWasOpen = screenWasOpen;
    }

    synchronized void put(CaptureStage stage, NativeImage image) {
        if (closed) {
            image.close();
            throw new IllegalStateException("Capture already closed");
        }
        pictures.setDimensions(image.getWidth(), image.getHeight());
        NativeImage old = stages.put(stage, image);
        if (old != null) {
            old.close();
        }
    }

    synchronized void addLayerRead(String groupId, NativeImage image, boolean unflipped) {
        if (closed) {
            image.close();
            throw new IllegalStateException("Capture already closed");
        }
        rawLayerReads.add(new RawLayerRead(groupId, image, unflipped));
    }

    synchronized boolean hasLayerReads() {
        return !closed && !rawLayerReads.isEmpty();
    }

    synchronized List<RawLayerRead> takeRawLayerReads() {
        if (closed || rawLayerReads.isEmpty()) {
            return List.of();
        }
        List<RawLayerRead> copy = List.copyOf(rawLayerReads);
        rawLayerReads.clear();
        return copy;
    }

    synchronized void setLayersPending(boolean pending) {
        this.layersPending = !closed && pending;
        if (!this.layersPending) {
            notifyAll();
        }
    }

    public synchronized boolean isLayersPending() {
        return !closed && layersPending;
    }

    /** Waits up to {@code timeoutMs} for pending HUD layer extraction to finish. */
    public synchronized boolean awaitLayers(long timeoutMs) {
        if (!layersPending || closed) {
            return !closed && !layersPending;
        }
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        while (layersPending && !closed) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !closed && !layersPending;
    }

    synchronized void setEntityMaskPending() {
        entityMaskPending = !closed;
    }

    /** Ends the wait for the mask. Null means there is none for this shot: no entities, or it failed. */
    synchronized void setEntityMask(@Nullable EntityMask mask) {
        entityMaskPending = false;
        entityMask = closed ? null : mask;
        notifyAll();
    }

    /** Waits up to {@code timeoutMs} for the mask, which arrives a frame or two after the shot. Null if there is none. */
    @Nullable
    public synchronized EntityMask awaitEntityMask(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        while (entityMaskPending && !closed) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return closed ? null : entityMask;
    }

    synchronized void setLayerStacks(@Nullable LayerStack fullStack, @Nullable LayerStack previewStack) {
        if (closed) {
            return;
        }
        this.layerStack = fullStack;
        this.previewLayerStack = previewStack;
        this.layersPending = false;
        if (fullStack != null) {
            pictures.setDimensions(fullStack.width(), fullStack.height());
            pictures.setAvailableGroups(Set.copyOf(fullStack.groups()));
        } else {
            pictures.setAvailableGroups(Set.of());
        }
        if (savedStage != null) {
            pictures.markSaved(savedStage, initialHiddenGroups, List.of(), null, FilterSettings.NONE);
        }
        notifyAll();
    }

    @Nullable
    public synchronized LayerStack layerStack() {
        return closed ? null : layerStack;
    }

    @Nullable
    public synchronized LayerStack previewLayerStack() {
        return closed ? null : previewLayerStack;
    }

    public synchronized List<String> groups() {
        return !closed && layerStack != null ? layerStack.groups() : List.of();
    }

    synchronized void setInitialHiddenGroups(Set<String> hiddenGroups) {
        this.initialHiddenGroups = hiddenGroups != null ? Set.copyOf(hiddenGroups) : Set.of();
    }

    public synchronized Set<String> initialHiddenGroups() {
        return initialHiddenGroups;
    }

    public synchronized boolean has(CaptureStage stage) {
        return !closed && stages.containsKey(stage);
    }

    /** Only valid while the caller holds a lease. */
    public synchronized NativeImage get(CaptureStage stage) {
        if (closed) {
            throw new IllegalStateException("Capture already closed");
        }
        NativeImage image = stages.get(stage);
        if (image == null) {
            throw new IllegalArgumentException("No " + stage + " stage");
        }
        return image;
    }

    public File file() {
        return file;
    }

    /** The shot to name a shader retake after: the first shot, also when this one is a retake itself. */
    public File retakeBase() {
        File base = retakeOf;
        return base != null ? base : file;
    }

    void setRetakeOf(@Nullable File first) {
        retakeOf = first;
    }

    void setChatMarker(@Nullable GuiMessage newest) {
        chatMarker = newest;
        chatMarkerKnown = true;
    }

    /** False if the chat wasn't looked at. Otherwise {@link #chatMarker()} is the newest line, null for an empty chat. */
    public boolean chatMarkerKnown() {
        return chatMarkerKnown;
    }

    @Nullable
    public GuiMessage chatMarker() {
        return chatMarker;
    }

    public boolean screenWasOpen() {
        return screenWasOpen;
    }

    @Nullable
    public synchronized CaptureStage savedStage() {
        return savedStage;
    }

    synchronized void setSavedStage(CaptureStage stage) {
        setSavedPicture(stage, Set.of());
    }

    synchronized void setSavedPicture(CaptureStage stage, Set<String> hiddenGroups) {
        savedStage = stage;
        pictures.markSaved(stage, hiddenGroups, List.of(), null, FilterSettings.NONE);
    }

    /** True if a file already holds this view and crop, either the original or a copy. */
    public synchronized boolean isSaved(CaptureStage view, @Nullable CropRect crop) {
        return isSaved(view, Set.of(), List.of(), crop, FilterSettings.NONE);
    }

    public synchronized boolean isSaved(
            CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
        return isSaved(view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
    }

    public synchronized boolean isSaved(
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            @Nullable FilterSettings filter) {
        return pictures.isSaved(view, hiddenGroups, eraseBoxes, crop, filter);
    }

    synchronized void markSaved(CaptureStage view, @Nullable CropRect crop) {
        markSaved(view, Set.of(), List.of(), crop, FilterSettings.NONE);
    }

    synchronized void markSaved(
            CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
        markSaved(view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
    }

    synchronized void markSaved(
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            @Nullable FilterSettings filter) {
        pictures.markSaved(view, hiddenGroups, eraseBoxes, crop, filter);
    }

    /** True if this working picture has already been added to the gallery from this capture. */
    public synchronized boolean isInGallery(
            CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
        return isInGallery(view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
    }

    public synchronized boolean isInGallery(
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            @Nullable FilterSettings filter) {
        return pictures.isInGallery(view, hiddenGroups, eraseBoxes, crop, filter);
    }

    public synchronized void markInGallery(
            CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
        markInGallery(view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
    }

    public synchronized void markInGallery(
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            @Nullable FilterSettings filter) {
        pictures.markInGallery(view, hiddenGroups, eraseBoxes, crop, filter);
    }

    public synchronized void unmarkInGallery(
            CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, @Nullable CropRect crop) {
        unmarkInGallery(view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
    }

    public synchronized void unmarkInGallery(
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            @Nullable FilterSettings filter) {
        pictures.unmarkInGallery(view, hiddenGroups, eraseBoxes, crop, filter);
    }

    /** Returns false if the capture is already gone. */
    public synchronized boolean acquire() {
        if (closed) {
            return false;
        }
        leases++;
        return true;
    }

    public synchronized void release() {
        if (closed) {
            return;
        }
        if (--leases <= 0) {
            closed = true;
            layersPending = false;
            layerStack = null;
            previewLayerStack = null;
            rawLayerReads.forEach(RawLayerRead::closeImage);
            rawLayerReads.clear();
            stages.values().forEach(NativeImage::close);
            stages.clear();
            entityMaskPending = false;
            entityMask = null;
            notifyAll();
        }
    }

    public synchronized boolean isClosed() {
        return closed;
    }
}
