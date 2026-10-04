package net.mcwow.bridge.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Aiming at WoW's world (Phase 4 step 4, 2026-10-01). Minecraft's crosshair only hits Minecraft
 * blocks; WoW's ground and walls exist only as triangles (McwowGeomStore, streamed by geom_server
 * for collision). After vanilla picking (Minecraft.pick), this casts the same ray against those
 * triangles; if a WoW surface is closer, the target becomes a block hit on the AIR cell just in
 * front of the surface, facing the player along the triangle normal's dominant axis. Air is
 * "replaceable", so vanilla placement (BlockPlaceContext) puts the block exactly in that cell, and
 * the server's checks (reach, hit point inside the clicked cell) pass. Breaking air does nothing,
 * so WoW surfaces can't be broken - except WoW ground with real blocks under it (breakable
 * terrain, 2026-10-02): an upward-facing hit there targets the top block in that column instead.
 *
 * Also publishes the selection box (what to outline) to mcwow.dll, which draws it in WoW's frame.
 */
public final class McwowTargeting {
    public static final int REN_SELECTION = 8;
    private static boolean lastHas;
    private static final float[] LAST = new float[6];

    private McwowTargeting() {
    }

    /** WoW dropped everything (full resend): send the selection again even if unchanged. */
    static void reset() {
        lastHas = false;
        java.util.Arrays.fill(LAST, Float.NaN);
    }

    /** Called at the end of Minecraft.pick(partialTick). */
    public static void afterPick(Minecraft mc, float partialTick) {
        Player player = mc.player;
        if (player == null || mc.level == null || !McwowGeomStore.appliesTo(mc.level)) return;
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 dir = player.getViewVector(partialTick);
        double range = player.blockInteractionRange();
        HitResult vanilla = mc.hitResult;
        double vanillaDist = vanilla != null && vanilla.getType() != HitResult.Type.MISS
                ? vanilla.getLocation().distanceTo(eye) : Double.MAX_VALUE;

        // Region-local ray (the stored triangles are region-local; offsets are whole blocks).
        double ox = eye.x - McwowGeomStore.regionOffsetX, oy = eye.y, oz = eye.z - McwowGeomStore.regionOffsetZ;
        double ex = ox + dir.x * range, ey = oy + dir.y * range, ez = oz + dir.z * range;
        List<McwowGeomStore.Tri> tris = McwowGeomStore.near(
                (float) Math.min(ox, ex), (float) Math.min(oy, ey), (float) Math.min(oz, ez),
                (float) Math.max(ox, ex), (float) Math.max(oy, ey), (float) Math.max(oz, ez));
        double bestT = Double.MAX_VALUE;
        double bnx = 0, bny = 0, bnz = 0;
        for (McwowGeomStore.Tri t : tris) {
            // Moller-Trumbore
            double e1x = t.x1 - t.x0, e1y = t.y1 - t.y0, e1z = t.z1 - t.z0;
            double e2x = t.x2 - t.x0, e2y = t.y2 - t.y0, e2z = t.z2 - t.z0;
            double px = dir.y * e2z - dir.z * e2y, py = dir.z * e2x - dir.x * e2z, pz = dir.x * e2y - dir.y * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-12) continue;
            double inv = 1.0 / det;
            double sx = ox - t.x0, sy = oy - t.y0, sz = oz - t.z0;
            double u = (sx * px + sy * py + sz * pz) * inv;
            if (u < 0 || u > 1) continue;
            double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            double v = (dir.x * qx + dir.y * qy + dir.z * qz) * inv;
            if (v < 0 || u + v > 1) continue;
            double dist = (e2x * qx + e2y * qy + e2z * qz) * inv;
            if (dist <= 1e-4 || dist > range || dist >= bestT) continue;
            bestT = dist;
            bnx = e1y * e2z - e1z * e2y;
            bny = e1z * e2x - e1x * e2z;
            bnz = e1x * e2y - e1y * e2x;
        }
        HitResult result = vanilla;
        boolean wowTarget = false;
        if (bestT < vanillaDist) {
            // Normal turned towards the player, snapped to its dominant axis.
            if (bnx * dir.x + bny * dir.y + bnz * dir.z > 0) { bnx = -bnx; bny = -bny; bnz = -bnz; }
            Direction face = Direction.getApproximateNearest(bnx, bny, bnz);
            Vec3 hit = eye.add(dir.scale(bestT));
            BlockPos ground = face == Direction.UP ? groundBelow(mc, hit) : null;
            if (ground != null) {
                // WoW ground with real blocks under it (breakable terrain): aim at the top block, so
                // it breaks like any block; placing on its top fills the cell under the WoW surface.
                result = new BlockHitResult(new Vec3(hit.x, ground.getY() + 1.0, hit.z), Direction.UP, ground, false);
            } else {
                BlockPos cell = BlockPos.containing(hit.x + face.getStepX() * 0.01, hit.y + face.getStepY() * 0.01,
                        hit.z + face.getStepZ() * 0.01);
                result = new BlockHitResult(hit, face, cell, false);
                wowTarget = true;
            }
            mc.hitResult = result;
        }
        publishSelection(mc, result, wowTarget);
    }

    /**
     * The real block just under a point on WoW's ground, in the same column: the top block the
     * terrain fill (McwowTerrainFill) put under the WoW surface, at most a few blocks down on a slope.
     */
    private static BlockPos groundBelow(Minecraft mc, Vec3 hit) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(hit.x, hit.y, hit.z).mutable();
        for (int i = 0; i < 4; ++i, pos.move(Direction.DOWN)) {
            if (!mc.level.getBlockState(pos).isAir()) return pos.immutable();
        }
        return null;
    }

    private static void publishSelection(Minecraft mc, HitResult hit, boolean wowTarget) {
        boolean has = false;
        float[] box = new float[6];
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            AABB b = null;
            if (wowTarget) {
                b = new AABB(pos); // the cell a block would go into
            } else {
                VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
                if (!shape.isEmpty()) b = shape.bounds().move(pos);
            }
            if (b != null) {
                has = true;
                box[0] = (float) (b.minX - McwowGeomStore.regionOffsetX); box[1] = (float) b.minY;
                box[2] = (float) (b.minZ - McwowGeomStore.regionOffsetZ);
                box[3] = (float) (b.maxX - McwowGeomStore.regionOffsetX); box[4] = (float) b.maxY;
                box[5] = (float) (b.maxZ - McwowGeomStore.regionOffsetZ);
            }
        }
        if (has == lastHas && (!has || java.util.Arrays.equals(box, LAST)) && !Float.isNaN(LAST[0])) return; // unchanged
        ByteBuffer msg = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        msg.putInt(has ? 1 : 0).putInt(0);
        for (float f : box) msg.putFloat(f);
        msg.flip();
        if (McwowRenderLink.active() && McwowRenderLink.tryWrite(REN_SELECTION, msg, null)) {
            lastHas = has;
            System.arraycopy(box, 0, LAST, 0, 6);
        }
    }
}
