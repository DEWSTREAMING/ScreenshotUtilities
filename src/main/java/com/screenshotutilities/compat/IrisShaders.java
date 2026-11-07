package com.screenshotutilities.compat;

import com.mojang.logging.LogUtils;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * The only place that talks to Iris. Iris's public API can only switch shaders on and off, so choosing a pack goes through
 * its internal classes, reached by reflection and looked up once. If anything is missing (another Iris version), the
 * retake is simply not offered. Nothing here is touched unless {@code ModList} says Iris is loaded.
 *
 * <p>Checked against Iris 1.8.12 and 1.8.14-beta.1 for 1.21.1. Switching uses the same calls Iris's own API uses for on and
 * off ({@code setShadersEnabled}, {@code save}, {@code reload}), plus {@code setShaderPackName}. {@code Iris.toggleShaders}
 * is avoided because it puts a line in chat, which would end up in the shot.</p>
 */
public final class IrisShaders {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String IRIS = "net.irisshaders.iris.Iris";
    private static final String CONFIG = "net.irisshaders.iris.config.IrisConfig";
    private static final String DIRECTORY_MANAGER = "net.irisshaders.iris.shaderpack.discovery.ShaderpackDirectoryManager";

    private static boolean looked;
    @Nullable
    private static Handles handles;

    private record Handles(
            Method shaderpacksDirectory,
            Method directoryManager,
            Method enumerate,
            Method isValidToShowPack,
            Method irisConfig,
            Method reload,
            Method currentPack,
            Method isFallback,
            Method getPackName,
            Method setPackName,
            Method areShadersEnabled,
            Method setShadersEnabled,
            Method save) {
    }

    /** Thrown when Iris refused or failed a call. The message is for the log. */
    public static final class IrisException extends Exception {
        IrisException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    private IrisShaders() {
    }

    /** Whether Iris is loaded and has everything the retake needs. Looked up on the first call only. */
    public static synchronized boolean available() {
        if (!looked) {
            looked = true;
            try {
                if (ModList.get().isLoaded("iris")) {
                    handles = lookUp();
                }
            } catch (Throwable t) {
                handles = null;
                LOGGER.info("Shader retake is off, this Iris version doesn't have what it needs: {}", t.toString());
            }
        }
        return handles != null;
    }

    private static Handles lookUp() throws ReflectiveOperationException {
        ClassLoader loader = IrisShaders.class.getClassLoader();
        Class<?> iris = Class.forName(IRIS, false, loader);
        Class<?> config = Class.forName(CONFIG, false, loader);
        Class<?> manager = Class.forName(DIRECTORY_MANAGER, false, loader);
        return new Handles(
                iris.getMethod("getShaderpacksDirectory"),
                iris.getMethod("getShaderpacksDirectoryManager"),
                manager.getMethod("enumerate"),
                iris.getMethod("isValidToShowPack", Path.class),
                iris.getMethod("getIrisConfig"),
                iris.getMethod("reload"),
                iris.getMethod("getCurrentPack"),
                iris.getMethod("isFallback"),
                config.getMethod("getShaderPackName"),
                config.getMethod("setShaderPackName", String.class),
                config.getMethod("areShadersEnabled"),
                config.getMethod("setShadersEnabled", boolean.class),
                config.getMethod("save"));
    }

    private static Handles handles() throws IrisException {
        if (!available()) {
            throw new IrisException("Iris is not there", null);
        }
        return handles;
    }

    /** The shaderpacks folder. */
    public static Path packsFolder() throws IrisException {
        return (Path) call(handles().shaderpacksDirectory(), null);
    }

    /**
     * The packs Iris would list in its own screen, in its order. Opens zip files to check them, so call it on a worker.
     */
    public static List<String> listPacks() throws IrisException {
        Handles h = handles();
        Path dir = (Path) call(h.shaderpacksDirectory(), null);
        Object manager = call(h.directoryManager(), null);
        List<?> names = (List<?>) call(h.enumerate(), manager);
        List<String> packs = new ArrayList<>(names.size());
        for (Object name : names) {
            if (name instanceof String s) {
                try {
                    if ((Boolean) call(h.isValidToShowPack(), null, dir.resolve(s))) {
                        packs.add(s);
                    }
                } catch (IrisException | RuntimeException e) {
                    LOGGER.debug("Skipping shader pack {}", s, e);
                }
            }
        }
        return packs;
    }

    /** What Iris has chosen right now, from its config in memory. */
    public static ShaderSetup current() throws IrisException {
        Handles h = handles();
        Object config = call(h.irisConfig(), null);
        Optional<?> name = (Optional<?>) call(h.getPackName(), config);
        boolean on = (Boolean) call(h.areShadersEnabled(), config);
        return new ShaderSetup(name.map(Object::toString).orElse(null), on);
    }

    /**
     * Chooses {@code setup} in Iris's config, saves it and reloads. Render thread only. Holds up the game while the pack
     * loads, a few seconds for a big one.
     */
    public static void apply(ShaderSetup setup) throws IrisException {
        Handles h = handles();
        Object config = call(h.irisConfig(), null);
        call(h.setPackName(), config, setup.packName());
        call(h.setShadersEnabled(), config, setup.shadersOn());
        call(h.save(), config);
        call(h.reload(), null);
    }

    /** True when a pack is loaded and its pipeline built. A pack that failed leaves Iris on its fallback. */
    public static boolean packRunning() throws IrisException {
        Handles h = handles();
        Optional<?> pack = (Optional<?>) call(h.currentPack(), null);
        return pack.isPresent() && !(Boolean) call(h.isFallback(), null);
    }

    private static Object call(Method method, @Nullable Object target, Object... args) throws IrisException {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new IrisException("Iris " + method.getName() + " failed", e.getCause());
        } catch (IllegalAccessException | IllegalArgumentException | ClassCastException e) {
            throw new IrisException("Couldn't call Iris " + method.getName(), e);
        }
    }
}
