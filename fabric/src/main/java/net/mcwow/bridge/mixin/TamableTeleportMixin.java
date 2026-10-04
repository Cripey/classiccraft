package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowTriHeight;
import net.minecraft.world.entity.TamableAnimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A pet's follow-teleport lands on WoW's real ground (classiccraft, 2026-10-02). Vanilla picks a
 * whole-block height beside the owner, but WoW's ground is a continuous surface, so the pet could
 * appear above a slope and drop - and it kept its fall distance: wolves took fall damage after
 * a 20-yard teleport across open ground. Snapped to the walkable triangle under the chosen spot
 * (within a few blocks) and the fall cleared, as the player's placements do.
 */
@Mixin(TamableAnimal.class)
public abstract class TamableTeleportMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    /** How far the chosen height may sit from WoW's ground and still be snapped to it (blocks). */
    private static final double SNAP_RANGE = 4.0;

    @Inject(method = "maybeTeleportTo", at = @At("RETURN"))
    private void mcwow$landOnWowGround(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        TamableAnimal self = (TamableAnimal) (Object) this;
        double fell = self.fallDistance;
        self.resetFallDistance();
        if (!McwowGeomStore.appliesTo(self.level())) return;
        double lx = self.getX() - McwowGeomStore.regionOffsetX;
        double lz = self.getZ() - McwowGeomStore.regionOffsetZ;
        double ground = McwowTriHeight.heightAt(lx, lz, self.getY() + 1.0);
        if (!Double.isNaN(ground) && Math.abs(ground - self.getY()) <= SNAP_RANGE) {
            self.setPos(self.getX(), ground, self.getZ());
        }
        LOGGER.info("mcwow-bridge: {} teleported to its owner at y {} (WoW ground {}, fall distance {} cleared)",
                self.getType().toShortString(), String.format("%.2f", (double) y),
                Double.isNaN(ground) ? "unknown" : String.format("%.2f", ground), String.format("%.2f", fell));
    }
}
