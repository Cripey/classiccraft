package net.mcwow.bridge.mixin;

import net.mcwow.bridge.combat.McwowCombat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Knockback from a mirrored WoW hit (2026-10-01): the server only has a stale copy of a player's
 * velocity, so vanilla's knockback there flattened mid-air jumps ("forces me back to the ground").
 * Captured here and applied on the client with the player's real velocity (McwowCombat.clientKnockback).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityKnockbackMixin {
    @Inject(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V", at = @At("HEAD"), cancellable = true)
    private void mcwow$clientKnockback(double power, double xd, double zd, DamageSource source, float damage,
                                       boolean comesFromEffect, CallbackInfo ci) {
        if (McwowCombat.mirroring && (Object) this instanceof ServerPlayer) {
            McwowCombat.capturedKnockback = new double[] {power, xd, zd};
            ci.cancel();
        }
    }
}
