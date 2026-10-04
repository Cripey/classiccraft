package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A walk target on WoW's ground is a stable destination (vanilla wants a solid block below). */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
    @Shadow @Final protected Level level;

    @Inject(method = "isStableDestination", at = @At("HEAD"), cancellable = true)
    private void mcwow$stableOnWow(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (McwowPathing.standsOnWow(this.level, pos)) {
            cir.setReturnValue(true);
        }
    }
}
