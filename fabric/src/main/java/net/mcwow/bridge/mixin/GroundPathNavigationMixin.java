package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Path targets on WoW's ground (2026-10-02: wolves following the player and zombies chasing it stood
 * still at the foot of slopes). Every entity-targeted ground path (follow owner, chase, melee) goes
 * through findSurfacePosition, which moves an AIR target down to the first non-air block - and in a
 * void world with nothing below, UP to the build limit (y=1536): the mob then paths at the sky, its
 * best path is the cell it stands in, and it never moves. An air target on WoW's ground snaps onto
 * that ground instead (McwowPathing, as RandomPosMixin does for walk targets); a real Minecraft block
 * above the WoW surface still wins, as vanilla would find it.
 */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin {
    private static final int SEARCH = 8; // blocks up/down, as RandomPosMixin

    @Inject(method = "findSurfacePosition", at = @At("HEAD"), cancellable = true)
    private void mcwow$surfaceOnWow(LevelChunk chunk, BlockPos pos, int accuracy, CallbackInfoReturnable<BlockPos> cir) {
        Level level = chunk.getLevel();
        if (!McwowGeomStore.appliesTo(level) || !chunk.getBlockState(pos).isAir()) return;
        if (McwowPathing.standsOnWow(level, pos)) {
            cir.setReturnValue(pos);
            return;
        }
        BlockPos snapped = McwowPathing.snapToWowGround(level, pos, SEARCH);
        if (snapped == pos) return;
        // A Minecraft block between the target and the WoW surface is the ground vanilla would pick.
        for (BlockPos.MutableBlockPos c = pos.mutable(); c.getY() >= snapped.getY(); c.move(0, -1, 0)) {
            if (!chunk.getBlockState(c).isAir()) return;
        }
        cir.setReturnValue(snapped);
    }
}
