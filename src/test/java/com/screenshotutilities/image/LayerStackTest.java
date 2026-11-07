package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LayerStackTest {
    @Test
    void allGroupsVisibleEqualsFinal() {
        int width = 8;
        int height = 6;
        int[] world = filled(width * height, 0xFF102030);
        int[] afterCrosshair = world.clone();
        afterCrosshair[2 * width + 3] = 0xFFFFFFFF;
        afterCrosshair[2 * width + 4] = 0xFFFFFFFF;

        int[] afterHotbar = afterCrosshair.clone();
        for (int x = 1; x < 7; x++) {
            afterHotbar[5 * width + x] = 0xFF808080;
        }

        LayerStack stack = LayerStack.fromSnapshots(
                width,
                height,
                world,
                List.of("crosshair", "hotbar"),
                List.of(afterCrosshair, afterHotbar));

        assertArrayEquals(afterHotbar, stack.rebuild(Set.of()));
    }

    @Test
    void oneGroupHiddenRemovesOnlyThatGroup() {
        int width = 6;
        int height = 4;
        int[] world = filled(width * height, 0xFF001122);
        int[] afterCrosshair = world.clone();
        afterCrosshair[1 * width + 2] = 0xFFFFFFFF;

        int[] afterHotbar = afterCrosshair.clone();
        afterHotbar[3 * width + 4] = 0xFFAA5500;

        LayerStack stack = LayerStack.fromSnapshots(
                width,
                height,
                world,
                List.of("crosshair", "hotbar"),
                List.of(afterCrosshair, afterHotbar));

        int[] withoutCrosshair = stack.rebuild(Set.of("crosshair"));
        assertEquals(0xFF001122, withoutCrosshair[1 * width + 2]);
        assertEquals(0xFFAA5500, withoutCrosshair[3 * width + 4]);

        int[] withoutHotbar = stack.rebuild(Set.of("hotbar"));
        assertEquals(0xFFFFFFFF, withoutHotbar[1 * width + 2]);
        assertEquals(0xFF001122, withoutHotbar[3 * width + 4]);
    }

    @Test
    void overlappingGroupsKeepRemainingGroupWhenOneIsHidden() {
        int width = 5;
        int height = 5;
        int overlapIdx = 2 * width + 2;
        int onlyAIdx = 1 * width + 1;
        int onlyBIdx = 3 * width + 3;

        int[] world = filled(width * height, 0xFF000000);
        int[] afterA = world.clone();
        afterA[onlyAIdx] = 0xFFFF0000;
        afterA[overlapIdx] = 0xFFFF0000;

        int[] afterB = afterA.clone();
        afterB[onlyBIdx] = 0xFF00FF00;
        afterB[overlapIdx] = 0xFF00FF00;

        LayerStack stack = LayerStack.fromSnapshots(
                width,
                height,
                world,
                List.of("group_a", "group_b"),
                List.of(afterA, afterB));

        // Hiding bottom group A keeps top group B at the overlap pixel and restores onlyAIdx to world.
        int[] hideA = stack.rebuild(Set.of("group_a"));
        assertEquals(0xFF000000, hideA[onlyAIdx]);
        assertEquals(0xFF00FF00, hideA[overlapIdx]);
        assertEquals(0xFF00FF00, hideA[onlyBIdx]);

        // Hiding top group B reveals bottom group A at the overlap pixel and restores onlyBIdx to world.
        int[] hideB = stack.rebuild(Set.of("group_b"));
        assertEquals(0xFFFF0000, hideB[onlyAIdx]);
        assertEquals(0xFFFF0000, hideB[overlapIdx]);
        assertEquals(0xFF000000, hideB[onlyBIdx]);
    }

    @Test
    void nothingChangedGroupIsOmittedFromGroups() {
        int width = 4;
        int height = 4;
        int[] world = filled(width * height, 0xFF223344);
        int[] afterEmpty = world.clone();
        int[] afterHotbar = world.clone();
        afterHotbar[0] = 0xFFFFFFFF;

        LayerStack.Part emptyPart = LayerStack.extractPart("boss_bar", world, afterEmpty, width, height);
        assertNull(emptyPart);

        LayerStack stack = LayerStack.fromSnapshots(
                width,
                height,
                world,
                List.of("boss_bar", "hotbar"),
                List.of(afterEmpty, afterHotbar));

        assertEquals(List.of("hotbar"), stack.groups());
        assertArrayEquals(world, stack.rebuild(Set.of("hotbar")));
    }

    @Test
    void oddImageSizesAndMultiplePartsPerGroupWorkCorrectly() {
        int width = 7;
        int height = 5;
        int[] world = new int[width * height];
        for (int i = 0; i < world.length; i++) {
            world[i] = 0xFF000000 | (i * 7);
        }

        int[] afterHotbar1 = world.clone();
        afterHotbar1[0] = 0xFF111111;

        int[] afterCrosshair = afterHotbar1.clone();
        afterCrosshair[width * height / 2] = 0xFF222222;

        int[] afterHotbar2 = afterCrosshair.clone();
        afterHotbar2[width * height - 1] = 0xFF333333;

        LayerStack stack = LayerStack.fromSnapshots(
                width,
                height,
                world,
                List.of("hotbar", "crosshair", "hotbar"),
                List.of(afterHotbar1, afterCrosshair, afterHotbar2));

        assertEquals(List.of("hotbar", "crosshair"), stack.groups());
        assertEquals(3, stack.parts().size());

        int[] hideHotbar = stack.rebuild(Set.of("hotbar"));
        assertEquals(world[0], hideHotbar[0]);
        assertEquals(0xFF222222, hideHotbar[width * height / 2]);
        assertEquals(world[width * height - 1], hideHotbar[width * height - 1]);

        LayerStack scaled = stack.downscale(3, 2);
        assertNotNull(scaled);
        assertEquals(3, scaled.width());
        assertEquals(2, scaled.height());
        assertEquals(6, scaled.rebuild(Set.of()).length);
    }

    @Test
    void eraseBoxesRestoreTargetRegionToWorldPixels() {
        int width = 6;
        int height = 5;
        int[] world = filled(width * height, 0xFF102030);
        int[] finalPixels = filled(width * height, 0xFFABCDEF);

        LayerStack stack = LayerStack.fromSnapshots(
                width,
                height,
                world,
                List.of("mod_overlays"),
                List.of(finalPixels));

        CropRect box = new CropRect(1, 1, 2, 2);
        int[] rebuilt = stack.rebuild(Set.of(), List.of(box));

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int expected = (x >= 1 && x < 3 && y >= 1 && y < 3) ? 0xFF102030 : 0xFFABCDEF;
                assertEquals(expected, rebuilt[y * width + x], "Mismatch at (" + x + ", " + y + ")");
            }
        }
    }

    private static int[] filled(int length, int color) {
        int[] arr = new int[length];
        Arrays.fill(arr, color);
        return arr;
    }
}
