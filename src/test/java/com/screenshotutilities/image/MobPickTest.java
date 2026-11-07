package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.screenshotutilities.capture.CaptureStage;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MobPickTest {
    private static final PickedArea.PictureSource FINAL = PickedArea.PictureSource.FINAL_AS_IS;

    /** A mask from rows of characters: '.' is nothing, a digit is that id. */
    private static EntityMask mask(String... rows) {
        int width = rows[0].length();
        int height = rows.length;
        short[] ids = new short[width * height];
        int max = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                char c = rows[y].charAt(x);
                int id = c == '.' ? 0 : c - '0';
                ids[y * width + x] = (short) id;
                max = Math.max(max, id);
            }
        }
        String[] names = new String[max];
        for (int i = 0; i < max; i++) {
            names[i] = "Mob " + (i + 1);
        }
        return new EntityMask(width, height, ids, null, List.of(names));
    }

    private static BitSet pixels(int width, int... xy) {
        BitSet set = new BitSet();
        for (int i = 0; i < xy.length; i += 2) {
            set.set(xy[i + 1] * width + xy[i]);
        }
        return set;
    }

    @Test
    void mobSplitByAFenceComesBackWhole() {
        // Id 1 is a pig, the fence posts in front of it are not in the mask.
        EntityMask mask = mask(
                "........",
                ".11.111.",
                ".11.111.",
                "........");
        PickedArea pick = MobPick.select(mask, 1, 1, null, FINAL);
        assertNotNull(pick);
        assertEquals(10, pick.pixelCount());
        assertTrue(pick.contains(1, 1));
        assertTrue(pick.contains(6, 2));
        assertFalse(pick.contains(3, 1));
        assertEquals(1, pick.x());
        assertEquals(1, pick.y());
        assertEquals(6, pick.width());
        assertEquals(2, pick.height());
        assertTrue(pick.isMob());
        assertEquals(1, pick.mobId());
    }

    @Test
    void otherMobsAreLeftOut() {
        EntityMask mask = mask(
                "1122",
                "1122");
        PickedArea pick = MobPick.select(mask, 3, 0, null, FINAL);
        assertEquals(4, pick.pixelCount());
        assertFalse(pick.contains(0, 0));
        assertTrue(pick.contains(2, 1));
        assertEquals(2, pick.mobId());
    }

    @Test
    void coveredPixelsAreRemoved() {
        EntityMask mask = mask(
                "111",
                "111");
        BitSet covered = pixels(3, 0, 1, 1, 1);
        PickedArea pick = MobPick.select(mask, 2, 0, covered, FINAL);
        assertEquals(4, pick.pixelCount());
        assertFalse(pick.contains(0, 1));
        assertFalse(pick.contains(1, 1));
        assertTrue(pick.contains(2, 1));
    }

    @Test
    void clickOnACoveredPixelGivesNothing() {
        EntityMask mask = mask(
                "111",
                "111");
        assertNull(MobPick.select(mask, 1, 1, pixels(3, 1, 1), FINAL));
    }

    @Test
    void clickOffAnyMobGivesNothing() {
        EntityMask mask = mask(
                ".1",
                "..");
        assertNull(MobPick.select(mask, 0, 0, null, FINAL));
        assertNull(MobPick.select(mask, 5, 0, null, FINAL));
        assertNull(MobPick.select(mask, -1, 0, null, FINAL));
    }

    @Test
    void anIdThatIsNotThereGivesNothing() {
        EntityMask mask = mask(
                "11",
                "11");
        assertNull(MobPick.pixelsOf(mask, 7, 0, 0, null, FINAL));
        assertNull(MobPick.pixelsOf(mask, EntityMask.NONE, 0, 0, null, FINAL));
    }

    @Test
    void aMobWhollyCoveredGivesNothing() {
        EntityMask mask = mask("11");
        assertNull(MobPick.pixelsOf(mask, 1, 0, 0, pixels(2, 0, 0, 1, 0), FINAL));
    }

    @Test
    void mobPickAndColourPickAtTheSameSeedAreNotEqual() {
        EntityMask mask = mask("11");
        PickedArea mob = MobPick.select(mask, 0, 0, null, FINAL);
        BitSet bits = new BitSet();
        bits.set(0, 2);
        PickedArea colour = new PickedArea(0, 0, MagicWand.DEFAULT_TOLERANCE, 0, 0, 2, 1, bits, FINAL);
        PickedArea colourZero = new PickedArea(0, 0, 0, 0, 0, 2, 1, bits, FINAL);
        assertNotEquals(mob, colour);
        assertNotEquals(mob, colourZero);
        assertFalse(colourZero.isMob());
        assertEquals(mob, MobPick.select(mask, 0, 0, null, FINAL));
        assertEquals(mob.hashCode(), MobPick.select(mask, 0, 0, null, FINAL).hashCode());
    }

    @Test
    void picksOfTwoMobsAtTheSameSeedDiffer() {
        PickedArea a = MobPick.select(mask("1"), 0, 0, null, FINAL);
        PickedArea b = MobPick.select(mask("2"), 0, 0, null, FINAL);
        assertNotEquals(a, b);
    }

    @Test
    void scaledMobPickStaysAMobPick() {
        EntityMask mask = mask(
                "....",
                ".11.",
                ".11.",
                "....");
        PickedArea pick = MobPick.select(mask, 1, 1, null, FINAL);
        PickedArea small = pick.scaledTo(4, 4, 2, 2);
        assertTrue(small.isMob());
        assertEquals(1, small.mobId());
        assertEquals(4, small.pixelCount());
        assertEquals(0, small.seedX());
        assertEquals(0, small.seedY());
        assertEquals(pick.source(), small.source());
    }

    @Test
    void hudCoverTakesShownGroupsOnly() {
        BitSet hotbarBits = new BitSet();
        hotbarBits.set(0, 2);
        BitSet vignetteBits = new BitSet();
        vignetteBits.set(0, 16);
        BitSet chatBits = new BitSet();
        chatBits.set(0);
        List<LayerStack.Part> parts = List.of(
                new LayerStack.Part("screen_effects", 0, 0, 4, 4, vignetteBits, new int[16]),
                new LayerStack.Part("hotbar", 1, 3, 2, 1, hotbarBits, new int[2]),
                new LayerStack.Part("chat", 0, 0, 1, 1, chatBits, new int[1]));
        BitSet cover = MobPick.hudCover(parts, Set.of("chat"), Set.of("screen_effects"), List.of(), 4, 4);
        assertEquals(pixels(4, 1, 3, 2, 3), cover);
    }

    @Test
    void eraseBoxesFreeTheirPixels() {
        BitSet bits = new BitSet();
        bits.set(0, 8);
        List<LayerStack.Part> parts = List.of(new LayerStack.Part("hotbar", 0, 2, 4, 2, bits, new int[8]));
        BitSet cover = MobPick.hudCover(parts, Set.of(), Set.of(), List.of(new CropRect(0, 3, 2, 1)), 4, 4);
        assertEquals(6, cover.cardinality());
        assertFalse(cover.get(3 * 4));
        assertFalse(cover.get(3 * 4 + 1));
        assertTrue(cover.get(3 * 4 + 2));
    }

    @Test
    void clearBoxesStaysInsideThePicture() {
        BitSet cover = new BitSet();
        cover.set(0, 16);
        MobPick.clearBoxes(cover, List.of(new CropRect(2, 2, 10, 10)), 4, 4);
        assertEquals(12, cover.cardinality());
        assertFalse(cover.get(15));
        assertTrue(cover.get(7));
    }

    @Test
    void worldSourceKeepsTheMobPickEqualAcrossHudChoices() {
        EntityMask mask = mask("1");
        PickedArea.PictureSource worldA = new PickedArea.PictureSource(CaptureStage.WORLD, Set.of("hotbar"), List.of());
        PickedArea.PictureSource worldB = new PickedArea.PictureSource(CaptureStage.WORLD, Set.of(), List.of());
        assertEquals(MobPick.select(mask, 0, 0, null, worldA), MobPick.select(mask, 0, 0, null, worldB));
        assertNotEquals(MobPick.select(mask, 0, 0, null, worldA), MobPick.select(mask, 0, 0, null, FINAL));
    }

    @Test
    void largePictureIsQuickEnough() {
        int width = 2560;
        int height = 1440;
        short[] ids = new short[width * height];
        for (int y = 400; y < 1000; y++) {
            for (int x = 800; x < 1600; x++) {
                ids[y * width + x] = 3;
            }
        }
        EntityMask mask = new EntityMask(width, height, ids, null, List.of("a", "b", "c"));
        BitSet covered = new BitSet(width * height);
        covered.set(1300 * width, 1440 * width);
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long start = System.nanoTime();
            assertEquals(600 * 800, MobPick.select(mask, 1000, 500, covered, FINAL).pixelCount());
            best = Math.min(best, System.nanoTime() - start);
        }
        assertTrue(best < 500_000_000L, "took " + best / 1_000_000 + " ms");
    }
}
