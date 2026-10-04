package net.mcwow.bridge.mixin;

import net.mcwow.bridge.combat.McwowCombat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player dying in a WoW map keeps their items (user, 2026-10-04): nothing is dropped here, and
 * McwowCombat's COPY_FROM hands the inventory to the respawned player (keepInventory, per map).
 */
@Mixin(Player.class)
public abstract class PlayerKeepInventoryMixin {
    @Inject(method = "dropEquipment", at = @At("HEAD"), cancellable = true)
    private void mcwow$keepInWow(ServerLevel level, CallbackInfo ci) {
        if (McwowCombat.keepsInventory((Player) (Object) this)) ci.cancel();
    }
}
