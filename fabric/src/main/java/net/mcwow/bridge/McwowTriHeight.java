package net.mcwow.bridge;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

// REACTIVATED 2026-10-02, same day it was parked: re-reading chasmlol/SkyCraft's own design
// clarified that its 8x8x8 sub-voxel occupancy mask (BlockCollisionsMixin + McwowTriBox) was
// NEVER meant to handle the local player's own slope/stair traversal - that's TriCollider's job
// specifically, and TriCollider resolves directly against raw triangles, not the voxel grid, for
// exactly the reason this class exists: TriCollider's own doc comment calls vanilla block
// collision "stair-stepped slopes" - a real limitation no amount of voxel resolution removes.
// This is McwowCollider's continuous height source again - see its own doc comment for the full
// vertical-resolution design (authoritative from requested.y, never vanilla's own move.y).
//
// Real-geometry replacement for the old point-sampled wow::TraceLine grid (McwowGroundState),
// per the user's explicit direction 2026-10-02 after live testing kept finding gaps on stairs/
// inclines that the point-ray grid structurally couldn't cover (groundMask=0 - a complete
// sensing miss - was caught live right before a 58-block fall into the void on a real
// staircase). geom_server already exports REAL WMO/terrain triangles (see CLAUDE.md "Real WoW
// geometry export" and "geom_server CONFIRMED LIVE") into McwowGeomStore; this is the first
// code that actually CONSUMES them for collision (McwowTriCollider was only a forward-looking
// comment in McwowGeomClient before this). Unlike a point grid, a continuous triangle mesh has
// no "ray missed" gaps near walls/landings/railings - the real surface is either covered by a
// real triangle or it genuinely isn't part of the walkable mesh at all.
public final class McwowTriHeight {
    private McwowTriHeight() {
    }

    // Same "+3 above, cast down" margin convention wow::GetGroundHeight used (see CLAUDE.md
    // "WorldExporter stage B, take 2") - lets this double as a mild recovery margin (catches a
    // surface slightly above a point that's briefly embedded in geometry) without reaching all
    // the way up to an unrelated floor/landing above, the exact failure mode that motivated
    // reverting a wider margin on the old WoW-side ray.
    private static final double ABOVE_MARGIN = 3.0;

    // Result cache (first added for a "stuck + camera locks" stall: repeated identical queries per
    // tick). Entries stay valid while the geometry bucket under them is unchanged - see heightAt.
    private record Cached(double height, Object stamp) {
    }
    private static final ConcurrentHashMap<Long, Cached> CACHE = new ConcurrentHashMap<>();

    /**
     * The highest WALKABLE real-triangle surface at (x,z), at or below referenceY + ABOVE_MARGIN.
     * Exact geometric intersection against real exported triangles (barycentric in the XZ plane,
     * not a ray step loop - there is no stepping/missing involved), so a continuous slope or
     * staircase tread is found precisely at the queried point, not snapped to any grid. Returns
     * NaN if no matching real geometry is loaded there yet (too far from the player for
     * geom_server's own radius, or geom_server isn't running) - callers should treat that as "no
     * data", same as the old point grid's missing-cell case, not as "floor is very far away".
     */
    public static double heightAt(double x, double z, double referenceY) {
        // Quantized to a quarter-block so near-identical repeated queries (the common case -
        // BlockCollisionsMixin always asks about block-column centers) actually collide in the
        // cache, while still being far finer than anything that could matter for a VoxelShape.
        long key = (((long) Math.round(x * 4) & 0x1FFFFFL) << 42)
                | (((long) Math.round(z * 4) & 0x1FFFFFL) << 21)
                | ((long) Math.round(referenceY * 4) & 0x1FFFFFL);
        // Valid while the triangle bucket under (x, z) is unchanged (McwowGeomStore.bucketStamp) -
        // was a 60 ms timer until 2026-10-01 (mob pathfinding asks about hundreds of columns).
        Object stamp = McwowGeomStore.bucketStamp((int) Math.floor(x), (int) Math.floor(z));
        Cached cached = CACHE.get(key);
        if (cached != null && cached.stamp() == stamp) {
            return cached.height();
        }
        double result = computeHeightAt(x, z, referenceY);
        if (CACHE.size() > 100_000) CACHE.clear(); // pure perf cache, bounded bluntly
        CACHE.put(key, new Cached(result, stamp));
        return result;
    }

    /**
     * The LOWEST real surface at (x, z) between fromY and toY, walkable or not (hole walls,
     * 2026-10-02): over a column's top block the first surface up is WoW's ground itself, however
     * steep - never a rock or tree standing on it, which heightAt's highest-walkable rule would find,
     * nor a slope too steep to be walkable, which it skips. NaN if none. Uncached (few callers).
     */
    public static double lowestSurface(double x, double z, double fromY, double toY) {
        float fx = (float) x, fz = (float) z;
        List<McwowGeomStore.Tri> tris = McwowGeomStore.near(
                fx - 0.001f, (float) fromY, fz - 0.001f,
                fx + 0.001f, (float) toY, fz + 0.001f);
        double best = Double.NaN;
        for (McwowGeomStore.Tri t : tris) {
            Double y = columnIntersect(t, fx, fz);
            if (y == null || y < fromY || y > toY) continue;
            if (Double.isNaN(best) || y < best) best = y;
        }
        return best;
    }

    private static double computeHeightAt(double x, double z, double referenceY) {
        float fx = (float) x, fz = (float) z;
        List<McwowGeomStore.Tri> tris = McwowGeomStore.near(
                fx - 0.001f, -1.0e6f, fz - 0.001f,
                fx + 0.001f, 1.0e6f, fz + 0.001f);
        double best = Double.NaN;
        double limit = referenceY + ABOVE_MARGIN;
        for (McwowGeomStore.Tri t : tris) {
            if (!t.walkable) continue;
            Double y = columnIntersect(t, fx, fz);
            if (y == null || y > limit) continue;
            if (Double.isNaN(best) || y > best) best = y;
        }
        return best;
    }

    // Barycentric point-in-triangle test projected onto the XZ plane, then interpolates Y from
    // the three real vertices at that (x,z) - the same "intersect a vertical line against a real
    // triangle" idea as a downward ray-cast, but exact (no step size to tune, no chance of
    // stepping clean over a thin surface the way a coarse ray march could).
    private static Double columnIntersect(McwowGeomStore.Tri t, float x, float z) {
        float x0 = t.x0, z0 = t.z0, x1 = t.x1, z1 = t.z1, x2 = t.x2, z2 = t.z2;
        float d = (z1 - z2) * (x0 - x2) + (x2 - x1) * (z0 - z2);
        if (Math.abs(d) < 1.0e-9f) return null; // degenerate in XZ - a near-vertical wall triangle
        float a = ((z1 - z2) * (x - x2) + (x2 - x1) * (z - z2)) / d;
        float b = ((z2 - z0) * (x - x2) + (x0 - x2) * (z - z2)) / d;
        float c = 1f - a - b;
        float eps = -1.0e-4f; // tolerate real triangles sharing an edge exactly at the boundary
        if (a < eps || b < eps || c < eps) return null;
        return (double) (a * t.y0 + b * t.y1 + c * t.y2);
    }
}
