package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowTriHeight;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sneaking on WoW ground (2026-10-01). Minecraft's sneak edge-protection looks for block collision
 * under the player; players walk on WoW's exact triangles (not blocks - BlockCollisionsMixin gives
 * them no voxels), so every direction looked like a drop and crouching froze the player in place.
 * SkyCraft's PlayerEdgeMixin simply turns the check off; here it stays, and only when vanilla backs
 * off is WoW asked too: if WoW's walkable ground holds up the destination footprint (within the
 * step height below the feet), the move is allowed - so sneaking still stops at real WoW drop-offs.
 */
@Mixin(Player.class)
public abstract class PlayerEdgeMixin {
    @Inject(method = "maybeBackOffFromEdge", at = @At("RETURN"), cancellable = true)
    private void mcwow$wowGroundIsGround(Vec3 delta, MoverType moverType, CallbackInfoReturnable<Vec3> cir) {
        Vec3 vanilla = cir.getReturnValue();
        if (vanilla.equals(delta)) return;
        Player self = (Player) (Object) this;
        if (!McwowGeomStore.appliesTo(self.level())) return;
        // Region-local, like the stored triangles.
        AABB box = self.getBoundingBox().move(delta.x - McwowGeomStore.regionOffsetX, 0, delta.z - McwowGeomStore.regionOffsetZ);
        double feet = box.minY, drop = Math.max(self.maxUpStep(), 0.6) + 0.05;
        double in = 0.05; // just inside the footprint, like vanilla's AABB test
        double[][] pts = {{(box.minX + box.maxX) / 2, (box.minZ + box.maxZ) / 2}, {box.minX + in, box.minZ + in},
                {box.maxX - in, box.minZ + in}, {box.minX + in, box.maxZ - in}, {box.maxX - in, box.maxZ - in}};
        for (double[] p : pts) {
            double h = McwowTriHeight.heightAt(p[0], p[1], feet);
            if (!Double.isNaN(h) && h >= feet - drop && h <= feet + 0.1) {
                cir.setReturnValue(delta);
                return;
            }
        }
    }
}
