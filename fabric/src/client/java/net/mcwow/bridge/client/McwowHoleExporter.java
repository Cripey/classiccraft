package net.mcwow.bridge.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowTerrainFill;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Holes dug into WoW's terrain (breakable terrain, Phase 2, 2026-10-02). A column of WoW ground is
 * OPEN when its top filled block (McwowTerrainFill.TOPS, recorded in the chunk when it was filled)
 * is no longer natural ground (dug out, or built over with stairs, planks...; isNaturalGround), or
 * something solid stands in the gap cell above it. Open columns, per chunk near the player, go to
 * benilla as REN_HOLES whenever a chunk's set changes; benilla stops drawing WoW's ground over them
 * and clips them out of the WoW collision it sends back. Putting ground back on top (dirt, stone,
 * sand...) closes the column, and WoW's ground returns.
 */
public final class McwowHoleExporter {
    /** i32 cx, i32 cz (region-local chunk), then 8 u32: column z * 16 + x is bit (i % 32) of word i / 32. */
    public static final int REN_HOLES = 13;
    private static final int RADIUS = 6;
    private static final long PERIOD_NANOS = 100_000_000L;

    private static final Map<Long, int[]> SENT = new HashMap<>();
    /** Any open column near the player (read by the geometry consumer thread). */
    private static volatile boolean anyHoles;
    /** Geometry cells replaced since the last frame (geometry consumer thread -> client thread). */
    private static final ConcurrentLinkedQueue<Long> CHANGED_CELLS = new ConcurrentLinkedQueue<>();
    private static long lastNanos;

    private McwowHoleExporter() {
    }

    /** benilla dropped everything (REN_CLEAR_ALL): every non-empty mask goes again. */
    static void reset() {
        SENT.clear();
        anyHoles = false;
    }

    /** A column's top filled block (world x, z), or NO_TOP: not filled, unloaded, or no ground. */
    static int topOf(ClientLevel level, int x, int z) {
        return net.mcwow.bridge.McwowColumns.topOf(level, x, z);
    }

    /** Whether the column at world (x, z) is open (dug). */
    static boolean isOpen(ClientLevel level, int x, int z) {
        return net.mcwow.bridge.McwowColumns.isOpen(level, x, z);
    }

    private static boolean isOpen(ClientLevel level, int top, int x, int z, BlockPos.MutableBlockPos pos) {
        return net.mcwow.bridge.McwowColumns.isOpen(level, top, x, z, pos);
    }

    /**
     * A geometry cell (McwowGeomStore, 32 yd square) was replaced: the hole walls in it take their
     * heights from its triangles, which change a moment after a hole opens or closes (benilla clips
     * the open columns out), so its walls are meshed again next frame and settle on the final shape.
     * Geometry consumer thread.
     */
    static void cellChanged(long cellId) {
        if (anyHoles) CHANGED_CELLS.add(cellId);
    }

    static void frame(ClientLevel level, LocalPlayer player) {
        for (Long cell; (cell = CHANGED_CELLS.poll()) != null; ) remeshCell(level, cell);
        long now = System.nanoTime();
        if (now - lastNanos < PERIOD_NANOS) return;
        lastNanos = now;
        int offX = McwowGeomStore.regionOffsetX, offZ = McwowGeomStore.regionOffsetZ;
        int pcx = Mth.floor(player.getX()) >> 4, pcz = Mth.floor(player.getZ()) >> 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = pcx - RADIUS; cx <= pcx + RADIUS; ++cx) {
            for (int cz = pcz - RADIUS; cz <= pcz + RADIUS; ++cz) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                int[] tops = chunk == null ? null : chunk.getAttached(McwowTerrainFill.TOPS);
                if (tops == null) continue;
                int[] mask = new int[8];
                boolean any = false;
                for (int i = 0; i < 256; ++i) {
                    if (isOpen(level, tops[i], cx * 16 + (i & 15), cz * 16 + (i >> 4), pos)) {
                        mask[i >> 5] |= 1 << (i & 31);
                        any = true;
                    }
                }
                // Region-local chunk, as benilla and the geometry use.
                int lcx = cx - (offX >> 4), lcz = cz - (offZ >> 4);
                long key = net.mcwow.bridge.McwowTerrainStore.key(lcx, lcz);
                int[] sent = SENT.get(key);
                if (sent == null ? !any : java.util.Arrays.equals(sent, mask)) continue;
                remeshAround(level, cx, cz, sent, mask);
                ByteBuffer msg = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putInt(lcx).putInt(lcz);
                for (int w : mask) msg.putInt(w);
                if (!McwowRenderLink.tryWrite(REN_HOLES, msg.flip(), null)) return; // ring busy: next time
                if (any) SENT.put(key, mask);
                else SENT.remove(key);
            }
        }
        anyHoles = !SENT.isEmpty();
    }

    /**
     * A column opened or closed: the hole walls (McwowWorldExporter skirts) of it and its four
     * neighbours change, so their gap cells' sections are meshed again. World chunk coordinates.
     */
    private static void remeshAround(ClientLevel level, int cx, int cz, int[] before, int[] after) {
        for (int i = 0; i < 256; ++i) {
            int was = before == null ? 0 : (before[i >> 5] >>> (i & 31)) & 1;
            if (was == ((after[i >> 5] >>> (i & 31)) & 1)) continue;
            int x = cx * 16 + (i & 15), z = cz * 16 + (i >> 4);
            for (int[] d : new int[][] { { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                remeshGap(level, x + d[0], z + d[1]);
            }
        }
    }

    /**
     * The section holding a column's gap cell, where its hole walls are meshed, and its top block's
     * (which faces of it are drawn depends on its neighbours being open - McwowWorldExporter.hiddenFaces).
     */
    private static void remeshGap(ClientLevel level, int x, int z) {
        int top = topOf(level, x, z);
        if (top == McwowTerrainFill.NO_TOP) return;
        McwowWorldExporter.markDirtyNow(x >> 4, (top + 1) >> 4, z >> 4);
        if (top >> 4 != (top + 1) >> 4) McwowWorldExporter.markDirtyNow(x >> 4, top >> 4, z >> 4);
    }

    private static void remeshCell(ClientLevel level, long cellId) {
        int cx = (int) ((cellId >>> 16) & 0xFFFF) - 32768, cy = (int) (cellId & 0xFFFF) - 32768;
        float s = McwowWorldPlacement.BLOCKS_TO_YARDS;
        // Cell cx spans WoW x (Minecraft z) [cx * 32, cx * 32 + 32); cy WoW y (Minecraft x). Region-local.
        int z0 = Mth.floor(cx * 32.0F / s) - 1, z1 = Mth.floor((cx + 1) * 32.0F / s) + 1;
        int x0 = Mth.floor(cy * 32.0F / s) - 1, x1 = Mth.floor((cy + 1) * 32.0F / s) + 1;
        int offX = McwowGeomStore.regionOffsetX, offZ = McwowGeomStore.regionOffsetZ;
        java.util.Set<Long> marked = new java.util.HashSet<>();
        for (int z = z0 + offZ; z <= z1 + offZ; ++z) {
            for (int x = x0 + offX; x <= x1 + offX; ++x) {
                int top = topOf(level, x, z);
                if (top == McwowTerrainFill.NO_TOP) continue;
                if (marked.add(SectionPos.asLong(x >> 4, (top + 1) >> 4, z >> 4))) {
                    McwowWorldExporter.markDirtyNow(x >> 4, (top + 1) >> 4, z >> 4);
                }
            }
        }
    }
}
