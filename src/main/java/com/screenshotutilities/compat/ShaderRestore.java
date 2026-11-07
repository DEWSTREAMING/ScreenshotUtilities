package com.screenshotutilities.compat;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Iris keeps its choices in its own config, so a retake that is cut short (a crash, a forced close) would leave the player
 * on the retake's pack. Before switching, the retake writes what to go back to into a file of ours, and deletes it once it
 * has gone back. A file still there at startup is put back here.
 */
public final class ShaderRestore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean checked;
    /** One thread, so a delete from one retake can never land after the next retake's write. */
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Screenshot Utilities shader restore");
        thread.setDaemon(true);
        return thread;
    });

    private ShaderRestore() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(ShaderRestore::onClientTick);
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(ShaderSetup.FILE_NAME);
    }

    /** Writes the file off the render thread. The future fails if it couldn't be written. */
    public static CompletableFuture<Void> writeLater(ShaderSetup setup) {
        return CompletableFuture.runAsync(() -> {
            try {
                setup.write(file());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, FILES);
    }

    private static void delete() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException e) {
            LOGGER.warn("Couldn't delete {}", file(), e);
        }
    }

    public static void deleteLater() {
        FILES.execute(ShaderRestore::delete);
    }

    // Iris has loaded its pack by the time the loading overlay is gone, so that is when a left over file is applied.
    private static void onClientTick(ClientTickEvent.Post event) {
        if (checked) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null) {
            return;
        }
        checked = true;
        if (!IrisShaders.available()) {
            // Kept for when Iris is back: its config still holds whatever the cut short retake chose.
            return;
        }
        FILES.execute(() -> {
            Path file = file();
            if (!Files.exists(file)) {
                return;
            }
            ShaderSetup setup;
            try {
                setup = ShaderSetup.read(file);
            } catch (IOException e) {
                setup = null;
            }
            if (setup == null) {
                LOGGER.warn("Couldn't read the shader restore file {}, deleting it and leaving Iris as it is", file);
                delete();
                return;
            }
            boolean packThere;
            try {
                packThere = setup.packName() == null || Files.exists(IrisShaders.packsFolder().resolve(setup.packName()));
            } catch (Throwable t) {
                LOGGER.warn("Couldn't look for shader pack {}", setup.packName(), t);
                packThere = false;
            }
            ShaderSetup toRestore = setup;
            boolean found = packThere;
            mc.execute(() -> restoreLeftOver(toRestore, found));
        });
    }

    private static void restoreLeftOver(ShaderSetup setup, boolean packThere) {
        if (!packThere && setup.shadersOn()) {
            LOGGER.warn("A shader retake didn't finish last time, but shader pack {} is gone, so Iris is left as it is",
                    setup.packName());
            deleteLater();
            return;
        }
        try {
            ShaderSetup now = IrisShaders.current();
            if (now.equals(setup)) {
                LOGGER.info("A shader retake didn't finish last time, Iris already has pack {} with shaders {}",
                        setup.packName(), setup.shadersOn() ? "on" : "off");
            } else {
                IrisShaders.apply(setup);
                LOGGER.info("A shader retake didn't finish last time, put back pack {} with shaders {} (was {} with shaders {})",
                        setup.packName(), setup.shadersOn() ? "on" : "off", now.packName(), now.shadersOn() ? "on" : "off");
            }
            deleteLater();
        } catch (Throwable t) {
            // The file stays, so the next start tries again.
            LOGGER.error("Couldn't put back shader pack {} after a retake that didn't finish", setup.packName(), t);
        }
    }
}
