package net.mcwow.bridge.client;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.world.level.LightLayer;
import net.mcwow.bridge.McwowTerrainFill;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ships Minecraft's blocks to WoW as geometry (protocol/mcwow_render_protocol.h): block and fluid
 * meshes built by Minecraft's own block renderer (models, tint, smooth lighting) and the texture
 * atlas; mcwow.dll draws them inside WoW's frame so WoW geometry occludes them. Port of SkyCraft's
 * WorldExporter (blocks part only - lights, NPC collision, dig holes and entities come later).
 * Sections are sent REGION-LOCAL: the instance region offset (McwowGeomStore.regionOffsetX/Z, a
 * multiple of 16) is taken off the section coords. Render thread only.
 */
public final class McwowWorldExporter {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final int SECTIONS_PER_FRAME = 12;

    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
    private static final LongOpenHashSet SENT = new LongOpenHashSet(); // sections WoW holds a mesh for
    private static final LongOpenHashSet LIT = new LongOpenHashSet(); // sections WoW holds lights for
    private static final ByteBuffer LIGHTS = ByteBuffer.allocate(16 * 16 * 16 * 8).order(ByteOrder.LITTLE_ENDIAN);
    public static final int REN_LIGHTS = 9; // McwowRenLights + McwowRenLight[count]
    private static final int REN_XP_CLAIM = 14; // leveling: a picked-up orb's WoW XP share
    private static final int REN_CHAT = 15; // a "." GM command typed in chat: u32 length, UTF-8
    private static final int REN_INTERACT = 17; // a right-click on the crosshair's WoW target
    private static final int REN_DIALOG = 19; // a choice in a WoW NPC window (McwowDialogs)
    private static final int REN_HARVEST = 21; // a WoW ore vein mined with a pickaxe: u64 its guid
    private static final int REN_RESPAWN = 20; // respawned after a death: u32 kind (0 home, 1 at), u32 map, f32 x, y, z, o
    private static final int REN_WAYGATE = 18; // a waygate travel: u32 map, f32 x, y, z, o, u64 owner
    private static int sentGeneration = Integer.MIN_VALUE;
    private static ClientLevel sentLevel;
    private static ResourceKey<Level> sentDimension;
    private static int sentOffX, sentOffZ;
    private static int meshesSent;
    private static int unloadScan;
    /** Each chunk's WATER attachment as last meshed with (by identity: a sync replaces the array). */
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> WATER_SEEN =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static McwowAtlas atlas;
    private static ModelBlockRenderer blockRenderer;
    private static FluidRenderer fluidRenderer;
    private static final MeshBuilder MESH = new MeshBuilder();

    private McwowWorldExporter() {
    }

    public static void markDirtyNow(int sx, int sy, int sz) {
        synchronized (DIRTY) {
            DIRTY.addAndMoveToFirst(SectionPos.asLong(sx, sy, sz));
        }
    }

    private static void markDirty(int sx, int sy, int sz) {
        synchronized (DIRTY) {
            DIRTY.add(SectionPos.asLong(sx, sy, sz));
        }
    }

    /** Called once per rendered frame (McwowFrameExporter.afterRender). */
    /** Phase 5: hits on WoW creatures' stand-ins (McwowCombat) -> MCWOW_REN_HIT for mcwow.dll. */
    private static void sendHits() {
        Integer ev;
        while ((ev = net.mcwow.bridge.combat.McwowCombat.EVENTS.peek()) != null) {
            ByteBuffer msg = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(ev).putInt(0).putLong(0L).flip();
            if (!McwowRenderLink.write(REN_EVENT, msg, null)) return;
            net.mcwow.bridge.combat.McwowCombat.EVENTS.poll();
        }
        ByteBuffer respawn;
        while ((respawn = net.mcwow.bridge.combat.McwowCombat.RESPAWNS.peek()) != null) {
            if (!McwowRenderLink.write(REN_RESPAWN, respawn.duplicate(), null)) return;
            net.mcwow.bridge.combat.McwowCombat.RESPAWNS.poll();
        }
        net.mcwow.bridge.combat.McwowCombat.WowHit hit;
        while ((hit = net.mcwow.bridge.combat.McwowCombat.HITS.peek()) != null) {
            ByteBuffer msg = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(hit.guid()).putInt(hit.wowDamage()).putInt(hit.flags())
                    .putInt(hit.attackerId()).putInt(0).flip();
            if (!McwowRenderLink.write(REN_HIT, msg, null)) return; // retried next frame
            net.mcwow.bridge.combat.McwowCombat.HITS.poll();
        }
        // Picked-up XP orbs' shares of a kill's WoW XP (leveling): REN_XP_CLAIM, u32 drop id, u32 XP.
        net.mcwow.bridge.combat.McwowXp.Claim claim;
        while ((claim = net.mcwow.bridge.combat.McwowXp.CLAIMS.peek()) != null) {
            ByteBuffer msg = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(claim.dropId()).putInt(claim.xp()).flip();
            if (!McwowRenderLink.write(REN_XP_CLAIM, msg, null)) return;
            net.mcwow.bridge.combat.McwowXp.CLAIMS.poll();
        }
        // Waygate travels (McwowWaygateClient).
        ByteBuffer gate;
        while ((gate = McwowWaygateClient.OUT.peek()) != null) {
            if (!McwowRenderLink.write(REN_WAYGATE, gate.duplicate(), null)) return;
            McwowWaygateClient.OUT.poll();
        }
        // Right-clicks on WoW NPCs and objects (McwowInteract).
        Long vein;
        while ((vein = McwowGather.HARVESTS.peek()) != null) {
            ByteBuffer msg = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(vein).flip();
            if (!McwowRenderLink.write(REN_HARVEST, msg, null)) return;
            McwowGather.HARVESTS.poll();
        }
        while (McwowInteract.OUT.get() > 0) {
            ByteBuffer msg = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).flip();
            if (!McwowRenderLink.write(REN_INTERACT, msg, null)) return;
            McwowInteract.OUT.decrementAndGet();
        }
        // Choices in WoW NPC windows (McwowDialogs).
        ByteBuffer dlg;
        while ((dlg = McwowDialogs.OUT.peek()) != null) {
            if (!McwowRenderLink.write(REN_DIALOG, dlg.duplicate(), null)) return;
            McwowDialogs.OUT.poll();
        }
        // GM commands typed in Minecraft's chat (McwowGmChat).
        String line;
        while ((line = McwowGmChat.OUT.peek()) != null) {
            byte[] text = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteBuffer msg = ByteBuffer.allocate(4 + text.length).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(text.length).put(text).flip();
            if (!McwowRenderLink.write(REN_CHAT, msg, null)) return;
            McwowGmChat.OUT.poll();
        }
        // The mob list for the server's proxies, whenever the server thread has a new one.
        long seq = net.mcwow.bridge.combat.McwowCombat.mobsSeq;
        if (seq != sentMobsSeq) {
            java.util.List<net.mcwow.bridge.combat.McwowCombat.Mob> mobs = net.mcwow.bridge.combat.McwowCombat.MOBS;
            ByteBuffer msg = ByteBuffer.allocate(8 + mobs.size() * 24).order(ByteOrder.LITTLE_ENDIAN);
            msg.putInt(mobs.size()).putInt(0);
            for (net.mcwow.bridge.combat.McwowCombat.Mob m : mobs) {
                msg.putInt(m.id()).put((byte) m.kind()).put((byte) m.hpPct()).putShort((short) 0)
                        .putFloat(m.x()).putFloat(m.y()).putFloat(m.z()).putFloat(m.yaw());
            }
            if (McwowRenderLink.write(REN_MOBS, msg.flip(), null)) sentMobsSeq = seq;
        }
    }

    private static long sentMobsSeq = -1;

    /**
     * REN_HIT (classiccraft, 24 bytes): u64 creature guid, u32 WoW damage, u32 HIT_* flags, u32 attacker
     * (0 the player, else a Minecraft mob's entity id), u32 pad. REN_MOBS: u32 count, u32 pad, then
     * per mob u32 id, u8 kind (1 hostile, 2 passive, 3 companion), u8 health %, u16 pad, f32 x, y, z
     * (region-local), f32 yaw (degrees).
     */
    public static final int REN_HIT = 10, REN_EVENT = 11, REN_MOBS = 12;

    public static void frame(Minecraft minecraft) {
        if (McwowRenderLink.active()) sendHits(); // also outside the WoW map (death/respawn events)
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || !McwowRenderLink.active()) {
            return;
        }
        // Only the WoW map's dimension is drawn in WoW; anywhere else (e.g. the overworld before
        // the first placement) send nothing.
        ResourceKey<Level> dim = McwowGeomStore.activeDimension;
        if (dim == null || level.dimension() != dim) {
            return;
        }
        int gen = McwowRenderLink.generation();
        if (gen != sentGeneration || sentLevel != level || sentDimension != dim
                || sentOffX != McwowGeomStore.regionOffsetX || sentOffZ != McwowGeomStore.regionOffsetZ
                || atlas == null || atlas.stale(minecraft)) {
            resendEverything(minecraft, level, gen, dim);
        }
        meshDirtySections(level);
        dropUnloaded(level);
        remeshWateredChunks(level, minecraft);
        McwowHoleExporter.frame(level, minecraft.player);
        // Animated textures (water, lava, fire, ...): the frame for this game tick.
        McwowEntityExporter.frame(minecraft, atlas, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        atlas.animate(level.getGameTime(), region -> {
            ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(region.x()).putInt(region.y()).putInt(region.w()).putInt(region.h()).flip();
            return McwowRenderLink.tryWrite(McwowRenderLink.REN_ATLAS_REGION, header, region.pixels());
        });
    }

    private static void resendEverything(Minecraft minecraft, ClientLevel level, int gen, ResourceKey<Level> dim) {
        sentGeneration = gen;
        sentLevel = level;
        sentDimension = dim;
        sentOffX = McwowGeomStore.regionOffsetX;
        sentOffZ = McwowGeomStore.regionOffsetZ;
        atlas = McwowAtlas.build(minecraft);
        McwowEntityExporter.reset(); // entity textures go again
        McwowTargeting.reset();
        McwowHoleExporter.reset();
        boolean ao = minecraft.options.ambientOcclusion().get();
        blockRenderer = new ModelBlockRenderer(ao, true, minecraft.getBlockColors());
        fluidRenderer = new FluidRenderer(minecraft.getModelManager().getFluidStateModelSet());
        McwowRenderLink.write(McwowRenderLink.REN_CLEAR_ALL, ByteBuffer.allocate(0), null);
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(atlas.width).putInt(atlas.height).flip();
        boolean ok = McwowRenderLink.write(McwowRenderLink.REN_ATLAS, header, atlas.pixels.duplicate().clear());
        LOGGER.info("mcwow-bridge: render resend - {}x{} atlas ({}), {} animated textures, dimension {} region ({}, {})",
                atlas.width, atlas.height, ok ? "ok" : "FAILED", atlas.animatedSprites(), dim.identifier(), sentOffX, sentOffZ);
        SENT.clear();
        LIT.clear();
        synchronized (DIRTY) {
            DIRTY.clear();
        }
        // Everything already loaded needs meshing again; later chunk loads mark themselves dirty.
        int radius = minecraft.options.getEffectiveRenderDistance() + 1;
        int pcx = SectionPos.blockToSectionCoord(minecraft.player.getBlockX());
        int pcz = SectionPos.blockToSectionCoord(minecraft.player.getBlockZ());
        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    if (!sections[i].hasOnlyAir()) markDirty(cx, chunk.getSectionYFromSectionIndex(i), cz);
                }
            }
        }
    }

    private static void meshDirtySections(ClientLevel level) {
        // Chunk loads dirty many all-air sections; those cost a lookup. Real meshing is limited.
        long deadline = System.nanoTime() + 3_000_000L;
        int meshed = 0;
        while (meshed < SECTIONS_PER_FRAME && System.nanoTime() < deadline) {
            long key;
            synchronized (DIRTY) {
                if (DIRTY.isEmpty()) return;
                key = DIRTY.removeFirstLong();
            }
            if (meshSection(level, key)) meshed++;
        }
    }

    /**
     * WoW holds only what Minecraft has loaded: a sent section whose chunk unloaded is cleared in
     * WoW (an empty mesh and no lights), and the chunk's reload marks it dirty again
     * (LevelExtractorMixin). Kept before, every section ever passed stayed in WoW - 4,350 sections,
     * 15M vertices and a growing frame cost after a short flight (2026-10-02). Checked twice a second.
     */
    private static void dropUnloaded(ClientLevel level) {
        if (++unloadScan % 30 != 0 || (SENT.isEmpty() && LIT.isEmpty())) return;
        var chunks = level.getChunkSource();
        LongOpenHashSet gone = new LongOpenHashSet();
        for (LongOpenHashSet set : new LongOpenHashSet[] { SENT, LIT }) {
            for (long key : set) {
                if (chunks.getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false) == null) gone.add(key);
            }
        }
        for (long key : gone) {
            int lsx = SectionPos.x(key) - (sentOffX >> 4), sy = SectionPos.y(key), lsz = SectionPos.z(key) - (sentOffZ >> 4);
            if (SENT.contains(key)) {
                ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(lsx).putInt(sy).putInt(lsz).putInt(0).flip();
                if (!McwowRenderLink.write(McwowRenderLink.REN_SECTION, header, null)) return; // ring full; next scan
                SENT.remove(key);
            }
            if (LIT.contains(key)) {
                ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(lsx).putInt(sy).putInt(lsz).putInt(0).flip();
                if (!McwowRenderLink.write(REN_LIGHTS, header, null)) return;
                LIT.remove(key);
            }
        }
    }

    /**
     * A chunk's WATER attachment reaches the client apart from its blocks, often after its sections
     * were meshed - their WoW-water blocks then went to WoW and showed over its banks (2026-10-02).
     * When it arrives or changes, the chunk's water band is meshed again. Checked twice a second
     * around the player.
     */
    private static void remeshWateredChunks(ClientLevel level, Minecraft minecraft) {
        if (unloadScan % 30 != 15 || minecraft.player == null) return;
        if (WATER_SEEN.size() > 8192) WATER_SEEN.clear();
        int radius = minecraft.options.getEffectiveRenderDistance() + 1;
        int pcx = minecraft.player.getBlockX() >> 4, pcz = minecraft.player.getBlockZ() >> 4;
        for (int cx = pcx - radius; cx <= pcx + radius; ++cx) {
            for (int cz = pcz - radius; cz <= pcz + radius; ++cz) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                int[] water = chunk.getAttached(McwowTerrainFill.WATER), tops = chunk.getAttached(McwowTerrainFill.TOPS);
                long key = net.minecraft.world.level.ChunkPos.pack(cx, cz);
                if (water == null || water == WATER_SEEN.get(key)) continue;
                WATER_SEEN.put(key, water);
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int i = 0; i < 256; ++i) {
                    if (water[i] == McwowTerrainFill.NO_TOP || tops == null || tops[i] == McwowTerrainFill.NO_TOP) continue;
                    lo = Math.min(lo, tops[i] + 1);
                    hi = Math.max(hi, water[i]);
                }
                for (int sy = lo >> 4; lo <= hi && sy <= hi >> 4; ++sy) markDirty(cx, sy, cz);
            }
        }
    }

    /** Returns true if real meshing work was done. */
    private static boolean meshSection(ClientLevel level, long key) {
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        if (chunk == null) return false; // unloaded: dropUnloaded clears it in WoW
        int index = level.getSectionIndexFromSectionY(sy);
        LevelChunkSection section = index >= 0 && index < chunk.getSections().length ? chunk.getSections()[index] : null;
        boolean empty = section == null || section.hasOnlyAir();
        MESH.reset();
        LIGHTS.clear();
        int lightCount = 0;
        MESH.cardinal = level.cardinalLighting();
        // Hole walls live in the gap cells over the ground, often an all-air section.
        addSkirts(level, sx, sy, sz);
        if (empty && MESH.vertexCount() == 0 && !SENT.contains(key) && !LIT.contains(key)) return false;
        if (!empty) {
            BlockPos origin = SectionPos.of(sx, sy, sz).origin();
            // WoW's own lakes and seas (outdoor water): WoW draws them, so their water blocks stay out.
            int[] waterTops = chunk.getAttached(McwowTerrainFill.WATER);
            int[] groundTops = chunk.getAttached(McwowTerrainFill.TOPS);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                        BlockState state = chunk.getBlockState(pos);
                        if (state.isAir()) continue;
                        // Light-emitting blocks (torches, lava, glowstone...) light WoW's world too.
                        int emission = state.getLightEmission();
                        if (emission > 0) {
                            LIGHTS.put((byte) x).put((byte) y).put((byte) z).put((byte) emission)
                                    .putInt(McwowBlockLightColors.of(state));
                            lightCount++;
                        }
                        FluidState fluid = state.getFluidState();
                        if (!fluid.isEmpty() && !(waterTops != null && groundTops != null
                                && fluid.is(net.minecraft.tags.FluidTags.WATER)
                                && origin.getY() + y <= waterTops[z * 16 + x]
                                && origin.getY() + y > groundTops[z * 16 + x])) {
                            // WoW ground in the fluid's cell: the fluid is drawn in the space above
                            // it (SkyCraft's fluidGround), so it sits on WoW's slope instead of
                            // poking through as a full cube - per vertex (squeeze()).
                            MESH.fluidActive = true;
                            MESH.fluidBaseY = y;
                            MESH.fluidOriginX = origin.getX() - McwowGeomStore.regionOffsetX;
                            MESH.fluidOriginY = origin.getY();
                            MESH.fluidOriginZ = origin.getZ() - McwowGeomStore.regionOffsetZ;
                            fluidRenderer.tesselate(level, pos, MESH, state, fluid);
                            MESH.fluidActive = false;
                        }
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
                            // A closed column's top block is buried under WoW's ground: seen from a
                            // hole beside it, its sides are under the wall's grass fringe, so they
                            // show its body (dirt for grass), not a second fringe (2026-10-02).
                            MESH.buriedSides = groundTops != null && groundTops[z * 16 + x] == origin.getY() + y
                                    && !McwowHoleExporter.isOpen(level, pos.getX(), pos.getZ()) ? fillSprite(state) : null;
                            MESH.hiddenFaces = MESH.buriedSides == null ? 0 : hiddenFaces(level, pos);
                            blockRenderer.tesselateBlock(MESH, x, y, z, level, pos.immutable(), state, model, state.getSeed(pos));
                            MESH.buriedSides = null;
                            MESH.hiddenFaces = 0;
                        }
                    }
                }
            }
        }
        if (MESH.vertexCount() == 0 && !SENT.contains(key) && lightCount == 0 && !LIT.contains(key)) return true;
        // Region-local section coords (the region offset is a whole number of sections).
        int lsx = sx - (sentOffX >> 4), lsz = sz - (sentOffZ >> 4);
        ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(lsx).putInt(sy).putInt(lsz).putInt(MESH.vertexCount()).flip();
        if (McwowRenderLink.write(McwowRenderLink.REN_SECTION, header, MESH.bytes())) {
            if (MESH.vertexCount() > 0) SENT.add(key);
            else SENT.remove(key);
            if (lightCount > 0 || LIT.contains(key)) {
                ByteBuffer lh = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(lsx).putInt(sy).putInt(lsz).putInt(lightCount).flip();
                if (!McwowRenderLink.write(REN_LIGHTS, lh, LIGHTS.flip())) markDirty(sx, sy, sz);
                else if (lightCount > 0) LIT.add(key);
                else LIT.remove(key);
            }
            if (++meshesSent <= 10 || meshesSent % 200 == 0) {
                LOGGER.info("mcwow-bridge: block mesh for section {} {} {} (local {} {} {}): {} vertices ({} sections in WoW)",
                        sx, sy, sz, lsx, sy, lsz, MESH.vertexCount(), SENT.size());
            }
        } else {
            markDirty(sx, sy, sz); // ring full; try again later
        }
        return true;
    }

    /**
     * Faces of a closed column's top block (buried under WoW's ground) that nothing can see: its top
     * always, and each side facing a closed neighbour's gap cell (also under WoW's ground). Drawn,
     * they showed through wherever WoW's ground dips under the block between the points its column
     * was sampled at - the player seemed to sink into the ground (2026-10-03, once every chunk near
     * the player got its shell). Sides facing a hole, a tunnel or a cave stay.
     */
    private static int hiddenFaces(ClientLevel level, BlockPos pos) {
        int hidden = 1 << Direction.UP.get3DDataValue();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = pos.getX() + d.getStepX(), nz = pos.getZ() + d.getStepZ();
            int top = McwowHoleExporter.topOf(level, nx, nz);
            if (top != McwowTerrainFill.NO_TOP && pos.getY() > top
                    && !net.mcwow.bridge.McwowColumns.isOpen(level, top, nx, nz, at)) {
                hidden |= 1 << d.get3DDataValue();
            }
        }
        return hidden;
    }

    /**
     * Hole walls (breakable terrain, 2026-10-02; SkyCraft's dig-hole walls): between a column's top
     * filled block and the WoW surface there is a gap the WoW terrain covers from above - looking
     * sideways into a dug column, it showed through to the world beyond. For each column still
     * closed next to an open one, a strip on that side from the top block up to the WoW ground along
     * the edge, in the top block's side texture (a grass block's green fringe meets the WoW ground).
     */
    private static void addSkirts(ClientLevel level, int sx, int sy, int sz) {
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        int[] tops = chunk == null ? null : chunk.getAttached(McwowTerrainFill.TOPS);
        if (tops == null) return;
        int offX = McwowGeomStore.regionOffsetX, offZ = McwowGeomStore.regionOffsetZ;
        int ox = sx << 4, oy = sy << 4, oz = sz << 4;
        MESH.originX = ox;
        MESH.originY = oy;
        MESH.originZ = oz;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 256; ++i) {
            int top = tops[i];
            if (top == McwowTerrainFill.NO_TOP) continue;
            int y0 = top + 1;
            if (y0 < oy || y0 > oy + 15) continue;
            int x = ox + (i & 15), z = oz + (i >> 4);
            if (McwowHoleExporter.isOpen(level, x, z)) continue; // itself open: its own blocks show
            BlockState state = level.getBlockState(pos.set(x, top, z));
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (!McwowHoleExporter.isOpen(level, x + d.getStepX(), z + d.getStepZ())) continue;
                pos.set(x + d.getStepX(), y0, z + d.getStepZ());
                int light = (level.getBrightness(LightLayer.BLOCK, pos) << 4) | (level.getBrightness(LightLayer.SKY, pos) << 20);
                MESH.skirt(sideSprite(state, d), fillSprite(state), x - ox, y0 - oy, z - oz, d, light,
                        (px, pz) -> wowGround(px - offX, pz - offZ, y0));
            }
        }
    }

    /** The WoW ground's height (Minecraft y) over a column's gap cell at a point, or NaN. */
    private static double wowGround(double lx, double lz, int gapY) {
        return net.mcwow.bridge.McwowTriHeight.lowestSurface(lx, lz, gapY - 0.05, gapY + 9.0);
    }

    private static final java.util.Map<BlockState, TextureAtlasSprite[]> SIDE_SPRITES = new java.util.HashMap<>();

    /** A block's body texture, for wall bands under the top one (grass: dirt, as its particle). */
    private static TextureAtlasSprite fillSprite(BlockState state) {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
    }

    /** A block's texture on one side: its model's first quad there (grass: the plain side), else the particle. */
    private static TextureAtlasSprite sideSprite(BlockState state, Direction d) {
        TextureAtlasSprite[] sides = SIDE_SPRITES.computeIfAbsent(state, s -> new TextureAtlasSprite[6]);
        if (sides[d.ordinal()] == null) {
            var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            var parts = new java.util.ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
            model.collectParts(net.minecraft.util.RandomSource.create(42L), parts);
            TextureAtlasSprite sprite = model.particleMaterial().sprite();
            for (var part : parts) {
                var quads = part.getQuads(d);
                if (!quads.isEmpty()) {
                    sprite = quads.get(0).materialInfo().sprite();
                    break;
                }
            }
            sides[d.ordinal()] = sprite;
        }
        return sides[d.ordinal()];
    }

    /**
     * Collects Minecraft's block quads (and fluid vertices) as triangles in the RenVertex layout:
     * section-relative position, combined-atlas UV, RGBA colour (tint and shading), block/sky light.
     * SkyCraft's MeshBuilder, minus the dig-hole walls and Skyrim-ground fluid squeezing.
     */
    private static final class MeshBuilder implements net.minecraft.client.renderer.block.BlockQuadOutput,
            FluidRenderer.Output, VertexConsumer {
        private ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
        private int vertices;
        private final float[] fq = new float[4 * 8]; // fluid quad assembly
        private int fqCount;
        private boolean fluidTranslucent;
        /** The level's fixed per-face brightness, which WoW-side lighting replaces. */
        CardinalLighting cardinal = CardinalLighting.DEFAULT;

        void reset() {
            this.buf.clear();
            this.vertices = 0;
            this.fqCount = 0;
        }

        int vertexCount() {
            return this.vertices;
        }

        ByteBuffer bytes() {
            return this.buf.duplicate().flip();
        }

        private void ensure(int bytes) {
            if (this.buf.remaining() < bytes) {
                ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(this.buf.capacity() * 2, this.buf.position() + bytes))
                        .order(ByteOrder.LITTLE_ENDIAN);
                this.buf.flip();
                bigger.put(this.buf);
                this.buf = bigger;
            }
        }

        private void vertex(float x, float y, float z, float u, float v, int argb, int light, int flags) {
            this.buf.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
            this.buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            int block = (light >> 4) & 0xF;
            int sky = (light >> 20) & 0xF;
            this.buf.putInt(block | (sky << 8));
            this.buf.putInt(flags);
            this.vertices++;
        }

        /** Segments per hole wall: WoW's ground bends between samples (2 yd triangles), so sample finely. */
        private static final int SKIRT_SEGMENTS = 8;
        /** How far into the closed cell the ground is sampled: off the clipped hole's edge, close to the line. */
        private static final float SKIRT_INSET = 0.002F;
        /** Tallest wall, blocks: a steep slope's edge stands well above the column's top block (3 cut walls short). */
        private static final float SKIRT_MAX = 8.0F;

        /**
         * A hole wall on side d of the cell at section-relative (x, y0, z): a strip along the shared
         * edge from y0 up to the WoW ground, sampled at SKIRT_SEGMENTS + 1 points, drawn in one-block
         * bands that follow the ground's line - the top band in the side texture (its top on the
         * ground), the ones under it in the block's body texture. Both windings: seen from the hole.
         */
        void skirt(TextureAtlasSprite side, TextureAtlasSprite body, int x, int y0, int z, Direction d, int light,
                   java.util.function.DoubleBinaryOperator ground) {
            // The edge runs along the side's axis-perpendicular line, at the face of the cell.
            float ex = x + (d.getStepX() > 0 ? 1 : 0), ez = z + (d.getStepZ() > 0 ? 1 : 0);
            boolean alongZ = d.getAxis() == Direction.Axis.X;
            int n = SKIRT_SEGMENTS + 1;
            float[] h = new float[n];
            boolean[] found = new boolean[n];
            float sum = 0.0F, tallest = 0.0F;
            int hits = 0;
            for (int k = 0; k < n; ++k) {
                // Sampled just inside this cell, off the corners too: a corner is shared with the next
                // column along, whose ground is gone when it's dug as well (the wall used to taper to
                // a point there).
                float along = Math.max(SKIRT_INSET, Math.min(1.0F - SKIRT_INSET, (float) k / SKIRT_SEGMENTS));
                float px = alongZ ? ex - d.getStepX() * SKIRT_INSET : x + along;
                float pz = alongZ ? z + along : ez - d.getStepZ() * SKIRT_INSET;
                double g = ground.applyAsDouble(px + this.originX, pz + this.originZ);
                found[k] = !Double.isNaN(g);
                if (found[k]) {
                    h[k] = (float) Math.max(0.0, Math.min(SKIRT_MAX, g - (y0 + this.originY)));
                    sum += h[k];
                    tallest = Math.max(tallest, h[k]);
                    hits++;
                }
            }
            if (hits == 0) return;
            // A sample with no ground found takes the edge's mean.
            for (int k = 0; k < n; ++k) {
                if (!found[k]) h[k] = sum / hits;
            }
            int flags = flags(false, d);
            int bands = (int) Math.ceil(tallest);
            for (int k = 0; k < SKIRT_SEGMENTS; ++k) {
                if (h[k] <= 0.01F && h[k + 1] <= 0.01F) continue;
                float s0 = (float) k / SKIRT_SEGMENTS, s1 = (float) (k + 1) / SKIRT_SEGMENTS;
                float ax = alongZ ? ex : x + s0, az = alongZ ? z + s0 : ez;
                float bx = alongZ ? ex : x + s1, bz = alongZ ? z + s1 : ez;
                for (int b = 0; b < bands; ++b) {
                    // Band b spans 1 block, b blocks under the ground's line, cut at y0.
                    float aTop = h[k] - b, bTop = h[k + 1] - b;
                    if (aTop <= 0.0F && bTop <= 0.0F) break;
                    aTop = Math.max(0.0F, aTop);
                    bTop = Math.max(0.0F, bTop);
                    float aBot = Math.max(0.0F, aTop - 1.0F), bBot = Math.max(0.0F, bTop - 1.0F);
                    TextureAtlasSprite sprite = b == 0 ? side : body;
                    this.ensure(12 * McwowRenderLink.VERTEX_BYTES);
                    float[][] quad = {
                            { ax, y0 + aBot, az, s0, aTop - aBot },
                            { bx, y0 + bBot, bz, s1, bTop - bBot },
                            { bx, y0 + bTop, bz, s1, 0.0F },
                            { ax, y0 + aTop, az, s0, 0.0F } };
                    for (int[] order : new int[][] { { 0, 1, 2, 0, 2, 3 }, { 0, 2, 1, 0, 3, 2 } }) {
                        for (int v : order) {
                            float[] q = quad[v];
                            this.vertex(q[0], q[1], q[2], atlas.u(sprite, sprite.getU(q[3])),
                                    atlas.v(sprite, sprite.getV(q[4])), 0xFFFFFFFF, light, flags);
                        }
                    }
                }
            }
        }

        /** World block coordinates of the section being meshed (for the skirt's ground lookups). */
        int originX, originY, originZ;

        /** Cutout or translucent, plus the face normal (Direction ordinal + 1; 0 = none). */
        private static int flags(boolean translucent, Direction normal) {
            return (translucent ? 2 : 1) | (normal == null ? 0 : (normal.ordinal() + 1) << 4);
        }

        /** Takes Minecraft's fixed face brightness back out of a colour, leaving tint and AO. */
        private static int unshade(int argb, float shade) {
            if (shade >= 0.999F || shade <= 0.0F) return argb;
            int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) / shade));
            int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) / shade));
            int b = Math.min(255, Math.round((argb & 0xFF) / shade));
            return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
        }

        /** While set, the block being meshed is a buried column top: its sides in this texture, no overlay. */
        TextureAtlasSprite buriedSides;
        /** Faces of the block being meshed left out (bit per Direction.get3DDataValue). */
        int hiddenFaces;

        // ---- block quads (BlockQuadOutput) ----
        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            TextureAtlasSprite from = quad.materialInfo().sprite();
            TextureAtlasSprite sprite = from;
            if ((this.hiddenFaces & (1 << quad.direction().get3DDataValue())) != 0) return;
            boolean buried = this.buriedSides != null && quad.direction().getAxis().isHorizontal();
            if (buried) {
                if (quad.materialInfo().isTinted()) return; // grass's side overlay: the fringe
                sprite = this.buriedSides;
            }
            this.ensure(6 * McwowRenderLink.VERTEX_BYTES);
            boolean translucent = quad.materialInfo().layer().translucent();
            int emission = quad.materialInfo().lightEmission();
            // Plants and the like are shaded as if facing up whatever way they face: no normal for them.
            Direction override = quad.materialInfo().shadeDirectionOverride();
            Direction normal = override == Direction.UP && quad.direction() != Direction.UP ? null
                    : override != null ? override : quad.direction();
            float shade = this.cardinal.byFace(override != null ? override : quad.direction());
            int flags = flags(translucent, normal);
            for (int k : new int[] { 0, 1, 2, 0, 2, 3 }) {
                var p = quad.position(k);
                long uv = quad.packedUV(k);
                float qu = UVPair.unpackU(uv), qv = UVPair.unpackV(uv);
                if (sprite != from) {
                    // The same place on the other sprite.
                    qu = sprite.getU((qu - from.getU0()) / (from.getU1() - from.getU0()));
                    qv = sprite.getV((qv - from.getV0()) / (from.getV1() - from.getV0()));
                }
                float u = atlas.u(sprite, qu);
                float v = atlas.v(sprite, qv);
                this.vertex(p.x() + x, p.y() + y, p.z() + z, u, v, unshade(instance.getColor(k), shade),
                        instance.getLightCoordsWithEmission(k, emission), flags);
            }
        }

        // ---- fluids (FluidRenderer.Output + VertexConsumer) ----
        // The fluid's cell (section-relative y) and the section's region-local origin: a Minecraft
        // fluid level counts from the cell's floor, so on WoW ground partway up the cell the fluid
        // is squeezed into the space above it (thin edges stay visible on the ground).
        boolean fluidActive;
        int fluidBaseY;
        int fluidOriginX, fluidOriginY, fluidOriginZ;

        /**
         * A fluid vertex's y, squeezed over WoW's ground at the vertex's own (x, z): neighbouring
         * cells share their corners, so their surfaces meet. With one ground height per cell they
         * stepped where the ground rose, and Minecraft draws no side between two water cells - WoW's
         * ground showed through the seams of placed water (2026-10-02).
         */
        private float squeeze(float x, float y, float z) {
            double base = this.fluidOriginY + this.fluidBaseY;
            double ground = net.mcwow.bridge.McwowTriHeight.lowestSurface(
                    this.fluidOriginX + x, this.fluidOriginZ + z, base - 0.001, base + 1.0);
            float g = Double.isNaN(ground) ? 0.0F : (float) Math.max(0.0, Math.min(1.0, ground - base));
            float t = Math.max(0.0F, Math.min(1.0F, y - this.fluidBaseY));
            return this.fluidBaseY + g + t * (1.0F - g);
        }

        @Override
        public VertexConsumer getBuilder(ChunkSectionLayer layer) {
            this.fluidTranslucent = layer.translucent();
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                              float nx, float ny, float nz) {
            int o = this.fqCount * 8;
            if (this.fluidActive) {
                y = this.squeeze(x, y, z);
            }
            this.fq[o] = x;
            this.fq[o + 1] = y;
            this.fq[o + 2] = z;
            this.fq[o + 3] = atlas.blockU(u);
            this.fq[o + 4] = atlas.blockV(v);
            this.fq[o + 5] = Float.intBitsToFloat(color);
            this.fq[o + 6] = Float.intBitsToFloat(light);
            if (++this.fqCount == 4) {
                this.fqCount = 0;
                this.ensure(6 * McwowRenderLink.VERTEX_BYTES);
                Direction normal = nx == 0 && ny == 0 && nz == 0 ? null : Direction.getApproximateNearest(nx, ny, nz);
                float shade = normal == null ? 1.0F
                        : normal.getAxis() == Direction.Axis.Y ? this.cardinal.byFace(normal)
                        : this.cardinal.up() * this.cardinal.byFace(normal);
                int flags = flags(this.fluidTranslucent, normal);
                for (int k : new int[] { 0, 1, 2, 0, 2, 3 }) {
                    int b = k * 8;
                    this.vertex(this.fq[b], this.fq[b + 1], this.fq[b + 2], this.fq[b + 3], this.fq[b + 4],
                            unshade(Float.floatToRawIntBits(this.fq[b + 5]), shade), Float.floatToRawIntBits(this.fq[b + 6]), flags);
                }
            }
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
