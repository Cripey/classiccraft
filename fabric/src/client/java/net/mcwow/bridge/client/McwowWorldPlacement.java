package net.mcwow.bridge.client;

import java.util.UUID;

import net.mcwow.bridge.McwowDecks;
import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where Steve lives in Minecraft (protocol v2, 2026-10-01) - replaces the old session anchor.
 *
 * <p>WoW and Minecraft positions are related by a FIXED mapping (protocol/mcwow_protocol.h,
 * MCWOW_WOW_TO_MC_*): MC x = WoW y / S, MC y = WoW z / S, MC z = WoW x / S. Each WoW map has its
 * own dimension {@code mcwow:map_<id>} (generated datapack, tools/gen_dimensions.py; dimension
 * type mcwow:wow, y -512..1535). Inside a dimension, each instance of a dungeon gets its own
 * region ("slot"), offset by a whole number of blocks - slot 0 is the map's open world.
 *
 * <p>WoW decides where the player IS only at "placements": mcwow.dll bumps wowPlayer.teleportSeq
 * on bridge enable, map change, or the game moving the player (portal, hearth, boat...). This class
 * then teleports Steve to the fixed-mapped WoW position in the right dimension/region, holds him
 * there until geometry for the new place has arrived, and publishes teleportAck = seq. Until the
 * ack the DLL writes neither puppet nor camera; after it, Minecraft is authoritative again.
 */
public final class McwowWorldPlacement {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");

    /** Must equal MCWOW_MC_BLOCKS_TO_WOW_YARDS (protocol/mcwow_protocol.h), same float math. */
    public static final float BLOCKS_TO_YARDS = 1.4667f;
    /** Region size per instance slot: 47 region files (> one WoW map, 23,272 blocks across). */
    public static final int REGION_BLOCKS = 47 * 512;
    private static final long REISSUE_NANOS = 5_000_000_000L;
    private static final long MAX_HOLD_NANOS = 5_000_000_000L;

    /** Snapshot of the WoW -> MC slot, read under the seqlock by McwowBridgeClient. */
    public record WowState(int mapId, int teleportSeq, float x, float y, float z, float facing,
                           int difficulty, long guid) {
    }

    private static int handledSeq; // seq we've issued a teleport for (0 = none yet)
    private static boolean arrived;
    private static boolean held;
    private static long issuedNanos, arrivedNanos;
    private static ResourceKey<Level> targetDim;
    private static Vec3 targetPos;
    private static float[] targetLocal = new float[3]; // region-local, like the geometry store
    private static volatile int ackOut;
    private static long lastMissingLog;
    private static int visitMap = -1, issuedSlot;
    private static long pendingSinceNanos;
    private static final long INSTANCE_WAIT_NANOS = 10_000_000_000L;

    private McwowWorldPlacement() {
    }

    /** A placement holds Steve (teleported, waiting for the ground or deck there). */
    public static boolean holding() {
        return handledSeq != 0 && !held;
    }

    /** Value McwowBridgeClient publishes as McwowMcCameraState::teleportAck. */
    public static int ack() {
        return ackOut;
    }

    public static ResourceKey<Level> dimensionFor(int mapId) {
        return ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("mcwow", "map_" + mapId));
    }

    /**
     * Whole-block offset of an instance slot's region. Slot 0 (open world) sits at the origin;
     * slots 1.. fill rows of 2000 regions at z >= 1 region, so none ever overlaps slot 0 and all
     * stay inside the 30M world border (~2.5M slots).
     */
    public static int[] regionOffset(int slot) {
        if (slot <= 0) return new int[] {0, 0};
        int n = slot - 1;
        int gx = (n % 2000) - 1000;
        int gz = (n / 2000) + 1;
        return new int[] {gx * REGION_BLOCKS, gz * REGION_BLOCKS};
    }

    public static void tick(Minecraft mc, WowState s) {
        if (mc.player == null || s == null) return;
        if (s.teleportSeq() == 0) { // bridge never enabled this WoW session - leave Steve alone
            ackOut = 0;
            return;
        }
        if (s.mapId() < 0) { // WoW not in world (loading screen): keep the DLL waiting
            ackOut = s.teleportSeq() - 1;
            return;
        }
        ResourceKey<Level> dim = dimensionFor(s.mapId());
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            ackOut = s.teleportSeq() - 1;
            return;
        }
        long now = System.nanoTime();
        // Which region of the dimension: 0 = open world; dungeon/raid runs and BG visits get their
        // own (McwowInstances). -1 while the instance id is still being looked up in the DB.
        boolean newVisit = s.mapId() != visitMap;
        if (newVisit) {
            visitMap = s.mapId();
            pendingSinceNanos = now;
        }
        int slot = McwowInstances.slotFor(server, s.mapId(), s.difficulty(), s.guid(), newVisit);
        if (slot < 0) {
            if (now - pendingSinceNanos < INSTANCE_WAIT_NANOS) {
                ackOut = s.teleportSeq() - 1; // DLL keeps waiting (WoW's own camera shows)
                return;
            }
            if (now - lastMissingLog > 5_000_000_000L) {
                lastMissingLog = now;
                LOGGER.warn("mcwow-bridge: instance id for map {} not found ({}) - using the "
                        + "map's open-world region for now", s.mapId(),
                        McwowInstances.lastDbError() != null ? McwowInstances.lastDbError()
                                : "no bind in the DB yet");
            }
            slot = 0;
        }
        boolean inTarget = mc.player.level().dimension() == dim;

        boolean newSeq = s.teleportSeq() != handledSeq;
        boolean lostIt = held && !inTarget;               // died/respawned elsewhere
        boolean stuck = !arrived && now - issuedNanos > REISSUE_NANOS;
        boolean slotChanged = !newSeq && slot != issuedSlot; // instance id resolved late
        if (newSeq || lostIt || stuck || slotChanged) {
            issue(mc, s, dim, slot, newSeq ? "seq " + s.teleportSeq()
                    : lostIt ? "left the WoW dimension"
                    : slotChanged ? "instance region resolved" : "teleport not arrived, retrying");
        }

        long deck = McwowDeckRide.riding();
        // Aboard, the deck carries Steve off the placement spot before this check ever saw him
        // there (a zeppelin crossing never "arrived" and never handed control back, 2026-10-02):
        // in the target dimension near WoW's live position counts.
        Vec3 live = new Vec3(s.y() / BLOCKS_TO_YARDS + (double) McwowGeomStore.regionOffsetX,
                s.z() / BLOCKS_TO_YARDS, s.x() / BLOCKS_TO_YARDS + (double) McwowGeomStore.regionOffsetZ);
        if (!arrived && inTarget && targetPos != null
                && (mc.player.position().distanceToSqr(targetPos) < 4.0
                        || deck != 0 && mc.player.position().distanceToSqr(live) < 64.0)) {
            arrived = true;
            arrivedNanos = now;
        }
        if (arrived && !held && deck != 0) {
            // Aboard a transport (a boat or zeppelin crossing to another continent): the deck moves
            // on during the hold, so Steve follows WoW's live position - benilla keeps the WoW body
            // on the deck - until the deck itself is in, which is the ground he needs; holding
            // him at the placement spot left him over open water as the ship sailed on.
            McwowDecks.Pose pose = McwowDecks.pose(deck);
            boolean deckIn = McwowDecks.has(deck) && pose != null;
            mc.player.resetFallDistance();
            if (deckIn || now - arrivedNanos > MAX_HOLD_NANOS) {
                held = true;
                resetServerFallDistance(mc);
                McwowWaygateClient.placed(mc);
                LOGGER.info("mcwow-bridge: placement seq {} done aboard deck {} in {}", handledSeq,
                        Long.toHexString(deck), dim.identifier());
            } else {
                mc.player.setDeltaMovement(Vec3.ZERO);
                mc.player.setPos(live);
            }
        } else if (arrived && !held) {
            // Hold Steve still until the new place's geometry is in, so he can't fall through
            // the empty dimension in the gap (geom_server republishes within ~0.2-1 s). Checked
            // as "a triangle under Steve", not "any geometry", so leftovers of the previous map
            // still draining from the ring can't end the hold early.
            boolean haveGeometry = !McwowGeomStore.near(targetLocal[0] - 1, targetLocal[1] - 64,
                    targetLocal[2] - 1, targetLocal[0] + 1, targetLocal[1] + 2,
                    targetLocal[2] + 1).isEmpty();
            mc.player.resetFallDistance();
            if (haveGeometry || now - arrivedNanos > MAX_HOLD_NANOS) {
                held = true;
                resetServerFallDistance(mc);
                McwowWaygateClient.placed(mc);
                LOGGER.info("mcwow-bridge: placement seq {} done at {} in {} ({} geometry objects)",
                        handledSeq, targetPos, dim.identifier(), McwowGeomStore.objectCount());
            } else {
                mc.player.setDeltaMovement(Vec3.ZERO);
                mc.player.setPos(targetPos);
            }
        }
        ackOut = (held && inTarget) ? handledSeq : handledSeq - 1;
    }

    /** The integrated server's copy of Steve, where fall damage is decided. */
    private static void resetServerFallDistance(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) sp.resetFallDistance();
        });
    }

    private static void issue(Minecraft mc, WowState s, ResourceKey<Level> dim, int slot,
                              String why) {
        long now = System.nanoTime();
        MinecraftServer server = mc.getSingleplayerServer();
        ServerLevel level = server == null ? null : server.getLevel(dim);
        if (level == null) {
            if (now - lastMissingLog > 5_000_000_000L) {
                lastMissingLog = now;
                LOGGER.warn("mcwow-bridge: cannot place Steve - {} ({})", server == null
                        ? "no integrated server (singleplayer only)"
                        : "dimension " + dim.identifier() + " missing (datapack not loaded?)",
                        why);
            }
            return;
        }
        int[] off = regionOffset(slot);
        // Fixed mapping, identical float math to MCWOW_WOW_TO_MC_* on the C++ side.
        float localX = s.y() / BLOCKS_TO_YARDS;
        float localY = s.z() / BLOCKS_TO_YARDS;
        float localZ = s.x() / BLOCKS_TO_YARDS;
        Vec3 pos = new Vec3(localX + (double) off[0], localY, localZ + (double) off[1]);
        // DLL: WoW facing = -mcYaw (radians) - see camdriver::Apply.
        float yaw = (float) Math.toDegrees(-s.facing());

        boolean geometryMoves = McwowGeomStore.activeDimension != dim
                || McwowGeomStore.regionOffsetX != off[0] || McwowGeomStore.regionOffsetZ != off[1];
        McwowGeomStore.activeDimension = dim;
        McwowGeomStore.regionOffsetX = off[0];
        McwowGeomStore.regionOffsetZ = off[1];
        if (geometryMoves) {
            McwowGeomStore.clear(); // stale geometry from the previous map
            McwowGeomClient.requestRefresh();
        }

        UUID id = mc.player.getUUID();
        float pitch = mc.player.getXRot();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) {
                sp.teleport(new TeleportTransition(level, pos, Vec3.ZERO, yaw, pitch,
                        TeleportTransition.DO_NOTHING));
                // A placement is WoW moving us, not a fall (classiccraft: Thunder Bluff ->
                // Stormwind killed Steve with the height difference).
                sp.resetFallDistance();
            }
        });
        LOGGER.info("mcwow-bridge: placing Steve ({}) - WoW map {} ({}, {}, {}) -> {} {} slot {}",
                why, s.mapId(), s.x(), s.y(), s.z(), dim.identifier(), pos, slot);
        handledSeq = s.teleportSeq();
        issuedSlot = slot;
        targetDim = dim;
        targetPos = pos;
        targetLocal = new float[] {localX, localY, localZ};
        arrived = false;
        held = false;
        issuedNanos = now;
    }
}
