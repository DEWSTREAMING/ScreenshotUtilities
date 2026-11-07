package com.screenshotutilities.capture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.screenshotutilities.gallery.GalleryStore;
import com.screenshotutilities.image.CropRect;
import com.screenshotutilities.image.FileNames;
import com.screenshotutilities.image.FilterSettings;
import com.screenshotutilities.image.LayerStack;
import com.screenshotutilities.image.WorkingPicture;
import com.screenshotutilities.screen.ScreenshotPopupScreen;
import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Writes captures to disk on the IO pool and reports in chat the way vanilla does.
 */
public final class CaptureSaver {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Maximum wait for HUD layer extraction before falling back to the unedited FINAL stage. */
    public static final long REBUILD_TIMEOUT_MS = 2000;

    /**
     * Either a direct unedited stage {@link NativeImage} (owned by {@link Capture}) or a rebuilt/cropped {@code int[]}
     * pixel buffer ready to be written or encoded.
     */
    public record RebuiltPicture(@Nullable NativeImage directStage, @Nullable int[] pixels, int width, int height) {
    }

    private CaptureSaver() {
    }

    /** Saves one stage of the capture as its original file. */
    static void saveOriginal(Capture capture, CaptureStage stage) {
        saveOriginal(capture, stage, Set.of(), false);
    }

    /**
     * Saves the capture as its original file with {@code hiddenGroups} removed when layer data is present. If rebuilding the
     * picture fails or times out, falls back to {@link CaptureStage#FINAL} and writes the original file only once.
     */
    static void saveOriginal(Capture capture, CaptureStage stage, Set<String> hiddenGroups) {
        saveOriginal(capture, stage, hiddenGroups, false);
    }

    /**
     * Saves the capture as its original file and optionally copies that finished file into the gallery folder afterwards.
     */
    static void saveOriginal(Capture capture, CaptureStage stage, Set<String> hiddenGroups, boolean copyToGallery) {
        Set<String> safeHidden = hiddenGroups != null ? Set.copyOf(hiddenGroups) : Set.of();
        capture.setSavedPicture(stage, safeHidden);
        if (copyToGallery) {
            capture.markInGallery(stage, safeHidden, List.of(), null, FilterSettings.NONE);
        }
        if (safeHidden.isEmpty() || stage != CaptureStage.FINAL) {
            write(capture, stage, Set.of(), List.of(), null, FilterSettings.NONE, capture.file(), copyToGallery);
            return;
        }
        writeOriginalWithAlwaysHide(capture, safeHidden, capture.file(), copyToGallery);
    }

    /**
     * Saves {@code view} (with an optional {@code crop}) as a new file next to the original, named
     * {@code <original>_edit.png} or the next free name.
     */
    public static void saveCopy(Capture capture, CaptureStage view, @Nullable CropRect crop) {
        saveCopy(capture, view, Set.of(), List.of(), crop, FilterSettings.NONE);
    }

    /**
     * Saves {@code view} (with {@code hiddenGroups}, {@code eraseBoxes}, and optional {@code crop}) as a new file next to
     * the original, named {@code <original>_edit.png} or the next free name. The picture counts as saved straight away so
     * the button can't queue the same copy twice.
     */
    public static void saveCopy(
            Capture capture,
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop) {
        saveCopy(capture, view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
    }

    /**
     * Saves {@code view} (with {@code hiddenGroups}, {@code eraseBoxes}, optional {@code crop}, and {@code filter}) as a
     * new file next to the original, named {@code <original>_edit.png} or the next free name.
     */
    public static void saveCopy(
            Capture capture,
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            @Nullable FilterSettings filter) {
        File original = capture.file();
        File dir = original.getParentFile();
        String name = FileNames.editCopy(original.getName(), n -> new File(dir, n).exists());
        FilterSettings safeFilter = filter != null ? filter : FilterSettings.NONE;
        capture.markSaved(view, hiddenGroups, eraseBoxes, crop, safeFilter);
        write(capture, view, hiddenGroups, eraseBoxes, crop, safeFilter, new File(dir, name), false);
    }

    /**
     * Builds the working picture on a worker thread under a {@link Capture} lease and writes it to the gallery folder
     * under a free name. Marks the picture as added immediately so the popup button cannot queue the same picture twice.
     */
    public static void addToGallery(
            Capture capture,
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop) {
        addToGallery(capture, view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
    }

    /**
     * Builds the working picture (including filter) on a worker thread under a {@link Capture} lease and writes it to
     * the gallery folder under a free name.
     */
    public static void addToGallery(
            Capture capture,
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            @Nullable FilterSettings filter) {
        Set<String> safeHidden = hiddenGroups != null ? Set.copyOf(hiddenGroups) : Set.of();
        List<CropRect> safeErase = eraseBoxes != null ? List.copyOf(eraseBoxes) : List.of();
        FilterSettings safeFilter = filter != null ? filter : FilterSettings.NONE;
        capture.markInGallery(view, safeHidden, safeErase, crop, safeFilter);
        if (!capture.acquire()) {
            capture.unmarkInGallery(view, safeHidden, safeErase, crop, safeFilter);
            overlay(Component.translatable("screenshotutilities.message.gallery_add_failed"), true);
            return;
        }
        try {
            Util.ioPool().execute(() -> {
                NativeImage temp = null;
                try {
                    if (!safeHidden.isEmpty() && view == CaptureStage.FINAL) {
                        capture.awaitLayers(REBUILD_TIMEOUT_MS);
                    }
                    RebuiltPicture pic = buildPicture(capture, view, safeHidden, safeErase, crop, safeFilter);
                    NativeImage out = pic.directStage() != null
                            ? pic.directStage()
                            : (temp = imageFromPixels(pic.pixels(), pic.width(), pic.height()));
                    File original = capture.file();
                    File screenshotsDir = original.getParentFile();
                    GalleryStore store = screenshotsDir != null
                            ? GalleryStore.fromScreenshotsDir(screenshotsDir.toPath())
                            : GalleryStore.fromGameDir(Minecraft.getInstance().gameDirectory.toPath());
                    store.add(original.getName(), target -> out.writeToFile(target.toFile()));
                    overlay(Component.translatable("screenshotutilities.message.gallery_added"), false);
                } catch (Throwable t) {
                    LOGGER.warn("Couldn't add screenshot to gallery", t);
                    capture.unmarkInGallery(view, safeHidden, safeErase, crop, safeFilter);
                    overlay(Component.translatable("screenshotutilities.message.gallery_add_failed"), true);
                } finally {
                    if (temp != null) {
                        temp.close();
                    }
                    capture.release();
                }
            });
        } catch (RuntimeException e) {
            capture.unmarkInGallery(view, safeHidden, safeErase, crop, safeFilter);
            capture.release();
            overlay(Component.translatable("screenshotutilities.message.gallery_add_failed"), true);
        }
    }

    /** Saves a lone image (the fallback) and closes it afterwards. */
    static void saveImage(NativeImage image, File target, boolean copyToGallery) {
        Util.ioPool().execute(() -> {
            try {
                File file = freeFile(target);
                image.writeToFile(file);
                chat(success(file));
                if (copyToGallery) {
                    copyFileToGallery(null, CaptureStage.FINAL, Set.of(), file);
                }
            } catch (Exception e) {
                LOGGER.warn("Couldn't save screenshot", e);
                chat(Component.translatable("screenshot.failure", e.getMessage()));
            } finally {
                image.close();
            }
        });
    }

    /**
     * Builds the full-size working picture on a worker thread. The caller must hold a {@link Capture} lease for the
     * duration of the call and while using {@link RebuiltPicture#directStage()}.
     */
    public static RebuiltPicture buildPicture(
            Capture capture,
            CaptureStage view,
            @Nullable Set<String> hiddenGroups,
            @Nullable List<CropRect> eraseBoxes,
            @Nullable CropRect crop) {
        return buildPicture(capture, view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
    }

    /**
     * Builds the full-size working picture on a worker thread: hidden groups, erase boxes, filter, then crop. With a filter
     * on, a newly allocated pixel array is always returned and the capture stage image is never modified.
     */
    public static RebuiltPicture buildPicture(
            Capture capture,
            CaptureStage view,
            @Nullable Set<String> hiddenGroups,
            @Nullable List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            @Nullable FilterSettings filter) {
        CaptureStage effectiveStage = view;
        LayerStack stack = capture.layerStack();
        Set<String> safeHidden = hiddenGroups != null ? hiddenGroups : Set.of();
        List<CropRect> safeErase = eraseBoxes != null ? eraseBoxes : List.of();
        FilterSettings effectiveFilter = filter != null ? filter : FilterSettings.NONE;

        boolean hasHidden = effectiveStage == CaptureStage.FINAL
                && !safeHidden.isEmpty()
                && stack != null
                && safeHidden.stream().anyMatch(stack.groups()::contains);
        if (hasHidden && capture.has(CaptureStage.WORLD) && stack.groups().stream().allMatch(safeHidden::contains)) {
            effectiveStage = CaptureStage.WORLD;
            hasHidden = false;
        }
        boolean hasErase = effectiveStage == CaptureStage.FINAL
                && !safeErase.isEmpty()
                && capture.has(CaptureStage.WORLD);

        if (hasHidden || hasErase) {
            int width;
            int height;
            int[] fullPixels;
            if (stack != null) {
                width = stack.width();
                height = stack.height();
                fullPixels = stack.rebuild(safeHidden, safeErase);
            } else {
                NativeImage finalImg = capture.get(CaptureStage.FINAL);
                NativeImage worldImg = capture.get(CaptureStage.WORLD);
                width = finalImg.getWidth();
                height = finalImg.getHeight();
                CropRect full = new CropRect(0, 0, width, height);
                fullPixels = full.extract(finalImg::getPixelRGBA);
                int[] worldPixels = full.extract(worldImg::getPixelRGBA);
                LayerStack.applyEraseBoxes(fullPixels, worldPixels, width, height, safeErase);
            }
            return finishPicture(fullPixels, width, height, effectiveFilter, crop);
        }

        NativeImage source = capture.get(effectiveStage);
        int width = source.getWidth();
        int height = source.getHeight();
        if (effectiveFilter.isActive()) {
            // Read into a new buffer, so the stage image itself never changes.
            int[] fullPixels = new CropRect(0, 0, width, height).extract(source::getPixelRGBA);
            return finishPicture(fullPixels, width, height, effectiveFilter, crop);
        }
        if (crop != null && !crop.isFullImage(width, height)) {
            CropRect clamped = crop.clamp(width, height);
            return new RebuiltPicture(null, clamped.extract(source::getPixelRGBA), clamped.width(), clamped.height());
        }
        return new RebuiltPicture(source, null, width, height);
    }

    private static RebuiltPicture finishPicture(
            int[] fullPixels, int width, int height, FilterSettings filter, @Nullable CropRect crop) {
        WorkingPicture.Pixels done = WorkingPicture.filterThenCrop(fullPixels, width, height, filter, crop);
        return new RebuiltPicture(null, done.pixels(), done.width(), done.height());
    }

    /**
     * Builds a new {@link NativeImage} from row-major packed ABGR pixels. The caller must close the returned image.
     */
    public static NativeImage imageFromPixels(int[] pixels, int width, int height) {
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                for (int x = 0; x < width; x++) {
                    out.setPixelRGBA(x, y, pixels[rowOffset + x]);
                }
            }
            return out;
        } catch (Throwable t) {
            out.close();
            throw t;
        }
    }

    private static void writeOriginalWithAlwaysHide(
            Capture capture, Set<String> hiddenGroups, File target, boolean copyToGallery) {
        if (!capture.acquire()) {
            if (copyToGallery) {
                capture.unmarkInGallery(CaptureStage.FINAL, hiddenGroups, List.of(), null, FilterSettings.NONE);
            }
            chat(Component.translatable("screenshotutilities.message.save_failed"));
            return;
        }
        AtomicBoolean written = new AtomicBoolean(false);
        try {
            Util.ioPool().execute(() -> {
                NativeImage temp = null;
                try {
                    boolean ready = capture.awaitLayers(REBUILD_TIMEOUT_MS);
                    if (!ready || capture.layerStack() == null) {
                        LOGGER.warn("HUD layer extraction {} for Always hide; saving FINAL stage instead",
                                !ready ? "timed out" : "failed");
                        writeFallbackFinalOnce(capture, hiddenGroups, target, written, copyToGallery);
                        return;
                    }
                    RebuiltPicture pic;
                    try {
                        pic = buildPicture(capture, CaptureStage.FINAL, hiddenGroups, List.of(), null, FilterSettings.NONE);
                    } catch (Throwable t) {
                        LOGGER.warn("Couldn't rebuild screenshot for Always hide, saving FINAL stage instead", t);
                        writeFallbackFinalOnce(capture, hiddenGroups, target, written, copyToGallery);
                        return;
                    }
                    if (pic.directStage() == null) {
                        temp = imageFromPixels(pic.pixels(), pic.width(), pic.height());
                    }
                    // Claimed last, so a failure while building still reaches the FINAL fallback.
                    if (!written.compareAndSet(false, true)) {
                        return;
                    }
                    File file = freeFile(target);
                    (temp != null ? temp : pic.directStage()).writeToFile(file);
                    chat(success(file));
                    if (copyToGallery) {
                        copyFileToGallery(capture, CaptureStage.FINAL, hiddenGroups, file);
                    }
                } catch (Exception e) {
                    if (written.get()) {
                        LOGGER.warn("Couldn't save screenshot", e);
                        chat(Component.translatable("screenshot.failure", e.getMessage()));
                    } else {
                        LOGGER.warn("Couldn't build rebuilt screenshot, trying FINAL stage fallback", e);
                        writeFallbackFinalOnce(capture, hiddenGroups, target, written, copyToGallery);
                    }
                } finally {
                    if (temp != null) {
                        temp.close();
                    }
                    capture.release();
                }
            });
        } catch (RuntimeException e) {
            capture.release();
            throw e;
        }

        // Watchdog in case the worker thread itself is delayed beyond the timeout.
        if (capture.acquire()) {
            try {
                CompletableFuture.delayedExecutor(REBUILD_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS, Util.ioPool())
                        .execute(() -> {
                            try {
                                if (!written.get()) {
                                    LOGGER.warn("Always hide rebuild exceeded watchdog timeout; saving FINAL stage instead");
                                    writeFallbackFinalOnce(capture, hiddenGroups, target, written, copyToGallery);
                                }
                            } finally {
                                capture.release();
                            }
                        });
            } catch (Throwable t) {
                capture.release();
            }
        }
    }

    private static void writeFallbackFinalOnce(
            Capture capture, Set<String> originalHidden, File target, AtomicBoolean written, boolean copyToGallery) {
        if (!written.compareAndSet(false, true)) {
            return;
        }
        try {
            capture.setSavedStage(CaptureStage.FINAL);
            if (copyToGallery) {
                capture.unmarkInGallery(CaptureStage.FINAL, originalHidden, List.of(), null, FilterSettings.NONE);
                capture.markInGallery(CaptureStage.FINAL, Set.of(), List.of(), null, FilterSettings.NONE);
            }
            File file = freeFile(target);
            capture.get(CaptureStage.FINAL).writeToFile(file);
            chat(success(file));
            if (copyToGallery) {
                copyFileToGallery(capture, CaptureStage.FINAL, Set.of(), file);
            }
        } catch (Exception e) {
            if (copyToGallery) {
                capture.unmarkInGallery(CaptureStage.FINAL, Set.of(), List.of(), null, FilterSettings.NONE);
            }
            LOGGER.warn("Couldn't save fallback FINAL screenshot", e);
            chat(Component.translatable("screenshot.failure", e.getMessage()));
        }
    }

    private static void write(
            Capture capture,
            CaptureStage stage,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            FilterSettings filter,
            File target,
            boolean copyToGallery) {
        if (!capture.acquire()) {
            if (copyToGallery) {
                capture.unmarkInGallery(stage, hiddenGroups, eraseBoxes, crop, filter);
            }
            chat(Component.translatable("screenshotutilities.message.save_failed"));
            return;
        }
        try {
            Util.ioPool().execute(() -> {
                NativeImage temp = null;
                try {
                    if (!hiddenGroups.isEmpty() && stage == CaptureStage.FINAL) {
                        capture.awaitLayers(REBUILD_TIMEOUT_MS);
                    }
                    File file = freeFile(target);
                    RebuiltPicture pic = buildPicture(capture, stage, hiddenGroups, eraseBoxes, crop, filter);
                    if (pic.directStage() != null) {
                        pic.directStage().writeToFile(file);
                    } else {
                        temp = imageFromPixels(pic.pixels(), pic.width(), pic.height());
                        temp.writeToFile(file);
                    }
                    chat(success(file));
                    if (copyToGallery) {
                        copyFileToGallery(capture, stage, hiddenGroups, file);
                    }
                } catch (Exception e) {
                    if (copyToGallery) {
                        capture.unmarkInGallery(stage, hiddenGroups, eraseBoxes, crop, filter);
                    }
                    LOGGER.warn("Couldn't save screenshot", e);
                    chat(Component.translatable("screenshot.failure", e.getMessage()));
                } finally {
                    if (temp != null) {
                        temp.close();
                    }
                    capture.release();
                }
            });
        } catch (RuntimeException e) {
            if (copyToGallery) {
                capture.unmarkInGallery(stage, hiddenGroups, eraseBoxes, crop, filter);
            }
            capture.release();
            throw e;
        }
    }

    private static void copyFileToGallery(
            @Nullable Capture capture, CaptureStage stage, Set<String> hiddenGroups, File savedFile) {
        try {
            File screenshotsDir = savedFile.getParentFile();
            GalleryStore store = screenshotsDir != null
                    ? GalleryStore.fromScreenshotsDir(screenshotsDir.toPath())
                    : GalleryStore.fromGameDir(Minecraft.getInstance().gameDirectory.toPath());
            store.copyInto(savedFile.toPath());
        } catch (Throwable t) {
            LOGGER.warn("Couldn't copy screenshot to gallery", t);
            if (capture != null) {
                capture.unmarkInGallery(stage, hiddenGroups, List.of(), null, FilterSettings.NONE);
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    if (mc.screen instanceof ScreenshotPopupScreen popup) {
                        popup.refreshButtons();
                    }
                });
            }
        }
    }

    /** A free file next to {@code original}, named after it with {@code suffix} added. */
    static File nextTo(File original, String suffix) {
        String name = original.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : ".png";
        return freeFile(new File(original.getParentFile(), stem + suffix + extension));
    }

    /** The planned name was picked a frame earlier, so another shot may have taken it since. */
    private static File freeFile(File target) {
        File dir = target.getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
        return new File(dir, FileNames.unique(target.getName(), n -> new File(dir, n).exists()));
    }

    private static Component success(File file) {
        Component link = Component.literal(file.getName())
                .withStyle(ChatFormatting.UNDERLINE)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath())));
        return Component.translatable("screenshot.success", link);
    }

    static void chat(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.getChat().addMessage(message));
    }

    static void overlay(Component message, boolean refreshPopup) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            mc.gui.setOverlayMessage(message, false);
            if (mc.screen instanceof ScreenshotPopupScreen popup) {
                popup.showOverlayMessage(message);
                if (refreshPopup) {
                    popup.refreshButtons();
                }
            }
        });
    }
}
