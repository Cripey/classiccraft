package net.mcwow.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Memory budget for the persistent WoW geometry cells (geom protocol v2, 2026-10-01). Every 2 s on
 * the server thread: cells near the player are marked visited; while the store holds more than
 * MAX_TRIANGLES (~70 bytes each incl. the spatial index -> ~175 MB), the least recently visited
 * cells are dropped and reported to geom_server (sent again on return) - except PINNED cells:
 * where Minecraft has the chunk loaded and the player has built (any non-air block - the WoW map
 * dimensions are otherwise empty) or items, XP, arrows or Minecraft mobs are lying around, the
 * ground must stay or they would lose it (user, 2026-10-01). Beyond Minecraft's loaded chunks
 * nothing simulates, so those cells may go; EntityFreezeMixin holds things still until the cell
 * is back.
 */
public final class McwowGeomCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    public static final long MAX_TRIANGLES = 2_500_000L;
    private static final double NEAR_BLOCKS = 96.0; // "visited" radius (~140 yd > geom_server's send radius)
    private static int tick;

    private McwowGeomCache() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(McwowGeomCache::serverTick);
    }

    private static void serverTick(MinecraftServer server) {
        if (++tick % 40 != 0) return;
        var dim = McwowGeomStore.activeDimension;
        ServerLevel level = dim != null ? server.getLevel(dim) : null;
        if (level == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() == level) {
                McwowGeomStore.touchLocal(p.getX() - McwowGeomStore.regionOffsetX, p.getZ() - McwowGeomStore.regionOffsetZ, NEAR_BLOCKS);
            }
        }
        long total = McwowGeomStore.triangleTotal();
        if (total <= MAX_TRIANGLES) return;
        long target = MAX_TRIANGLES * 9 / 10;
        List<Map.Entry<Long, long[]>> cells = new ArrayList<>(McwowGeomStore.CELLS.entrySet());
        cells.sort((a, b) -> Long.compare(a.getValue()[0], b.getValue()[0])); // least recently visited first
        long now = System.nanoTime();
        int dropped = 0, pinned = 0;
        for (Map.Entry<Long, long[]> e : cells) {
            if (total <= target) break;
            if (now - e.getValue()[0] < 30_000_000_000L) break; // visited in the last 30 s: keep the rest
            long id = e.getKey();
            if (isPinned(level, id)) {
                pinned++;
                continue;
            }
            total -= e.getValue()[1];
            McwowGeomStore.remove(id);
            var sink = McwowGeomStore.evictSink;
            if (sink != null) sink.accept(id);
            dropped++;
        }
        LOGGER.info("mcwow-bridge: geometry over budget - dropped {} least recently visited cells ({} kept pinned); {} triangles left",
                dropped, pinned, McwowGeomStore.triangleTotal());
    }

    /** The player's things are in this cell where Minecraft has it loaded. */
    static boolean isPinned(ServerLevel level, long cellId) {
        double[] b = McwowGeomStore.cellBoxLocal(cellId);
        double x0 = b[0] + McwowGeomStore.regionOffsetX, z0 = b[1] + McwowGeomStore.regionOffsetZ;
        double x1 = b[2] + McwowGeomStore.regionOffsetX, z1 = b[3] + McwowGeomStore.regionOffsetZ;
        boolean anyLoaded = false;
        for (int cx = (int) Math.floor(x0) >> 4; cx <= ((int) Math.floor(x1) >> 4); cx++) {
            for (int cz = (int) Math.floor(z0) >> 4; cz <= ((int) Math.floor(z1) >> 4); cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                anyLoaded = true;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (LevelChunkSection section : chunk.getSections()) {
                    if (!section.hasOnlyAir()) return true; // something built here
                }
            }
        }
        if (!anyLoaded) return false;
        AABB box = new AABB(x0, level.getMinY(), z0, x1, level.getMaxY(), z1);
        return !level.getEntities((Entity) null, box, e -> e instanceof ItemEntity || e instanceof ExperienceOrb
                || e instanceof AbstractArrow || e instanceof Mob).isEmpty();
    }
}
