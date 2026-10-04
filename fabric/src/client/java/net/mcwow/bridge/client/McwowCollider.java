package net.mcwow.bridge.client;

import java.util.List;

import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowTriCollider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feeds the local player's movement through {@link McwowTriCollider} against nearby WoW
 * triangles - a direct port of chasmlol/SkyCraft's {@code SkyCollider} (read in full
 * 2026-10-01). REPLACES the earlier hand-rolled climb/stick logic here, which tried to smooth
 * the player on top of the voxel layer; SkyCraft instead takes players OUT of the voxel layer
 * entirely (see {@code McwowGeomStore.usesSmoothCollider}) and resolves them against the exact
 * triangles only, including wall sliding and ceilings.
 */
public final class McwowCollider {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static long lastLogNanos;

    private McwowCollider() {
    }

    public static Vec3 collide(LocalPlayer player, Vec3 move) {
        if (!McwowGeomStore.appliesTo(player.level())) {
            return move;
        }
        AABB worldBox = player.getBoundingBox();
        // Region-local, like the stored triangles (exact: the offset is whole blocks). The result
        // of resolve() is a movement delta, so it needs no conversion back.
        AABB box = worldBox.move(-McwowGeomStore.regionOffsetX, 0, -McwowGeomStore.regionOffsetZ);
        double step = player.maxUpStep();
        AABB q = box.expandTowards(move).inflate(1.0, 1.0 + step, 1.0);
        List<McwowTriCollider.T> tris = McwowTriCollider.trianglesNear(q.minX, q.minY, q.minZ, q.maxX, q.maxY, q.maxZ);
        if (tris.isEmpty()) {
            return move;
        }
        double[] r = McwowTriCollider.resolve(
                tris, (box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5, box.getXsize() * 0.5,
                box.getYsize(), step, player.onGround(), move.x, move.y, move.z);

        long now = System.nanoTime();
        if (now - lastLogNanos > 500_000_000L) {
            lastLogNanos = now;
            double wowGround = net.mcwow.bridge.McwowGroundState.wowGroundUnderPlayer;
            LOGGER.info("mcwow-bridge: collider y0={} wowGround={} diff={} tris={} onGround={} move=({},{},{}) -> ({},{},{})",
                    String.format("%.3f", box.minY),
                    Double.isNaN(wowGround) ? "NaN" : String.format("%.3f", wowGround),
                    Double.isNaN(wowGround) ? "NaN" : String.format("%+.3f", box.minY - wowGround),
                    tris.size(), player.onGround(),
                    String.format("%.3f", move.x), String.format("%.3f", move.y), String.format("%.3f", move.z),
                    String.format("%.3f", r[0]), String.format("%.3f", r[1]), String.format("%.3f", r[2]));
        }

        if (r[0] == move.x && r[1] == move.y && r[2] == move.z) {
            return move;
        }
        // The triangle pass (snapping down a slope, pushing out of a wall) can move the player into a
        // Minecraft block placed on the terrain; collide that result with Minecraft blocks again.
        return Entity.collideBoundingBox(player, new Vec3(r[0], r[1], r[2]), worldBox, player.level(), List.of());
    }
}
