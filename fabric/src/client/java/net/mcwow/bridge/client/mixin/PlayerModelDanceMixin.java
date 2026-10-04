package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowDance;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dancing player's pose (McwowDance), over vanilla's own animation of the frame. */
@Mixin(PlayerModel.class)
public abstract class PlayerModelDanceMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void mcwow$dance(AvatarRenderState state, CallbackInfo ci) {
        McwowDance.apply((PlayerModel) (Object) this, state);
    }
}
