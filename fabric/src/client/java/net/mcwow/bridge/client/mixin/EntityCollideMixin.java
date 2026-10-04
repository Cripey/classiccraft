package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowCollider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * After vanilla has collided the local player's movement with Minecraft blocks, refine it
 * against WoW's own live-sensed ground height - the same integration point chasmlol/SkyCraft
 * uses for its own (real-triangle-mesh-backed) smooth collision, confirmed identical in our own
 * 26.3 jar via javap before writing this (the user's explicit request to mimic it as closely as
 * possible).
 */
@Mixin(Entity.class)
public abstract class EntityCollideMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void mcwow$smoothWowCollision(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof LocalPlayer player && !player.noPhysics) {
            cir.setReturnValue(McwowCollider.collide(player, cir.getReturnValue()));
        }
    }
}
