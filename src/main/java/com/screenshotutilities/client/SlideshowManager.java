package com.screenshotutilities.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.screenshotutilities.ScreenshotUtilities;
import com.screenshotutilities.capture.CaptureSaver;
import com.screenshotutilities.config.ScreenshotConfig;
import com.screenshotutilities.gallery.GalleryStore;
import com.screenshotutilities.gallery.Slideshow;
import com.screenshotutilities.image.CoverFit;
import com.screenshotutilities.image.Downscale;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one slideshow for the whole game session. Only used from the render thread; worker threads hand their results back
 * through {@code Minecraft.execute}. Keeps at most the current and next picture as textures.
 */
public final class SlideshowManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlideshowManager.class);
    private static final SlideshowManager INSTANCE = new SlideshowManager();
    private static final long FADE_MILLIS = 1000;
    private static final int MAX_WIDTH = 2560;
    private static final int MAX_HEIGHT = 1440;
    /** Light darkening over the picture so menu text stays readable on a bright shot. */
    private static final int DIM_COLOR = 0x50000000;

    private record Loaded(ResourceLocation id, DynamicTexture texture, int width, int height) {
    }

    private final Slideshow show = new Slideshow(new Random());
    private final Map<Path, Loaded> textures = new HashMap<>();
    private final Set<Path> decoding = new HashSet<>();
    private boolean listing;
    private boolean listPending = true;
    private boolean drewPicture;
    private int nextTextureId;
    /** Bumped when everything is dropped, so a decode that was started before it closes its result. */
    private int generation;

    private SlideshowManager() {
    }

    public static SlideshowManager get() {
        return INSTANCE;
    }

    /** Called when a title screen opens, so pictures added or removed in the manager show up. */
    public void requestRefresh() {
        listPending = true;
    }

    /** Drops every texture. */
    public void clear() {
        generation++;
        Minecraft mc = Minecraft.getInstance();
        for (Loaded loaded : textures.values()) {
            release(mc, loaded);
        }
        textures.clear();
    }

    public static boolean isLoadingScreen(Screen screen) {
        return screen instanceof LevelLoadingScreen
                || screen instanceof ReceivingLevelScreen
                || screen instanceof GenericMessageScreen
                || screen instanceof ConnectScreen
                || screen instanceof ProgressScreen;
    }

    /** Draws the slideshow over whatever the screen has drawn so far. Does nothing when it is off or has nothing to show. */
    public void render(Screen screen, GuiGraphics graphics) {
        drewPicture = false;
        Minecraft mc = Minecraft.getInstance();
        boolean loading = isLoadingScreen(screen);
        if (loading) {
            if (!ScreenshotConfig.SLIDESHOW_LOADING.get()) {
                return;
            }
        } else if (!ScreenshotConfig.SLIDESHOW_MAIN_MENU.get() || mc.level != null) {
            return;
        }
        if (listPending && !listing) {
            startListing(mc);
        }
        long periodMillis = ScreenshotConfig.SLIDESHOW_SECONDS.get() * 1000L;
        Slideshow.Frame frame = show.tick(Util.getMillis(), periodMillis, FADE_MILLIS, textures::containsKey);
        syncTextures(mc);
        Loaded current = frame.current() != null ? textures.get(frame.current()) : null;
        if (current == null || frame.currentAlpha() <= 0f) {
            return;
        }
        int w = screen.width;
        int h = screen.height;
        RenderSystem.enableBlend();
        draw(graphics, current, frame.currentAlpha(), w, h);
        Loaded incoming = frame.next() != null ? textures.get(frame.next()) : null;
        if (incoming != null && frame.nextAlpha() > 0f) {
            draw(graphics, incoming, frame.nextAlpha(), w, h);
        }
        graphics.setColor(1f, 1f, 1f, 1f);
        graphics.fill(0, 0, w, h, DIM_COLOR);
        RenderSystem.disableBlend();
        drewPicture = true;
    }

    /** Loading screens would otherwise blur the picture away. Sub-menus keep vanilla's blur so their lists stay readable. */
    public boolean keepsPictureSharp(Screen screen) {
        return drewPicture && isLoadingScreen(screen);
    }

    private static void draw(GuiGraphics graphics, Loaded pic, float alpha, int w, int h) {
        float[] src = CoverFit.sourceRect(pic.width(), pic.height(), w, h);
        graphics.setColor(1f, 1f, 1f, alpha);
        graphics.blit(pic.id(), 0, 0, w, h, src[0], src[1], Math.round(src[2]), Math.round(src[3]),
                pic.width(), pic.height());
    }

    private void startListing(Minecraft mc) {
        listing = true;
        listPending = false;
        int gen = generation;
        GalleryStore store = GalleryStore.fromGameDir(mc.gameDirectory.toPath());
        try {
            Util.ioPool().execute(() -> {
                List<Path> found = store.list();
                mc.execute(() -> {
                    listing = false;
                    if (gen == generation) {
                        show.setPictures(found);
                        syncTextures(mc);
                    }
                });
            });
        } catch (Throwable t) {
            listing = false;
        }
    }

    /** Frees textures nobody wants any more and starts decoding the current and next picture. */
    private void syncTextures(Minecraft mc) {
        Path current = show.current();
        Path next = show.next();
        Iterator<Map.Entry<Path, Loaded>> it = textures.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Path, Loaded> e = it.next();
            if (!e.getKey().equals(current) && !e.getKey().equals(next)) {
                release(mc, e.getValue());
                it.remove();
            }
        }
        startDecode(mc, current);
        startDecode(mc, next);
    }

    private void startDecode(Minecraft mc, Path path) {
        if (path == null || textures.containsKey(path) || !decoding.add(path)) {
            return;
        }
        int gen = generation;
        int winW = mc.getWindow().getWidth();
        int winH = mc.getWindow().getHeight();
        // A minimised window reports 0, which would shrink the picture to nothing.
        int maxW = winW < 64 ? MAX_WIDTH : Math.min(MAX_WIDTH, winW);
        int maxH = winH < 64 ? MAX_HEIGHT : Math.min(MAX_HEIGHT, winH);
        try {
            Util.ioPool().execute(() -> decode(mc, path, gen, maxW, maxH));
        } catch (Throwable t) {
            decoding.remove(path);
        }
    }

    private void decode(Minecraft mc, Path path, int gen, int maxW, int maxH) {
        NativeImage full = null;
        NativeImage result = null;
        try (InputStream in = Files.newInputStream(path)) {
            full = NativeImage.read(in);
            int srcW = full.getWidth();
            int srcH = full.getHeight();
            int[] size = Downscale.fitWithin(srcW, srcH, maxW, maxH);
            if (size[0] == srcW && size[1] == srcH) {
                result = full;
            } else {
                NativeImage source = full;
                int[] pixels = Downscale.box(source::getPixelRGBA, srcW, srcH, size[0], size[1]);
                result = CaptureSaver.imageFromPixels(pixels, size[0], size[1]);
                full.close();
                full = null;
            }
            NativeImage delivered = result;
            result = null;
            full = null;
            deliver(mc, path, gen, delivered);
        } catch (Throwable t) {
            if (result != null) {
                result.close();
            }
            if (full != null && full != result) {
                full.close();
            }
            LOGGER.debug("Couldn't read slideshow picture {}", path, t);
            fail(mc, path, gen);
        }
    }

    private void deliver(Minecraft mc, Path path, int gen, NativeImage image) {
        try {
            mc.execute(() -> {
                decoding.remove(path);
                boolean wanted = gen == generation && !textures.containsKey(path)
                        && (path.equals(show.current()) || path.equals(show.next()));
                if (!wanted) {
                    image.close();
                    return;
                }
                try {
                    int w = image.getWidth();
                    int h = image.getHeight();
                    DynamicTexture texture = new DynamicTexture(image);
                    texture.setFilter(true, false);
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                            ScreenshotUtilities.MOD_ID, "slideshow/" + nextTextureId++);
                    mc.getTextureManager().register(id, texture);
                    textures.put(path, new Loaded(id, texture, w, h));
                } catch (Throwable t) {
                    image.close();
                    show.markBroken(path);
                }
            });
        } catch (Throwable t) {
            image.close();
        }
    }

    private void fail(Minecraft mc, Path path, int gen) {
        try {
            mc.execute(() -> {
                decoding.remove(path);
                if (gen == generation) {
                    show.markBroken(path);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void release(Minecraft mc, Loaded loaded) {
        try {
            mc.getTextureManager().release(loaded.id());
        } catch (Throwable ignored) {
        }
        try {
            loaded.texture().close();
        } catch (Throwable ignored) {
        }
    }
}
