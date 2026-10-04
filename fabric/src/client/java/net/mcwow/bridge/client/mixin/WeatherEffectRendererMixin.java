package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowOverlayLink;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nothing of this belongs over WoW's picture while WoW reads the overlay (see LevelRendererMixin). */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
    @Inject(method = {"render", "renderOit"}, at = @At("HEAD"), cancellable = true)
    private void mcwow$skip(CallbackInfo ci) {
        if (McwowOverlayLink.linked()) ci.cancel();
    }
}
