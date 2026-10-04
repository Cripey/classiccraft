package net.mcwow.bridge;

import java.util.concurrent.ConcurrentHashMap;

/**
 * WoW ground per Minecraft block column (breakable terrain, Phase 1, 2026-10-02): what benilla's
 * terrain export (classiccraft crate, terrain.rs) sends over the geometry ring as MSG_TERRAIN, one
 * 16x16-column chunk per message. Region-local chunk coordinates, like the geometry store; written
 * by the ring consumer thread, read by the server thread (McwowTerrainFill).
 */
public final class McwowTerrainStore {
    private McwowTerrainStore() {
    }

    /**
     * One chunk's columns, index z * 16 + x. surface: the WoW ground's lowest point over the column
     * (Minecraft y), NaN = no terrain (a hole or off the map); fillTop: surface, or lower under a
     * structure below the ground; material: McwowTerrainFill.MAT_*; area: AreaTable id; liquid:
     * McwowTerrainFill.LIQ_* of the WoW terrain liquid over the column (0 none), liquidTop its
     * surface (Minecraft y, NaN for none).
     */
    public record Chunk(float[] surface, float[] fillTop, byte[] material, short[] area, byte[] liquid,
                        float[] liquidTop) {
    }

    /** Past this many chunks the store is dropped and benilla asked to resend what's near. */
    public static final int MAX_CHUNKS = 16384;

    private static final ConcurrentHashMap<Long, Chunk> CHUNKS = new ConcurrentHashMap<>();

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** False if the store was full and has been dropped (the caller asks for a resend). */
    public static boolean put(int cx, int cz, Chunk chunk) {
        CHUNKS.put(key(cx, cz), chunk);
        if (CHUNKS.size() <= MAX_CHUNKS) return true;
        CHUNKS.clear();
        return false;
    }

    public static Chunk get(int cx, int cz) {
        return CHUNKS.get(key(cx, cz));
    }

    public static void clear() {
        CHUNKS.clear();
    }

    public static int size() {
        return CHUNKS.size();
    }
}
