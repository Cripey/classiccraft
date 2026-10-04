package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowColumns;
import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mobs caught under WoW's ground, in their column's gap over its hidden top block (2026-10-02:
 * after a reload some stood under the terrain - landed on the top block while WoW's ground was
 * still streaming in) are set back on it (McwowColumns.buriedSurface).
 */
@Mixin(Mob.class)
public abstract class BuriedMobRescueMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    /** Ticks between checks, staggered by entity id. */
    private static final int PERIOD = 10;
    /** McwowWowShapes' sub-voxel size (blocks). */
    private static final double SUB_CELL = 1.0 / 8.0;

    @Inject(method = "tick", at = @At("TAIL"))
    private void mcwow$rescueBuried(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide() || self.isPassenger() || (self.tickCount + self.getId()) % PERIOD != 0
                || !McwowGeomStore.appliesTo(self.level())) {
            return;
        }
        double ground = McwowColumns.buriedSurface(self);
        if (Double.isNaN(ground)) return;
        LOGGER.info("mcwow-bridge: {} #{} buried at {} - set on WoW's ground at y {}",
                self.getType().toShortString(), self.getId(), self.position(), String.format("%.2f", ground));
        // Over the sub-voxel cells the mob stands on (McwowWowShapes, 1/8 block), which reach up to a
        // cell over the exact surface: set inside one, vanilla ignores the shape it already overlaps
        // and the mob fell straight back (2026-10-02, a horse re-buried twice a second for 7 s).
        self.setPos(self.getX(), ground + SUB_CELL + 0.01, self.getZ());
        self.setDeltaMovement(self.getDeltaMovement().multiply(1.0, 0.0, 1.0));
        self.resetFallDistance();
    }
}
