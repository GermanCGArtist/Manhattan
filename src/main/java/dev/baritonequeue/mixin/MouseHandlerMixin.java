package dev.baritonequeue.mixin;

import dev.baritonequeue.ImGuiManager;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void manhattan$onMove(long window, double xpos, double ypos, CallbackInfo ci) {
        if (ImGuiManager.onMouseMove(window, xpos, ypos)) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void manhattan$onButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (ImGuiManager.onMouseButton(window, buttonInfo.button(), action, buttonInfo.modifiers())) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void manhattan$onScroll(long window, double xoffset, double yoffset, CallbackInfo ci) {
        if (ImGuiManager.onMouseScroll(window, xoffset, yoffset)) {
            ci.cancel();
        }
    }
}
