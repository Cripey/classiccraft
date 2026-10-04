package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowColumns;
import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WoW's water surface, exactly (outdoor water, 2026-10-02): the top water block of a WoW lake or
 * river column reports the WoW surface's height in it instead of a source block's 8/9, so swimming,
 * boats, the eye's underwater check and floating items all meet the water WoW draws (a boat sat up
 * to half a block under it). Every fluid height query - entities, boats, the camera, the fluid's
 * box - goes through FlowingFluid.getHeight. Only where the block above holds no water of its own.
 */
@Mixin(FlowingFluid.class)
public abstract class FluidHeightMixin {
    @Inject(method = "getHeight(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
            at = @At("HEAD"), cancellable = true)
    private void mcwow$wowSurface(FluidState fluid, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (!fluid.isSource() || !McwowGeomStore.appliesTo(level)) return;
        float height = McwowColumns.wowWaterHeight(level, pos);
        if (Float.isNaN(height) || fluid.getType().isSame(level.getFluidState(pos.above()).getType())) return;
        cir.setReturnValue(height);
    }
}
