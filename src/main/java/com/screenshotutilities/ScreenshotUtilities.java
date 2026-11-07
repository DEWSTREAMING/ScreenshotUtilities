package com.screenshotutilities;

import com.screenshotutilities.capture.CaptureController;
import com.screenshotutilities.client.ModKeys;
import com.screenshotutilities.client.SlideshowEvents;
import com.screenshotutilities.compat.ShaderRestore;
import com.screenshotutilities.config.ScreenshotConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = ScreenshotUtilities.MOD_ID, dist = Dist.CLIENT)
public class ScreenshotUtilities {
    public static final String MOD_ID = "screenshotutilities";

    private static ModContainer container;

    public ScreenshotUtilities(IEventBus modBus, ModContainer modContainer) {
        container = modContainer;
        modContainer.registerConfig(ModConfig.Type.CLIENT, ScreenshotConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(ModKeys::register);
        CaptureController.register(NeoForge.EVENT_BUS);
        SlideshowEvents.register(NeoForge.EVENT_BUS);
        ShaderRestore.register(NeoForge.EVENT_BUS);
    }

    public static ModContainer container() {
        return container;
    }
}
