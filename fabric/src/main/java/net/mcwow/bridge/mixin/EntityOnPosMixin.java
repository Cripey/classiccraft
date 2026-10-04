package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowColumns;
import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowTerrainFill;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The block "under" an entity standing on WoW's ground (2026-10-02: horses and mobs made no steps):
 * vanilla's step sounds, sprint and landing particles and fall handling read the block below the
 * feet (getOnPosLegacy, and getOnPos for the step's own gate), which on WoW's ground is the air of
 * the gap over the column's hidden top
 * block. There it is that top block, which the terrain fill made like the WoW ground (grass, sand,
 * snow, stone...). The player's own steps are McwowFootsteps' (they also know building floors).
 */
@Mixin(Entity.class)
public abstract class EntityOnPosMixin {
    /**
     * Blocks the hidden top block may lie under the feet: it sits under the column's LOWEST WoW
     * ground, so on a slope the uphill side stands up to a few blocks over it (2 went silent there,
     * and a horse on a steep Dun Morogh slope stood 5 over it).
     */
    private static final int REACH = 6;
    /**
     * How far (blocks) to look for a column with ground when the one underfoot has none: WoW cuts
     * the terrain away under a building, so a mob on its porch or apron stands over empty columns
     * (2026-10-02, a horse silent on the Kharanos blacksmith's stone apron). The nearest ground's
     * block sounds instead.
     */
    private static final int NEIGHBOUR_REACH = 8;

    @Inject(method = "getOnPosLegacy", at = @At("RETURN"), cancellable = true)
    private void mcwow$wowGroundBlock(CallbackInfoReturnable<BlockPos> cir) {
        mcwow$redirect(cir);
    }

    // The step sound's own gate (applyMovementEmissionAndPlaySound) reads this one.
    @Inject(method = "getOnPos()Lnet/minecraft/core/BlockPos;", at = @At("RETURN"), cancellable = true)
    private void mcwow$wowGroundBlockStep(CallbackInfoReturnable<BlockPos> cir) {
        mcwow$redirect(cir);
    }

    private void mcwow$redirect(CallbackInfoReturnable<BlockPos> cir) {
        Entity self = (Entity) (Object) this;
        if (!self.onGround() || !McwowGeomStore.appliesTo(self.level())) return;
        BlockPos pos = cir.getReturnValue();
        if (!self.level().getBlockState(pos).isAir()) return;
        int top = McwowColumns.topOf(self.level(), pos.getX(), pos.getZ());
        if (top == McwowTerrainFill.NO_TOP) {
            BlockPos near = mcwow$nearestGround(self, pos);
            if (near != null) cir.setReturnValue(near);
            return;
        }
        if (top >= pos.getY() || pos.getY() - top > REACH) return;
        cir.setReturnValue(new BlockPos(pos.getX(), top, pos.getZ()));
    }

    /** The top block of the nearest column with ground around pos (ring by ring), or null. */
    private static BlockPos mcwow$nearestGround(Entity self, BlockPos pos) {
        for (int r = 1; r <= NEIGHBOUR_REACH; ++r) {
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; ++dx) {
                for (int dz = -r; dz <= r; ++dz) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = pos.getX() + dx, z = pos.getZ() + dz;
                    int top = McwowColumns.topOf(self.level(), x, z);
                    if (top == McwowTerrainFill.NO_TOP || top >= pos.getY() + REACH || pos.getY() - top > 2 * REACH) {
                        continue;
                    }
                    double dist = dx * dx + dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new BlockPos(x, top, z);
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }
}
