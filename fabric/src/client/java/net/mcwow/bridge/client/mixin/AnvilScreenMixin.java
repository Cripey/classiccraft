package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * No "Enchantment Cost: N" / "Too Expensive!" label on the anvil in WoW map dimensions: it costs
 * no levels there (AnvilMenuMixin).
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {
    @Redirect(method = "extractLabels", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AnvilMenu;getCost()I"))
    private int mcwow$noCostLabel(AnvilMenu menu) {
        var p = Minecraft.getInstance().player;
        return p != null && McwowGeomStore.appliesTo(p.level()) ? 0 : menu.getCost();
    }
}
