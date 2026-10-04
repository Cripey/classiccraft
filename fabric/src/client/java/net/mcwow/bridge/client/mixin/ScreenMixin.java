package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowOverlayLink;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** WoW never pauses, so neither does Minecraft while linked (SkyCraft's own). */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "isPauseScreen", at = @At("HEAD"), cancellable = true)
    private void mcwow$neverPauseWhileLinked(CallbackInfoReturnable<Boolean> cir) {
        if (McwowOverlayLink.linked()) cir.setReturnValue(false);
    }
}
