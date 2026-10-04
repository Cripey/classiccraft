package net.mcwow.bridge.mixin;

import net.mcwow.bridge.combat.McwowCombat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ghost mode (McwowCombat.updateGhost): a spirit's melee hits nothing. */
@Mixin(Player.class)
public abstract class PlayerGhostMixin {
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void mcwow$ghostsDontFight(Entity target, CallbackInfo ci) {
        if (McwowCombat.ghost()) ci.cancel();
    }
}
