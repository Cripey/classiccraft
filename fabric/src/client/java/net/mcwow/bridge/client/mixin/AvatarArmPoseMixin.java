package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.McwowGuns;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guns are aimed, not carried (user, 2026-10-04: in third person Steve pointed the rifle at the
 * ground, his arm as with a sword): a rifle or blunderbuss in hand takes Minecraft's loaded-crossbow
 * pose, both arms forward. Its third-person model runs the barrel along the arm
 * (gen_mod_data.py GUN_DISPLAY), which is forward in this pose. A swing keeps vanilla's pose.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarArmPoseMixin {
    @Inject(method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
            at = @At("HEAD"), cancellable = true)
    private static void mcwow$aimGuns(Avatar avatar, ItemStack stack, InteractionHand hand,
                                      CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
        if (stack.getItem() instanceof McwowGuns.GunItem && !avatar.isSwinging()) {
            cir.setReturnValue(HumanoidModel.ArmPose.CROSSBOW_HOLD);
        }
    }
}
