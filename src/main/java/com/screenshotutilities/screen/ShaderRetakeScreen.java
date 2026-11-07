package com.screenshotutilities.screen;

import com.mojang.logging.LogUtils;
import com.screenshotutilities.capture.Capture;
import com.screenshotutilities.capture.CaptureController;
import com.screenshotutilities.capture.RetakeChat;
import com.screenshotutilities.compat.IrisShaders;
import com.screenshotutilities.compat.ShaderRestore;
import com.screenshotutilities.compat.ShaderSetup;
import com.screenshotutilities.image.CarriedEdits;
import com.screenshotutilities.image.FileNames;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Takes over from the popup for a retake with another shader pack. It pauses the game and draws nothing over the world
 * except a short line while Iris reloads, which is hidden for the frames that are captured.
 *
 * <p>Steps, all on screen ticks so a reload never runs in the middle of a frame: the line is drawn, the restore file is
 * written, Iris switches, the picture settles, the shot is taken the same way as F2, the line is drawn again, Iris switches
 * back, and the popup opens on the new shot. A cancel or failure switches back and returns to the earlier popup.</p>
 */
public final class ShaderRetakeScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * After a reload the world's chunks are built again, and packs with temporal effects or auto exposure need frames to
     * catch up. So once the chunks are built, wait this long and this many frames.
     */
    static final long SETTLE_MS = 1000;
    static final int SETTLE_FRAMES = 30;
    /** Chunks that never finish building (a huge view distance on a slow PC) don't hold the retake up for ever. */
    static final long CHUNK_WAIT_MAX_MS = 10000;

    private enum Step {
        /** Showing the line, waiting for it to be on screen. */
        LINE,
        /** Writing the restore file on a worker. */
        SAVING,
        /** Switched, waiting for the picture to settle. */
        SETTLE,
        /** The capture is armed. */
        CAPTURE,
        /** Showing the line again before switching back. */
        LINE_BACK
    }

    private final ScreenshotPopupScreen popup;
    @Nullable
    private final Screen popupParent;
    /** The shot shown in the popup. The parked popup keeps its lease, this only reads its name and chat marker. */
    private final Capture source;
    private final File original;
    private final ShaderSetup before;
    private final ShaderSetup target;
    /** The popup's edits and the picture size they were made on, handed to the popup on the new shot. */
    private final CarriedEdits edits;
    @Nullable
    private final int[] sourceSize;

    private Step step = Step.LINE;
    private boolean lineShown;
    @Nullable
    private CompletableFuture<File> saving;
    private boolean switched;
    @Nullable
    private File retakeFile;
    private long settleStartMs;
    private int settleFrames;
    /** When the chunks were last seen all built, or -1 while they are building. */
    private long chunksDoneMs = -1;
    private int chunksDoneFrames;
    private boolean cancelRequested;
    @Nullable
    private Capture result;
    @Nullable
    private Component failure;
    private boolean finished;
    @Nullable
    private RetakeChat.Hidden hiddenChat;

    public ShaderRetakeScreen(
            ScreenshotPopupScreen popup, @Nullable Screen popupParent, Capture source, ShaderSetup before, ShaderSetup target) {
        super(Component.translatable("screenshotutilities.retake.title"));
        this.popup = popup;
        this.popupParent = popupParent;
        this.source = source;
        this.original = source.retakeBase();
        this.edits = popup.carriedEdits();
        this.sourceSize = ScreenshotPopupScreen.pictureSize(source);
        this.before = before;
        this.target = target;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Nothing over the world, it's what is being captured.
    }

    private boolean showsLine() {
        return step == Step.LINE || step == Step.SAVING || step == Step.LINE_BACK;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (showsLine()) {
            Component line = Component.translatable("screenshotutilities.retake.switching");
            int w = font.width(line);
            int x = (width - w) / 2;
            int y = height / 2 - font.lineHeight / 2;
            graphics.fill(x - 6, y - 5, x + w + 6, y + font.lineHeight + 4, 0xC0000000);
            graphics.drawString(font, line, x, y, 0xFFFFFF, false);
            lineShown = true;
        } else if (step == Step.SETTLE) {
            settleFrames++;
            // The first frames after a reload haven't queued the rebuild yet, so an empty queue then means nothing.
            boolean built = settleFrames > 2 && minecraft.levelRenderer.hasRenderedAllSections();
            if (!built) {
                chunksDoneMs = -1;
                chunksDoneFrames = 0;
            } else {
                if (chunksDoneMs < 0) {
                    chunksDoneMs = Util.getMillis();
                }
                chunksDoneFrames++;
            }
        }
    }

    @Override
    public void tick() {
        if (finished) {
            return;
        }
        switch (step) {
            case LINE -> {
                if (cancelRequested) {
                    leave(null);
                } else if (lineShown) {
                    startSaving();
                }
            }
            case SAVING -> afterSaving();
            case SETTLE -> {
                if (cancelRequested) {
                    backToLine();
                } else if (settled()) {
                    arm();
                }
            }
            case CAPTURE -> {
                if (cancelRequested && CaptureController.cancelRetake()) {
                    backToLine();
                }
            }
            case LINE_BACK -> {
                if (lineShown) {
                    leave(result);
                }
            }
        }
    }

    private void startSaving() {
        step = Step.SAVING;
        File dir = original.getParentFile();
        String name = original.getName();
        String pack = target.activePack();
        saving = ShaderRestore.writeLater(before).thenApply(ignored -> new File(dir,
                FileNames.retakeCopy(name, pack, n -> new File(dir, n).exists())));
    }

    private void afterSaving() {
        CompletableFuture<File> future = saving;
        if (future == null || !future.isDone()) {
            return;
        }
        File file;
        try {
            file = future.join();
        } catch (Throwable t) {
            LOGGER.error("Couldn't write the shader restore file, so the retake didn't start", t);
            ShaderRestore.deleteLater();
            failure = Component.translatable("screenshotutilities.message.retake_failed");
            leave(null);
            return;
        }
        if (cancelRequested) {
            ShaderRestore.deleteLater();
            leave(null);
            return;
        }
        long start = System.nanoTime();
        try {
            switched = true;
            IrisShaders.apply(target);
            if (target.activePack() != null && !IrisShaders.packRunning()) {
                LOGGER.warn("Shader pack {} didn't load for the retake", target.activePack());
                failure = Component.translatable("screenshotutilities.message.retake_pack_failed");
                backToLine();
                return;
            }
        } catch (Throwable t) {
            LOGGER.error("Couldn't switch Iris to {} for the retake", target.activePack(), t);
            failure = Component.translatable("screenshotutilities.message.retake_failed");
            backToLine();
            return;
        }
        LOGGER.info("Switched shaders to {} for a retake in {} ms",
                describe(target), (System.nanoTime() - start) / 1_000_000);
        retakeFile = file;
        try {
            CaptureController.hidePrompt();
        } catch (Throwable t) {
            LOGGER.debug("Couldn't hide the screenshot prompt", t);
        }
        try {
            hiddenChat = RetakeChat.hideNewerThan(source);
        } catch (Throwable t) {
            LOGGER.warn("Couldn't keep new chat lines out of the retake", t);
        }
        step = Step.SETTLE;
        settleStartMs = Util.getMillis();
        settleFrames = 0;
        chunksDoneMs = -1;
        chunksDoneFrames = 0;
    }

    private boolean settled() {
        long now = Util.getMillis();
        if (chunksDoneMs >= 0) {
            return chunksDoneFrames >= SETTLE_FRAMES && now - chunksDoneMs >= SETTLE_MS;
        }
        return now - settleStartMs >= CHUNK_WAIT_MAX_MS;
    }

    private void arm() {
        CaptureController.RetakeListener listener = new CaptureController.RetakeListener() {
            @Override
            public void captured(Capture capture) {
                onCaptured(capture);
            }

            @Override
            public void failed() {
                onCaptureFailed();
            }
        };
        boolean armed = retakeFile != null && CaptureController.armRetake(retakeFile, original, listener);
        if (!armed) {
            LOGGER.warn("Couldn't start the retake capture, another capture is on its way");
            failure = Component.translatable("screenshotutilities.message.retake_failed");
            backToLine();
            return;
        }
        LOGGER.info("Retake settled for {} ms over {} frames, chunks built {} ms in", Util.getMillis() - settleStartMs,
                settleFrames, chunksDoneMs >= 0 ? chunksDoneMs - settleStartMs : -1);
        step = Step.CAPTURE;
    }

    private void onCaptured(Capture capture) {
        if (finished || step != Step.CAPTURE) {
            return;
        }
        if (capture.acquire()) {
            result = capture;
        } else {
            failure = Component.translatable("screenshotutilities.message.retake_failed");
        }
        backToLine();
    }

    private void onCaptureFailed() {
        if (finished || step != Step.CAPTURE) {
            return;
        }
        failure = Component.translatable("screenshotutilities.message.retake_failed");
        backToLine();
    }

    private void backToLine() {
        showChat();
        step = Step.LINE_BACK;
        lineShown = false;
    }

    /** Switches back if needed, then opens the popup on {@code shot}, or the earlier popup when there is none. */
    private void leave(@Nullable Capture shot) {
        finished = true;
        showChat();
        boolean restored = switchBack();
        if (!restored) {
            failure = Component.translatable("screenshotutilities.message.shaders_restore_failed");
        }
        if (shot != null) {
            result = null;
            ScreenshotPopupScreen next = new ScreenshotPopupScreen(shot, popupParent);
            carryEditsTo(next, shot);
            if (failure != null) {
                next.showOverlayMessage(failure);
            }
            minecraft.setScreen(next);
            return;
        }
        if (failure != null) {
            popup.showOverlayMessage(failure);
        }
        minecraft.setScreen(popup);
    }

    /** False only if Iris couldn't be put back, in which case the restore file stays for the next start. */
    private boolean switchBack() {
        if (!switched) {
            return true;
        }
        switched = false;
        long start = System.nanoTime();
        try {
            IrisShaders.apply(before);
            LOGGER.info("Switched shaders back to {} in {} ms", describe(before), (System.nanoTime() - start) / 1_000_000);
            ShaderRestore.deleteLater();
            return true;
        } catch (Throwable t) {
            LOGGER.error("Couldn't switch Iris back to {} after the retake", describe(before), t);
            return false;
        }
    }

    private void carryEditsTo(ScreenshotPopupScreen next, Capture shot) {
        int[] newSize = ScreenshotPopupScreen.pictureSize(shot);
        if (sourceSize == null || newSize == null) {
            return;
        }
        try {
            next.carryEdits(edits.mappedTo(sourceSize[0], sourceSize[1], newSize[0], newSize[1]));
        } catch (Throwable t) {
            LOGGER.warn("Couldn't carry the edits over to the retake", t);
        }
    }

    private void showChat() {
        RetakeChat.Hidden hidden = hiddenChat;
        hiddenChat = null;
        try {
            RetakeChat.putBack(hidden);
        } catch (Throwable t) {
            LOGGER.warn("Couldn't put chat lines back after the retake", t);
        }
    }

    private static String describe(ShaderSetup setup) {
        return setup.shadersOn() ? "pack " + setup.packName() : "no shaders (pack " + setup.packName() + ")";
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (step != Step.LINE_BACK) {
                cancelRequested = true;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        cancelRequested = true;
    }

    /** Anything else replacing this screen (leaving the world, another mod) still gets the player's shaders back. */
    @Override
    public void removed() {
        if (finished) {
            return;
        }
        finished = true;
        LOGGER.warn("Shader retake screen closed half way, switching back");
        CaptureController.cancelRetake();
        showChat();
        if (result != null) {
            result.release();
            result = null;
        }
        if (step == Step.SAVING && !switched) {
            ShaderRestore.deleteLater();
        }
        switchBack();
    }
}
