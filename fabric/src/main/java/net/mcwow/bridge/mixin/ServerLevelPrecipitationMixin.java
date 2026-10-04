package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No snow layers or ice from Minecraft's weather in a WoW map dimension (2026-10-02). Filled ground
 * now has real biomes (snowy plains in the snow, for mob spawning), and Minecraft's snowfall piled
 * snow layers onto the top blocks hidden under WoW's ground - an eighth of a block, enough to poke
 * through it wherever the gap is thinner - more and more over time. WoW has its own weather.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPrecipitationMixin {
    @Inject(method = "tickPrecipitation", at = @At("HEAD"), cancellable = true)
    private void mcwow$noWeatherBlocks(BlockPos pos, CallbackInfo ci) {
        if (McwowGeomStore.appliesTo(this)) ci.cancel();
    }
}
