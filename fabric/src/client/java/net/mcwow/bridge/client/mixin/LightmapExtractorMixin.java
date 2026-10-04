package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowRenderLink;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's lightmap from WoW's light (2026-10-01). The first-person hand and held item are the
 * only world-lit things Minecraft still draws itself (in the overlay); everything else in the
 * world is drawn by mcwow.dll with WoW's sun and ambient colours. This makes the hand match:
 * the lightmap shader computes ambient + skyLightColor * curve(sky) + block light, so with WoW's
 * ambient A and sun S it mirrors the DLL's block shader (A * lerp(0.3, 1, sky) + S * sky^2 *
 * facing, facing averaged to 0.5). Minecraft's brightness boost is dropped so it doesn't
 * brighten the hand beyond what WoW's own models get.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapExtractorMixin {
    @Inject(method = "extract", at = @At("RETURN"))
    private void mcwow$wowLight(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        if (!McwowRenderLink.active()) return;
        float[] l = McwowRenderLink.wowLight();
        if (l == null) return;
        state.ambientColor = new Vector3f(0.3F * l[0], 0.3F * l[1], 0.3F * l[2]);
        state.skyLightColor = new Vector3f(0.7F * l[0] + 0.5F * l[3], 0.7F * l[1] + 0.5F * l[4], 0.7F * l[2] + 0.5F * l[5]);
        state.skyFactor = 1.0F;
        state.brightness = 0.0F;
        state.needsUpdate = true;
    }
}
