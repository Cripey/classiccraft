package net.mcwow.bridge.client.mixin;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.combat.McwowActors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The first-person hand and held item shaded from WoW's sun (2026-10-02): the hand pass draws with
 * the level's two directional lights, fixed to one side of Minecraft's world, so the item was lit
 * from opposite the sun WoW shows. In a WoW map (where Minecraft draws no level, so nothing else
 * uses them) the first light points at WoW's sun (published by benilla with the hand's light colour,
 * LightmapMixin) and the second straight up, a soft sky fill; bound for the hand pass, as the last
 * lighting bound may be the GUI's.
 */
@Mixin(GameRenderer.class)
public abstract class HandSunMixin {
    private static final Vector3fc SKY_FILL = new Vector3f(0.0F, 0.35F, 0.0F);

    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void mcwow$wowSun(CameraRenderState camera, PlayerRenderState player, GpuTextureView target, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !McwowGeomStore.appliesTo(mc.level)) return;
        McwowActors.Me me = McwowActors.readMe();
        if (me == null || !me.hasLight()) return;
        Vector3f sun = octDecode(me.lightAmbient() >>> 24, me.lightSun() >>> 24);
        Lighting lighting = ((GameRenderer) (Object) this).lighting();
        ((LightingAccessor) lighting).mcwow$updateBuffer(Lighting.Entry.LEVEL, sun, SKY_FILL);
        lighting.setupFor(Lighting.Entry.LEVEL);
    }

    /** benilla combat.rs oct_encode: a unit vector from two bytes (octahedral, y up). */
    private static Vector3f octDecode(int bx, int bz) {
        float px = bx / 255.0F * 2.0F - 1.0F, pz = bz / 255.0F * 2.0F - 1.0F;
        float y = 1.0F - Math.abs(px) - Math.abs(pz);
        if (y < 0.0F) {
            float ax = Math.abs(px), az = Math.abs(pz);
            px = (1.0F - az) * Math.signum(px);
            pz = (1.0F - ax) * Math.signum(pz);
        }
        return new Vector3f(px, y, pz).normalize();
    }
}
