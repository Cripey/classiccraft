package net.mcwow.bridge.client.mixin;

import com.mojang.blaze3d.platform.Window;
import net.mcwow.bridge.client.McwowInputBridge;
import net.mcwow.bridge.client.McwowOverlayLink;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft acts focused while WoW forwards input (so it grabs its virtual mouse and never opens
 * the pause-on-focus-loss menu), and never iconified while WoW reads the overlay - SkyCraft's own.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
    private void mcwow$focused(CallbackInfoReturnable<Boolean> cir) {
        if (McwowInputBridge.tookOver()) cir.setReturnValue(true);
    }

    @Inject(method = "isIconified", at = @At("HEAD"), cancellable = true)
    private void mcwow$notIconified(CallbackInfoReturnable<Boolean> cir) {
        if (McwowOverlayLink.linked()) cir.setReturnValue(false);
    }
}
