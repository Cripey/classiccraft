package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowFootsteps;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The local player's vanilla step on WoW's ground is McwowFootsteps' (the WoW surface's sound). */
@Mixin(Entity.class)
public abstract class EntityStepSoundMixin {
    @Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
    private void mcwow$wowGroundStep(BlockPos pos, BlockState state, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer player && McwowFootsteps.onWowGround(player)) ci.cancel();
    }
}
