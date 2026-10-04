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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Minecraft critical hit on a WoW creature's stand-in is flagged so WoW can show a crit (SkyCraft's
 * ServerPlayerMixin); dying and respawning in a WoW map.
 */
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
    }

    /**
     * Respawning after a death in a WoW map (user, 2026-10-04): at the player's bed when it is in a
     * WoW map dimension, the WoW character resurrected there; otherwise right where Steve died (no
     * trip through the overworld) while the server resurrects the WoW character at its hearthstone
     * location and the placement brings Steve there. Forced respawn points (/spawnpoint, and the
     * death spots this mixin used to store) don't count as a bed.
     */
    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"), cancellable = true)
    private void mcwow$respawnInWow(boolean consumeSpawnBlock,
            net.minecraft.world.level.portal.TeleportTransition.PostTeleportTransition post,
            CallbackInfoReturnable<net.minecraft.world.level.portal.TeleportTransition> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!self.isDeadOrDying() || !McwowGeomStore.appliesTo(self.level())) return;
        var vanilla = cir.getReturnValue();
        ServerPlayer.RespawnConfig config = self.getRespawnConfig();
        int bedMap = config == null || config.forced() || vanilla.missingRespawnBlock()
                ? -1 : McwowCombat.wowMapOf(vanilla.newLevel().dimension());
        if (bedMap >= 0) {
            McwowCombat.queueRespawn(bedMap, vanilla.position(), vanilla.yRot());
            return;
        }
        McwowCombat.queueRespawn(-1, null, 0.0F);
        cir.setReturnValue(new net.minecraft.world.level.portal.TeleportTransition((net.minecraft.server.level.ServerLevel) self.level(),
                self.position(), net.minecraft.world.phys.Vec3.ZERO, self.getYRot(), 0.0F, post));
    }
}
