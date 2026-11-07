package com.screenshotutilities.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.screenshotutilities.capture.CaptureController;
import java.io.File;
import java.util.function.Consumer;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Marks the vanilla F2 grab so the screenshot listener can tell it apart from grabs made by other mods' code.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @WrapOperation(
            method = "keyPress",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Screenshot;grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V"))
    private void screenshotutilities$markPlayerGrab(File gameDirectory, RenderTarget target, Consumer<Component> messages, Operation<Void> original) {
        Consumer<Component> filtered = message -> {
            if (message != CaptureController.HANDLED) {
                messages.accept(message);
            }
        };
        CaptureController.setPlayerKeyPress(true);
        try {
            original.call(gameDirectory, target, filtered);
        } finally {
            CaptureController.setPlayerKeyPress(false);
        }
    }
}
