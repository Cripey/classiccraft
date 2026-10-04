package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGear;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Right-click equipping obeys the required level too (McwowGear, 2026-10-03). */
@Mixin(Equippable.class)
abstract class EquippableMixin {
    @Inject(method = "swapWithEquipmentSlot", at = @At("HEAD"), cancellable = true)
    private void mcwow$levelGate(ItemStack stack, Player player, CallbackInfoReturnable<InteractionResult> cir) {
        if (McwowGear.usable(stack)) return;
        McwowGear.Gear g = McwowGear.of(stack);
        if (g != null && !player.level().isClientSide()) {
            player.sendOverlayMessage(Component.literal("Requires level " + g.req()));
        }
        cir.setReturnValue(InteractionResult.FAIL);
    }
}
