package com.screenshotutilities.compat;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import javax.annotation.Nullable;

/**
 * Which shader pack Iris has chosen and whether shaders are on, plus the small file a retake writes so a crash half way
 * can't leave the player on the wrong pack. No Minecraft or Iris classes, so it can be tested on its own.
 *
 * @param packName  the pack's file name in the shaderpacks folder, or null when none is chosen
 * @param shadersOn whether shaders are switched on
 */
public record ShaderSetup(@Nullable String packName, boolean shadersOn) {
    public static final String FILE_NAME = "screenshotutilities-shader-restore.txt";
    private static final String PACK_KEY = "shaderPack";
    private static final String ON_KEY = "shadersOn";

    public ShaderSetup {
        if (packName != null && packName.isEmpty()) {
            packName = null;
        }
    }

    public static ShaderSetup noShaders(@Nullable String keepPack) {
        return new ShaderSetup(keepPack, false);
    }

    /** The pack that really draws, or null when shaders are off or none is chosen. */
    @Nullable
    public String activePack() {
        return shadersOn ? packName : null;
    }

    public String toText() {
        Properties props = new Properties();
        props.setProperty(PACK_KEY, packName != null ? packName : "");
        props.setProperty(ON_KEY, Boolean.toString(shadersOn));
        StringWriter out = new StringWriter();
        try {
            props.store(out, "Screenshot Utilities puts these Iris choices back if a shader retake was cut short");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /** Null if the text isn't a restore file, so a broken file is never applied. */
    @Nullable
    public static ShaderSetup fromText(String text) {
        Properties props = new Properties();
        try (Reader in = new StringReader(text)) {
            props.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        String pack = props.getProperty(PACK_KEY);
        String on = props.getProperty(ON_KEY);
        if (pack == null || on == null) {
            return null;
        }
        on = on.strip();
        if (!on.equals("true") && !on.equals("false")) {
            return null;
        }
        return new ShaderSetup(pack, on.equals("true"));
    }

    /** Written next to the file and moved over it, so a crash while writing never leaves half a file. */
    public void write(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            out.write(toText());
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Null when there is no file or it can't be read as one. */
    @Nullable
    public static ShaderSetup read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return fromText(Files.readString(file, StandardCharsets.UTF_8));
    }
}
