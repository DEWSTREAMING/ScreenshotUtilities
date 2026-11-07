package com.screenshotutilities.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HudGroupsTest {
    @Test
    void vanillaLayersMapToExpectedGroups() {
        Map<String, String> assigned = HudGroups.assignGroups(HudGroups.VANILLA_LAYER_ORDER);
        assertEquals(HudGroups.SCREEN_EFFECTS, assigned.get("minecraft:camera_overlays"));
        assertEquals(HudGroups.CROSSHAIR, assigned.get("minecraft:crosshair"));
        assertEquals(HudGroups.HOTBAR, assigned.get("minecraft:hotbar"));
        assertEquals(HudGroups.HOTBAR, assigned.get("minecraft:selected_item_name"));
        assertEquals(HudGroups.EXPERIENCE, assigned.get("minecraft:experience_bar"));
        assertEquals(HudGroups.EXPERIENCE, assigned.get("minecraft:experience_level"));
        assertEquals(HudGroups.HEALTH_FOOD, assigned.get("minecraft:player_health"));
        assertEquals(HudGroups.HEALTH_FOOD, assigned.get("minecraft:food_level"));
        assertEquals(HudGroups.EFFECTS, assigned.get("minecraft:effects"));
        assertEquals(HudGroups.BOSS_BAR, assigned.get("minecraft:boss_overlay"));
        assertEquals(HudGroups.DEBUG, assigned.get("minecraft:debug_overlay"));
        assertEquals(HudGroups.SIDEBAR, assigned.get("minecraft:scoreboard_sidebar"));
        assertEquals(HudGroups.MESSAGES, assigned.get("minecraft:overlay_message"));
        assertEquals(HudGroups.CHAT, assigned.get("minecraft:chat"));
        assertEquals(HudGroups.SIDEBAR, assigned.get("minecraft:tab_list"));
        assertEquals(HudGroups.MESSAGES, assigned.get("minecraft:subtitle_overlay"));
        assertEquals(HudGroups.MESSAGES, assigned.get("minecraft:saving_indicator"));
    }

    @Test
    void moddedLayersInsideSameVanillaGroupInheritThatGroup() {
        List<String> withAppleSkin = new ArrayList<>(HudGroups.VANILLA_LAYER_ORDER);
        int healthIndex = withAppleSkin.indexOf("minecraft:player_health");
        withAppleSkin.add(healthIndex, "appleskin:health_offset");
        int foodIndex = withAppleSkin.indexOf("minecraft:food_level");
        withAppleSkin.add(foodIndex + 1, "appleskin:saturation_level");

        Map<String, String> assigned = HudGroups.assignGroups(withAppleSkin);
        assertEquals(HudGroups.HEALTH_FOOD, assigned.get("appleskin:health_offset"));
        assertEquals(HudGroups.HEALTH_FOOD, assigned.get("appleskin:saturation_level"));
    }

    @Test
    void standaloneModdedLayerGetsOwnGroupIdAndReadableLabel() {
        List<String> withMod = new ArrayList<>(HudGroups.VANILLA_LAYER_ORDER);
        withMod.add("journeymap:minimap_hud");

        Map<String, String> assigned = HudGroups.assignGroups(withMod);
        assertEquals("journeymap:minimap_hud", assigned.get("journeymap:minimap_hud"));
        assertEquals("Minimap hud", HudGroups.displayNameFromId("journeymap:minimap_hud"));
        assertEquals("Jade", HudGroups.displayNameFromId("jade:hud"));
    }
}
