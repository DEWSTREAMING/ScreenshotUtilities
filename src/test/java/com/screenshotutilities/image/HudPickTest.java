package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HudPickTest {
    private static PickedArea pick(int seedX) {
        BitSet mask = new BitSet();
        mask.set(0, 4);
        return new PickedArea(seedX, 0, 40, 0, 0, 2, 2, mask, PickedArea.PictureSource.FINAL_AS_IS);
    }

    /** 4x2 picture: the map draws on the left half, the hotbar on top of it at (1, 0). */
    private static LayerStack stack() {
        int[] world = new int[8];
        int[] withMap = world.clone();
        withMap[0] = 1;
        withMap[1] = 1;
        withMap[4] = 1;
        withMap[5] = 1;
        int[] withHotbar = withMap.clone();
        withHotbar[1] = 2;
        LayerStack.Part map = LayerStack.extractPart("map", world, withMap, 4, 2);
        LayerStack.Part hotbar = LayerStack.extractPart("hotbar", withMap, withHotbar, 4, 2);
        return new LayerStack(4, 2, world, List.of(map, hotbar));
    }

    @Test
    void groupAtFindsTheTopGroup() {
        LayerStack stack = stack();
        assertEquals("map", stack.groupAt(0, 0, Set.of(), List.of()));
        assertEquals("hotbar", stack.groupAt(1, 0, Set.of(), List.of()));
        assertNull(stack.groupAt(3, 1, Set.of(), List.of()));
    }

    @Test
    void groupAtSkipsHiddenGroupsAndErasedPixels() {
        LayerStack stack = stack();
        assertEquals("map", stack.groupAt(1, 0, Set.of("hotbar"), List.of()));
        assertNull(stack.groupAt(1, 0, Set.of("hotbar", "map"), List.of()));
        assertNull(stack.groupAt(0, 0, Set.of(), List.of(new CropRect(0, 0, 1, 1))));
    }

    @Test
    void aPickOnAHiddenGroupMarksNothing() {
        PickedArea onMap = pick(0).onHudGroup("map");
        PickedArea onWorld = pick(3);
        FilterSettings settings = FilterSettings.NONE.withPickedAreas(List.of(onMap, onWorld));
        assertEquals(List.of(onWorld), settings.shownWith(Set.of("map")).pickedAreas());
        assertSame(settings, settings.shownWith(Set.of("chat")));
        assertSame(settings, settings.shownWith(Set.of()));
    }

    @Test
    void theOnlyPickHiddenLeavesNoFilter() {
        FilterSettings settings = FilterSettings.NONE.withPickedAreas(List.of(pick(0).onHudGroup("map")));
        assertTrue(settings.isActive());
        assertFalse(settings.shownWith(Set.of("map")).isActive());
    }

    @Test
    void theGroupSurvivesScalingAndRedoing() {
        PickedArea onMap = pick(0).onHudGroup("map");
        assertEquals("map", onMap.scaledTo(4, 2, 2, 1).hudGroup());
        assertEquals("map", onMap.withSelection(50, pick(0)).hudGroup());
        assertEquals(pick(0), onMap);
    }
}
