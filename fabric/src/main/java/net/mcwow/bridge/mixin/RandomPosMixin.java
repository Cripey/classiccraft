package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.RandomPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Random walk targets (strolling, panicking, fleeing - every Land/DefaultRandomPos picker goes
 * through here) land on WoW's ground in their column instead of at a random height (see
 * McwowPathing.snapToWowGround). Ground navigators only - flyers and swimmers pick 3D targets.
 */
@Mixin(RandomPos.class)
public abstract class RandomPosMixin {
    private static final int SEARCH = 8; // blocks up/down; vanilla's vertical range is +-7

    @Inject(method = "generateRandomPosTowardDirection", at = @At("RETURN"), cancellable = true)
    private static void mcwow$snapToWow(PathfinderMob mob, double xzDist, RandomSource random, BlockPos direction,
            CallbackInfoReturnable<BlockPos> cir) {
        BlockPos pos = cir.getReturnValue();
        if (pos == null || !mob.getNavigation().canNavigateGround()) return;
        BlockPos snapped = McwowPathing.snapToWowGround(mob.level(), pos, SEARCH);
        if (snapped != pos) cir.setReturnValue(snapped);
    }
}
