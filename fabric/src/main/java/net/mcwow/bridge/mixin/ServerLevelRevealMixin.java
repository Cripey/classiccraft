package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGroundReveal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change on the server, after vanilla's neighbour updates (Level.setBlock ends with
 * this call): ground placed where it's needed (McwowGroundReveal) - a dug block shows what's behind it.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRevealMixin {
    @Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"))
    private void mcwow$revealGround(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        McwowGroundReveal.changed((ServerLevel) (Object) this, pos, newState);
    }
}
