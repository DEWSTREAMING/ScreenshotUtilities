package com.screenshotutilities.clipboard;

import com.mojang.logging.LogUtils;
import com.screenshotutilities.capture.Capture;
import com.screenshotutilities.capture.CaptureSaver;
import com.screenshotutilities.capture.CaptureStage;
import com.screenshotutilities.image.CropRect;
import com.screenshotutilities.image.DibEncoder;
import com.screenshotutilities.image.FilterSettings;
import com.screenshotutilities.screen.ScreenshotPopupScreen;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Copies a capture stage (with optional hidden HUD groups, erase boxes, crop, and filter) to the system clipboard on a
 * worker thread and shows a short result on the overlay line.
 */
public final class ScreenshotClipboard {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ScreenshotClipboard() {
    }

    public static boolean isSupported() {
        return WindowsClipboard.isSupported();
    }

    public static void copy(Capture capture, CaptureStage view, @Nullable CropRect crop) {
        copy(capture, view, Set.of(), List.of(), crop, FilterSettings.NONE);
    }

    public static void copy(
            Capture capture,
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop) {
        copy(capture, view, hiddenGroups, eraseBoxes, crop, FilterSettings.NONE);
    }

    public static void copy(
            Capture capture,
            CaptureStage view,
            Set<String> hiddenGroups,
            List<CropRect> eraseBoxes,
            @Nullable CropRect crop,
            @Nullable FilterSettings filter) {
        if (!isSupported()) {
            return;
        }
        if (!capture.acquire()) {
            reportResult(false);
            return;
        }
        FilterSettings safeFilter = filter != null ? filter : FilterSettings.NONE;
        try {
            Util.ioPool().execute(() -> {
                try {
                    long start = System.nanoTime();
                    if (!hiddenGroups.isEmpty() && view == CaptureStage.FINAL) {
                        capture.awaitLayers(CaptureSaver.REBUILD_TIMEOUT_MS);
                    }
                    CaptureSaver.RebuiltPicture pic = CaptureSaver.buildPicture(
                            capture, view, hiddenGroups, eraseBoxes, crop, safeFilter);
                    int outWidth = pic.width();
                    int outHeight = pic.height();
                    byte[] dib;
                    if (pic.directStage() != null) {
                        dib = DibEncoder.encode(pic.directStage()::getPixelRGBA, outWidth, outHeight);
                    } else {
                        int[] pixels = pic.pixels();
                        dib = DibEncoder.encode((x, y) -> pixels[y * outWidth + x], outWidth, outHeight);
                    }
                    boolean ok = WindowsClipboard.setDib(dib);
                    LOGGER.debug("Clipboard copy {}x{} ({}) in {} ms",
                            outWidth, outHeight, ok ? "ok" : "failed", (System.nanoTime() - start) / 1_000_000);
                    reportResult(ok);
                } catch (Throwable t) {
                    LOGGER.warn("Couldn't copy screenshot to clipboard", t);
                    reportResult(false);
                } finally {
                    capture.release();
                }
            });
        } catch (RuntimeException e) {
            capture.release();
            reportResult(false);
        }
    }

    private static void reportResult(boolean success) {
        Component message = Component.translatable(
                success ? "screenshotutilities.message.copied" : "screenshotutilities.message.copy_failed");
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            mc.gui.setOverlayMessage(message, false);
            if (mc.screen instanceof ScreenshotPopupScreen popup) {
                popup.showOverlayMessage(message);
            }
        });
    }
}
