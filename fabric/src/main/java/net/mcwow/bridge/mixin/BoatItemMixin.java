package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Boats can be put down on WoW's ground (2026-10-02): placed where the crosshair meets the sloped,
 * stair-stepped WoW surface, the boat's box overlapped it and vanilla refused. It is lifted in
 * sixteenths, up to a block, to the first spot it fits, and settles from there.
 */
@Mixin(BoatItem.class)
public abstract class BoatItemMixin {
    private static final int STEPS = 16;

    @Redirect(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean mcwow$liftOntoWowGround(Level level, Entity boat, AABB box) {
        if (level.noCollision(boat, box)) return true;
        if (!McwowGeomStore.appliesTo(level)) return false;
        for (int k = 1; k <= STEPS; ++k) {
            double dy = (double) k / STEPS;
            if (level.noCollision(boat, box.move(0.0, dy, 0.0))) {
                boat.setPos(boat.getX(), boat.getY() + dy, boat.getZ());
                return true;
            }
        }
        return false;
    }
}
