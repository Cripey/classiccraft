package net.mcwow.bridge;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ground placed where it's needed (breakable terrain without the fill, 2026-10-03). Filling every
 * chunk passed with 48 blocks of ground cost frames; now a chunk gets only a SHELL - each column's
 * top block (and what holds up sand or gravel) - with its tops (TOPS), ground material (GROUND)
 * and a bit per ground cell (PLACED). Everything under the shell is VIRTUAL: the same ground,
 * caves and ores the fill would have made (McwowTerrainFill.ground/rock/cave, world coordinates),
 * placed only when it becomes visible or reachable:
 * <ul>
 * <li>a block in the ground turning into something you can see or walk through (dug, blown up,
 * water flowing in) places every virtual neighbour of it - breaking into a natural cave follows the
 * cave and walls it, out to REACH;</li>
 * <li>a player in the ground walls what lies around them as they walk (caves longer than REACH).</li>
 * </ul>
 * A cell's bit: for ground, placed (dug out later it stays air); for cave air, its neighbours were
 * placed. A chunk with TOPS but no PLACED was filled whole by the old fill: all of it is real.
 */
public final class McwowGroundReveal {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");

    /**
     * Each column's ground: McwowTerrainFill.MAT_* | UNDER (rock only, under a structure) | the WoW
     * area (AreaTable id) << AREA_SHIFT, whose zone picks the ores (McwowZoneOres).
     */
    public static final AttachmentType<int[]> GROUND = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath("mcwow", "wow_ground_kind"),
            Codec.INT_STREAM.xmap(java.util.stream.IntStream::toArray, java.util.Arrays::stream));
    /** A bit per ground cell, column i, d blocks under its top: i * CELLS + d. */
    public static final AttachmentType<long[]> PLACED = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath("mcwow", "wow_ground_placed"),
            Codec.LONG_STREAM.xmap(java.util.stream.LongStream::toArray, java.util.Arrays::stream));
    private static final int UNDER = 0x100, AREA_SHIFT = 16;
    private static final int CELLS = McwowTerrainFill.DEPTH + 1;

    /** How far a flood walls caves around where it starts (blocks, each axis). */
    private static final int REACH = 24;
    /** Cells one flood may visit. */
    private static final int MAX_CELLS = 40_000;
    /** A player in the ground is flooded around after moving this far (blocks, Manhattan). */
    private static final int WALK_STEP = 4;
    /** Clients told block by block (a reveal is tens to a few thousand blocks); no neighbour updates. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Direction[] DIRS = Direction.values();

    private static final java.util.Map<java.util.UUID, BlockPos> LAST_SEED = new java.util.HashMap<>();
    private static boolean busy;
    private static long statPlaced, lastLogNanos;

    private McwowGroundReveal() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(McwowGroundReveal::serverTick);
        // The zone ore tables now, not at the first dig: a broken table shows in the log at once.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
                server -> McwowZoneOres.oresFor(0));
    }

    /**
     * A new chunk's shell (onlyMissing false), or its columns with WoW ground but no top yet
     * (McwowTerrainFill.repair, onlyMissing true). Returns the columns given a top.
     */
    static int shell(ServerLevel level, LevelChunk chunk, McwowTerrainStore.Chunk data, boolean onlyMissing) {
        int[] tops = onlyMissing ? chunk.getAttached(McwowTerrainFill.TOPS).clone() : new int[256];
        int[] ground = onlyMissing && chunk.hasAttached(GROUND) ? chunk.getAttached(GROUND).clone() : new int[256];
        if (!onlyMissing) java.util.Arrays.fill(tops, McwowTerrainFill.NO_TOP);
        boolean[] added = new boolean[256];
        int columns = 0;
        for (int i = 0; i < 256; ++i) {
            float fillTop = data.fillTop()[i];
            if (Float.isNaN(fillTop) || tops[i] != McwowTerrainFill.NO_TOP) continue;
            tops[i] = McwowTerrainFill.topY(fillTop);
            ground[i] = data.material()[i] | (fillTop < data.surface()[i] - McwowTerrainFill.UNDER_STRUCTURE ? UNDER : 0)
                    | (data.area()[i] & 0xFFFF) << AREA_SHIFT;
            added[i] = true;
            columns++;
        }
        if (columns == 0) return 0;
        // The data first: place() reads it.
        if (!chunk.hasAttached(PLACED)) chunk.setAttached(PLACED, new long[(256 * CELLS + 63) / 64]);
        chunk.setAttached(GROUND, ground);
        // The tops reach clients only after the blocks: a client with a recorded top over air sees a
        // dug column and cuts WoW's ground out under the player (2026-10-03: sinking into the
        // terrain while walking). The tops are attached once the whole chunk has gone out.
        PENDING.put(chunk, tops);
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 256; ++i) {
            if (added[i]) statPlaced += place(level, baseX + (i & 15), tops[i], baseZ + (i >> 4), pos);
        }
        chunk.markUnsaved();
        // Whole, after its light: setBlock tells clients only in BLOCK_TICKING chunks, and the shell
        // reaches past the simulation distance - those blocks never arrived (2026-10-03).
        McwowTerrainFill.resend(level, chunk, () -> {
            int[] due = PENDING.remove(chunk);
            if (due != null) chunk.setAttached(McwowTerrainFill.TOPS, due);
            chunk.markUnsaved();
        });
        logStats();
        return columns;
    }

    /** Shelled chunks' tops waiting for their blocks to reach clients. */
    private static final java.util.Map<LevelChunk, int[]> PENDING = new java.util.IdentityHashMap<>();

    /** Whether a chunk's shell is placed and its tops not yet attached (McwowTerrainFill leaves it alone). */
    static boolean pending(LevelChunk chunk) {
        return PENDING.containsKey(chunk);
    }

    /** A chunk's tops: pending ones first (a repair's new columns), else attached. */
    private static int[] tops(LevelChunk chunk) {
        int[] p = PENDING.get(chunk);
        return p != null ? p : chunk.getAttached(McwowTerrainFill.TOPS);
    }

    /** Digging off (McwowTerrainFill.unfill): the chunk's ground data dropped. */
    static void forget(LevelChunk chunk) {
        chunk.removeAttached(PLACED);
        chunk.removeAttached(GROUND);
    }

    /** The chunk holding world column (x, z) if it is shelled ground (not the old whole fill), else null. */
    private static LevelChunk shelled(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        return chunk != null && chunk.hasAttached(PLACED) ? chunk : null;
    }

    /** The bit of ground cell (x, y, z) in a shelled chunk, or -1 outside its columns' ground. */
    private static int bit(ServerLevel level, LevelChunk chunk, int x, int y, int z) {
        int i = (z & 15) * 16 + (x & 15);
        int top = tops(chunk)[i];
        if (top == McwowTerrainFill.NO_TOP || y > top || y < McwowTerrainFill.bottomY(top, level.getMinY())) return -1;
        return i * CELLS + (top - y);
    }

    private static boolean isSet(long[] bits, int b) {
        return (bits[b >>> 6] & (1L << b)) != 0;
    }

    /**
     * Whether (x, y, z) is ground that is known - placed (maybe dug out since) or cave air already
     * walled. True outside shelled ground too: everything there is real.
     */
    public static boolean known(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = shelled(level, pos.getX(), pos.getZ());
        if (chunk == null) return true;
        int b = bit(level, chunk, pos.getX(), pos.getY(), pos.getZ());
        return b < 0 || isSet(chunk.getAttached(PLACED), b);
    }

    /**
     * For a flood: whether a space cell's neighbours are known to be placed already - a known ground
     * cell, or anything outside shelled ground. The gap over an open column has no bit and is looked
     * at every time (it is a few cells).
     */
    private static boolean walled(ServerLevel level, int x, int y, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        int[] tops = chunk == null ? null : tops(chunk);
        if (tops == null) return true;
        int top = tops[(z & 15) * 16 + (x & 15)];
        if (top == McwowTerrainFill.NO_TOP) return true;
        if (y > top) return false;
        long[] bits = chunk.getAttached(PLACED);
        if (bits == null) return true; // the old whole fill
        int b = bit(level, chunk, x, y, z);
        return b < 0 || isSet(bits, b);
    }

    /** The block virtual ground at (x, y, z) would be, or null: not virtual, or cave air. */
    private static BlockState hidden(ServerLevel level, int x, int y, int z) {
        LevelChunk chunk = shelled(level, x, z);
        if (chunk == null) return null;
        int b = bit(level, chunk, x, y, z);
        if (b < 0 || isSet(chunk.getAttached(PLACED), b)) return null;
        int i = (z & 15) * 16 + (x & 15);
        int top = tops(chunk)[i];
        int bottom = McwowTerrainFill.bottomY(top, level.getMinY()), d = top - y;
        if (y == bottom) return Blocks.BEDROCK.defaultBlockState();
        if (McwowTerrainFill.cave(x, y, z, d, y - bottom)) return null;
        int g = chunk.getAttached(GROUND)[i];
        McwowZoneOres.Ore[] ores = McwowZoneOres.oresFor(area(chunk, i, g));
        return (g & UNDER) != 0 ? McwowTerrainFill.rock(d, x, y, z, ores)
                : McwowTerrainFill.ground(g & 0xFF, d, x, y, z, ores);
    }

    /**
     * Column i's WoW area: from its ground word, or - shelled before the area was kept (2026-10-04) -
     * from the terrain benilla sent this session; 0 (Copper's ores) if neither knows.
     */
    private static int area(LevelChunk chunk, int i, int g) {
        int area = g >>> AREA_SHIFT;
        if (area != 0) return area;
        McwowTerrainStore.Chunk data = McwowTerrainStore.get(chunk.getPos().x() - (McwowGeomStore.regionOffsetX >> 4),
                chunk.getPos().z() - (McwowGeomStore.regionOffsetZ >> 4));
        return data == null ? 0 : data.area()[i] & 0xFFFF;
    }

    /** Marks ground cell (x, y, z) known (placed, or cave air walled). */
    private static void mark(ServerLevel level, int x, int y, int z) {
        LevelChunk chunk = shelled(level, x, z);
        if (chunk == null) return;
        int b = bit(level, chunk, x, y, z);
        if (b < 0) return;
        long[] bits = chunk.getAttached(PLACED);
        bits[b >>> 6] |= 1L << b;
        chunk.markUnsaved();
    }

    /**
     * Places virtual ground at (x, y, z), if it is any; sand or gravel gets what holds it up first
     * (unsupported it fell into the empty ground under it). Only into air: never over the player's
     * blocks. Returns the blocks placed.
     */
    private static int place(ServerLevel level, int x, int y, int z, BlockPos.MutableBlockPos pos) {
        BlockState state = hidden(level, x, y, z);
        if (state == null) return 0;
        mark(level, x, y, z);
        int placed = state.getBlock() instanceof FallingBlock ? place(level, x, y - 1, z, pos) : 0;
        pos.set(x, y, z);
        if (level.getBlockState(pos).isAir() && level.setBlock(pos, state, FLAGS)) placed++;
        return placed;
    }

    /**
     * Whether (x, y, z) is space in the ground a player could be in or see through: not a solid
     * opaque block, and either a ground cell that isn't virtual rock (dug, cave, the player's glass)
     * or the gap over an OPEN column's top block, up to its highest neighbour's top (a hole on a
     * slope looks at the uphill column's side). A closed column's gap lies under WoW's ground.
     */
    private static boolean space(ServerLevel level, int x, int y, int z, BlockPos.MutableBlockPos pos) {
        int top = McwowColumns.topOf(level, x, z);
        if (top == McwowTerrainFill.NO_TOP || y < McwowTerrainFill.bottomY(top, level.getMinY())) return false;
        if (level.getBlockState(pos.set(x, y, z)).isSolidRender()) return false;
        if (y <= top) return hidden(level, x, y, z) == null;
        if (!McwowColumns.isOpen(level, top, x, z, pos)) return false;
        int reach = top;
        for (int k = 2; k < 6; ++k) {
            int t = McwowColumns.topOf(level, x + DIRS[k].getStepX(), z + DIRS[k].getStepZ());
            if (t != McwowTerrainFill.NO_TOP) reach = Math.max(reach, t);
        }
        return y <= reach;
    }

    /**
     * From seed, through connected space within REACH: every space cell's virtual neighbours are
     * placed and the cell marked known. all: through known cells too (a walking player, whose
     * caves were walled only out to the last flood's reach); else known cells end the flood (a
     * dug block opens what's next to it, and a cave behind that). Returns the blocks placed.
     */
    private static int flood(ServerLevel level, BlockPos seed, boolean all) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        if (!space(level, seed.getX(), seed.getY(), seed.getZ(), pos)) return 0;
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(seed.asLong());
        queue.enqueue(seed.asLong());
        int placed = 0;
        boolean first = true;
        while (!queue.isEmpty() && seen.size() < MAX_CELLS) {
            long p = queue.dequeueLong();
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            boolean known = !first && walled(level, x, y, z);
            first = false;
            if (!known) {
                for (Direction dir : DIRS) placed += place(level, x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ(), pos);
                mark(level, x, y, z);
            } else if (!all) {
                continue;
            }
            for (Direction dir : DIRS) {
                int nx = x + dir.getStepX(), ny = y + dir.getStepY(), nz = z + dir.getStepZ();
                if (Math.abs(nx - seed.getX()) > REACH || Math.abs(ny - seed.getY()) > REACH
                        || Math.abs(nz - seed.getZ()) > REACH) {
                    continue;
                }
                long n = BlockPos.asLong(nx, ny, nz);
                if (!seen.contains(n) && space(level, nx, ny, nz, pos)) {
                    seen.add(n);
                    queue.enqueue(n);
                }
            }
        }
        return placed;
    }

    /**
     * A block changed (ServerLevelRevealMixin, after vanilla's own updates): turned into something
     * you can see or walk through, what's behind it is placed.
     */
    public static void changed(ServerLevel level, BlockPos pos, BlockState now) {
        // Server thread only. Chunk generation reaches here from worker threads too (WorldGenRegion
        // calls ServerLevel.updatePOIOnBlockStateChange); a chunk lookup from there waits on the
        // server thread, which was waiting on that generation - a deadlock (2026-10-03: the world
        // hung after a teleport; before that every generated block paid the round trip).
        if (!level.getServer().isSameThread()) return;
        if (busy || now.isSolidRender() || !McwowTerrainFill.digging() || !McwowGeomStore.appliesTo(level)) return;
        busy = true;
        try {
            int n = flood(level, pos.immutable(), false);
            statPlaced += n;
            if (n > 64) LOGGER.info("mcwow-bridge: ground revealed at {}: {} blocks", pos, n);
        } finally {
            busy = false;
        }
        logStats();
    }

    /** Players in the ground: what lies around them, as they walk. */
    private static void serverTick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) return;
        if (!McwowTerrainFill.digging()) {
            LAST_SEED.clear();
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel level) || !McwowGeomStore.appliesTo(level)) continue;
            BlockPos feet = player.blockPosition();
            BlockPos last = LAST_SEED.get(player.getUUID());
            if (last != null && last.distManhattan(feet) < WALK_STEP) continue;
            LAST_SEED.put(player.getUUID(), feet);
            busy = true;
            try {
                statPlaced += flood(level, feet, true);
            } finally {
                busy = false;
            }
        }
        logStats();
    }

    private static void logStats() {
        long now = System.nanoTime();
        if (statPlaced == 0 || now - lastLogNanos < 5_000_000_000L) return;
        LOGGER.info("mcwow-bridge: ground placed where needed: {} blocks", statPlaced);
        lastLogNanos = now;
        statPlaced = 0;
    }
}
