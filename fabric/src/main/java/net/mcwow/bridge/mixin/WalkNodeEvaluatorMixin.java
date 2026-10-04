package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ground mobs path over WoW's ground (see McwowPathing): WoW geometry in an air cell is BLOCKED or
 * WALKABLE instead of OPEN, and floor heights come from the WoW shapes. Everything else (neighbour
 * search, jump-up, drop-down, headroom via the collision boxes) is vanilla.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
    @Inject(method = "getPathTypeFromState", at = @At("HEAD"), cancellable = true)
    private static void mcwow$wowPathType(BlockGetter level, BlockPos pos, CallbackInfoReturnable<PathType> cir) {
        double top = McwowPathing.standHeight(level, pos);
        if (top > 0.0) {
            cir.setReturnValue(top > McwowPathing.STAND_IN_MAX ? PathType.BLOCKED : PathType.WALKABLE);
        }
    }

    @Inject(method = "getFloorLevel(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)D",
            at = @At("HEAD"), cancellable = true)
    private static void mcwow$wowFloorLevel(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Double> cir) {
        // Own cell first, BLOCKED ones too: a start node can be one (a mob standing right at the
        // threshold), and its floor must still be where the mob really stands, not a block lower.
        double own = McwowPathing.standHeight(level, pos);
        if (own > 0.0) {
            cir.setReturnValue(pos.getY() + own);
            return;
        }
        BlockPos below = pos.below();
        double under = McwowPathing.standHeight(level, below);
        if (under > 0.0) {
            cir.setReturnValue(below.getY() + under);
        }
    }
}
