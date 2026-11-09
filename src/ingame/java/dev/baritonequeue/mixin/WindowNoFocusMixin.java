package dev.baritonequeue.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test games only (this class lives in the test-bridge jar, which is installed only in test profiles, and acts only with
 * -Dmanhattan.tests=true): the window is created without taking the focus, so a test game never steals the user's
 * mouse or keyboard when it starts.
 */
@Mixin(Window.class)
public abstract class WindowNoFocusMixin {
    @Inject(method = "createGlfwWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void manhattan$noFocus(CallbackInfoReturnable<Long> cir) {
        if (!"true".equals(System.getProperty("manhattan.tests"))) return;
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
    }
}
