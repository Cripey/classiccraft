package net.mcwow.bridge;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Makes Minecraft ray casts (arrows, thrown eggs/snowballs/pearls) hit WoW's surfaces - port of
 * chasmlol/SkyCraft's SkyClip (minus its dig-hole walls). Vanilla still clips against real
 * Minecraft blocks; whichever hit is nearer wins. A WoW hit is a {@link WowHitResult}: a block hit
 * on the AIR cell the surface is in, which the projectile mixins treat as a real hit (arrows stick
 * there - the cell stays air, as recorded at impact, so vanilla never makes them fall out - and
 * eggs/snowballs break). Only in the active WoW map dimension (McwowGeomStore.activeDimension).
 */
public final class McwowClip {
    private McwowClip() {
    }

    /** A hit on WoW geometry (Minecraft sees air there). */
    public static final class WowHitResult extends BlockHitResult {
        public WowHitResult(Vec3 location, Direction face, BlockPos pos) {
            super(location, face, pos, false);
        }
    }

    /** Nearest WoW triangle on the segment, world coords: {t (0..1), nx, ny, nz} or null. */
    public static double[] cast(Vec3 from, Vec3 to) {
        double ox = from.x - McwowGeomStore.regionOffsetX, oy = from.y, oz = from.z - McwowGeomStore.regionOffsetZ;
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double ex = ox + dx, ey = oy + dy, ez = oz + dz;
        List<McwowGeomStore.Tri> tris = McwowGeomStore.near(
                (float) Math.min(ox, ex), (float) Math.min(oy, ey), (float) Math.min(oz, ez),
                (float) Math.max(ox, ex), (float) Math.max(oy, ey), (float) Math.max(oz, ez));
        double best = Double.MAX_VALUE, bnx = 0, bny = 0, bnz = 0;
        for (McwowGeomStore.Tri t : tris) {
            double e1x = t.x1 - t.x0, e1y = t.y1 - t.y0, e1z = t.z1 - t.z0;
            double e2x = t.x2 - t.x0, e2y = t.y2 - t.y0, e2z = t.z2 - t.z0;
            double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-12) continue;
            double inv = 1.0 / det;
            double sx = ox - t.x0, sy = oy - t.y0, sz = oz - t.z0;
            double u = (sx * px + sy * py + sz * pz) * inv;
            if (u < 0 || u > 1) continue;
            double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            double v = (dx * qx + dy * qy + dz * qz) * inv;
            if (v < 0 || u + v > 1) continue;
            double tt = (e2x * qx + e2y * qy + e2z * qz) * inv; // segment parameter
            if (tt < 0 || tt > 1 || tt >= best) continue;
            best = tt;
            bnx = e1y * e2z - e1z * e2y;
            bny = e1z * e2x - e1x * e2z;
            bnz = e1x * e2y - e1y * e2x;
        }
        if (best == Double.MAX_VALUE) return null;
        if (bnx * dx + bny * dy + bnz * dz > 0) { bnx = -bnx; bny = -bny; bnz = -bnz; } // facing the ray
        return new double[] { best, bnx, bny, bnz };
    }

    /** Vanilla's block hit, or a nearer WoW-surface hit. */
    public static BlockHitResult refine(Object level, Vec3 from, Vec3 to, BlockHitResult vanilla) {
        if (!McwowGeomStore.appliesTo(level)) return vanilla;
        double[] hit = cast(from, to);
        if (hit == null) return vanilla;
        Vec3 location = from.add(to.subtract(from).scale(hit[0]));
        if (vanilla.getType() != HitResult.Type.MISS && from.distanceToSqr(vanilla.getLocation()) <= from.distanceToSqr(location)) {
            return vanilla;
        }
        Direction face = Direction.getApproximateNearest(hit[1], hit[2], hit[3]);
        // The cell the surface is in: just behind the hit point, away from the ray's side.
        BlockPos cell = BlockPos.containing(location.x - face.getStepX() * 0.01, location.y - face.getStepY() * 0.01,
                location.z - face.getStepZ() * 0.01);
        return new WowHitResult(location, face, cell);
    }
}
