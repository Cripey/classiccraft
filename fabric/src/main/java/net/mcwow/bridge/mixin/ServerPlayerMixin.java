package net.mcwow.bridge.mixin;

import net.mcwow.bridge.combat.McwowActorEntity;
import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.combat.McwowCombat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Minecraft critical hit on a WoW creature's stand-in is flagged so WoW can show a crit (SkyCraft's ServerPlayerMixin). */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "crit", at = @At("HEAD"))
    private void mcwow$critWow(Entity entity, CallbackInfo ci) {
        if (entity instanceof McwowActorEntity proxy) proxy.markCritical();
    }

    /** Dying in Minecraft is dying in WoW (user, 2026-10-01). */
    @Inject(method = "die", at = @At("HEAD"))
    private void mcwow$diesInWow(DamageSource source, CallbackInfo ci) {
        if (McwowGeomStore.activeDimension == null) return;
        McwowCombat.EVENTS.add(1);
        // Respawn where we died, in the WoW map's dimension (a forced respawn point, like
        // /spawnpoint): Minecraft's default world spawn is in the overworld, so respawning meant two
        // dimension changes (two terrain-loading screens) before WoW's graveyard teleport.
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.level().dimension() == McwowGeomStore.activeDimension) {
            self.setRespawnPosition(new ServerPlayer.RespawnConfig(
                    net.minecraft.world.level.storage.LevelData.RespawnData.of(self.level().dimension(), self.blockPosition(),
                            self.getYRot(), 0.0F), true), false);
        }
    }
}
