package dev.baritonequeue.mixin;

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.baritonequeue.ImGuiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Inject(method = "flipFrame", at = @At("HEAD"))
    private static void manhattan$renderImGui(TracyFrameCapture tracyFrameCapture, CallbackInfo ci) {
        ImGuiManager.renderFrame();
    }
}
