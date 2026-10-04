package net.mcwow.bridge;

import java.util.concurrent.ConcurrentHashMap;

// Shared between the geometry-ring consumer thread (writer - McwowGeomClient, client sourceset)
// and any collision code that wants real WoW triangles (reader - same split McwowGroundState
// already established for the old point-height grid, see its own comment for why). Holds the
// real triangles geom_server (a NEW native Linux helper process, see CLAUDE.md "Real WoW
// geometry export") extracts from AzerothCore's own VMAP/terrain files, keyed by the same
// stable per-object id the protocol uses (protocol/mcwow_geom_protocol.h) - a model's real
// triangles are published once and left alone until that object leaves range (MCWOW_GEOM_MSG_
// REMOVE), not re-sent every tick, since the geometry itself never changes.
public final class McwowGeomStore {
    // One real triangle, already in MC block space (Y-up) - geom_server does the WoW-yards ->
    // MC-blocks conversion via the live coordinate anchor before this ever reaches Java, same
    // convention McwowGroundState's own cells already used.
    public static final class Tri {
        public final float x0, y0, z0, x1, y1, z1, x2, y2, z2;
        public final boolean walkable;

        public Tri(float x0, float y0, float z0, float x1, float y1, float z1, float x2,
                   float y2, float z2, boolean walkable) {
            this.x0 = x0; this.y0 = y0; this.z0 = z0;
            this.x1 = x1; this.y1 = y1; this.z1 = z1;
            this.x2 = x2; this.y2 = y2; this.z2 = z2;
            this.walkable = walkable;
        }

        public float minX() { return Math.min(x0, Math.min(x1, x2)); }
        public float maxX() { return Math.max(x0, Math.max(x1, x2)); }
        public float minY() { return Math.min(y0, Math.min(y1, y2)); }
        public float maxY() { return Math.max(y0, Math.max(y1, y2)); }
        public float minZ() { return Math.min(z0, Math.min(z1, z2)); }
        public float maxZ() { return Math.max(z0, Math.max(z1, z2)); }
    }

    // objectId -> its triangles. Replaced as a whole array per object on update (never mutated
    // element-wise), same "swap, don't mutate" rule McwowGroundState's own cells array uses, so
    // a reader always sees a fully-consistent set for any one object without needing a lock.
    private static final ConcurrentHashMap<Long, Tri[]> OBJECTS = new ConcurrentHashMap<>();

    // Where the stored (region-local, fixed-mapping) geometry lives in the Minecraft world -
    // protocol v2, 2026-10-01. Triangles are kept REGION-LOCAL (|x|,|z| <= ~11,640 blocks, so
    // floats stay precise); consumers subtract the region offset from world positions before
    // querying. The offset is a whole number of blocks (an instance slot's region, see
    // McwowWorldPlacement), so the conversion is exact. Geometry applies only in
    // activeDimension (the WoW map's dimension, mcwow:map_<id>); null = nowhere.
    public static volatile net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> activeDimension;
    public static volatile int regionOffsetX, regionOffsetZ;

    /** True if WoW geometry applies to this level (the active WoW map's dimension). */
    public static boolean appliesTo(Object level) {
        var dim = activeDimension;
        return dim != null && level instanceof net.minecraft.world.level.Level l && l.dimension() == dim;
    }

    private McwowGeomStore() {
    }

    // Entities (the local player) that collide with WoW's exact triangles (McwowTriCollider)
    // INSTEAD of the voxel shapes BlockCollisionsMixin builds - mirrors SkyCraft's own
    // SkyCollision.setSmoothCollider/usesSmoothCollider. Set from the client initializer, since
    // this (main) sourceset can't reference client classes.
    private static volatile java.util.function.Predicate<net.minecraft.world.entity.Entity> smoothCollider = e -> false;

    public static void setSmoothCollider(java.util.function.Predicate<net.minecraft.world.entity.Entity> predicate) {
        smoothCollider = predicate;
    }

    public static boolean usesSmoothCollider(net.minecraft.world.entity.Entity entity) {
        return entity != null && smoothCollider.test(entity);
    }

    // ---- spatial index (2026-10-01) -------------------------------------------------------
    // Every collision query (player, items, mobs, arrows, fluids, crosshair) used to scan EVERY
    // stored triangle - fine for ~2-10k triangles, not for persistent geometry (hundreds of
    // thousands). Triangles are bucketed by the XZ grid cells (BUCKET blocks) their bounding box
    // overlaps; a query visits only the buckets under its box. Each bucket's array is immutable
    // and swapped as a whole (copy-on-write), so readers on any thread never lock.
    public static final int BUCKET = 8;
    private static final Tri[] NONE = new Tri[0];
    private static final ConcurrentHashMap<Long, Tri[]> BUCKETS = new ConcurrentHashMap<>();
    /** objectId -> the bucket keys it occupies (to take it out again). */
    private static final ConcurrentHashMap<Long, long[]> OBJECT_BUCKETS = new ConcurrentHashMap<>();

    private static long bucketKey(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xFFFFFFFFL);
    }

    private static int bucketOf(float v) {
        return Math.floorDiv((int) Math.floor(v), BUCKET);
    }

    /**
     * The current triangle array of the bucket holding region-local block column (x, z), or null.
     * Bucket arrays are immutable and replaced whole on every change, so an unchanged reference
     * means unchanged geometry there - caches of per-block results (McwowWowShapes,
     * McwowTriHeight) keep them as their validity stamp instead of expiring on a timer.
     */
    public static Object bucketStamp(int x, int z) {
        return BUCKETS.get(bucketKey(Math.floorDiv(x, BUCKET), Math.floorDiv(z, BUCKET)));
    }

    /** Writer side (one thread: the geometry consumer). */
    private static void unindex(long objectId, Tri[] old) {
        long[] keys = OBJECT_BUCKETS.remove(objectId);
        if (keys == null || old == null) return;
        java.util.Set<Tri> gone = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Collections.addAll(gone, old);
        for (long k : keys) {
            BUCKETS.computeIfPresent(k, (key, arr) -> {
                int keep = 0;
                for (Tri t : arr) if (!gone.contains(t)) keep++;
                if (keep == 0) return null;
                Tri[] out = new Tri[keep];
                int i = 0;
                for (Tri t : arr) if (!gone.contains(t)) out[i++] = t;
                return out;
            });
        }
    }

    private static void index(long objectId, Tri[] tris) {
        java.util.HashMap<Long, java.util.ArrayList<Tri>> add = new java.util.HashMap<>();
        for (Tri t : tris) {
            int bx0 = bucketOf(t.minX()), bx1 = bucketOf(t.maxX()), bz0 = bucketOf(t.minZ()), bz1 = bucketOf(t.maxZ());
            for (int bx = bx0; bx <= bx1; bx++)
                for (int bz = bz0; bz <= bz1; bz++)
                    add.computeIfAbsent(bucketKey(bx, bz), k -> new java.util.ArrayList<>()).add(t);
        }
        long[] keys = new long[add.size()];
        int i = 0;
        for (var e : add.entrySet()) {
            keys[i++] = e.getKey();
            Tri[] extra = e.getValue().toArray(NONE);
            BUCKETS.merge(e.getKey(), extra, (cur, more) -> {
                Tri[] out = java.util.Arrays.copyOf(cur, cur.length + more.length);
                System.arraycopy(more, 0, out, cur.length, more.length);
                return out;
            });
        }
        OBJECT_BUCKETS.put(objectId, keys);
    }

    // ---- persistent cells (geom protocol v2, 2026-10-01) -------------------------------------
    // geom_server sends fixed 32-yd WoW cells once (protocol/mcwow_geom_protocol.h); they stay
    // here until McwowGeomCache evicts them (memory budget; never pinned cells).
    public static final long CELL_ID_BIT = 1L << 62;
    private static final float S = 1.4667f;      // MCWOW_MC_BLOCKS_TO_WOW_YARDS
    private static final float CELL_YD = 32.0f;  // MCWOW_GEOM_CELL_YD
    /** cell objectId -> {last time the player was near (nanoTime), triangle count}. */
    public static final ConcurrentHashMap<Long, long[]> CELLS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong TRIANGLES = new java.util.concurrent.atomic.AtomicLong();
    /** Set by the client: reports an evicted cell to geom_server (so it is sent again later). */
    public static volatile java.util.function.LongConsumer evictSink;

    public static boolean isCell(long objectId) {
        return (objectId & CELL_ID_BIT) != 0;
    }

    public static long cellId(int cx, int cy) {
        return CELL_ID_BIT | ((long) ((cx + 32768) & 0xFFFF) << 16) | ((cy + 32768) & 0xFFFF);
    }

    /** {cx, cy} of a cell objectId. */
    public static int[] cellXY(long id) {
        return new int[] {(int) ((id >> 16) & 0xFFFF) - 32768, (int) (id & 0xFFFF) - 32768};
    }

    /** The cell containing a REGION-LOCAL Minecraft position (mcX = wowY/S, mcZ = wowX/S). */
    public static long cellAtLocal(double mcX, double mcZ) {
        return cellId((int) Math.floor(mcZ * S / CELL_YD), (int) Math.floor(mcX * S / CELL_YD));
    }

    /** Region-local Minecraft XZ box of a cell {minX, minZ, maxX, maxZ}. */
    public static double[] cellBoxLocal(long id) {
        int[] c = cellXY(id);
        double k = CELL_YD / S;
        return new double[] {c[1] * k, c[0] * k, (c[1] + 1) * k, (c[0] + 1) * k};
    }

    /** Is WoW's ground loaded at this region-local position? */
    public static boolean knownAtLocal(double mcX, double mcZ) {
        return CELLS.containsKey(cellAtLocal(mcX, mcZ));
    }

    /** Marks the cells within {@code radius} blocks of a region-local position as just visited. */
    public static void touchLocal(double mcX, double mcZ, double radius) {
        long now = System.nanoTime();
        double k = CELL_YD / S;
        int cx0 = (int) Math.floor((mcZ - radius) / k), cx1 = (int) Math.floor((mcZ + radius) / k);
        int cy0 = (int) Math.floor((mcX - radius) / k), cy1 = (int) Math.floor((mcX + radius) / k);
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cy = cy0; cy <= cy1; cy++) {
                long[] v = CELLS.get(cellId(cx, cy));
                if (v != null) v[0] = now;
            }
    }

    public static long triangleTotal() {
        return TRIANGLES.get();
    }

    public static void put(long objectId, Tri[] tris) {
        Tri[] old = OBJECTS.put(objectId, tris);
        TRIANGLES.addAndGet(tris.length - (old != null ? old.length : 0));
        if (isCell(objectId)) CELLS.put(objectId, new long[] {System.nanoTime(), tris.length});
        unindex(objectId, old);
        index(objectId, tris);
    }

    public static void remove(long objectId) {
        Tri[] old = OBJECTS.remove(objectId);
        if (old != null) TRIANGLES.addAndGet(-old.length);
        CELLS.remove(objectId);
        unindex(objectId, old);
    }

    public static void clear() {
        OBJECTS.clear();
        CELLS.clear();
        TRIANGLES.set(0);
        BUCKETS.clear();
        OBJECT_BUCKETS.clear();
    }

    public static int objectCount() {
        return OBJECTS.size();
    }

    public static int triangleCount() {
        int n = 0;
        for (Tri[] t : OBJECTS.values()) n += t.length;
        return n;
    }

    /**
     * All triangles whose bounding box overlaps the given MC-block-space (region-local) box, each
     * once. Visits only the index buckets under the box.
     */
    public static java.util.List<Tri> near(float minX, float minY, float minZ, float maxX,
                                            float maxY, float maxZ) {
        java.util.List<Tri> out = new java.util.ArrayList<>();
        int bx0 = bucketOf(minX), bx1 = bucketOf(maxX), bz0 = bucketOf(minZ), bz1 = bucketOf(maxZ);
        boolean many = bx0 != bx1 || bz0 != bz1;
        java.util.Set<Tri> seen = many ? java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()) : null;
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                Tri[] arr = BUCKETS.get(bucketKey(bx, bz));
                if (arr == null) continue;
                for (Tri t : arr) {
                    if (t.maxX() < minX || t.minX() > maxX) continue;
                    if (t.maxY() < minY || t.minY() > maxY) continue;
                    if (t.maxZ() < minZ || t.minZ() > maxZ) continue;
                    if (seen != null && !seen.add(t)) continue;
                    out.add(t);
                }
            }
        }
        return out;
    }
}
