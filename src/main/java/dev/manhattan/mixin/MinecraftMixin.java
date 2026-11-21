package dev.manhattan.mixin;

import dev.manhattan.ImGuiManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the Manhattan Dear ImGui workspace/overlay into {@code gameRenderer.mainRenderTarget()}
 * immediately before {@code windowSurface.blitFromTexture(...)} presents the frame.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(
            method = "renderFrame",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"
            )
    )
    private void manhattan$renderImGui(boolean renderLevel, CallbackInfo ci) {
        ImGuiManager.renderFrame();
    }
}
