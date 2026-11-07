package com.screenshotutilities.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class ModKeys {
    public static final String CATEGORY = "key.categories.screenshotutilities";

    public static final KeyMapping OPEN_LAST = new KeyMapping(
            "key.screenshotutilities.open_last",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F4,
            CATEGORY);

    public static final KeyMapping OPEN_GALLERY = new KeyMapping(
            "key.screenshotutilities.open_gallery",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY);

    private ModKeys() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_LAST);
        event.register(OPEN_GALLERY);
    }
}
