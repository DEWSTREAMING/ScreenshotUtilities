package com.screenshotutilities.mixin;

import com.screenshotutilities.client.SlideshowManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The title screen has its own {@code renderPanorama}, so it needs its own hook. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenPanoramaMixin {
    @Inject(method = "renderPanorama", at = @At("TAIL"), require = 0)
    private void screenshotutilities$slideshow(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        SlideshowManager.get().render((Screen) (Object) this, graphics);
    }
}
