package com.screenshotutilities.capture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Maps GUI layers to toggle groups and builds group runs in draw order. Kept free of Minecraft classes so the grouping and
 * run-splitting logic can be unit tested.
 */
public final class HudGroups {
    public static final String SCREEN_EFFECTS = "screen_effects";
    public static final String CROSSHAIR = "crosshair";
    public static final String HOTBAR = "hotbar";
    public static final String HEALTH_FOOD = "health_food";
    public static final String EXPERIENCE = "experience";
    public static final String EFFECTS = "effects";
    public static final String BOSS_BAR = "boss_bar";
    public static final String SIDEBAR = "sidebar";
    public static final String CHAT = "chat";
    public static final String MESSAGES = "messages";
    public static final String DEBUG = "debug";
    public static final String MOD_OVERLAYS = "mod_overlays";
    public static final String MENUS_TOASTS = "menus_toasts";

    public static final Set<String> BUILT_IN_GROUPS = Set.of(
            SCREEN_EFFECTS,
            CROSSHAIR,
            HOTBAR,
            HEALTH_FOOD,
            EXPERIENCE,
            EFFECTS,
            BOSS_BAR,
            SIDEBAR,
            CHAT,
            MESSAGES,
            DEBUG,
            MOD_OVERLAYS,
            MENUS_TOASTS);

    /** All 25 {@code VanillaGuiLayers} in the exact order {@code Gui} registers them. */
    public static final List<String> VANILLA_LAYER_ORDER = List.of(
            "minecraft:camera_overlays",
            "minecraft:crosshair",
            "minecraft:hotbar",
            "minecraft:jump_meter",
            "minecraft:experience_bar",
            "minecraft:player_health",
            "minecraft:armor_level",
            "minecraft:food_level",
            "minecraft:vehicle_health",
            "minecraft:air_level",
            "minecraft:selected_item_name",
            "minecraft:spectator_tooltip",
            "minecraft:experience_level",
            "minecraft:effects",
            "minecraft:boss_overlay",
            "minecraft:sleep_overlay",
            "minecraft:demo_overlay",
            "minecraft:debug_overlay",
            "minecraft:scoreboard_sidebar",
            "minecraft:overlay_message",
            "minecraft:title",
            "minecraft:chat",
            "minecraft:tab_list",
            "minecraft:subtitle_overlay",
            "minecraft:saving_indicator");

    private static final Map<String, String> VANILLA_GROUPS = Map.ofEntries(
            Map.entry("minecraft:camera_overlays", SCREEN_EFFECTS),
            Map.entry("minecraft:crosshair", CROSSHAIR),
            Map.entry("minecraft:hotbar", HOTBAR),
            Map.entry("minecraft:jump_meter", HEALTH_FOOD),
            Map.entry("minecraft:experience_bar", EXPERIENCE),
            Map.entry("minecraft:player_health", HEALTH_FOOD),
            Map.entry("minecraft:armor_level", HEALTH_FOOD),
            Map.entry("minecraft:food_level", HEALTH_FOOD),
            Map.entry("minecraft:vehicle_health", HEALTH_FOOD),
            Map.entry("minecraft:air_level", HEALTH_FOOD),
            Map.entry("minecraft:selected_item_name", HOTBAR),
            Map.entry("minecraft:spectator_tooltip", HOTBAR),
            Map.entry("minecraft:experience_level", EXPERIENCE),
            Map.entry("minecraft:effects", EFFECTS),
            Map.entry("minecraft:boss_overlay", BOSS_BAR),
            Map.entry("minecraft:sleep_overlay", SCREEN_EFFECTS),
            Map.entry("minecraft:demo_overlay", MESSAGES),
            Map.entry("minecraft:debug_overlay", DEBUG),
            Map.entry("minecraft:scoreboard_sidebar", SIDEBAR),
            Map.entry("minecraft:overlay_message", MESSAGES),
            Map.entry("minecraft:title", MESSAGES),
            Map.entry("minecraft:chat", CHAT),
            Map.entry("minecraft:tab_list", SIDEBAR),
            Map.entry("minecraft:subtitle_overlay", MESSAGES),
            Map.entry("minecraft:saving_indicator", MESSAGES));

    private static final Set<String> GENERIC_LAYER_PATHS = Set.of("hud", "overlay", "overlays", "default", "main");

    private HudGroups() {
    }

    /** Returns the vanilla group for a {@code minecraft:...} layer id, or {@code null} if it is a mod layer. */
    @Nullable
    public static String vanillaGroup(String layerId) {
        String known = VANILLA_GROUPS.get(layerId);
        if (known != null) {
            return known;
        }
        if (layerId.startsWith("minecraft:")) {
            return layerId.substring("minecraft:".length());
        }
        return null;
    }

    /**
     * Assigns each layer in {@code orderedLayerIds} to its toggle group id. A non-vanilla layer that sits inside a vanilla
     * group (between two vanilla layers of the same group, or an {@code _offset} pre-hook right before that group) stays in
     * that vanilla group; any other non-vanilla layer gets its own toggle named from its id.
     */
    public static Map<String, String> assignGroups(List<String> orderedLayerIds) {
        int n = orderedLayerIds.size();
        String[] vanillaAt = new String[n];
        for (int i = 0; i < n; i++) {
            vanillaAt[i] = vanillaGroup(orderedLayerIds.get(i));
        }

        String[] prevVanilla = new String[n];
        String last = null;
        for (int i = 0; i < n; i++) {
            if (vanillaAt[i] != null) {
                last = vanillaAt[i];
            }
            prevVanilla[i] = last;
        }

        String[] nextVanilla = new String[n];
        String next = null;
        for (int i = n - 1; i >= 0; i--) {
            if (vanillaAt[i] != null) {
                next = vanillaAt[i];
            }
            nextVanilla[i] = next;
        }

        Map<String, String> result = new LinkedHashMap<>(n);
        for (int i = 0; i < n; i++) {
            String id = orderedLayerIds.get(i);
            if (vanillaAt[i] != null) {
                result.put(id, vanillaAt[i]);
            } else if (prevVanilla[i] != null && prevVanilla[i].equals(nextVanilla[i])) {
                result.put(id, prevVanilla[i]);
            } else if (id.endsWith("_offset") && nextVanilla[i] != null) {
                result.put(id, nextVanilla[i]);
            } else {
                result.put(id, id);
            }
        }
        return result;
    }

    @Nullable
    public static String translationKey(String groupId) {
        return BUILT_IN_GROUPS.contains(groupId) ? "screenshotutilities.group." + groupId : null;
    }

    /** Formats a non-vanilla layer id like {@code modid:mini_map} into a short label like {@code Mini map}. */
    public static String displayNameFromId(String groupId) {
        String namespace = "";
        String path = groupId;
        int colon = groupId.indexOf(':');
        if (colon >= 0) {
            namespace = groupId.substring(0, colon);
            path = groupId.substring(colon + 1);
        }
        String chosen = GENERIC_LAYER_PATHS.contains(path.toLowerCase(Locale.ROOT)) && !namespace.isEmpty()
                ? namespace
                : path;
        String cleaned = chosen.replace('_', ' ').replace('-', ' ').replace('/', ' ').trim();
        if (cleaned.isEmpty()) {
            cleaned = groupId;
        }
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }
}
