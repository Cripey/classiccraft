package net.mcwow.bridge.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.mcwow.bridge.client.McwowInputBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While WoW forwards input: virtual keyboard state, and never grab/release the real mouse (SkyCraft's). */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void mcwow$isKeyDown(int key, CallbackInfoReturnable<Boolean> cir) {
        if (McwowInputBridge.tookOver()) cir.setReturnValue(McwowInputBridge.isKeyDown(key));
    }

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private static void mcwow$grabMouse(Window window, double xpos, double ypos, CallbackInfo ci) {
        if (McwowInputBridge.tookOver()) ci.cancel();
    }

    @Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
    private static void mcwow$releaseMouse(Window window, double xpos, double ypos, CallbackInfo ci) {
        if (McwowInputBridge.tookOver()) ci.cancel();
    }
}
