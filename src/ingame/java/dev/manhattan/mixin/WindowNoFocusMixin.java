package dev.manhattan.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder for MC 26.3+ (SDL3 windowing replaced GLFW createGlfwWindow).
 */
@Mixin(Window.class)
public abstract class WindowNoFocusMixin {
}
