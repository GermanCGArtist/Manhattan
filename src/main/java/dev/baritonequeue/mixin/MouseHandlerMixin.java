package dev.baritonequeue.mixin;

import dev.baritonequeue.ImGuiManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private boolean mouseGrabbed;
    @Shadow private boolean ignoreFirstMove;
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Shadow private MouseButtonInfo activeButton;

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void manhattan$onGrabMouse(CallbackInfo ci) {
        if (ImGuiManager.isActive()) {
            this.mouseGrabbed = false;
            ci.cancel();
        }
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void manhattan$onTurnPlayer(double movementTime, CallbackInfo ci) {
        if (ImGuiManager.isActive()) {
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            ci.cancel();
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void manhattan$onMove(long window, double xpos, double ypos, CallbackInfo ci) {
        if (ImGuiManager.onMouseMove(window, xpos, ypos)) {
            ci.cancel();
            return;
        }
        if (!this.mouseGrabbed && ImGuiManager.isDockedViewportActive()) {
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            if (this.minecraft.screen != null || "true".equals(System.getProperty("manhattan.tests"))) {
                this.xpos = ImGuiManager.remapMouseX(xpos);
                this.ypos = ImGuiManager.remapMouseY(ypos);
            }
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void manhattan$onButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (ImGuiManager.onMouseButton(window, buttonInfo.button(), action, buttonInfo.modifiers())) {
            ci.cancel();
            return;
        }
        if (!this.mouseGrabbed && ImGuiManager.isDockedViewportActive()) {
            if (this.minecraft.screen == null && !"true".equals(System.getProperty("manhattan.tests"))) {
                ci.cancel();
                return;
            }
            this.xpos = ImGuiManager.remapMouseX(ImGuiManager.lastMouseX());
            this.ypos = ImGuiManager.remapMouseY(ImGuiManager.lastMouseY());
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void manhattan$onScroll(long window, double xoffset, double yoffset, CallbackInfo ci) {
        if (ImGuiManager.onMouseScroll(window, xoffset, yoffset)) {
            ci.cancel();
        }
    }
}
