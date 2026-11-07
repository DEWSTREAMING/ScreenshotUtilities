package com.screenshotutilities.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.screenshotutilities.image.EntityMask;
import com.screenshotutilities.mixin.ItemInHandRendererInvoker;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;

/**
 * Works out which entity each pixel of a shot is. On a capture frame only, each entity is drawn a second time, unseen, into a
 * target of our own in a flat colour that is its id. The read back goes into a pixel buffer and is collected on a later
 * frame, so the F2 frame doesn't wait for it. The result is handed to the {@link Capture} as an {@link EntityMask}.
 *
 * <p>The depth is copied mid-frame because it doesn't last: translucent blocks write to it next, and {@code GameRenderer}
 * clears it before the hand and before the GUI. The drawing waits until the frame is over, when Iris has stopped swapping
 * shaders.</p>
 */
final class MobMaskPass {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Lets a mob pass against its own surface in the copied depth, where another shader may round differently. */
    private static final float DEPTH_BIAS_FACTOR = -1f;
    private static final float DEPTH_BIAS_UNITS = -4f;
    private static final int DEPTH_SAMPLES_X = 8;
    private static final int DEPTH_SAMPLES_Y = 5;
    private static final int START_BUFFER_BYTES = 1 << 20;
    /** A read the GPU hasn't finished by then is given up, so its buffer can't be held forever. */
    private static final int MAX_POLLS = 600;

    @Nullable
    private static RenderTarget target;
    /** The GUI's depth, kept for shots that have no HUD layer reads to say where the HUD is. */
    @Nullable
    private static RenderTarget hudTarget;
    private static boolean hudCopied;
    private static long prepareMicros;
    private static long hudMicros;
    @Nullable
    private static View view;
    private static final Map<InteractionHand, HandDraw> HANDS = new EnumMap<>(InteractionHand.class);
    private static final List<Readback> READBACKS = new ArrayList<>();

    private MobMaskPass() {
    }

    /** What the world was drawn with on this frame. */
    private record View(
            Matrix4f modelView,
            Matrix4f projection,
            Vec3 cameraPos,
            Camera camera,
            Frustum frustum,
            DeltaTracker delta,
            boolean depthCopied,
            long depthMicros) {
    }

    /** How the game drew one hand this frame, so it can be drawn again over the mobs. */
    private record HandDraw(
            Matrix4f pose,
            Matrix3f normal,
            Matrix4f projection,
            Matrix4f modelView,
            float partialTick,
            float pitch,
            float swingProgress,
            float equipProgress,
            ItemStack item,
            int light) {
    }

    /** A read on its way from the GPU. The buffer holds the colour first, then the depth samples. */
    private static final class Readback {
        final Capture capture;
        final int buffer;
        final long fence;
        final int width;
        final int height;
        final List<String> names;
        final boolean hudMeasured;
        final long onFrameMicros;
        final int handsDrawn;
        int polls;

        Readback(Capture capture, int buffer, long fence, int width, int height, List<String> names, boolean hudMeasured,
                long onFrameMicros, int handsDrawn) {
            this.capture = capture;
            this.buffer = buffer;
            this.fence = fence;
            this.width = width;
            this.height = height;
            this.names = names;
            this.hudMeasured = hudMeasured;
            this.onFrameMicros = onFrameMicros;
            this.handsDrawn = handsDrawn;
        }

        long colourBytes() {
            return (long) width * height * 4;
        }

        void free() {
            GL32.glDeleteSync(fence);
            GL15.glDeleteBuffers(buffer);
        }
    }

    /**
     * Called at the start of a capture frame, before anything is drawn.
     *
     * @param measureHud whether the shot gets no HUD layer reads, so the mask has to say where the HUD is
     */
    static void prepare(boolean measureHud) {
        discard();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long start = System.nanoTime();
        RenderTarget main = mc.getMainRenderTarget();
        target = newTarget(main);
        if (measureHud) {
            hudTarget = newTarget(main);
        }
        main.bindWrite(true);
        prepareMicros = (System.nanoTime() - start) / 1000;
    }

    private static RenderTarget newTarget(RenderTarget main) {
        RenderTarget made = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        try {
            if (main.isStencilEnabled()) {
                made.enableStencil();
            }
            made.setClearColor(0f, 0f, 0f, 0f);
            made.clear(Minecraft.ON_OSX);
            return made;
        } catch (Throwable t) {
            made.destroyBuffers();
            throw t;
        }
    }

    /**
     * Called when the HUD is drawn. {@code GameRenderer} clears depth before the GUI and again after it, so this is the one
     * moment the depth shows exactly where the HUD drew. Overlays such as the vignette don't write depth, so they don't count.
     */
    static void onGuiDrawn() {
        RenderTarget hud = hudTarget;
        if (hud == null || hudCopied || target == null) {
            return;
        }
        long start = System.nanoTime();
        int readBinding = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int drawBinding = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            drainErrors();
            hud.copyDepthFrom(Minecraft.getInstance().getMainRenderTarget());
            int error = GlStateManager._getError();
            hudCopied = error == GL11.GL_NO_ERROR;
            if (!hudCopied) {
                LOGGER.warn("Mob mask: HUD depth copy gave GL error {}", error);
            }
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBinding);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
        }
        hudMicros = (System.nanoTime() - start) / 1000;
    }

    /** Notes how a hand was drawn. Iris draws hands more than once a frame, the last one counts. */
    static void onHandDrawn(RenderHandEvent event) {
        if (target == null) {
            return;
        }
        PoseStack.Pose last = event.getPoseStack().last();
        HANDS.put(event.getHand(), new HandDraw(
                new Matrix4f(last.pose()),
                new Matrix3f(last.normal()),
                new Matrix4f(RenderSystem.getProjectionMatrix()),
                new Matrix4f(RenderSystem.getModelViewMatrix()),
                event.getPartialTick(),
                event.getInterpolatedPitch(),
                event.getSwingProgress(),
                event.getEquipProgress(),
                event.getItemStack().copy(),
                event.getPackedLight()));
    }

    /** Fires inside level rendering, so whatever target is bound (Fabulous or Iris own one) has to be put back. */
    static void onLevelStage(RenderLevelStageEvent event) {
        RenderTarget made = target;
        if (made == null || view != null) {
            return;
        }
        long start = System.nanoTime();
        int readBinding = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int drawBinding = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean copied = false;
        try {
            drainErrors();
            made.copyDepthFrom(Minecraft.getInstance().getMainRenderTarget());
            int error = GlStateManager._getError();
            copied = error == GL11.GL_NO_ERROR;
            if (!copied) {
                LOGGER.warn("Mob mask: depth copy gave GL error {}", error);
            }
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBinding);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
        }
        view = new View(
                new Matrix4f(event.getModelViewMatrix()),
                new Matrix4f(event.getProjectionMatrix()),
                event.getCamera().getPosition(),
                event.getCamera(),
                event.getFrustum(),
                event.getPartialTick(),
                copied,
                (System.nanoTime() - start) / 1000);
    }

    /**
     * Draws and starts the read. Runs after the shot is safely queued. Nothing here may reach the caller, so a failure costs
     * the mask and never the screenshot.
     */
    static void run(Capture capture) {
        RenderTarget made = target;
        View seen = view;
        if (made == null || seen == null) {
            discard();
            return;
        }
        if (!seen.depthCopied()) {
            LOGGER.debug("Mob mask skipped, the depth copy failed");
            discard();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        List<Entity> entities = entitiesToDraw(mc, seen);
        if (entities.isEmpty()) {
            LOGGER.debug("Mob mask skipped, no entities in view");
            discard();
            return;
        }

        int readBinding = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int drawBinding = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int packBinding = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        float[] shaderColor = RenderSystem.getShaderColor().clone();
        var shader = RenderSystem.getShader();
        RenderSystem.backupProjectionMatrix();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        ByteBuffer colourMask = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colourMask);

        long start = System.nanoTime();
        List<String> names = new ArrayList<>();
        int buffer = 0;
        long fence = 0;
        boolean started = false;
        boolean hudMeasured = false;
        int handsDrawn = 0;
        try {
            made.bindWrite(true);
            buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            long colourBytes = (long) made.width * made.height * 4;
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, colourBytes + DEPTH_SAMPLES_X * DEPTH_SAMPLES_Y * 4L, GL15.GL_STREAM_READ);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, made.frameBufferId);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            // Read before anything is drawn, so they show the copied world depth.
            readDepthSamples(made, colourBytes);

            RenderSystem.setProjectionMatrix(seen.projection(), VertexSorting.DISTANCE_TO_ORIGIN);
            modelView.identity();
            modelView.mul(seen.modelView());
            RenderSystem.applyModelViewMatrix();
            drawEntities(mc, seen, made, entities, names);
            handsDrawn = drawHands(mc, made, modelView);
            RenderTarget hud = hudTarget;
            if (hud != null && (hudCopied || mc.options.hideGui)) {
                // With F1 on nothing was drawn and the HUD copy may not have happened, so no pixel is HUD.
                if (hudCopied) {
                    markHud(made, hud, modelView);
                }
                // Toasts and an open screen come after the depth clear that follows the HUD, so they are still in
                // the main depth now.
                markHud(made, mc.getMainRenderTarget(), modelView);
                hudMeasured = true;
            }

            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, made.frameBufferId);
            GL11.glReadPixels(0, 0, made.width, made.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (fence == 0) {
                throw new IllegalStateException("No fence");
            }
            long onFrame = prepareMicros + seen.depthMicros() + hudMicros + (System.nanoTime() - start) / 1000;
            capture.setEntityMaskPending();
            READBACKS.add(new Readback(
                    capture, buffer, fence, made.width, made.height, List.copyOf(names), hudMeasured, onFrame, handsDrawn));
            started = true;
        } catch (Throwable t) {
            LOGGER.warn("Mob mask failed, the screenshot is not affected", t);
        } finally {
            if (!started) {
                if (fence != 0) {
                    GL32.glDeleteSync(fence);
                }
                if (buffer != 0) {
                    GL15.glDeleteBuffers(buffer);
                }
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBinding);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            RenderSystem.setShader(() -> shader);
            if (depthTest) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            if (blend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            if (cull) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
            RenderSystem.depthFunc(depthFunc);
            RenderSystem.depthMask(depthMask);
            RenderSystem.colorMask(colourMask.get(0) != 0, colourMask.get(1) != 0, colourMask.get(2) != 0, colourMask.get(3) != 0);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBinding);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            // The pending read keeps what it needs, GL frees the target once it is done with it.
            discard();
        }
    }

    /**
     * Collects reads the GPU has finished. Called once a frame on the render thread. The copy out of the buffer is the only
     * part on the frame, turning it into ids happens on a worker.
     */
    static void poll() {
        if (READBACKS.isEmpty()) {
            return;
        }
        Iterator<Readback> it = READBACKS.iterator();
        while (it.hasNext()) {
            Readback read = it.next();
            if (read.capture.isClosed()) {
                read.free();
                it.remove();
                continue;
            }
            int state = GL32.glClientWaitSync(read.fence, 0, 0L);
            if (state == GL32.GL_TIMEOUT_EXPIRED && ++read.polls < MAX_POLLS) {
                continue;
            }
            it.remove();
            if (state != GL32.GL_ALREADY_SIGNALED && state != GL32.GL_CONDITION_SATISFIED) {
                LOGGER.warn("Mob mask read never finished ({})", state);
                read.free();
                read.capture.setEntityMask(null);
                continue;
            }
            collect(read);
        }
    }

    private static void collect(Readback read) {
        long start = System.nanoTime();
        int[] pixels = null;
        int depthHits = 0;
        int packBinding = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, read.buffer);
            long size = read.colourBytes() + DEPTH_SAMPLES_X * DEPTH_SAMPLES_Y * 4L;
            ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, size, GL30.GL_MAP_READ_BIT);
            if (mapped != null) {
                try {
                    mapped.order(ByteOrder.nativeOrder());
                    for (int i = 0; i < DEPTH_SAMPLES_X * DEPTH_SAMPLES_Y; i++) {
                        if (mapped.getFloat((int) read.colourBytes() + i * 4) < 1f) {
                            depthHits++;
                        }
                    }
                    if (depthHits > 0) {
                        pixels = new int[read.width * read.height];
                        mapped.asIntBuffer().get(pixels);
                    }
                } finally {
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Couldn't collect the mob mask", t);
            pixels = null;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBinding);
            read.free();
        }
        long collectMicros = (System.nanoTime() - start) / 1000;
        if (pixels == null) {
            // Empty depth means the copy came out blank, and then every mob would show through walls.
            LOGGER.debug("Mob mask dropped, {} of {} depth samples hold depth", depthHits, DEPTH_SAMPLES_X * DEPTH_SAMPLES_Y);
            read.capture.setEntityMask(null);
            return;
        }
        int[] done = pixels;
        try {
            Util.backgroundExecutor().execute(() -> build(read, done, collectMicros));
        } catch (Throwable t) {
            LOGGER.warn("Couldn't queue the mob mask", t);
            read.capture.setEntityMask(null);
        }
    }

    private static void build(Readback read, int[] pixels, long collectMicros) {
        EntityMask mask = null;
        long start = System.nanoTime();
        try {
            mask = EntityMask.fromBottomUp(pixels, read.width, read.height, read.hudMeasured, read.names);
        } catch (Throwable t) {
            LOGGER.warn("Couldn't build the mob mask", t);
        } finally {
            read.capture.setEntityMask(mask);
        }
        if (mask != null) {
            int[] counts = mask.pixelCounts();
            int shown = 0;
            for (int i = 1; i < counts.length; i++) {
                if (counts[i] > 0) {
                    shown++;
                }
            }
            BitSet hud = mask.hudPixels();
            LOGGER.debug("Mob mask {}x{}: {} of {} entities show, {} stray pixels, {} hands, HUD {}. {} ms on the F2 frame, "
                            + "{} ms to collect after {} frames, {} ms on a worker",
                    read.width, read.height, shown, read.names.size(), mask.strayPixels(), read.handsDrawn,
                    hud == null ? "from layers" : hud.cardinality() + " pixels", ms(read.onFrameMicros),
                    ms(collectMicros), read.polls + 1, ms((System.nanoTime() - start) / 1000));
        }
    }

    /** Frees the target. Safe to call any time, including when a capture frame died half way. */
    static void discard() {
        view = null;
        hudCopied = false;
        hudMicros = 0;
        HANDS.clear();
        RenderTarget old = target;
        target = null;
        RenderTarget oldHud = hudTarget;
        hudTarget = null;
        try {
            if (old != null) {
                old.destroyBuffers();
            }
        } finally {
            if (oldHud != null) {
                oldHud.destroyBuffers();
            }
        }
    }

    private static String ms(long micros) {
        return micros / 1000 + "." + (micros % 1000) / 100;
    }

    private static void drainErrors() {
        for (int i = 0; i < 16 && GlStateManager._getError() != GL11.GL_NO_ERROR; i++) {
            // Someone else's error, it must not be blamed on the copy.
        }
    }

    /** A coarse grid of depth values, read into the buffer after the colour. All at the far plane means the copy is empty. */
    private static void readDepthSamples(RenderTarget made, long offset) {
        int i = 0;
        for (int gy = 0; gy < DEPTH_SAMPLES_Y; gy++) {
            for (int gx = 0; gx < DEPTH_SAMPLES_X; gx++) {
                int x = (gx * 2 + 1) * made.width / (DEPTH_SAMPLES_X * 2);
                int y = (gy * 2 + 1) * made.height / (DEPTH_SAMPLES_Y * 2);
                GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, offset + i * 4L);
                i++;
            }
        }
    }

    private static List<Entity> entitiesToDraw(Minecraft mc, View seen) {
        List<Entity> found = new ArrayList<>();
        LevelRenderer levelRenderer = mc.levelRenderer;
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (found.size() >= EntityMask.MAX_ID) {
                break;
            }
            if (isDrawnThisFrame(mc, levelRenderer, dispatcher, seen.camera(), seen.cameraPos(), seen.frustum(), entity)) {
                found.add(entity);
            }
        }
        return found;
    }

    private static void drawEntities(Minecraft mc, View seen, RenderTarget made, List<Entity> entities, List<String> names) {
        ClientLevel level = mc.level;
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        Vec3 cam = seen.cameraPos();
        var ticks = level.tickRateManager();
        PoseStack pose = new PoseStack();
        ByteBufferBuilder bytes = new ByteBufferBuilder(START_BUFFER_BYTES);
        try {
            MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(bytes);
            FlatSource flat = new FlatSource(buffers, made);
            for (Entity entity : entities) {
                names.add(entity.getName().getString());
                flat.setId(names.size());
                float partialTick = seen.delta().getGameTimeDeltaPartialTick(!ticks.isEntityFrozen(entity));
                try {
                    draw(dispatcher, entity, cam, partialTick, pose, flat);
                } catch (Throwable t) {
                    LOGGER.warn("Mob mask: couldn't draw {}", entity, t);
                    pose = new PoseStack();
                }
            }
            buffers.endBatch();
        } finally {
            bytes.close();
        }
    }

    /**
     * Draws the held item and arm again in id 0, over the mobs, the way the game drew them this frame. The game clears depth
     * before the hand, so the hand covers everything. Both stages have the hand, so it can be left in the mask for good.
     */
    private static int drawHands(Minecraft mc, RenderTarget made, Matrix4fStack modelView) {
        if (HANDS.isEmpty() || !(mc.player instanceof AbstractClientPlayer player)) {
            return 0;
        }
        made.bindWrite(false);
        RenderSystem.depthMask(true);
        RenderSystem.clearDepth(1.0);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        ItemInHandRendererInvoker hands = (ItemInHandRendererInvoker) mc.gameRenderer.itemInHandRenderer;
        int drawn = 0;
        ByteBufferBuilder bytes = new ByteBufferBuilder(START_BUFFER_BYTES);
        try {
            MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(bytes);
            FlatSource flat = new FlatSource(buffers, made);
            flat.setId(EntityMask.NONE);
            for (Map.Entry<InteractionHand, HandDraw> entry : HANDS.entrySet()) {
                HandDraw hand = entry.getValue();
                RenderSystem.setProjectionMatrix(hand.projection(), VertexSorting.DISTANCE_TO_ORIGIN);
                modelView.identity();
                modelView.mul(hand.modelView());
                RenderSystem.applyModelViewMatrix();
                PoseStack pose = new PoseStack();
                pose.last().pose().set(hand.pose());
                pose.last().normal().set(hand.normal());
                try {
                    hands.screenshotutilities$renderArmWithItem(player, hand.partialTick(), hand.pitch(), entry.getKey(),
                            hand.swingProgress(), hand.item(), hand.equipProgress(), pose, flat, hand.light());
                    // Drawn now, while this hand's matrices are set.
                    buffers.endBatch();
                    drawn++;
                } catch (Throwable t) {
                    LOGGER.warn("Mob mask: couldn't draw the {} hand", entry.getKey(), t);
                }
            }
        } finally {
            bytes.close();
        }
        return drawn;
    }

    /** Marks in blue the pixels where {@code depthSource} holds anything nearer than the far plane. */
    private static void markHud(RenderTarget made, RenderTarget depthSource, Matrix4fStack modelView) {
        made.copyDepthFrom(depthSource);
        made.bindWrite(false);
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        // A full screen quad just short of the far plane passes only where the GUI left something nearer.
        RenderSystem.depthFunc(GL11.GL_GREATER);
        RenderSystem.depthMask(false);
        RenderSystem.colorMask(false, false, true, false);
        try {
            float z = 0.999999f;
            BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            quad.addVertex(-1f, -1f, z).setColor(0, 0, 255, 255);
            quad.addVertex(1f, -1f, z).setColor(0, 0, 255, 255);
            quad.addVertex(1f, 1f, z).setColor(0, 0, 255, 255);
            quad.addVertex(-1f, 1f, z).setColor(0, 0, 255, 255);
            BufferUploader.drawWithShader(quad.buildOrThrow());
        } finally {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
        }
    }

    /** The same tests {@code LevelRenderer.renderLevel} makes. */
    private static boolean isDrawnThisFrame(
            Minecraft mc,
            LevelRenderer levelRenderer,
            EntityRenderDispatcher dispatcher,
            Camera camera,
            Vec3 cam,
            Frustum frustum,
            Entity entity) {
        if (!dispatcher.shouldRender(entity, frustum, cam.x, cam.y, cam.z) && !entity.hasIndirectPassenger(mc.player)) {
            return false;
        }
        BlockPos pos = entity.blockPosition();
        if (!mc.level.isOutsideBuildHeight(pos.getY()) && !levelRenderer.isSectionCompiled(pos)) {
            return false;
        }
        Entity viewer = camera.getEntity();
        boolean canSeeSelf = camera.isDetached() || (viewer instanceof LivingEntity living && living.isSleeping());
        if (entity == viewer && !canSeeSelf) {
            return false;
        }
        return !(entity instanceof LocalPlayer) || viewer == entity || (entity == mc.player && !mc.player.isSpectator());
    }

    /** Calls the renderer directly, because the dispatcher is what adds the shadow, the fire and the hitbox. */
    private static <E extends Entity> void draw(
            EntityRenderDispatcher dispatcher, E entity, Vec3 cam, float partialTick, PoseStack pose, MultiBufferSource source) {
        EntityRenderer<? super E> renderer = dispatcher.getRenderer(entity);
        double x = Mth.lerp((double) partialTick, entity.xOld, entity.getX()) - cam.x;
        double y = Mth.lerp((double) partialTick, entity.yOld, entity.getY()) - cam.y;
        double z = Mth.lerp((double) partialTick, entity.zOld, entity.getZ()) - cam.z;
        float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
        Vec3 offset = renderer.getRenderOffset(entity, partialTick);
        pose.pushPose();
        try {
            pose.translate(x + offset.x, y + offset.y, z + offset.z);
            renderer.render(entity, yaw, partialTick, pose, source, LightTexture.FULL_BRIGHT);
        } finally {
            pose.popPose();
        }
    }

    /**
     * Swaps every render type for a flat one, the way {@code OutlineBufferSource} does for glowing mobs. Types with no
     * outline in vanilla are the extras around a body, such as glint, eyes, name tags and the leash, and are dropped.
     */
    private static final class FlatSource implements MultiBufferSource {
        private final MultiBufferSource.BufferSource buffers;
        private final RenderTarget target;
        private final Map<RenderType, RenderType> flatTypes = new IdentityHashMap<>();
        private int red;
        private int green;
        private int blue;

        FlatSource(MultiBufferSource.BufferSource buffers, RenderTarget target) {
            this.buffers = buffers;
            this.target = target;
        }

        void setId(int id) {
            int[] rgb = EntityMask.rgbOf(id);
            red = rgb[0];
            green = rgb[1];
            blue = rgb[2];
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            boolean body = type.outline().isPresent() || type.isOutline();
            if (!body || !type.format().contains(VertexFormatElement.UV0)) {
                // A new one each call: VertexMultiConsumer refuses the same consumer twice.
                return new DroppedConsumer();
            }
            RenderType flat = flatTypes.computeIfAbsent(type, t -> new FlatType(t, target));
            return new FlatConsumer(buffers.getBuffer(flat), red, green, blue);
        }
    }

    /**
     * Keeps the mob's own texture, culling and layering by setting up its real render type, then takes over shader and
     * target. {@code rendertype_outline} throws away only the empty parts of the texture, which keeps a chicken's or a
     * spider's gaps.
     */
    private static final class FlatType extends RenderType {
        FlatType(RenderType base, RenderTarget target) {
            super(
                    "screenshotutilities_flat",
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                    base.mode(),
                    base.bufferSize(),
                    false,
                    false,
                    () -> setUp(base, target),
                    () -> tearDown(base));
        }

        private static void setUp(RenderType base, RenderTarget target) {
            base.setupRenderState();
            target.bindWrite(false);
            RenderSystem.setShader(GameRenderer::getRendertypeOutlineShader);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enablePolygonOffset();
            RenderSystem.polygonOffset(DEPTH_BIAS_FACTOR, DEPTH_BIAS_UNITS);
        }

        private static void tearDown(RenderType base) {
            RenderSystem.polygonOffset(0f, 0f);
            RenderSystem.disablePolygonOffset();
            base.clearRenderState();
        }
    }

    /** Passes on position and texture and paints the id over everything else. */
    private record FlatConsumer(VertexConsumer delegate, int red, int green, int blue) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z).setColor(red, green, blue, 255);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    private static final class DroppedConsumer implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
