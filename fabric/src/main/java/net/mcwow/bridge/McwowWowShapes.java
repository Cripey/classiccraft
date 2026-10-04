package net.mcwow.bridge;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * WoW geometry inside one Minecraft block, as an 8x8x8 sub-voxel shape built from the real WoW
 * triangles (McwowGeomStore) - moved out of BlockCollisionsMixin (2026-10-01) so the fluid rules
 * (FlowingFluidMixin) can use it too. Coordinates are REGION-LOCAL block coords (like the stored
 * triangles). See BlockCollisionsMixin's class comment for the design history (SAT test, fill-down
 * against thin-shell tunnelling, the short cache).
 */
public final class McwowWowShapes {
    private McwowWowShapes() {
    }

    private static final int SUBDIV = 8; // per axis, per block - see class doc comment
    private static final double CELL = 1.0 / SUBDIV;
    private static final double HALF_CELL = CELL / 2.0;

    // Per-block shapes are cached until the geometry under them changes: each entry keeps the
    // triangle bucket array it was built from (McwowGeomStore.bucketStamp) and is valid while that
    // bucket is unchanged. (Was a ~2-tick timer until 2026-10-01: mob pathfinding asks about
    // hundreds of cells per path, so nearly every query rebuilt its shape - server ticks of
    // 0.6-1.5 s in the first zombie-vs-WoW-wolf fight.)
    private static final int CACHE_MAX = 100_000;
    private record Cached(VoxelShape shape, Object stamp) {
    }
    private static final ConcurrentHashMap<Long, Cached> CACHE = new ConcurrentHashMap<>();

    public static VoxelShape shapeAt(int x, int y, int z) {
        // 21 bits per axis. (Was 22-bit masks in 21-bit slots until 2026-10-01: negative z set y's
        // low bit, so a cell shared its cache entry with the one above it - mobs fell through the
        // ground on slopes, or stood on phantom ground a block up.)
        long key = (((long) x & 0x1FFFFFL) << 42) | (((long) y & 0x1FFFFFL) << 21) | ((long) z & 0x1FFFFFL);
        Object stamp = McwowGeomStore.bucketStamp(x, z);
        Cached cached = CACHE.get(key);
        if (cached != null && cached.stamp() == stamp) {
            return cached.shape();
        }
        VoxelShape shape = stamp == null ? Shapes.empty() : computeShape(x, y, z);
        if (CACHE.size() > CACHE_MAX) CACHE.clear(); // pure perf cache, bounded bluntly
        CACHE.put(key, new Cached(shape, stamp));
        return shape;
    }

    // FIXED 2026-10-02, same day as the SUBDIV 4->8 bump: raising resolution made clipping WORSE
    // ("clip through the ground... going up stairs or a natural hill") - counterintuitive until
    // you consider that each sub-cell only marks itself solid where a triangle LITERALLY passes
    // through it, so the solid representation of any real surface is a shell exactly one sub-cell
    // thick. A finer SUBDIV makes that shell THINNER (0.125 blocks at SUBDIV=8 vs 0.25 at
    // SUBDIV=4) - the classic "thin wall" collision problem: fast-enough per-tick movement can
    // tunnel through a thin shell between discrete position samples, and it gets EASIER to tunnel
    // through as the shell gets thinner, not harder. Real ground isn't a zero-thickness membrane;
    // it's a solid mass extending downward. Fixed by filling each sub-COLUMN downward from its
    // first (topmost) real intersection to the bottom of THIS block only - bounded to one block
    // of fill, so it can't incorrectly solidify real open space under a genuine overhang/archway
    // (that space lives in a DIFFERENT, separately-computed block below), but it eliminates thin-
    // shell tunneling at any SUBDIV. Scanning top-down and stopping at the first hit also makes
    // this CHEAPER on average than testing every sub-cell independently.
    private static VoxelShape computeShape(int x, int y, int z) {
        List<McwowGeomStore.Tri> tris = McwowGeomStore.near(
                (float) x, (float) y, (float) z, (float) (x + 1), (float) (y + 1), (float) (z + 1));
        if (tris.isEmpty()) {
            return Shapes.empty();
        }
        // One 8x8x8 bit grid, turned into a shape once (was a Shapes.or per column - up to 64
        // full shape merges per block, the hot spot in the 2026-10-01 lag profile).
        BitSetDiscreteVoxelShape grid = new BitSetDiscreteVoxelShape(SUBDIV, SUBDIV, SUBDIV);
        boolean any = false;
        for (int ix = 0; ix < SUBDIV; ++ix) {
            double lx0 = ix * CELL;
            double cx = x + lx0 + HALF_CELL;
            for (int iz = 0; iz < SUBDIV; ++iz) {
                double lz0 = iz * CELL;
                double cz = z + lz0 + HALF_CELL;
                int topIy = -1;
                for (int iy = SUBDIV - 1; iy >= 0; --iy) {
                    double cy = y + iy * CELL + HALF_CELL;
                    boolean solid = false;
                    for (McwowGeomStore.Tri t : tris) {
                        if (McwowTriBox.intersects(t.x0, t.y0, t.z0, t.x1, t.y1, t.z1, t.x2, t.y2, t.z2,
                                cx, cy, cz, HALF_CELL, HALF_CELL, HALF_CELL)) {
                            solid = true;
                            break;
                        }
                    }
                    if (solid) {
                        topIy = iy;
                        break; // fill-down handles everything below this cell - stop here
                    }
                }
                for (int iy = 0; iy <= topIy; ++iy) grid.fill(ix, iy, iz);
                any |= topIy >= 0;
            }
        }
        return any ? new CubeVoxelShape(grid) : Shapes.empty();
    }

}
