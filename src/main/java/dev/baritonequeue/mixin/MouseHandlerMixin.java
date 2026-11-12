package dev.baritonequeue.mixin;

import dev.baritonequeue.ImGuiManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
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

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void manhattan$onMove(long window, double xpos, double ypos, CallbackInfo ci) {
        if (ImGuiManager.onMouseMove(window, xpos, ypos)) {
            ci.cancel();
            return;
        }
        if (!this.mouseGrabbed && ImGuiManager.isDockedViewportActive()) {
            if (window != this.minecraft.getWindow().handle()) {
                ci.cancel();
                return;
            }
            if (!ImGuiManager.insideViewport(xpos, ypos) && this.activeButton == null) {
                ci.cancel();
                return;
            }
            double rx = ImGuiManager.remapMouseX(xpos);
            double ry = ImGuiManager.remapMouseY(ypos);
            if (this.ignoreFirstMove) {
                this.xpos = rx;
                this.ypos = ry;
                this.ignoreFirstMove = false;
            } else {
                if (this.minecraft.isWindowActive()) {
                    this.accumulatedDX += rx - this.xpos;
                    this.accumulatedDY += ry - this.ypos;
                }
                this.xpos = rx;
                this.ypos = ry;
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

    @Inject(method = "releaseMouse", at = @At("TAIL"))
    private void manhattan$onReleaseMouse(CallbackInfo ci) {
        if (ImGuiManager.isDockedViewportActive() && !"true".equals(System.getProperty("manhattan.tests"))) {
            GLFW.glfwSetCursorPos(this.minecraft.getWindow().handle(), ImGuiManager.viewportCenterX(), ImGuiManager.viewportCenterY());
        }
    }
}
