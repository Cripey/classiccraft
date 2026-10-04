package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowInteract;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A right-click on a WoW NPC or object under the crosshair is WoW's (McwowInteract), not Minecraft's. */
@Mixin(Minecraft.class)
public abstract class MinecraftUseMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void mcwow$wowInteract(CallbackInfo ci) {
        if (McwowInteract.tryUse((Minecraft) (Object) this)) ci.cancel();
    }
}
