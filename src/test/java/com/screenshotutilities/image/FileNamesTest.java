package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FileNamesTest {
    private static final String SHOT = "2026-10-31_13.17.20.png";

    @Test
    void editCopyAddsSuffixBeforeExtension() {
        assertEquals("2026-10-31_13.17.20_edit.png", FileNames.editCopy(SHOT, n -> false));
    }

    @Test
    void editCopyCountsUpWhenTaken() {
        Set<String> taken = new HashSet<>(Set.of("2026-10-31_13.17.20_edit.png"));
        assertEquals("2026-10-31_13.17.20_edit_2.png", FileNames.editCopy(SHOT, taken::contains));
        taken.add("2026-10-31_13.17.20_edit_2.png");
        taken.add("2026-10-31_13.17.20_edit_3.png");
        assertEquals("2026-10-31_13.17.20_edit_4.png", FileNames.editCopy(SHOT, taken::contains));
    }

    @Test
    void editCopyOfVanillaDuplicateKeepsItsNumber() {
        assertEquals("2026-10-31_13.17.20_2_edit.png", FileNames.editCopy("2026-10-31_13.17.20_2.png", n -> false));
    }

    @Test
    void editCopyWithoutExtensionUsesPng() {
        assertEquals("shot_edit.png", FileNames.editCopy("shot", n -> false));
    }

    @Test
    void editCopyKeepsOtherExtensions() {
        assertEquals("shot_edit.jpg", FileNames.editCopy("shot.jpg", n -> false));
    }

    @Test
    void uniqueKeepsFreeName() {
        assertEquals(SHOT, FileNames.unique(SHOT, n -> false));
    }

    @Test
    void uniqueFollowsVanillaNumbering() {
        Set<String> taken = Set.of(SHOT, "2026-10-31_13.17.20_2.png");
        assertEquals("2026-10-31_13.17.20_3.png", FileNames.unique(SHOT, taken::contains));
    }

    @Test
    void dotFilesAreNotTreatedAsExtensions() {
        assertEquals(".hidden_edit.png", FileNames.editCopy(".hidden", n -> false));
    }

    @Test
    void retakeAddsPackWithoutZip() {
        assertEquals("2026-10-31_13.17.20_ComplementaryUnbound_r5.9.3.png",
                FileNames.retakeCopy(SHOT, "ComplementaryUnbound_r5.9.3.zip", n -> false));
    }

    @Test
    void retakeWithNoShaders() {
        assertEquals("2026-10-31_13.17.20_no_shaders.png", FileNames.retakeCopy(SHOT, null, n -> false));
    }

    @Test
    void retakeCountsUpWhenTaken() {
        Set<String> taken = Set.of("2026-10-31_13.17.20_BSL_v8.4.png");
        assertEquals("2026-10-31_13.17.20_BSL_v8.4_2.png", FileNames.retakeCopy(SHOT, "BSL_v8.4.zip", taken::contains));
    }

    @Test
    void packPartReplacesUnsafeCharacters() {
        assertEquals("My_Pack_v1.2", FileNames.packPart("My Pack: v1.2"));
        assertEquals("a_b_c_d", FileNames.packPart("a/b\\c*?\"<>|d"));
        assertEquals("Pack_with_spaces", FileNames.packPart("  Pack   with  spaces  "));
        assertEquals("Fancy_Pack", FileNames.packPart("§6Fancy §lPack.ZIP"));
        assertEquals("光影", FileNames.packPart("光影.zip"));
    }

    @Test
    void packPartTrimsDotsAndUnderscores() {
        assertEquals("pack", FileNames.packPart("..pack.."));
        assertEquals("pack", FileNames.packPart("_(pack)_"));
        assertEquals("shader", FileNames.packPart("..."));
        assertEquals("shader", FileNames.packPart(".zip"));
        assertEquals("shader", FileNames.packPart("$$$"));
    }

    @Test
    void packPartIsCut() {
        assertEquals(FileNames.MAX_PACK_PART, FileNames.packPart("x".repeat(100) + ".zip").length());
        // A cut that lands on a run of underscores leaves none at the end.
        String a39 = "a".repeat(FileNames.MAX_PACK_PART - 1);
        assertEquals(a39, FileNames.packPart(a39 + " bbbb"));
    }

    @Test
    void packPartNeverSplitsASurrogatePair() {
        String name = "a".repeat(FileNames.MAX_PACK_PART - 1) + "𝐀" + "zz";
        assertEquals("a".repeat(FileNames.MAX_PACK_PART - 1), FileNames.packPart(name));
    }
}
