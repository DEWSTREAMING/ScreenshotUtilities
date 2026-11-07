package com.screenshotutilities.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.screenshotutilities.client.ModKeys;
import com.screenshotutilities.clipboard.ScreenshotClipboard;
import com.screenshotutilities.config.ScreenshotConfig;
import com.screenshotutilities.image.Downscale;
import com.screenshotutilities.image.LayerStack;
import com.screenshotutilities.screen.GalleryScreen;
import com.screenshotutilities.screen.ScreenshotPopupScreen;
import com.screenshotutilities.screen.ShaderRetakeScreen;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ScreenshotEvent;
import net.neoforged.neoforge.client.gui.GuiLayerManager;
import org.slf4j.Logger;

/**
 * Takes over the player's F2 and captures the next frame in stages. Everything here runs on the render thread except the
 * background diff extraction.
 *
 * <p>Flow: the vanilla grab fires {@link ScreenshotEvent}, which is cancelled and armed. In the next frame the World stage
 * is read at {@link RenderGuiEvent.Pre}, HUD layer groups at {@link RenderGuiLayerEvent.Post} and
 * {@link RenderGuiEvent.Post}, and the Final stage at {@link RenderFrameEvent.Post}, then the shot is saved and held for
 * the popup. The image vanilla handed over is kept until then and saved instead if anything goes wrong.</p>
 */
public final class CaptureController {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Result message for the cancelled vanilla grab. The wrapped chat consumer drops this exact instance, so chat shows
     * neither "Screenshot canceled" nor an empty line.
     */
    public static final Component HANDLED = Component.literal("");

    /** A frame that hasn't arrived by then never will (window not rendering, or the frame died half way). */
    private static final long PENDING_TIMEOUT_MS = 3000;

    private static final SystemToast.SystemToastId PROMPT_TOAST = new SystemToast.SystemToastId();

    private static boolean playerKeyPress;
    @Nullable
    private static Pending pending;
    @Nullable
    private static Capture building;
    @Nullable
    private static Capture held;
    private static boolean hideGuiChanged;

    private static List<String> observedLayerOrder = HudGroups.VANILLA_LAYER_ORDER;
    private static Map<String, String> groupByLayer = HudGroups.assignGroups(observedLayerOrder);
    private static boolean triedReflectionDiscovery;
    private static boolean layerOrderKnown;
    private static final List<String> frameLayerIds = new ArrayList<>();
    private static boolean observingFrameLayers;

    private static final Set<String> endOfRunLayers = new HashSet<>();
    @Nullable
    private static String dirtyLayerGroup;
    private static long frameStartedNano;
    private static int frameReadCount;
    private static long frameReadMicros;

    private CaptureController() {
    }

    /** Hears how a retake went, on the render thread. */
    public interface RetakeListener {
        /** The shot is saved and held like an F2 shot. Take a lease to keep it. */
        void captured(Capture capture);

        void failed();
    }

    private static final class Pending {
        /** What vanilla grabbed at the key press. A retake has none, nobody pressed F2. */
        @Nullable
        final NativeImage fallback;
        final File file;
        final CapturePlan plan;
        final long armedAt;
        @Nullable
        final RetakeListener retake;
        @Nullable
        final File retakeOf;

        Pending(@Nullable NativeImage fallback, File file, CapturePlan plan, long armedAt,
                @Nullable RetakeListener retake, @Nullable File retakeOf) {
            this.fallback = fallback;
            this.file = file;
            this.plan = plan;
            this.armedAt = armedAt;
            this.retake = retake;
            this.retakeOf = retakeOf;
        }
    }

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOW, CaptureController::onScreenshot);
        bus.addListener(EventPriority.HIGHEST, CaptureController::onFramePre);
        bus.addListener(CaptureController::onLevelStage);
        bus.addListener(EventPriority.LOWEST, CaptureController::onRenderHand);
        bus.addListener(EventPriority.HIGHEST, CaptureController::onGuiPre);
        bus.addListener(EventPriority.HIGHEST, CaptureController::onLayerPre);
        bus.addListener(EventPriority.LOWEST, CaptureController::onLayerPost);
        bus.addListener(EventPriority.HIGHEST, CaptureController::onGuiPostHigh);
        bus.addListener(EventPriority.LOWEST, CaptureController::onGuiPostLow);
        bus.addListener(EventPriority.LOWEST, CaptureController::onFramePost);
        bus.addListener(CaptureController::onClientTick);
        bus.addListener(CaptureController::onLoggingOut);
        bus.addListener(CaptureController::onScreenOpening);
    }

    /** Called by the mixin around the vanilla F2 grab, so grabs from other mods' code are left alone. */
    public static void setPlayerKeyPress(boolean value) {
        playerKeyPress = value;
    }

    // Low priority so mods that cancel or redirect screenshots get their say first.
    private static void onScreenshot(ScreenshotEvent event) {
        if (!playerKeyPress) {
            return;
        }
        NativeImage image = event.getImage();
        Screen activeScreen = Minecraft.getInstance().screen;
        if (pending != null || activeScreen instanceof ScreenshotPopupScreen || activeScreen instanceof GalleryScreen
                || activeScreen instanceof ShaderRetakeScreen) {
            // Second press before the frame arrived, or F2 while the popup or gallery is open.
            event.setCanceled(true);
            event.setResultMessage(HANDLED);
            image.close();
            return;
        }
        try {
            CapturePlan plan = CapturePlan.of(
                    ScreenshotConfig.SHOW_POPUP.get(),
                    ScreenshotConfig.HIDE_HUD.get(),
                    ScreenshotConfig.HIDE_HAND.get(),
                    !ScreenshotConfig.alwaysHideSet().isEmpty());
            pending = new Pending(image, event.getScreenshotFile(), plan, Util.getMillis(), null, null);
            event.setCanceled(true);
            event.setResultMessage(HANDLED);
        } catch (Throwable t) {
            // Leave the event alone and vanilla saves the shot as usual.
            LOGGER.error("Couldn't take over the screenshot, leaving it to vanilla", t);
            pending = null;
            event.setCanceled(false);
            event.setResultMessage(null);
        }
    }

    private static void onFramePre(RenderFrameEvent.Pre event) {
        restoreHideGui();
        try {
            MobMaskPass.poll();
        } catch (Throwable t) {
            LOGGER.warn("Couldn't collect the mob mask", t);
        }
        if (pending == null || building != null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            frameStartedNano = System.nanoTime();
            frameReadCount = 0;
            frameReadMicros = 0;
            dirtyLayerGroup = null;
            endOfRunLayers.clear();
            // The retake screen draws nothing over the world, so it doesn't count as a screen in the shot.
            building = new Capture(pending.file, pending.retake == null && mc.screen != null);
            building.setRetakeOf(pending.retakeOf);
            if (pending.plan.hideHand() && !mc.options.hideGui) {
                // Restored at RenderGuiEvent.Pre, before any HUD layer checks it, so only the hand (and block outline) go.
                mc.options.hideGui = true;
                hideGuiChanged = true;
            }
        } catch (Throwable t) {
            fail("Capture setup failed", t);
            return;
        }
        try {
            MobMaskPass.prepare(!pending.plan.readLayers());
        } catch (Throwable t) {
            LOGGER.warn("Couldn't set up the mob mask", t);
            discardMobMask();
        }
    }

    private static void onLevelStage(RenderLevelStageEvent event) {
        if (building == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        try {
            MobMaskPass.onLevelStage(event);
        } catch (Throwable t) {
            LOGGER.warn("Couldn't keep the camera and depth for the mob mask", t);
        }
    }

    // Lowest and not for cancelled events, so only a hand the game really draws is noted.
    private static void onRenderHand(RenderHandEvent event) {
        if (building == null) {
            return;
        }
        try {
            MobMaskPass.onHandDrawn(event);
        } catch (Throwable t) {
            LOGGER.warn("Couldn't note the hand for the mob mask", t);
        }
    }

    private static void discardMobMask() {
        try {
            MobMaskPass.discard();
        } catch (Throwable t) {
            LOGGER.warn("Couldn't free the mob mask target", t);
        }
    }

    private static void onGuiPre(RenderGuiEvent.Pre event) {
        restoreHideGui();
        Minecraft mc = Minecraft.getInstance();
        if (!triedReflectionDiscovery && mc.gui != null) {
            triedReflectionDiscovery = true;
            discoverRegisteredLayers(mc.gui);
        }
        if (!layerOrderKnown) {
            frameLayerIds.clear();
            observingFrameLayers = true;
        }

        if (building == null || pending == null || !pending.plan.wants(CaptureStage.WORLD)) {
            return;
        }
        try {
            event.getGuiGraphics().flush();
            read(CaptureStage.WORLD);
            if (pending.plan.readLayers()) {
                planLayerRuns(mc);
            }
        } catch (Throwable t) {
            // The Final stage is enough for a shot, so carry on without World.
            LOGGER.warn("World stage read failed", t);
        }
    }

    private static void onLayerPre(RenderGuiLayerEvent.Pre event) {
        if (observingFrameLayers) {
            frameLayerIds.add(event.getName().toString());
        }
        if (building == null || pending == null || !pending.plan.readLayers() || dirtyLayerGroup == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String layerId = event.getName().toString();
        if (!isLayerActive(mc, layerId)) {
            return;
        }
        String nextGroup = groupForLayer(layerId);
        if (!dirtyLayerGroup.equals(nextGroup)) {
            String toRead = dirtyLayerGroup;
            dirtyLayerGroup = null;
            flushAndReadGroup(event.getGuiGraphics(), toRead);
        }
    }

    private static void onLayerPost(RenderGuiLayerEvent.Post event) {
        if (building == null || pending == null || !pending.plan.readLayers() || !building.has(CaptureStage.WORLD)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String layerId = event.getName().toString();
        if (!isLayerActive(mc, layerId)) {
            return;
        }
        String group = groupForLayer(layerId);
        dirtyLayerGroup = group;
        if (endOfRunLayers.contains(layerId)) {
            dirtyLayerGroup = null;
            flushAndReadGroup(event.getGuiGraphics(), group);
        }
    }

    private static void onGuiPostHigh(RenderGuiEvent.Post event) {
        if (building == null || pending == null || !pending.plan.readLayers() || dirtyLayerGroup == null) {
            return;
        }
        String toRead = dirtyLayerGroup;
        dirtyLayerGroup = null;
        flushAndReadGroup(event.getGuiGraphics(), toRead);
    }

    private static void onGuiPostLow(RenderGuiEvent.Post event) {
        if (observingFrameLayers) {
            observingFrameLayers = false;
            if (!frameLayerIds.isEmpty() && !frameLayerIds.equals(observedLayerOrder)) {
                observedLayerOrder = List.copyOf(frameLayerIds);
                groupByLayer = HudGroups.assignGroups(observedLayerOrder);
            }
        }
        if (building != null) {
            try {
                event.getGuiGraphics().flush();
                MobMaskPass.onGuiDrawn();
            } catch (Throwable t) {
                LOGGER.warn("Couldn't keep the HUD depth for the mob mask", t);
            }
        }
        if (building == null || pending == null || !pending.plan.readLayers() || !building.has(CaptureStage.WORLD)) {
            return;
        }
        dirtyLayerGroup = null;
        flushAndReadGroup(event.getGuiGraphics(), HudGroups.MOD_OVERLAYS);
    }

    private static void onFramePost(RenderFrameEvent.Post event) {
        try {
            observingFrameLayers = false;
            dirtyLayerGroup = null;
            restoreHideGui();
            if (building != null) {
                read(CaptureStage.FINAL);
                try {
                    building.setChatMarker(RetakeChat.newest());
                } catch (Throwable t) {
                    LOGGER.debug("Couldn't note the newest chat line", t);
                }
                long frameMicros = (System.nanoTime() - frameStartedNano) / 1000;
                LOGGER.info("Capture frame total: {} reads in {}.{} ms (frame {}.{} ms)",
                        frameReadCount,
                        frameReadMicros / 1000, (frameReadMicros % 1000) / 100,
                        frameMicros / 1000, (frameMicros % 1000) / 100);
                Capture capture = building;
                finish();
                // Last, so the shot is already on its way whatever happens in here.
                try {
                    MobMaskPass.run(capture);
                } catch (Throwable t) {
                    LOGGER.warn("Mob mask failed, the shot is not affected", t);
                    discardMobMask();
                }
            }
        } catch (Throwable t) {
            fail("Final stage read failed", t);
        }
    }

    /**
     * Captures the next frame the same way as F2, into {@code file}, and tells {@code listener} instead of opening the
     * popup. False if a capture is already on its way.
     */
    public static boolean armRetake(File file, File retakeOf, RetakeListener listener) {
        if (pending != null || building != null) {
            return false;
        }
        CapturePlan plan = CapturePlan.of(
                true,
                ScreenshotConfig.HIDE_HUD.get(),
                ScreenshotConfig.HIDE_HAND.get(),
                !ScreenshotConfig.alwaysHideSet().isEmpty());
        pending = new Pending(null, file, plan, Util.getMillis(), listener, retakeOf);
        return true;
    }

    /** Drops a retake that hasn't reached its frame yet. False if there was none waiting. */
    public static boolean cancelRetake() {
        if (pending == null || pending.retake == null || building != null) {
            return false;
        }
        pending = null;
        return true;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        // Ticks run between frames, so a capture still being built here means its frame died.
        restoreHideGui();
        if (building != null) {
            fail("Frame ended without finishing the capture", null);
        } else if (pending != null && Util.getMillis() - pending.armedAt > PENDING_TIMEOUT_MS) {
            fail("No frame arrived after F2", null);
        }
        while (ModKeys.OPEN_LAST.consumeClick()) {
            openLast();
        }
        while (ModKeys.OPEN_GALLERY.consumeClick()) {
            openGallery();
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        restoreHideGui();
        if (pending != null) {
            fail("Left the world before the capture finished", null);
        }
        setHeld(null);
        ScreenshotPopupScreen.releaseParked();
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        ScreenshotPopupScreen.onScreenOpening(event.getNewScreen());
    }

    private static void discoverRegisteredLayers(Gui gui) {
        try {
            Field lmField = Gui.class.getDeclaredField("layerManager");
            lmField.setAccessible(true);
            Object lm = lmField.get(gui);
            Field layersField = GuiLayerManager.class.getDeclaredField("layers");
            layersField.setAccessible(true);
            List<?> namedLayers = (List<?>) layersField.get(lm);
            if (namedLayers != null && !namedLayers.isEmpty()) {
                List<String> ids = new ArrayList<>(namedLayers.size());
                for (Object entry : namedLayers) {
                    if (entry instanceof GuiLayerManager.NamedLayer nl) {
                        ids.add(nl.name().toString());
                    }
                }
                if (!ids.isEmpty()) {
                    observedLayerOrder = List.copyOf(ids);
                    groupByLayer = HudGroups.assignGroups(observedLayerOrder);
                    layerOrderKnown = true;
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Using runtime observation for GUI layer order", t);
        }
    }

    private static String groupForLayer(String layerId) {
        String mapped = groupByLayer.get(layerId);
        if (mapped != null) {
            return mapped;
        }
        String vanilla = HudGroups.vanillaGroup(layerId);
        return vanilla != null ? vanilla : layerId;
    }

    private static void planLayerRuns(Minecraft mc) {
        endOfRunLayers.clear();
        List<String> active = new ArrayList<>(observedLayerOrder.size());
        for (String id : observedLayerOrder) {
            if (isLayerActive(mc, id)) {
                active.add(id);
            }
        }
        for (int i = 0; i < active.size(); i++) {
            String curGroup = groupForLayer(active.get(i));
            boolean isLastOfRun = i + 1 == active.size() || !curGroup.equals(groupForLayer(active.get(i + 1)));
            if (isLastOfRun) {
                endOfRunLayers.add(active.get(i));
            }
        }
    }

    /**
     * Skips reads for split vanilla layers whose draw condition in {@code Gui} is false on this frame, and for F1 hidden
     * vanilla layers.
     */
    private static boolean isLayerActive(Minecraft mc, String layerId) {
        if (layerId.endsWith("_offset")) {
            return false;
        }
        if (layerId.startsWith("minecraft:")) {
            if (mc.options.hideGui && !layerId.equals("minecraft:sleep_overlay")) {
                return false;
            }
            return switch (layerId) {
                case "minecraft:jump_meter" -> mc.player != null && mc.player.jumpableVehicle() != null;
                case "minecraft:experience_bar" -> mc.player != null && mc.player.jumpableVehicle() == null
                        && mc.gameMode != null && mc.gameMode.hasExperience();
                case "minecraft:sleep_overlay" -> mc.player != null && mc.player.getSleepTimer() > 0;
                case "minecraft:demo_overlay" -> mc.isDemo();
                case "minecraft:debug_overlay" -> mc.getDebugOverlay().showDebugScreen();
                default -> true;
            };
        }
        return true;
    }

    private static void read(CaptureStage stage) {
        Minecraft mc = Minecraft.getInstance();
        long start = System.nanoTime();
        NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        long micros = (System.nanoTime() - start) / 1000;
        building.put(stage, image);
        frameReadCount++;
        frameReadMicros += micros;
        LOGGER.debug("{} stage read {}x{} in {}.{} ms", stage, image.getWidth(), image.getHeight(), micros / 1000, (micros % 1000) / 100);
    }

    private static void flushAndReadGroup(GuiGraphics graphics, String groupId) {
        if (building == null) {
            return;
        }
        try {
            graphics.flush();
            Minecraft mc = Minecraft.getInstance();
            long start = System.nanoTime();
            NativeImage image = readUnflipped(mc.getMainRenderTarget());
            long micros = (System.nanoTime() - start) / 1000;
            building.addLayerRead(groupId, image, true);
            frameReadCount++;
            frameReadMicros += micros;
            LOGGER.debug("Group {} read {}x{} in {}.{} ms",
                    groupId, image.getWidth(), image.getHeight(), micros / 1000, (micros % 1000) / 100);
        } catch (Throwable t) {
            LOGGER.warn("Group {} read failed", groupId, t);
        }
    }

    private static NativeImage readUnflipped(RenderTarget framebuffer) {
        NativeImage image = new NativeImage(framebuffer.width, framebuffer.height, false);
        try {
            RenderSystem.bindTexture(framebuffer.getColorTextureId());
            image.downloadTexture(0, false);
            return image;
        } catch (Throwable t) {
            image.close();
            throw t;
        }
    }

    private static void finish() {
        Pending p = pending;
        Capture capture = building;
        pending = null;
        building = null;
        if (p.fallback != null) {
            p.fallback.close();
        }
        setHeld(capture);

        try {
            if (capture.hasLayerReads()) {
                startLayerExtraction(capture);
            }
        } catch (Throwable t) {
            // The fallback image is gone by now, so this must not stop the save below.
            LOGGER.warn("Couldn't start HUD layer extraction", t);
        }

        CaptureStage stage;
        Set<String> alwaysHide;
        try {
            stage = CapturePlan.stageToSave(
                    ScreenshotConfig.HIDE_HUD.get(), capture.has(CaptureStage.WORLD), capture.screenWasOpen());
            alwaysHide = stage == CaptureStage.FINAL && capture.has(CaptureStage.WORLD)
                    ? ScreenshotConfig.alwaysHideSet()
                    : Set.of();
            capture.setInitialHiddenGroups(alwaysHide);
            CaptureSaver.saveOriginal(capture, stage, alwaysHide, ScreenshotConfig.ALWAYS_ADD_TO_GALLERY.get());
        } catch (Throwable t) {
            LOGGER.error("Couldn't queue the screenshot save", t);
            CaptureSaver.chat(Component.translatable("screenshotutilities.message.save_failed"));
            return;
        }
        if (ScreenshotConfig.ALWAYS_COPY.get()) {
            try {
                ScreenshotClipboard.copy(capture, stage, alwaysHide, List.of(), null);
            } catch (Throwable t) {
                LOGGER.error("Couldn't queue the clipboard copy", t);
            }
        }
        if (p.retake != null) {
            try {
                p.retake.captured(capture);
            } catch (Throwable t) {
                LOGGER.error("Couldn't hand the retake back", t);
            }
            return;
        }
        try {
            afterSave(capture);
        } catch (Throwable t) {
            LOGGER.error("Couldn't open the screenshot popup", t);
        }
    }

    private static void startLayerExtraction(Capture capture) {
        List<Capture.RawLayerRead> rawReads = capture.takeRawLayerReads();
        if (rawReads.isEmpty() || !capture.has(CaptureStage.WORLD) || !capture.has(CaptureStage.FINAL)) {
            rawReads.forEach(Capture.RawLayerRead::closeImage);
            capture.setLayersPending(false);
            return;
        }
        if (!capture.acquire()) {
            rawReads.forEach(Capture.RawLayerRead::closeImage);
            capture.setLayersPending(false);
            return;
        }
        capture.setLayersPending(true);
        try {
            Util.ioPool().execute(() -> extractLayersOnWorker(capture, rawReads));
        } catch (Throwable t) {
            rawReads.forEach(Capture.RawLayerRead::closeImage);
            capture.setLayersPending(false);
            capture.release();
            LOGGER.warn("Couldn't queue HUD layer extraction", t);
        }
    }

    private static void extractLayersOnWorker(Capture capture, List<Capture.RawLayerRead> rawReads) {
        long start = System.nanoTime();
        try {
            NativeImage worldImg = capture.get(CaptureStage.WORLD);
            NativeImage finalImg = capture.get(CaptureStage.FINAL);
            int width = worldImg.getWidth();
            int height = worldImg.getHeight();
            int[] worldPixels = worldImg.getPixelsRGBA();
            int[] prevPixels = worldPixels.clone();
            int[] currPixels = new int[width * height];
            List<LayerStack.Part> parts = new ArrayList<>(rawReads.size() + 1);

            for (Capture.RawLayerRead raw : rawReads) {
                NativeImage img = raw.image();
                if (img == null) {
                    continue;
                }
                try {
                    if (img.getWidth() == width && img.getHeight() == height) {
                        readIntoBuffer(img, raw.unflipped(), currPixels, width, height);
                        LayerStack.Part part = LayerStack.extractPart(raw.groupId(), prevPixels, currPixels, width, height);
                        if (part != null) {
                            parts.add(part);
                        }
                        int[] tmp = prevPixels;
                        prevPixels = currPixels;
                        currPixels = tmp;
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Couldn't diff HUD group {}", raw.groupId(), t);
                } finally {
                    raw.closeImage();
                }
            }

            if (finalImg.getWidth() == width && finalImg.getHeight() == height) {
                readIntoBuffer(finalImg, false, currPixels, width, height);
                LayerStack.Part menusPart = LayerStack.extractPart(
                        HudGroups.MENUS_TOASTS, prevPixels, currPixels, width, height);
                if (menusPart != null) {
                    parts.add(menusPart);
                }
            }

            LayerStack fullStack = new LayerStack(width, height, worldPixels, parts);
            int[] previewSize = Downscale.fitWithin(
                    width, height, ScreenshotPopupScreen.PREVIEW_MAX_WIDTH, ScreenshotPopupScreen.PREVIEW_MAX_HEIGHT);
            LayerStack previewStack = fullStack.downscale(previewSize[0], previewSize[1]);
            capture.setLayerStacks(fullStack, previewStack);
            LOGGER.debug("Extracted {} HUD groups ({} parts) in {} ms",
                    fullStack.groups().size(), parts.size(), (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            LOGGER.warn("HUD layer extraction failed", t);
            capture.setLayerStacks(null, null);
        } finally {
            rawReads.forEach(Capture.RawLayerRead::closeImage);
            capture.setLayersPending(false);
            capture.release();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen instanceof ScreenshotPopupScreen popup) {
                    popup.onLayersReady(capture);
                }
            });
        }
    }

    private static void readIntoBuffer(NativeImage img, boolean unflipped, int[] dst, int width, int height) {
        if (!unflipped) {
            for (int y = 0; y < height; y++) {
                int dstRow = y * width;
                for (int x = 0; x < width; x++) {
                    dst[dstRow + x] = img.getPixelRGBA(x, y);
                }
            }
            return;
        }
        for (int y = 0; y < height; y++) {
            int srcY = height - 1 - y;
            int dstRow = y * width;
            for (int x = 0; x < width; x++) {
                dst[dstRow + x] = img.getPixelRGBA(x, srcY) | 0xFF000000;
            }
        }
    }

    /** Falls back to the image vanilla grabbed at the key press, so the player still gets a normal screenshot. */
    private static void fail(String reason, @Nullable Throwable t) {
        if (pending != null && pending.retake != null) {
            LOGGER.error("{}, the retake is dropped", reason, t);
        } else {
            LOGGER.error("{}, saving the plain screenshot instead", reason, t);
        }
        discardMobMask();
        observingFrameLayers = false;
        dirtyLayerGroup = null;
        endOfRunLayers.clear();
        Pending p = pending;
        Capture partial = building;
        pending = null;
        building = null;
        if (partial != null) {
            partial.release();
        }
        if (p == null) {
            return;
        }
        if (p.retake != null || p.fallback == null) {
            // Nothing to fall back on for a retake. The earlier shot is still saved.
            if (p.retake != null) {
                try {
                    p.retake.failed();
                } catch (Throwable t2) {
                    LOGGER.error("Couldn't report the failed retake", t2);
                }
            }
            return;
        }
        Capture fallback = null;
        try {
            fallback = new Capture(p.file, false);
            fallback.put(CaptureStage.FINAL, p.fallback);
        } catch (Throwable t2) {
            LOGGER.error("Couldn't wrap the fallback screenshot", t2);
            fallback = null;
        }
        boolean addToGallery = ScreenshotConfig.ALWAYS_ADD_TO_GALLERY.get();
        try {
            if (fallback != null) {
                setHeld(fallback);
                CaptureSaver.saveOriginal(fallback, CaptureStage.FINAL, Set.of(), addToGallery);
            } else {
                CaptureSaver.saveImage(p.fallback, p.file, addToGallery);
            }
        } catch (Throwable t2) {
            LOGGER.error("Couldn't save the fallback screenshot", t2);
            CaptureSaver.chat(Component.translatable("screenshotutilities.message.save_failed"));
        }
        if (fallback != null && ScreenshotConfig.ALWAYS_COPY.get()) {
            try {
                ScreenshotClipboard.copy(fallback, CaptureStage.FINAL, null);
            } catch (Throwable t2) {
                LOGGER.error("Couldn't queue the fallback clipboard copy", t2);
            }
        }
    }

    private static void setHeld(@Nullable Capture capture) {
        Capture old = held;
        held = capture;
        if (old != null && old != capture) {
            old.release();
        }
    }

    private static void afterSave(Capture capture) {
        Minecraft mc = Minecraft.getInstance();
        boolean privateSinglePlayer = mc.hasSingleplayerServer() && !mc.getSingleplayerServer().isPublished();
        switch (CapturePlan.afterSave(ScreenshotConfig.SHOW_POPUP.get(), mc.level != null, privateSinglePlayer)) {
            case POPUP -> mc.tell(() -> {
                if (held == capture) {
                    openPopup(capture);
                }
            });
            case PROMPT -> showPrompt(mc);
            case NOTHING -> {
            }
        }
    }

    private static void showPrompt(Minecraft mc) {
        Component message = ModKeys.OPEN_LAST.isUnbound()
                ? Component.translatable("screenshotutilities.prompt.unbound")
                : Component.translatable("screenshotutilities.prompt.open", ModKeys.OPEN_LAST.getTranslatedKeyMessage());
        SystemToast.addOrUpdate(mc.getToasts(), PROMPT_TOAST, Component.translatable("screenshotutilities.prompt.title"), message);
    }

    /** Takes the "Screenshot saved" corner prompt away, so a shader retake on a server doesn't show it. */
    public static void hidePrompt() {
        SystemToast.forceHide(Minecraft.getInstance().getToasts(), PROMPT_TOAST);
    }

    public static void openLast() {
        Capture capture = held;
        if (capture == null || capture.isClosed()) {
            Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("screenshotutilities.message.nothing_held"), false);
            return;
        }
        openPopup(capture);
    }

    public static void openGallery() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof GalleryScreen) {
            return;
        }
        mc.setScreen(new GalleryScreen(mc.screen));
    }

    private static void openPopup(Capture capture) {
        if (!capture.acquire()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;
        Screen parent = current instanceof ScreenshotPopupScreen popup ? popup.parent() : current;
        mc.setScreen(new ScreenshotPopupScreen(capture, parent));
    }

    /** Ends Hide hand's change to {@code hideGui}. Safe to call any time. */
    private static void restoreHideGui() {
        if (hideGuiChanged) {
            hideGuiChanged = false;
            Minecraft.getInstance().options.hideGui = false;
        }
    }
}
