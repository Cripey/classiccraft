package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGear;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Crafted gear takes the item level of the material in the grid (McwowGear, 2026-10-03). */
@Mixin(ShapedRecipe.class)
abstract class ShapedRecipeMixin {
    @Inject(method = "assemble(Lnet/minecraft/world/item/crafting/CraftingInput;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"))
    private void mcwow$stampGear(CraftingInput input, CallbackInfoReturnable<ItemStack> cir) {
        McwowGear.stampCrafted(cir.getReturnValue(), input);
    }
}
