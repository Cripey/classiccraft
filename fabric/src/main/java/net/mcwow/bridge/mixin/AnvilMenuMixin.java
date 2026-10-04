package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Anvils cost only their materials in WoW map dimensions (user, 2026-10-04: "only the bars, no XP
 * cost") - the XP bar is the WoW level (McwowXp), not something to spend. Minecraft still works out
 * the cost (it decides whether there is anything to do: 0 = nothing), but no level is asked
 * (mayPickup), none is taken (onTake) and nothing is ever "Too Expensive!" (createResult's second
 * hasInfiniteMaterials, the 40-level cut; the first one is the creative book leniency and stays).
 * The repair bars, the sacrificed item or the book are used up as in vanilla.
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Shadow @Final private DataSlot cost;

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void mcwow$noLevelNeeded(Player player, boolean hasStack, CallbackInfoReturnable<Boolean> cir) {
        if (McwowGeomStore.appliesTo(player.level())) cir.setReturnValue(this.cost.get() > 0);
    }

    @Redirect(method = "onTake", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;giveExperienceLevels(I)V"))
    private void mcwow$noLevelsTaken(Player player, int levels) {
        if (!McwowGeomStore.appliesTo(player.level())) player.giveExperienceLevels(levels);
    }

    @Redirect(method = "createResult", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;hasInfiniteMaterials()Z", ordinal = 1))
    private boolean mcwow$neverTooExpensive(Player player) {
        return player.hasInfiniteMaterials() || McwowGeomStore.appliesTo(player.level());
    }
}
