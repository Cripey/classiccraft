package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Steve's Minecraft level is the WoW character's in a WoW map dimension (McwowXp.mirrorLevel,
 * 2026-10-02): no XP dropped on death (it never drops), and vanilla XP (ore, smelting, mobs,
 * bottles) gives nothing - not even the moment before the mirror would undo it (a level-up chime).
 * A WoW kill's orb claims its WoW XP before this (ExperienceOrbMixin).
 */
@Mixin(Player.class)
public abstract class PlayerXpMixin {
    @Inject(method = "getBaseExperienceReward", at = @At("HEAD"), cancellable = true)
    private void mcwow$noDeathXp(ServerLevel level, CallbackInfoReturnable<Integer> cir) {
        if (McwowGeomStore.appliesTo(level)) cir.setReturnValue(0);
    }

    @Inject(method = "giveExperiencePoints", at = @At("HEAD"), cancellable = true)
    private void mcwow$noVanillaXp(int amount, CallbackInfo ci) {
        if (McwowGeomStore.appliesTo(((Player) (Object) this).level())) ci.cancel();
    }
}
