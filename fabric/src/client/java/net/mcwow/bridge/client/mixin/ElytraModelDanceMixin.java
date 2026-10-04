package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowDance;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dancing player's elytra follows the dance's whole-body turn and shift (McwowDance.applyRoot). */
@Mixin(ElytraModel.class)
public abstract class ElytraModelDanceMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void mcwow$dance(HumanoidRenderState state, CallbackInfo ci) {
        McwowDance.applyRoot(((ElytraModel) (Object) this).root(), state);
    }
}
