package net.mcwow.bridge;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real ground under WoW's surface (breakable terrain, Phase 1, 2026-10-02). For each loaded chunk
 * near the player with WoW columns from benilla (McwowTerrainStore), fills every column from just
 * under the WoW surface down: surface layers by the WoW ground texture (grass, sand, snow...), then
 * stone, deepslate and ore veins, then bedrock. The top block stays under the WoW surface, so WoW's
 * terrain still covers it until digging (Phase 2) cuts a hole. Each chunk is filled once and marked
 * in the save (FILLED); blocks already there (the player's) are never replaced. Nearest chunks
 * first, under a per-tick time budget and chunk cap.
 *
 * <p>Blocks are set without telling clients one by one (2026-10-02: 1.25M per-block updates froze
 * the Minecraft client for a second while the server was fine); each filled chunk then goes out
 * whole, as when it loads. The server still lights every block (LevelChunk.setBlockState).
 */
public final class McwowTerrainFill {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");

    public static final int MAT_GRASS = 1, MAT_DIRT = 2, MAT_SAND = 3, MAT_SNOW = 4, MAT_STONE = 5,
            MAT_GRAVEL = 6, MAT_MUD = 7;
    /**
     * Outdoor water (WoW lakes and seas as Minecraft water) - OFF (user, 2026-10-02: parked as a
     * finishing touch; open: WoW's water shows through a boat's hull, which needs the boat's water
     * mask drawn depth-only in benilla). While off, each chunk's water placed earlier is removed once
     * per session (unwater) with its WATER/SURFACE data, which leaves FluidHeightMixin, the flow rule
     * and the boat water hooks idle. On again: chunks are watered afresh.
     */
    public static final boolean WATER_ENABLED = false;

    /** WoW terrain liquid kinds (benilla terrain.rs LIQ_*). Only water and ocean are filled so far. */
    public static final int LIQ_WATER = 1, LIQ_OCEAN = 2, LIQ_MAGMA = 3, LIQ_SLIME = 4;

    /** Chunks are filled out to this ring around the player (benilla sends 6). */
    private static final int RADIUS = 6;
    /** Blocks of ground under the top block; bedrock below. */
    public static final int DEPTH = 48;
    /** Deepslate from this far under the top block. */
    static final int DEEPSLATE_DEPTH = 24;
    /** The top block's top stays at least this far under the WoW surface. */
    private static final float SURFACE_GAP = 0.05F;
    /** A column whose fill was lowered this far under the surface is under a structure: rock only. */
    static final float UNDER_STRUCTURE = 0.5F;
    private static final long BUDGET_NANOS = 15_000_000L;
    /** Chunks per tick, so the client remeshes a few at a time rather than a burst. */
    private static final int MAX_CHUNKS_PER_TICK = 4;
    private static final int FLAGS = Block.UPDATE_KNOWN_SHAPE;

    public static final AttachmentType<Boolean> FILLED = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath("mcwow", "wow_ground_filled"), Codec.BOOL);

    /** A column of TOPS with no ground filled (a terrain hole, off the map). */
    public static final int NO_TOP = Integer.MIN_VALUE;

    /**
     * Each column's top filled block (y; NO_TOP for none), index z * 16 + x, recorded when the chunk
     * is filled, saved with it and synced to clients: the one truth for which block is a column's
     * top (whether it's dug, where its hole walls start). Recomputing it from the terrain benilla
     * sends each session drifted when the structure cut-off changed - a dug column then read as
     * closed, kept its WoW ground and walled itself in air (2026-10-02).
     */
    public static final AttachmentType<int[]> TOPS = AttachmentRegistry.<int[]>builder()
            .persistent(Codec.INT_STREAM.xmap(java.util.stream.IntStream::toArray, java.util.Arrays::stream))
            .syncWith(net.minecraft.network.codec.StreamCodec.of(
                            (net.minecraft.network.FriendlyByteBuf buf, int[] tops) -> buf.writeVarIntArray(tops),
                            net.minecraft.network.FriendlyByteBuf::readVarIntArray),
                    net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate.all())
            .buildAndRegister(Identifier.fromNamespaceAndPath("mcwow", "wow_ground_tops"));

    /**
     * Outdoor water (2026-10-02): each column's top water block (y; NO_TOP for none), index z * 16 + x,
     * recorded when the chunk's WoW lakes and seas were filled with water blocks; saved and synced to
     * clients. Its presence marks the chunk as watered. The exporter leaves these blocks out of WoW's
     * picture (McwowWorldExporter): WoW draws its own water there.
     */
    public static final AttachmentType<int[]> WATER = AttachmentRegistry.<int[]>builder()
            .persistent(Codec.INT_STREAM.xmap(java.util.stream.IntStream::toArray, java.util.Arrays::stream))
            .syncWith(net.minecraft.network.codec.StreamCodec.of(
                            (net.minecraft.network.FriendlyByteBuf buf, int[] tops) -> buf.writeVarIntArray(tops),
                            net.minecraft.network.FriendlyByteBuf::readVarIntArray),
                    net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate.all())
            .buildAndRegister(Identifier.fromNamespaceAndPath("mcwow", "wow_water_tops"));

    /**
     * Each column's exact WoW water surface (Minecraft y in 1/SURFACE_SCALE blocks; NO_TOP for none),
     * index z * 16 + x: the top water block's height (FluidHeightMixin). Its presence marks the chunk
     * as watered to the exact surface (2026-10-02; WATER alone: an earlier fill rounded to 8/9
     * source surfaces, a boat sat up to half a block under WoW's water).
     */
    public static final AttachmentType<int[]> SURFACE = AttachmentRegistry.<int[]>builder()
            .persistent(Codec.INT_STREAM.xmap(java.util.stream.IntStream::toArray, java.util.Arrays::stream))
            .syncWith(net.minecraft.network.codec.StreamCodec.of(
                            (net.minecraft.network.FriendlyByteBuf buf, int[] s) -> buf.writeVarIntArray(s),
                            net.minecraft.network.FriendlyByteBuf::readVarIntArray),
                    net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate.all())
            .buildAndRegister(Identifier.fromNamespaceAndPath("mcwow", "wow_water_surface"));
    public static final float SURFACE_SCALE = 1024.0F;
    /** Under this much of a block over a whole block, the surface rides on the block below's top. */
    private static final float THIN_TOP = 0.05F;
    /** Water placed without onPlace: no fluid tick per block (a sea is ~100k); lakes lie still until disturbed. */
    private static final int WATER_FLAGS = FLAGS | Block.UPDATE_SKIP_ON_PLACE;

    private static int statChunks;
    private static long statBlocks, statNanos, lastLogNanos;

    private McwowTerrainFill() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(McwowTerrainFill::serverTick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
                server -> digging = server.getDataStorage().computeIfAbsent(Setting.TYPE).on);
    }

    /**
     * Digging in the WoW world: ON by default (user, 2026-10-03, once the ground was placed only
     * where needed - McwowGroundReveal; the whole fill had cost frames and was off by default).
     * It is where ore comes from now (McwowZoneOres; the mine dimensions are gone, 2026-10-04), so
     * off means no mining: /mcwow digging on|off. Off, chunks filled earlier are emptied again (their
     * fill only, up to each column's top: what's built on WoW's ground stays).
     */
    private static volatile boolean digging;

    public static boolean digging() {
        return digging;
    }

    public static void setDigging(MinecraftServer server, boolean on) {
        Setting s = server.getDataStorage().computeIfAbsent(Setting.TYPE);
        s.on = on;
        s.setDirty();
        digging = on;
        LOGGER.info("mcwow-bridge: digging in the WoW world {}", on ? "on" : "off");
    }

    public static final class Setting extends net.minecraft.world.level.saveddata.SavedData {
        boolean on = true;
        // A new key: "digging" was saved false in worlds from when off was the default.
        static final com.mojang.serialization.Codec<Setting> CODEC = com.mojang.serialization.Codec.BOOL
                .optionalFieldOf("digging_on", true).codec().xmap(b -> {
                    Setting s = new Setting();
                    s.on = b;
                    return s;
                }, s -> s.on);
        static final net.minecraft.world.level.saveddata.SavedDataType<Setting> TYPE =
                new net.minecraft.world.level.saveddata.SavedDataType<>(
                        net.minecraft.resources.Identifier.fromNamespaceAndPath("mcwow", "terrain"), Setting::new, CODEC, null);
    }

    /** Digging off: a filled chunk's fill removed and its column data dropped. Returns blocks cleared. */
    private static int unfill(ServerLevel level, LevelChunk chunk) {
        int[] tops = chunk.getAttached(TOPS);
        int cleared = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 256; ++i) {
            if (tops[i] == NO_TOP) continue;
            int x = chunk.getPos().getMinBlockX() + (i & 15), z = chunk.getPos().getMinBlockZ() + (i >> 4);
            for (int y = bottomY(tops[i], level.getMinY()); y <= tops[i]; ++y) {
                pos.set(x, y, z);
                if (!chunk.getBlockState(pos).isAir() && level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)) cleared++;
            }
        }
        chunk.removeAttached(TOPS);
        chunk.removeAttached(FILLED);
        chunk.removeAttached(WATER);
        chunk.removeAttached(SURFACE);
        McwowGroundReveal.forget(chunk);
        return cleared;
    }

    private static void serverTick(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        if (!(player.level() instanceof ServerLevel level) || !McwowGeomStore.appliesTo(level)) return;
        int offX = McwowGeomStore.regionOffsetX, offZ = McwowGeomStore.regionOffsetZ;
        int pcx = Mth.floor((player.getX() - offX) / 16.0), pcz = Mth.floor((player.getZ() - offZ) / 16.0);
        long start = System.nanoTime();
        int done = 0;
        for (int r = 0; r <= RADIUS; ++r) {
            for (int dx = -r; dx <= r; ++dx) {
                for (int dz = -r; dz <= r; ++dz) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (System.nanoTime() - start > BUDGET_NANOS || done >= MAX_CHUNKS_PER_TICK) {
                        logStats();
                        return;
                    }
                    int cx = pcx + dx, cz = pcz + dz;
                    McwowTerrainStore.Chunk data = McwowTerrainStore.get(cx, cz);
                    if (data == null) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx + (offX >> 4), cz + (offZ >> 4));
                    if (chunk == null || McwowGroundReveal.pending(chunk)) continue;
                    if (!digging) {
                        if (!chunk.hasAttached(TOPS)) continue;
                        long t0 = System.nanoTime();
                        int n = unfill(level, chunk);
                        resend(level, chunk);
                        LOGGER.info("mcwow-bridge: terrain unfilled chunk {} ({} blocks, digging off)", chunk.getPos(), n);
                        statNanos += System.nanoTime() - t0;
                        done++;
                        continue;
                    }
                    if (chunk.hasAttached(TOPS)) {
                        boolean water = WATER_ENABLED ? waterOnce(level, chunk, data) | drainStrays(level, chunk)
                                : drainStrays(level, chunk) | unwater(level, chunk);
                        if (repair(level, chunk, data, offX, offZ) | setBiomeOnce(level, chunk, data) | water) {
                            resend(level, chunk);
                            done++;
                        }
                        continue;
                    }
                    long t0 = System.nanoTime();
                    if (Boolean.TRUE.equals(chunk.getAttached(FILLED))) {
                        // Filled before TOPS existed: read the tops back from the bedrock.
                        int[] recovered = recoverTops(level, chunk, data);
                        chunk.setAttached(TOPS, recovered);
                        long none = java.util.Arrays.stream(recovered).filter(t -> t == NO_TOP).count();
                        LOGGER.info("mcwow-bridge: terrain tops recovered for chunk {} ({} columns without ground)",
                                chunk.getPos(), none);
                        statNanos += System.nanoTime() - t0;
                        done++;
                        continue;
                    }
                    // Only the shell: the ground under it is placed where it's needed (McwowGroundReveal).
                    if (McwowGroundReveal.shell(level, chunk, data, false) == 0) chunk.setAttached(TOPS, noTops()); // no WoW ground at all
                    setBiome(level, chunk, data);
                    if (WATER_ENABLED) statBlocks += water(level, chunk, data);
                    statNanos += System.nanoTime() - t0;
                    statChunks++;
                    done++;
                }
            }
        }
        logStats();
    }

    /**
     * The filled chunk, whole, to every player that has it loaded - once the light engine (its own
     * thread) has lit the new ground: sent at once, the packet carried the empty chunk's daylight into
     * the new caves, which then glowed in WoW's sun deep underground (2026-10-02).
     */
    private static void resend(ServerLevel level, LevelChunk chunk) {
        resend(level, chunk, () -> {
        });
    }

    /** resend, then (on the server thread) then. */
    static void resend(ServerLevel level, LevelChunk chunk, Runnable then) {
        level.getChunkSource().getLightEngine().waitForPendingTasks(chunk.getPos().x(), chunk.getPos().z())
                .thenRunAsync(() -> {
                    var players = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
                    if (!players.isEmpty()) {
                        var packet = new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                chunk, level.getLightEngine(), null, null);
                        for (ServerPlayer p : players) p.connection.send(packet);
                    }
                    resendNeighbourLight(level, chunk);
                    then.run();
                }, level.getServer());
    }

    /**
     * The light of the eight chunks around a filled one, over its ground's height band: filling a
     * chunk darkens the edge of its neighbours' caves (they were lit sideways from its empty air
     * when they were filled first), and the server's corrected light never reached the client - a
     * cave chunk stayed lit like daylight up to a straight edge (2026-10-02: client sky 14, server 0).
     */
    private static void resendNeighbourLight(ServerLevel level, LevelChunk chunk) {
        int[] tops = chunk.getAttached(TOPS);
        if (tops == null) return;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int top : tops) {
            if (top == NO_TOP) continue;
            lo = Math.min(lo, bottomY(top, level.getMinY()));
            hi = Math.max(hi, top);
        }
        if (lo > hi) return;
        var engine = level.getLightEngine();
        // Light sections run from getMinLightSection (one under the world); bit 0 is that one.
        int first = (lo >> 4) - 1 - engine.getMinLightSection(), last = (hi >> 4) + 1 - engine.getMinLightSection();
        java.util.BitSet mask = new java.util.BitSet();
        mask.set(Math.max(0, first), Math.min(engine.getLightSectionCount(), last + 1));
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
                if (dx == 0 && dz == 0) continue;
                var at = new net.minecraft.world.level.ChunkPos(chunk.getPos().x() + dx, chunk.getPos().z() + dz);
                if (level.getChunkSource().getChunkNow(at.x(), at.z()) == null) continue;
                var players = level.getChunkSource().chunkMap.getPlayers(at, false);
                if (players.isEmpty()) continue;
                var packet = new net.minecraft.network.protocol.game.ClientboundLightUpdatePacket(at, engine, mask, mask);
                for (ServerPlayer p : players) p.connection.send(packet);
            }
        }
    }

    /**
     * A filled chunk's biome, by its ground: snowy plains, desert or plains. WoW's map dimensions are
     * void-biome worlds, whose empty spawn lists meant no mob ever spawned, not even in the dark caves
     * (2026-10-02); a real biome gives Minecraft's own spawning (strays in the snow, husks in sand).
     * Also tints grass as that biome. Returns false if it already had one.
     */
    static boolean setBiome(ServerLevel level, LevelChunk chunk, McwowTerrainStore.Chunk data) {
        var current = chunk.getNoiseBiome(0, chunk.getMinY() >> 2, 0);
        if (!current.is(net.minecraft.world.level.biome.Biomes.THE_VOID)) return false;
        int snow = 0, sand = 0, other = 0;
        for (int i = 0; i < 256; ++i) {
            if (Float.isNaN(data.fillTop()[i])) continue;
            switch (data.material()[i]) {
                case MAT_SNOW -> snow++;
                case MAT_SAND -> sand++;
                default -> other++;
            }
        }
        var key = snow > Math.max(sand, other) ? net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS
                : sand > other ? net.minecraft.world.level.biome.Biomes.DESERT
                : net.minecraft.world.level.biome.Biomes.PLAINS;
        var biome = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getOrThrow(key);
        chunk.fillBiomesFromNoise((x, y, z) -> biome);
        chunk.markUnsaved();
        return true;
    }

    private static void logStats() {
        long now = System.nanoTime();
        if (statChunks == 0 || now - lastLogNanos < 5_000_000_000L) return;
        LOGGER.info("mcwow-bridge: terrain fill: {} chunks, {} blocks, {} ms ({} ms/chunk); {} chunks of WoW ground known",
                statChunks, statBlocks, statNanos / 1_000_000, statNanos / 1_000_000 / statChunks,
                McwowTerrainStore.size());
        lastLogNanos = now;
        statChunks = 0;
        statBlocks = 0;
        statNanos = 0;
    }

    private static int[] noTops() {
        int[] tops = new int[256];
        java.util.Arrays.fill(tops, NO_TOP);
        return tops;
    }

    /** The top filled block of a column whose fill reaches fillTop (Minecraft y). */
    public static int topY(float fillTop) {
        return Mth.floor(fillTop - SURFACE_GAP) - 1;
    }

    /** The bedrock block under a column topped at top. */
    public static int bottomY(int top, int minY) {
        return Math.max(top - DEPTH, minY);
    }

    /**
     * A chunk filled before TOPS: each column's top is DEPTH above its bedrock, found near where the
     * terrain says it should be first, else anywhere in the column.
     */
    private static int[] recoverTops(ServerLevel level, LevelChunk chunk, McwowTerrainStore.Chunk data) {
        int[] tops = new int[256];
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 256; ++i) {
            tops[i] = NO_TOP;
            int x = baseX + (i & 15), z = baseZ + (i >> 4);
            float fillTop = data.fillTop()[i];
            int guess = Float.isNaN(fillTop) ? level.getMinY() : bottomY(topY(fillTop), level.getMinY());
            int found = Integer.MIN_VALUE;
            for (int dy = 0; dy <= 16 && found == Integer.MIN_VALUE; ++dy) {
                for (int y : new int[] { guess + dy, guess - dy }) {
                    if (y >= level.getMinY() && chunk.getBlockState(pos.set(x, y, z)).is(Blocks.BEDROCK)) {
                        found = y;
                        break;
                    }
                }
            }
            for (int y = level.getMinY(); found == Integer.MIN_VALUE && y < level.getMaxY(); ++y) {
                if (chunk.getBlockState(pos.set(x, y, z)).is(Blocks.BEDROCK)) found = y;
            }
            if (found != Integer.MIN_VALUE) tops[i] = found + DEPTH;
        }
        return tops;
    }

    /** Fills column i (z * 16 + x) of a chunk from its top block down to bedrock; returns the blocks placed. */
    private static int fillColumn(ServerLevel level, LevelChunk chunk, McwowTerrainStore.Chunk data, int i,
                                  int offX, int offZ, BlockPos.MutableBlockPos pos) {
        float fillTop = data.fillTop()[i];
        boolean underStructure = fillTop < data.surface()[i] - UNDER_STRUCTURE;
        int material = data.material()[i];
        McwowZoneOres.Ore[] ores = McwowZoneOres.oresFor(data.area()[i] & 0xFFFF);
        int top = topY(fillTop);
        int bottom = bottomY(top, level.getMinY());
        int x = chunk.getPos().getMinBlockX() + (i & 15), z = chunk.getPos().getMinBlockZ() + (i >> 4);
        int placed = 0;
        for (int y = top; y >= bottom; --y) {
            pos.set(x, y, z);
            if (!chunk.getBlockState(pos).isAir()) continue;
            int d = top - y;
            if (y != bottom && cave(x - offX, y, z - offZ, d, y - bottom)) continue;
            BlockState state = y == bottom ? Blocks.BEDROCK.defaultBlockState()
                    : underStructure ? rock(d, x - offX, y, z - offZ, ores) : ground(material, d, x - offX, y, z - offZ, ores);
            if (level.setBlock(pos, state, FLAGS)) placed++;
        }
        return placed;
    }

    /**
     * WoW's lakes, rivers and seas as Minecraft water (outdoor water, 2026-10-02): in each column
     * whose WoW liquid lies above its ground, water source blocks in the air from just over the
     * top block up to the block whose surface (8/9) is nearest WoW's. Air only, as the fill never
     * replaces a block. Records the tops in WATER; returns the blocks placed.
     */
    private static int water(ServerLevel level, LevelChunk chunk, McwowTerrainStore.Chunk data) {
        int[] tops = chunk.getAttached(TOPS);
        int[] waterTops = new int[256], surfaces = new int[256];
        java.util.Arrays.fill(waterTops, NO_TOP);
        java.util.Arrays.fill(surfaces, NO_TOP);
        int placed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; tops != null && i < 256; ++i) {
            int kind = data.liquid()[i];
            float liquidTop = data.liquidTop()[i];
            if (tops[i] == NO_TOP || (kind != LIQ_WATER && kind != LIQ_OCEAN) || Float.isNaN(liquidTop)
                    || !(liquidTop > data.surface()[i])) {
                continue;
            }
            int top = waterTop(liquidTop);
            if (top <= tops[i]) continue; // shallower than the gap over the top block
            int x = chunk.getPos().getMinBlockX() + (i & 15), z = chunk.getPos().getMinBlockZ() + (i >> 4);
            for (int y = tops[i] + 1; y <= top && y < level.getMaxY(); ++y) {
                pos.set(x, y, z);
                if (!chunk.getBlockState(pos).isAir()) continue;
                if (level.setBlock(pos, Blocks.WATER.defaultBlockState(), WATER_FLAGS)) placed++;
            }
            waterTops[i] = top;
            surfaces[i] = Math.round(liquidTop * SURFACE_SCALE);
        }
        chunk.setAttached(WATER, waterTops);
        chunk.setAttached(SURFACE, surfaces);
        return placed;
    }

    /**
     * The top water block of a column whose WoW liquid surface is at liquidTop (Minecraft y): the
     * block the surface lies in, whose water reports that height (FluidHeightMixin); a surface just
     * over a block's bottom rides on the full block below instead.
     */
    public static int waterTop(float liquidTop) {
        int y = Mth.floor(liquidTop);
        return liquidTop - y < THIN_TOP ? y - 1 : y;
    }

    /**
     * water() for a chunk filled before outdoor water, or before the exact surface (SURFACE): that
     * fill's tops were never above these, so it only adds water. Returns true if it placed any.
     */
    private static boolean waterOnce(ServerLevel level, LevelChunk chunk, McwowTerrainStore.Chunk data) {
        if (chunk.hasAttached(SURFACE)) return false;
        int placed = water(level, chunk, data);
        if (placed > 0) LOGGER.info("mcwow-bridge: WoW water filled in chunk {}: {} blocks", chunk.getPos(), placed);
        return placed > 0;
    }

    /**
     * With WATER_ENABLED off: the water placed in a chunk's WoW water columns (from over the top
     * block to its water top) is removed, with the chunk's WATER and SURFACE data. Returns true if
     * the chunk had any.
     */
    private static boolean unwater(ServerLevel level, LevelChunk chunk) {
        int[] water = chunk.getAttached(WATER), tops = chunk.getAttached(TOPS);
        if (water == null) return false;
        int removed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; tops != null && i < 256; ++i) {
            if (water[i] == NO_TOP || tops[i] == NO_TOP) continue;
            int x = chunk.getPos().getMinBlockX() + (i & 15), z = chunk.getPos().getMinBlockZ() + (i >> 4);
            for (int y = tops[i] + 1; y <= water[i] && y < level.getMaxY(); ++y) {
                pos.set(x, y, z);
                if (chunk.getBlockState(pos).is(Blocks.WATER) && level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)) {
                    removed++;
                }
            }
        }
        chunk.removeAttached(WATER);
        chunk.removeAttached(SURFACE);
        chunk.markUnsaved();
        if (removed > 0) LOGGER.info("mcwow-bridge: outdoor water off - removed {} water blocks in chunk {}", removed, chunk.getPos());
        return true;
    }

    /** Chunks checked for water that ran out of WoW's water this session. */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet DRAIN_CHECKED =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /** Blocks over a column's top block searched for stray flowing water. */
    private static final int STRAY_BAND = 12;

    /**
     * Flowing water that spread out of WoW's water before FlowingFluidMixin kept it in (2026-10-02:
     * river water lay on the banks, drawn over WoW's ground), removed once per chunk per session, in
     * chunks with WoW water in or beside them. Returns true if any was removed.
     */
    private static boolean drainStrays(ServerLevel level, LevelChunk chunk) {
        if (!DRAIN_CHECKED.add(chunk.getPos().pack())) return false;
        if (DRAIN_CHECKED.size() > 100_000) DRAIN_CHECKED.clear();
        boolean wet = false;
        for (int dx = -1; dx <= 1 && !wet; ++dx) {
            for (int dz = -1; dz <= 1 && !wet; ++dz) {
                LevelChunk near = level.getChunkSource().getChunkNow(chunk.getPos().x() + dx, chunk.getPos().z() + dz);
                int[] water = near == null ? null : near.getAttached(WATER);
                wet = water != null && java.util.Arrays.stream(water).anyMatch(t -> t != NO_TOP);
            }
        }
        int[] tops = chunk.getAttached(TOPS);
        if (!wet || tops == null) return false;
        int removed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 256; ++i) {
            if (tops[i] == NO_TOP) continue;
            int x = chunk.getPos().getMinBlockX() + (i & 15), z = chunk.getPos().getMinBlockZ() + (i >> 4);
            for (int y = tops[i] + 1; y <= tops[i] + STRAY_BAND && y < level.getMaxY(); ++y) {
                pos.set(x, y, z);
                var fluid = chunk.getBlockState(pos).getFluidState();
                if (!fluid.is(net.minecraft.tags.FluidTags.WATER) || McwowColumns.inWowWater(level, pos)) continue;
                // A source here formed from the spill (two sources make a third) if it is near WoW's
                // water; one placed away from it is the player's.
                if (fluid.isSource() && !touchesWowWater(level, pos)) continue;
                if (level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)) removed++;
            }
        }
        if (removed > 0) LOGGER.info("mcwow-bridge: removed {} stray flowing water blocks in chunk {}", removed, chunk.getPos());
        return removed > 0;
    }

    /** WoW water within 2 blocks sideways, diagonals included (a spill's source sat diagonally off it). */
    private static boolean touchesWowWater(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                if (McwowColumns.inWowWater(level, near.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz))) return true;
            }
        }
        return false;
    }

    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet BIOME_CHECKED =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** setBiome for a chunk filled before biomes were set, once per chunk per session. */
    private static boolean setBiomeOnce(ServerLevel level, LevelChunk chunk, McwowTerrainStore.Chunk data) {
        if (!BIOME_CHECKED.add(chunk.getPos().pack())) return false;
        if (BIOME_CHECKED.size() > 100_000) BIOME_CHECKED.clear();
        return setBiome(level, chunk, data);
    }

    /** Chunks whose unfilled columns were checked against the terrain this session. */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet REPAIR_CHECKED =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /**
     * Fills columns a chunk's fill skipped that do have WoW ground: a sample on the edge between two
     * MCNKs could round off both and read "no ground" (fixed in benilla's export, 2026-10-02), and
     * such a column had no blocks and no top - its WoW ground could never be dug away. Once per
     * chunk per session. Returns true if anything was filled.
     */
    private static boolean repair(ServerLevel level, LevelChunk chunk, McwowTerrainStore.Chunk data, int offX, int offZ) {
        if (!REPAIR_CHECKED.add(chunk.getPos().pack())) return false;
        if (REPAIR_CHECKED.size() > 100_000) REPAIR_CHECKED.clear();
        int[] tops = chunk.getAttached(TOPS);
        int[] fixed = null;
        int placed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Snow layers Minecraft's weather piled on the hidden ground before it was stopped
        // (ServerLevelPrecipitationMixin) poke through WoW's ground: cleared.
        int melted = 0;
        for (int i = 0; i < 256; ++i) {
            if (tops[i] == NO_TOP) continue;
            pos.set(chunk.getPos().getMinBlockX() + (i & 15), tops[i] + 1, chunk.getPos().getMinBlockZ() + (i >> 4));
            if (chunk.getBlockState(pos).is(Blocks.SNOW) && level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)) melted++;
        }
        if (melted > 0) LOGGER.info("mcwow-bridge: cleared {} weather snow layers in chunk {}", melted, chunk.getPos());
        if (chunk.hasAttached(McwowGroundReveal.PLACED) || java.util.Arrays.stream(tops).allMatch(t -> t == NO_TOP)) {
            int added = McwowGroundReveal.shell(level, chunk, data, true);
            if (added > 0) LOGGER.info("mcwow-bridge: terrain repair: chunk {} had {} columns with WoW ground but no top",
                    chunk.getPos(), added);
            return melted > 0 || added > 0;
        }
        for (int i = 0; i < 256; ++i) {
            if (tops[i] != NO_TOP || Float.isNaN(data.fillTop()[i])) continue;
            if (fixed == null) fixed = tops.clone();
            placed += fillColumn(level, chunk, data, i, offX, offZ, pos);
            fixed[i] = topY(data.fillTop()[i]);
        }
        if (fixed == null) return melted > 0;
        chunk.setAttached(TOPS, fixed);
        LOGGER.info("mcwow-bridge: terrain repair: chunk {} had {} unfilled columns with WoW ground; {} blocks placed",
                chunk.getPos(), java.util.Arrays.stream(fixed).filter(t -> t != NO_TOP).count()
                        - java.util.Arrays.stream(tops).filter(t -> t != NO_TOP).count(), placed);
        return true;
    }

    // Caves (breakable terrain, Phase 3, 2026-10-02): winding tunnels where two noises are both near
    // zero ("spaghetti"), and caverns where a third is high, deep enough down. A crust of CAVE_CRUST
    // blocks under the top stays solid, so a cave never opens under WoW's ground (it's found by
    // digging), and CAVE_FLOOR blocks over the bedrock. Region-local coordinates and fixed seeds:
    // the same place always gets the same caves. Ground filled before this has none.
    private static final int CAVE_CRUST = 6, CAVE_FLOOR = 2, CAVERN_DEPTH = 16;
    private static final net.minecraft.world.level.levelgen.synth.SimplexNoise TUNNEL_A =
            new net.minecraft.world.level.levelgen.synth.SimplexNoise(net.minecraft.util.RandomSource.create(0x5EED0001L));
    private static final net.minecraft.world.level.levelgen.synth.SimplexNoise TUNNEL_B =
            new net.minecraft.world.level.levelgen.synth.SimplexNoise(net.minecraft.util.RandomSource.create(0x5EED0002L));
    private static final net.minecraft.world.level.levelgen.synth.SimplexNoise CAVERN =
            new net.minecraft.world.level.levelgen.synth.SimplexNoise(net.minecraft.util.RandomSource.create(0x5EED0003L));

    /** Whether a block d under the top (aboveBottom over the bedrock) is cave air. */
    static boolean cave(int x, int y, int z, int d, int aboveBottom) {
        if (d < CAVE_CRUST || aboveBottom <= CAVE_FLOOR) return false;
        double a = TUNNEL_A.get(x / 28.0, y / 18.0, z / 28.0), b = TUNNEL_B.get(x / 28.0, y / 18.0, z / 28.0);
        if (a * a + b * b < 0.030) return true; // tunnels ~3-4 blocks wide, ~6% of the ground (measured)
        return d >= CAVERN_DEPTH && CAVERN.get(x / 48.0, y / 24.0, z / 48.0) > 0.76; // ~2.7%, 10-20 across
    }

    /** Surface layers by material, then rock. d = blocks under the top block. */
    static BlockState ground(int material, int d, int x, int y, int z, McwowZoneOres.Ore[] ores) {
        switch (material) {
            case MAT_GRASS -> {
                if (d == 0) return Blocks.GRASS_BLOCK.defaultBlockState();
                if (d <= 3) return Blocks.DIRT.defaultBlockState();
            }
            case MAT_SAND -> {
                if (d <= 2) return Blocks.SAND.defaultBlockState();
                if (d <= 4) return Blocks.SANDSTONE.defaultBlockState();
            }
            case MAT_SNOW -> {
                if (d == 0) return Blocks.SNOW_BLOCK.defaultBlockState();
                if (d <= 3) return Blocks.DIRT.defaultBlockState();
            }
            case MAT_GRAVEL -> {
                if (d <= 1) return Blocks.GRAVEL.defaultBlockState();
            }
            case MAT_MUD -> {
                if (d <= 1) return Blocks.MUD.defaultBlockState();
                if (d <= 3) return Blocks.DIRT.defaultBlockState();
            }
            case MAT_STONE -> {
            }
            default -> { // MAT_DIRT and anything unclassified
                if (d <= 3) return Blocks.DIRT.defaultBlockState();
            }
        }
        return rock(d, x, y, z, ores);
    }

    // Ore veins: each ore of the zone's table (McwowZoneOres) has a chance per 6x6x6 cell of one blob
    // (radius <= 1.4, kept inside the cell) at a hashed spot, within its depth band. First match
    // wins. Fixed coordinates, so the same place always gets the same ore.
    private static final int CELL = 6;

    static BlockState rock(int d, int x, int y, int z, McwowZoneOres.Ore[] ores) {
        boolean deep = d >= DEEPSLATE_DEPTH;
        int cx = Math.floorDiv(x, CELL), cy = Math.floorDiv(y, CELL), cz = Math.floorDiv(z, CELL);
        for (int k = 0; k < ores.length; ++k) {
            McwowZoneOres.Ore ore = ores[k];
            if (d < ore.minDepth() || d > ore.maxDepth()) continue;
            long h = hash(cx, cy, cz, k);
            if (Long.remainderUnsigned(h, 1000) >= ore.perMille()) continue;
            float ox = cx * CELL + 1.5F + 3.0F * ((h >>> 16) & 0xFFFF) / 65536.0F;
            float oy = cy * CELL + 1.5F + 3.0F * ((h >>> 32) & 0xFFFF) / 65536.0F;
            float oz = cz * CELL + 1.5F + 3.0F * ((h >>> 48) & 0xFFFF) / 65536.0F;
            float ex = x + 0.5F - ox, ey = y + 0.5F - oy, ez = z + 0.5F - oz;
            if (ex * ex + ey * ey + ez * ez <= ore.radius() * ore.radius()) return deep ? ore.deep() : ore.stone();
        }
        return deep ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private static long hash(int x, int y, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ salt * 0xD6E8FEB86659FD93L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
