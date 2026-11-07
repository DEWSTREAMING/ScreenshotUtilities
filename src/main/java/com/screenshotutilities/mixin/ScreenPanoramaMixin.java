package com.screenshotutilities.mixin;

import com.screenshotutilities.client.SlideshowManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the slideshow over vanilla's panorama. The panorama is still drawn first, so when the slideshow has nothing to show
 * the screen is exactly vanilla.
 */
@Mixin(Screen.class)
public abstract class ScreenPanoramaMixin {
    @Inject(method = "renderPanorama", at = @At("TAIL"), require = 0)
    private void screenshotutilities$slideshow(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        SlideshowManager.get().render((Screen) (Object) this, graphics);
    }

    @Inject(method = "renderBlurredBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void screenshotutilities$skipBlur(float partialTick, CallbackInfo ci) {
        if (SlideshowManager.get().keepsPictureSharp((Screen) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void screenshotutilities$skipMenuBackground(GuiGraphics graphics, CallbackInfo ci) {
        if (SlideshowManager.get().keepsPictureSharp((Screen) (Object) this)) {
            ci.cancel();
        }
    }
}
