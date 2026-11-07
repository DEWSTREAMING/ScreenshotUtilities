package com.screenshotutilities.client;

import com.screenshotutilities.config.ScreenshotConfig;
import com.screenshotutilities.screen.GalleryScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ScreenEvent;

public final class SlideshowEvents {
    private static final int BUTTON_WIDTH = 60;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MARGIN = 6;

    private SlideshowEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(SlideshowEvents::onOpening);
        bus.addListener(SlideshowEvents::onInitPost);
    }

    private static void onOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof TitleScreen) {
            if (ScreenshotConfig.SLIDESHOW_MAIN_MENU.get() || ScreenshotConfig.SLIDESHOW_LOADING.get()) {
                SlideshowManager.get().requestRefresh();
            } else {
                SlideshowManager.get().clear();
            }
        }
    }

    /** Top left corner: clear of vanilla's button column, the bottom row and the version text at the bottom. */
    private static void onInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen title) || !ScreenshotConfig.GALLERY_BUTTON.get()) {
            return;
        }
        Button button = Button.builder(Component.translatable("screenshotutilities.gallery.menu_button"),
                        b -> title.getMinecraft().setScreen(new GalleryScreen(title)))
                .bounds(MARGIN, MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        event.addListener(button);
    }
}
