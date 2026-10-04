package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.client.Camera;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The third-person camera stops at WoW's terrain and objects as it does at blocks (2026-10-04,
 * user). Vanilla's getMaxZoom pulls the camera in along eight rays from the eye, each corner of a
 * 0.2-block box, against blocks only; the same eight rays here meet WoW's geometry too
 * (McwowGeomStore.clip: terrain, buildings, doodad hulls, transport decks) and the walls of dug holes
 * (McwowTargeting.skirtDistance), and the nearest wins.
 * benilla renders from this camera position, so WoW's view follows.
 */
@Mixin(Camera.class)
abstract class CameraZoomMixin {
    @Shadow private Level level;
    @Shadow private Vec3 position;
    @Shadow @Final private Vector3f forwards;

    @Inject(method = "getMaxZoom", at = @At("RETURN"), cancellable = true)
    private void mcwow$wowZoom(float maxZoom, CallbackInfoReturnable<Float> cir) {
        if (!McwowGeomStore.appliesTo(level)) return;
        float zoom = cir.getReturnValueF();
        double dx = -forwards.x(), dy = -forwards.y(), dz = -forwards.z();
        for (int i = 0; i < 8; i++) {
            double ox = ((i & 1) * 2 - 1) * 0.1, oy = ((i >> 1 & 1) * 2 - 1) * 0.1, oz = ((i >> 2 & 1) * 2 - 1) * 0.1;
            double d = McwowGeomStore.clip(position.x + ox, position.y + oy, position.z + oz, dx, dy, dz, zoom);
            // ... and the walls of dug holes (drawn between the top blocks and WoW's ground, not geometry).
            d = net.mcwow.bridge.client.McwowTargeting.skirtDistance(level,
                    new Vec3(position.x + ox, position.y + oy, position.z + oz), new Vec3(dx, dy, dz), d);
            if (d < zoom) zoom = (float) d;
        }
        cir.setReturnValue(zoom);
    }
}
