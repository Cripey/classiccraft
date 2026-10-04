package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.combat.McwowActors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WoW's light on Minecraft's first-person hand and held item (2026-10-02, user's choice): Minecraft
 * draws them itself (into the overlay), lit by its lightmap - ambient + sky colour x sky level +
 * torch light. In a WoW map that lightmap takes WoW's light at the camera, which benilla publishes
 * (actors header: ambient with WoW's nearby point lights, and the sun): the hand darkens at night
 * and warms by a lantern like the WoW world around it. Minecraft's torches still add their own.
 * Minecraft draws no world of its own there, so nothing else uses this lightmap.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapMixin {
    @Inject(method = "extract", at = @At("RETURN"))
    private void mcwow$wowLight(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !McwowGeomStore.appliesTo(mc.level)) return;
        McwowActors.Me me = McwowActors.readMe();
        if (me == null || !me.hasLight()) return;
        state.ambientColor = unpack(me.lightAmbient());
        state.skyLightColor = unpack(me.lightSun());
        state.skyFactor = 1.0F;
        state.needsUpdate = true;
    }

    /** RGB8 over 0..2 (benilla combat.rs pack_light). */
    private static Vector3f unpack(int c) {
        return new Vector3f((c & 0xFF) / 127.5F, ((c >> 8) & 0xFF) / 127.5F, ((c >> 16) & 0xFF) / 127.5F);
    }
}
