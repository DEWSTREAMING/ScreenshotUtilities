package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityMaskTest {
    /** The pixel the GPU writes for an id, with alpha 255. */
    static int pixelOf(int id) {
        int[] rgb = EntityMask.rgbOf(id);
        return 0xFF000000 | rgb[2] << 16 | rgb[1] << 8 | rgb[0];
    }

    private static List<String> names(int count) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            out[i] = "Mob " + (i + 1);
        }
        return List.of(out);
    }

    @Test
    void idsSurviveColourAndBack() {
        for (int id : new int[] {0, 1, 2, 255, 256, 257, 4660, EntityMask.MAX_ID}) {
            assertEquals(id, EntityMask.idOfPixel(pixelOf(id)));
        }
    }

    @Test
    void everyIdIsItsOwnColour() {
        for (int id = 0; id <= EntityMask.MAX_ID; id++) {
            assertEquals(id, EntityMask.idOfPixel(pixelOf(id)));
        }
    }

    @Test
    void redIsTheLowByteAndBlueIsLeftForTheHud() {
        assertArrayEquals(new int[] {0x34, 0x12, 0}, EntityMask.rgbOf(0x1234));
        assertFalse(EntityMask.isHudPixel(pixelOf(0x1234)));
        assertTrue(EntityMask.isHudPixel(pixelOf(0x1234) | 0x00FF0000));
        assertEquals(0x1234, EntityMask.idOfPixel(pixelOf(0x1234) | 0x00FF0000));
    }

    @Test
    void idOutOfRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EntityMask.rgbOf(-1));
        assertThrows(IllegalArgumentException.class, () -> EntityMask.rgbOf(EntityMask.MAX_ID + 1));
    }

    @Test
    void clearedBackgroundIsNoMob() {
        assertEquals(EntityMask.NONE, EntityMask.idOfPixel(0x00000000));
        assertEquals(EntityMask.NONE, EntityMask.idOfPixel(0xFF000000));
    }

    @Test
    void bottomUpReadIsFlipped() {
        int[] raw = {pixelOf(3), pixelOf(4), pixelOf(1), pixelOf(2) | 0x00FF0000};
        EntityMask mask = EntityMask.fromBottomUp(raw, 2, 2, true, names(4));
        assertEquals(1, mask.idAt(0, 0));
        assertEquals(2, mask.idAt(1, 0));
        assertEquals(3, mask.idAt(0, 1));
        assertEquals(4, mask.idAt(1, 1));
        BitSet hud = mask.hudPixels();
        assertEquals(1, hud.cardinality());
        assertTrue(hud.get(1));
    }

    @Test
    void hudIsUnknownUnlessMeasured() {
        EntityMask mask = EntityMask.fromBottomUp(new int[] {pixelOf(1) | 0x00FF0000}, 1, 1, false, names(1));
        assertNull(mask.hudPixels());
    }

    @Test
    void highIdsReadBackUnsigned() {
        EntityMask mask = EntityMask.fromBottomUp(new int[] {pixelOf(EntityMask.MAX_ID)}, 1, 1, false, names(1));
        assertEquals(EntityMask.MAX_ID, mask.idAt(0, 0));
    }

    @Test
    void outsideThePictureIsNoMob() {
        EntityMask mask = EntityMask.fromBottomUp(new int[] {pixelOf(1)}, 1, 1, false, names(1));
        assertEquals(EntityMask.NONE, mask.idAt(-1, 0));
        assertEquals(EntityMask.NONE, mask.idAt(1, 0));
        assertEquals(EntityMask.NONE, mask.idAt(0, 1));
    }

    @Test
    void wrongSizeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EntityMask.fromBottomUp(new int[3], 2, 2, false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EntityMask(2, 2, new short[3], null, List.of()));
    }

    @Test
    void namesByIdAndCountsAndStrays() {
        int[] raw = {pixelOf(0), pixelOf(1), pixelOf(1), pixelOf(2), pixelOf(9), pixelOf(9)};
        EntityMask mask = EntityMask.fromBottomUp(raw, 6, 1, false, List.of("Pig", "Cow"));
        assertEquals("Pig", mask.nameOf(1));
        assertEquals("Cow", mask.nameOf(2));
        assertEquals("", mask.nameOf(9));
        assertEquals("", mask.nameOf(0));
        assertEquals(2, mask.entityCount());
        assertArrayEquals(new int[] {1, 2, 1}, mask.pixelCounts());
        assertEquals(2, mask.strayPixels());
    }

    @Test
    void hudPixelsAreACopy() {
        EntityMask mask = EntityMask.fromBottomUp(new int[] {pixelOf(1) | 0x00FF0000}, 1, 1, true, names(1));
        mask.hudPixels().clear();
        assertEquals(1, mask.hudPixels().cardinality());
    }

    @Test
    void largePictureIsQuickEnoughForAWorker() {
        int width = 2560;
        int height = 1440;
        int[] raw = new int[width * height];
        for (int i = 0; i < raw.length / 4; i++) {
            raw[i] = pixelOf(1 + i % 40);
        }
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long start = System.nanoTime();
            EntityMask.fromBottomUp(raw, width, height, true, names(40));
            best = Math.min(best, System.nanoTime() - start);
        }
        assertTrue(best < 1_000_000_000L, "took " + best / 1_000_000 + " ms");
    }
}
