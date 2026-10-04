package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGear;
import net.minecraft.world.inventory.ArmorSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Armor above the WoW character's level can't be put on (McwowGear, 2026-10-03). */
@Mixin(ArmorSlot.class)
abstract class ArmorSlotMixin {
    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void mcwow$levelGate(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!McwowGear.usable(stack)) cir.setReturnValue(false);
    }
}
