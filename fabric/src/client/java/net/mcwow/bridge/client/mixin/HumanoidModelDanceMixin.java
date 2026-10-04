package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowDance;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dancing player's armor (its own humanoid models) takes the dance's pose too. The player model
 * itself is PlayerModelDanceMixin's: it calls this setupAnim as its super, and the root's turn
 * would land twice.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelDanceMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void mcwow$dance(HumanoidRenderState state, CallbackInfo ci) {
        if (!((Object) this instanceof PlayerModel)) McwowDance.apply((HumanoidModel<?>) (Object) this, state);
    }
}
