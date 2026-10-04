package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowGather;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While the attack button gathers a WoW object (McwowGather), Minecraft doesn't attack or mine. */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void mcwow$gatherStart(CallbackInfoReturnable<Boolean> cir) {
        if (McwowGather.active()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void mcwow$gatherHold(boolean held, CallbackInfo ci) {
        if (McwowGather.active()) ci.cancel();
    }
}
