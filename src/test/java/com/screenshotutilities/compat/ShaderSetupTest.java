package com.screenshotutilities.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShaderSetupTest {
    @TempDir
    Path dir;

    @Test
    void textRoundTrip() {
        ShaderSetup setup = new ShaderSetup("ComplementaryUnbound_r5.9.3.zip", true);
        assertEquals(setup, ShaderSetup.fromText(setup.toText()));
    }

    @Test
    void noPackRoundTrip() {
        ShaderSetup setup = new ShaderSetup(null, false);
        assertEquals(setup, ShaderSetup.fromText(setup.toText()));
    }

    @Test
    void emptyPackNameIsNoPack() {
        assertNull(new ShaderSetup("", true).packName());
    }

    @Test
    void oddPackNamesSurvive() {
        String name = "§6Pack = with: odd #chars \\ and ünïcode 光影.zip";
        ShaderSetup setup = new ShaderSetup(name, false);
        assertEquals(setup, ShaderSetup.fromText(setup.toText()));
    }

    @Test
    void fileRoundTrip() throws Exception {
        Path file = dir.resolve("config").resolve(ShaderSetup.FILE_NAME);
        ShaderSetup setup = new ShaderSetup("BSL_v8.4.zip", true);
        setup.write(file);
        assertEquals(setup, ShaderSetup.read(file));
        assertFalse(Files.exists(file.resolveSibling(ShaderSetup.FILE_NAME + ".tmp")));
    }

    @Test
    void writeReplacesOldFile() throws Exception {
        Path file = dir.resolve(ShaderSetup.FILE_NAME);
        new ShaderSetup("a.zip", true).write(file);
        new ShaderSetup("b", false).write(file);
        assertEquals(new ShaderSetup("b", false), ShaderSetup.read(file));
    }

    @Test
    void missingFileReadsAsNull() throws Exception {
        assertNull(ShaderSetup.read(dir.resolve("nothing.txt")));
    }

    @Test
    void brokenFileReadsAsNull() throws Exception {
        Path file = dir.resolve(ShaderSetup.FILE_NAME);
        Files.writeString(file, "shaderPack=a.zip\n", StandardCharsets.UTF_8);
        assertNull(ShaderSetup.read(file));
        Files.writeString(file, "shaderPack=a.zip\nshadersOn=maybe\n", StandardCharsets.UTF_8);
        assertNull(ShaderSetup.read(file));
        Files.writeString(file, "", StandardCharsets.UTF_8);
        assertNull(ShaderSetup.read(file));
    }

    @Test
    void activePackOnlyWithShadersOn() {
        assertEquals("a.zip", new ShaderSetup("a.zip", true).activePack());
        assertNull(new ShaderSetup("a.zip", false).activePack());
        assertNull(new ShaderSetup(null, true).activePack());
        assertEquals(new ShaderSetup("a.zip", false), ShaderSetup.noShaders("a.zip"));
    }
}
