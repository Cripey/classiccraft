package net.mcwow.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A ridden horse on WoW's slopes (2026-10-02: its steps stalled on every slope, fine on the flat).
 * The rider's client moves the vehicle and the server replays each move without gravity
 * (handleMoveVehicle). Vanilla updates onGround only for a move with vertical motion, and then
 * only when that move hits something below; the client already set the vehicle down on the
 * ground, so nothing is hit. Vanilla ground only rises in whole steps, so this lasts a tick
 * there; on WoW's slopes every tick climbs or descends, and the server saw the horse airborne the
 * whole way, so the step gate (onGround) stayed shut. Here such a vehicle with ground just under
 * its feet is on the ground.
 */
@Mixin(Entity.class)
public abstract class RiddenVehicleGroundMixin {
    /** How far under the feet ground still counts (blocks): a slope's 1/8 sub-voxel steps and a tick's descent. */
    private static final double PROBE = 0.2;

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZZLnet/minecraft/world/phys/Vec3;)V"))
    private void mcwow$riddenOnGround(Entity self, boolean onGround, boolean horizontalCollision, Vec3 movement,
            Operation<Void> original, MoverType type, Vec3 requested) {
        if (!onGround && type == MoverType.PLAYER && !self.level().isClientSide()
                && self.getControllingPassenger() instanceof Player && movement.y != 0.0
                && McwowGeomStore.appliesTo(self.level())) {
            onGround = !self.level().noCollision(self, self.getBoundingBox().move(0.0, -PROBE, 0.0));
        }
        original.call(self, onGround, horizontalCollision, movement);
    }
}
