package dev.manhattan.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A test game never takes the user's mouse. Releasing it a tick after the game grabbed it (what TestBridge did) still
 * caught the cursor for a moment every time a screen closed or the window was clicked, and with 8 to 32 games running
 * that made the PC unusable. The bots aim through code, so nothing in a test needs the grab. Only in the test bridge
 * jar, which only test profiles load.
 */
@Mixin(MouseHandler.class)
public abstract class MouseNoGrabMixin {
    private static boolean manhattanTests$told;

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void manhattanTests$neverGrab(CallbackInfo ci) {
        if (!manhattanTests$told) {
            manhattanTests$told = true;
            System.out.println("[testbridge] mouse grab blocked (test games never take the mouse)");
        }
        ci.cancel();
    }
}
