package net.mcwow.bridge.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.mcwow.bridge.McwowGroundState;
import net.mcwow.bridge.McwowLinks;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Phase 0 ("Link") of the architecture pivot. Reads the shared file
// protocol/mcwow_protocol.h describes on every client tick and logs WoW's live player
// position/facing, proving the real Fabric/Loom toolchain can build and run against the same
// bridge fabric/prototype/McwowBridgeTestReader.java already proved works in plain Java.
//
// Byte layout mirrors protocol/mcwow_protocol.h by hand (Java can't #include a C header) - keep
// both in sync; there's no generator yet (noted as a Phase-0 TODO in that file too).
public final class McwowBridgeClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");

    private static final String SHM_PATH = McwowLinks.describe("classiccraft_v1.shm");
    private static final int MAGIC = 0x6D637731;
    // v3 (classiccraft): + ground contact, water, velocity, FOV for benilla's movement stream.
    private static final int VERSION = 3;
    private static final int TOTAL_SIZE = 260; // see protocol/mcwow_protocol.h
    private static final int GRID_RADIUS = 2; // 5x5

    private static final int OFF_MAGIC = 0, OFF_VERSION = 4, OFF_WOW_PID = 8, OFF_MC_PID = 12;
    private static final int OFF_WOW_HEARTBEAT = 16, OFF_MC_HEARTBEAT = 24;
    private static final int OFF_SEQ = 32, OFF_X = 36, OFF_Y = 40, OFF_Z = 44, OFF_FACING = 48,
            OFF_WOW_TICK = 52;
    // Added 2026-10-01, take 2 - WoW's real, independently-sensed ground height (via
    // wow::TraceLine, confirmed live: delta=-0.000 across three different real terrain
    // elevations - see CLAUDE.md "WorldExporter stage B, take 2"), ALREADY converted through the
    // anchor+scale into MC's own Y-up block coordinate system on the C++ side - no coordinate
    // math needed here. WIDENED 3x3 -> 5x5 after a live report: still falling through on
    // inclines, unable to recover once fallen through - a tight window gives almost no lead
    // distance against continuous/fast movement. `groundGrid[i]` is at MC block offset (dx,dz)
    // from the player's OWN column, dx,dz each in {-2,-1,0,1,2}, i=(dx+2)*5+(dz+2) - a fixed
    // pattern both sides hard-code, not sent over the wire.
    private static final int OFF_GROUND_GRID = 60; // 25 floats, 100 bytes (60..156)
    private static final int OFF_GROUND_MASK = 160; // bit i set = groundGrid[i] is a real hit
    // Protocol v2 (2026-10-01): map id + placement handshake + identity for the instance lookup.
    private static final int OFF_MAP_ID = 164, OFF_TELEPORT_SEQ = 168, OFF_DIFFICULTY = 172,
            OFF_PLAYER_GUID = 176;
    // McwowMcCameraState starts at 184 in v2 - offsets mirror protocol/mcwow_protocol.h's byte-offset
    // comment (checked there by the WoW-side static_assert, keep in sync by hand).
    private static final int OFF_MC_SEQ = 184, OFF_MC_X = 188, OFF_MC_Y = 192, OFF_MC_Z = 196,
            OFF_MC_YAW = 200, OFF_MC_PITCH = 204, OFF_MC_TICK = 208;
    // Player ENTITY feet, distinct from the camera/eye above (eye/feet conflation put WoW's
    // puppet ~1.6 blocks too high, confirmed live 2026-10-01).
    private static final int OFF_MC_FEET_X = 216, OFF_MC_FEET_Y = 220, OFF_MC_FEET_Z = 224;
    private static final int OFF_MC_FIRST_PERSON = 228;
    private static final int OFF_MC_TELEPORT_ACK = 232;
    private static final int OFF_MC_ON_GROUND = 236, OFF_MC_IN_WATER = 240, OFF_MC_VEL_X = 244,
            OFF_MC_VEL_Y = 248, OFF_MC_VEL_Z = 252, OFF_MC_FOV_DEG = 256;

    // Opened lazily on first tick, same "Init() once, reuse thereafter" pattern as
    // bridge::Init() on the WoW side - avoids retrying a file open every single tick before
    // WoW is even running.
    private ByteBuffer buf;
    private int openRetryTicks;
    private long lastWowHeartbeat, wowSeenNanos;
    private int tickCounter;

    @Override
    public void onInitializeClient() {
        McwowGearTooltip.init(); // item level / required level lines
        McwowDialogs.init(); // WoW NPC windows as Minecraft screens
        McwowQuestLog.init(); // WoW's quest log (J, /quests)
        // Knockback from WoW hits, Minecraft's own formula (LivingEntity.knockback) on the client
        // with the player's real velocity: airborne players keep their vertical speed.
        net.mcwow.bridge.combat.McwowCombat.clientKnockback = kb -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            var p = net.minecraft.client.Minecraft.getInstance().player;
            if (p == null) return;
            double power = kb[0] * (1.0 - p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE));
            if (power <= 0.0) return;
            var push = new net.minecraft.world.phys.Vec3(kb[1], 0.0, kb[2]).normalize().scale(power);
            var v = p.getDeltaMovement();
            p.setDeltaMovement(v.x / 2.0 - push.x, p.onGround() ? Math.min(0.4, v.y / 2.0 + power) : v.y, v.z / 2.0 - push.z);
        });
        net.mcwow.bridge.McwowGeomStore.evictSink = McwowGeomClient::evict;
        // WoW creature stand-ins (Phase 5): invisible, nothing to draw.
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                net.mcwow.bridge.combat.McwowCombat.WOW_ACTOR, net.minecraft.client.renderer.entity.NoopRenderer::new);
        // Wand bolts: their school's item sprite (McwowWands.LOOK) plus particles.
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                net.mcwow.bridge.McwowWands.BOLT, net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                net.mcwow.bridge.McwowGuns.BULLET, net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        // Diagnostic: what the crosshair is on, when it's a stand-in (throttled), to check the
        // boxes line up with WoW's creatures.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh
                    && eh.getEntity() instanceof net.mcwow.bridge.combat.McwowActorEntity a) {
                long now = System.nanoTime();
                if (now - lastAimLogNanos > 1_000_000_000L) {
                    lastAimLogNanos = now;
                    LOGGER.info("mcwow-bridge: crosshair on WoW creature stand-in #{} {}x{} blocks at ({}, {}, {})", a.getId(),
                            String.format("%.2f", a.getBbWidth()), String.format("%.2f", a.getBbHeight()),
                            String.format("%.1f", a.getX()), String.format("%.1f", a.getY()), String.format("%.1f", a.getZ()));
                }
            }
        });
        instance = this;
        LOGGER.info("mcwow-bridge client initialized - will look for {} on the first tick",
                SHM_PATH);
        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());
        ClientTickEvents.END_CLIENT_TICK.register(McwowTimeSync::tick);
        ClientTickEvents.END_CLIENT_TICK.register(McwowFootsteps::tick); // Minecraft steps on WoW ground
        ClientTickEvents.END_CLIENT_TICK.register(McwowBuriedRescue::tick); // out of the gap under WoW ground
        ClientTickEvents.START_CLIENT_TICK.register(McwowDeckRide::startTick); // carried by a transport deck
        ClientTickEvents.END_CLIENT_TICK.register(McwowDeckRide::endTick); // boarded or left one
        McwowGmChat.register(); // "." lines are WoW GM commands
        McwowDance.register(); // /dance [race] [m|f], /dance all, /dance stop
        McwowAutoWorld.register(); // first title screen: open the last world or make the Void one
        ClientTickEvents.END_CLIENT_TICK.register(McwowInteract::tick); // WoW under the crosshair
        ClientTickEvents.END_CLIENT_TICK.register(McwowGather::tick); // gathering WoW objects (after the focus)
        // Chopped WoW trees (McwowTrees), for the chop hint's regrow time.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                net.mcwow.bridge.McwowTrees.Chopped.TYPE, (msg, ctx) -> {
                    for (int k = 0; k < msg.keys().size(); ++k) {
                        net.mcwow.bridge.McwowTrees.CLIENT_CHOPPED.put(msg.keys().get(k), msg.regrow().get(k));
                    }
                });
        McwowWaygateClient.register(); // waygate naming and destinations
        ClientTickEvents.END_CLIENT_TICK.register(McwowDance::tick); // moving ends our own dance
        ClientTickEvents.END_CLIENT_TICK.register(McwowGmChat::tick); // their replies
        // No building aboard a transport (user, 2026-10-02): a placed block can't ride a moving deck.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) ->
                McwowDeckRide.riding() != 0
                        && player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem
                        ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        // Spike diagnostics (2026-10-01): integrated-server ticks over 50 ms.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(srv -> serverTickStart = System.nanoTime());
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(srv -> {
            long ms = (System.nanoTime() - serverTickStart) / 1_000_000;
            if (ms > 50) LOGGER.info("mcwow-bridge: SLOW SERVER TICK {} ms", ms);
        });
        // Real-geometry consumer (geom_server - see CLAUDE.md "Real WoW geometry export") - its
        // own background thread, started once, independent of this class's own per-tick file
        // (a separate shared-memory file, a separate process on the other end).
        McwowGeomClient.startIfNeeded();
        // Same predicate SkyCraft's own client sets: any Player (local AND the integrated server's
        // copy, so server-side movement validation agrees) skips the voxel shapes and uses the
        // exact-triangle collider instead.
        net.mcwow.bridge.McwowGeomStore.setSmoothCollider(
                e -> e instanceof net.minecraft.world.entity.player.Player
                        && net.mcwow.bridge.McwowGeomStore.objectCount() > 0);
    }

    private void onClientTick() {
        if (buf == null) {
            // Retry every 2 s: benilla (the WoW side, which creates the file) may start later.
            if (openRetryTicks-- > 0) return;
            openRetryTicks = 40;
            if (!tryOpen()) return;
        }

        int magic = buf.getInt(OFF_MAGIC);
        int version = buf.getInt(OFF_VERSION);
        if (magic != MAGIC || version != VERSION) {
            if (++tickCounter % 20 == 0) {
                LOGGER.warn("mcwow-bridge: bad magic/version in {} (magic=0x{} version={})",
                        SHM_PATH, Integer.toHexString(magic), version);
            }
            return;
        }

        // Camera/feet are no longer written here (20 Hz ticks stepped visibly in WoW) - see
        // publishFrame(), called after every rendered frame.

        float x = 0, y = 0, z = 0, facing = 0;
        float[] groundGrid = new float[25];
        int groundMask = 0;
        long tick = 0;
        int mapId = -1, teleportSeq = 0, difficulty = 0;
        long guid = 0;
        for (int retry = 0; retry < 50; ++retry) {
            int s1 = buf.getInt(OFF_SEQ);
            if ((s1 & 1) != 0) continue; // writer mid-update, retry
            x = buf.getFloat(OFF_X);
            y = buf.getFloat(OFF_Y);
            z = buf.getFloat(OFF_Z);
            facing = buf.getFloat(OFF_FACING);
            for (int i = 0; i < 25; ++i) {
                groundGrid[i] = buf.getFloat(OFF_GROUND_GRID + i * 4);
            }
            groundMask = buf.getInt(OFF_GROUND_MASK);
            tick = buf.getLong(OFF_WOW_TICK);
            mapId = buf.getInt(OFF_MAP_ID); // 0xFFFFFFFF (= -1) when WoW can't read it
            teleportSeq = buf.getInt(OFF_TELEPORT_SEQ);
            // Low byte: the instance difficulty (vanilla: 0). Bits 8-15: the WoW terrain type under
            // the player + 1, for Minecraft's footsteps (McwowFootsteps).
            int difficultyWord = buf.getInt(OFF_DIFFICULTY);
            difficulty = difficultyWord & 0xFF;
            net.mcwow.bridge.McwowGroundState.wowTerrain = ((difficultyWord >>> 8) & 0xFF) - 1;
            guid = buf.getLong(OFF_PLAYER_GUID);
            int s2 = buf.getInt(OFF_SEQ);
            if (s1 == s2) break;
        }

        // Every tick, not throttled - BlockCollisionsMixin reads this live on the collision
        // thread; no real block is ever placed (see CLAUDE.md "SkyCraft's own BlockCollisions
        // mixin" - the user's request to check how the Skyrim mod actually does this). Each
        // valid grid cell's (dx,dz) offset (the SAME fixed pattern the WoW side used to sample
        // it) is translated into an absolute MC block column centered on the player's own
        // current column, since the grid itself carries no coordinates, only heights.
        // Act on WoW's data only while WoW is alive (its heartbeat moved within the last second):
        // a file left by a previous run still holds a position and a teleportSeq, and placing
        // Steve from it dropped him into the void (classiccraft, 2026-10-02).
        long wowHb = buf.getLong(OFF_WOW_HEARTBEAT);
        long nowNanos = System.nanoTime();
        if (wowHb != lastWowHeartbeat) {
            lastWowHeartbeat = wowHb;
            wowSeenNanos = nowNanos;
        }
        boolean wowAlive = wowSeenNanos != 0 && nowNanos - wowSeenNanos < 1_000_000_000L;
        Minecraft mcForGrid = Minecraft.getInstance();
        if (mcForGrid != null && wowAlive) {
            McwowWorldPlacement.tick(mcForGrid, new McwowWorldPlacement.WowState(mapId, teleportSeq,
                    x, y, z, facing, difficulty, guid));
        }
        if (mcForGrid != null && mcForGrid.player != null) {
            BlockPos center = mcForGrid.player.blockPosition();
            // Shared reference Y for BlockCollisionsMixin's real-triangle queries (added
            // 2026-10-02 - see McwowGroundState.playerRefY for why a per-row reference caused
            // doorways to get blocked by unrelated elevated geometry).
            McwowGroundState.playerRefY = mcForGrid.player.getY();
            List<McwowGroundState.Cell> cells = new ArrayList<>(25);
            for (int dx = -GRID_RADIUS; dx <= GRID_RADIUS; ++dx) {
                for (int dz = -GRID_RADIUS; dz <= GRID_RADIUS; ++dz) {
                    int idx = (dx + GRID_RADIUS) * 5 + (dz + GRID_RADIUS);
                    if ((groundMask & (1 << idx)) == 0) continue;
                    cells.add(new McwowGroundState.Cell(center.getX() + dx, center.getZ() + dz,
                            groundGrid[idx]));
                }
            }
            McwowGroundState.cells = cells.toArray(new McwowGroundState.Cell[0]);
            int centerIdx = GRID_RADIUS * 5 + GRID_RADIUS;
            McwowGroundState.wowGroundUnderPlayer =
                    (groundMask & (1 << centerIdx)) != 0 ? groundGrid[centerIdx] : Double.NaN;
            maybeRescue(mcForGrid, center);
            raiseStepHeight(mcForGrid);
        }

        // Log once a second (20 ticks), not every tick - same throttling discipline used all
        // over the WoW-side C++ for exactly the same reason (avoid flooding the log).
        if (++tickCounter % 20 != 0) return;
        long heartbeat = buf.getLong(OFF_WOW_HEARTBEAT);
        long wowPid = Integer.toUnsignedLong(buf.getInt(OFF_WOW_PID));
        LOGGER.info("mcwow-bridge: wowPid={} heartbeat={} map={} pos=({}, {}, {}) facing={} "
                + "seq={} ack={} diff={} guid={} groundMask={} tick={}", wowPid, heartbeat, mapId,
                x, y, z, facing, teleportSeq, McwowWorldPlacement.ack(), difficulty,
                Long.toHexString(guid), Integer.toBinaryString(groundMask), tick);
    }

    // Stuck-recovery (2026-10-01), added after a live report: "when I fall through the ground I
    // can't get back up." Once the player ends up well below WoW's real sensed ground (the grid
    // not having caught up in time, or a fall into a gap the grid never covered), there is
    // nothing else to climb back onto in this empty Minecraft world - MC's own gravity just
    // keeps them falling forever. SkyCraft avoids this systemically (a wide exported mesh rarely
    // leaves a gap at all); this is the bounded, reactive alternative: if the player's own
    // column has a valid sensed ground height and they are DROPPING_THRESHOLD blocks below it
    // for SUSTAINED_TICKS in a row (not a single frame - a normal jump dips you by less than
    // this only briefly, so requiring it to persist avoids "rescuing" an ordinary jump), teleport
    // them back up to stand on it.
    private static final double DROPPING_THRESHOLD = 3.0;
    private static final int SUSTAINED_TICKS = 15; // ~0.75s at 20 tps
    private int stuckTicks;

    private void maybeRescue(Minecraft mc, BlockPos center) {
        McwowGroundState.Cell here = null;
        for (McwowGroundState.Cell cell : McwowGroundState.cells) {
            if (cell.x() == center.getX() && cell.z() == center.getZ()) {
                here = cell;
                break;
            }
        }
        if (here == null) {
            stuckTicks = 0;
            return;
        }
        double playerY = mc.player.getY();
        if (playerY < here.groundY() - DROPPING_THRESHOLD) {
            if (++stuckTicks >= SUSTAINED_TICKS) {
                LOGGER.warn("mcwow-bridge: rescuing player from ({}, {}, {}) up to sensed "
                        + "ground {} - stuck {} ticks below it", mc.player.getX(), playerY,
                        mc.player.getZ(), here.groundY(), stuckTicks);
                mc.player.teleportTo(mc.player.getX(), here.groundY(), mc.player.getZ());
                mc.player.setDeltaMovement(mc.player.getDeltaMovement().multiply(1, 0, 1));
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
    }

    // Raised step height (2026-10-01), added after a live report: "now I just get stuck on the
    // first stair." Root cause: this system quantizes WoW's continuous terrain into discrete
    // 1-block MC columns, each with a single sensed height (see CLAUDE.md "still falling
    // through on inclines" for why - a reactive grid, not SkyCraft's own full exported mesh of
    // real sloped triangles). Even a small real WoW stair riser becomes a vertical "cliff"
    // between adjacent MC columns in this representation, which Minecraft's default 0.6-block
    // auto-step can't absorb - the player would need to jump at every single column boundary,
    // the opposite of the user's actual goal ("just being able to run around and walk up stairs
    // ...seamlessly as it would be in World of Warcraft"). Raising the player's own
    // `Attributes.STEP_HEIGHT` (confirmed real via `javap` - same attribute horses/other
    // step-tall mobs use) lets Minecraft's OWN movement code auto-climb our blocky
    // representation of each riser without a jump, the same way a real WoW character just walks
    // up real stairs. Set every tick (cheap, a single attribute write) rather than once, so it
    // stays correct across respawns/attribute resets without needing to track world-join events.
    // Back to vanilla 0.6 (2026-10-01): the 1.5 bump was a workaround for the old per-column
    // height representation. With the exact-triangle collider (SkyCraft's TriCollider port),
    // real stair risers are stepped smoothly at vanilla height, exactly as SkyCraft does - and 1.5
    // would let the player walk straight up onto barrels/railings/ledges.
    private static final double STEP_HEIGHT_BLOCKS = 0.6; // vanilla default

    private static void raiseStepHeight(Minecraft mc) {
        var attr = mc.player.getAttribute(Attributes.STEP_HEIGHT);
        if (attr != null && attr.getBaseValue() != STEP_HEIGHT_BLOCKS) {
            attr.setBaseValue(STEP_HEIGHT_BLOCKS);
        }
    }

    // Writer-side seqlock (mirrors bridge::PublishWowPlayerState's own pattern on the WoW side,
    // just in the opposite direction): odd seq while writing tells a concurrent reader to
    // retry, even seq means safe to read. Reads the real Minecraft camera every tick - confirmed
    // against the actual 26.3 jar via javap (Camera.position()/.yRot()/.xRot()), not guessed.
    private static McwowBridgeClient instance;
    private static volatile long serverTickStart;
    private static long lastAimLogNanos;

    /**
     * Called right after every rendered Minecraft frame (MinecraftMixin -> McwowFrameExporter),
     * not per 20 Hz tick: smoothness fix 2026-10-01 - WoW renders ~200 fps and was being driven
     * by camera/feet samples that only changed 20 times a second, so both the camera and WASD
     * movement visibly stepped. Same as SkyCraft, which publishes in its afterRender every frame.
     */
    public static void publishFrame() {
        McwowBridgeClient c = instance;
        if (c != null && c.buf != null) c.writeMcCamera();
    }

    private void writeMcCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null || mc.player == null) return;
        Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null || !cam.isInitialized()) return;
        Vec3 pos = cam.position();
        float yaw = cam.yRot();
        float pitch = cam.xRot();
        // The player ENTITY's own feet position - distinct from the camera/eye position above
        // (see chasmlol/SkyCraft's own SkyClient.java: "mc.x/y/z = feet.x/y/z" vs "mc.eyeX/Y/Z
        // = eye.x/y/z", published separately for exactly this reason).
        // Interpolated between ticks exactly as Minecraft draws the player this frame (the raw
        // position() only changes at 20 Hz).
        Vec3 feet = mc.player.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        // Region-local (protocol v2): the instance slot's whole-block offset comes off here, so the
        // shared file only ever carries small, float-precise coordinates.
        double offX = net.mcwow.bridge.McwowGeomStore.regionOffsetX;
        double offZ = net.mcwow.bridge.McwowGeomStore.regionOffsetZ;

        // Aboard a transport: the same pose relative to its deck, written first so benilla never
        // reads a newer camera with an older rider block (McwowDeckRide).
        McwowDeckRide.publish(pos, feet, yaw, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));

        int seq = buf.getInt(OFF_MC_SEQ);
        // Self-heal (2026-10-01): if a PREVIOUS writer was killed (SIGKILL, window closed hard)
        // exactly between its own "+1" and "+2" writes, seq is left stuck ODD forever - "+2"
        // preserves parity, so every future write would stay odd too, and WoW's reader (which
        // requires an even seq before trusting a sample) would refuse this data permanently.
        // Confirmed live 2026-10-01: exactly this happened after a `pkill -9` mid-session,
        // explaining "Numpad+ isn't doing anything" - the anchor kept silently falling back to
        // (0,0,0) every time. Correcting to even here, on the very first write after any such
        // kill, restores the seqlock without needing to delete/recreate the shared file.
        if ((seq & 1) != 0) seq++;
        buf.putInt(OFF_MC_SEQ, seq + 1); // odd - tells WoW's reader to retry
        buf.putFloat(OFF_MC_X, (float) (pos.x - offX));
        buf.putFloat(OFF_MC_Y, (float) pos.y);
        buf.putFloat(OFF_MC_Z, (float) (pos.z - offZ));
        buf.putFloat(OFF_MC_YAW, yaw);
        buf.putFloat(OFF_MC_PITCH, pitch);
        buf.putFloat(OFF_MC_FEET_X, (float) (feet.x - offX));
        buf.putFloat(OFF_MC_FEET_Y, (float) feet.y);
        buf.putFloat(OFF_MC_FEET_Z, (float) (feet.z - offZ));
        buf.putInt(OFF_MC_FIRST_PERSON, firstPerson ? 1 : 0);
        buf.putInt(OFF_MC_TELEPORT_ACK, McwowWorldPlacement.ack());
        Vec3 vel = mc.player.getDeltaMovement(); // blocks per tick
        buf.putInt(OFF_MC_ON_GROUND, mc.player.onGround() ? 1 : 0);
        buf.putInt(OFF_MC_IN_WATER, mc.player.isInWater() ? 1 : 0);
        buf.putFloat(OFF_MC_VEL_X, (float) (vel.x * 20.0));
        buf.putFloat(OFF_MC_VEL_Y, (float) (vel.y * 20.0));
        buf.putFloat(OFF_MC_VEL_Z, (float) (vel.z * 20.0));
        buf.putFloat(OFF_MC_FOV_DEG, cam.getFov());
        long mcHeartbeat = buf.getLong(OFF_MC_HEARTBEAT) + 1;
        buf.putLong(OFF_MC_HEARTBEAT, mcHeartbeat);
        buf.putLong(OFF_MC_TICK, mcHeartbeat);
        buf.putInt(OFF_MC_SEQ, seq + 2); // back to even - safe to read again
    }

    private boolean tryOpen() {
        try {
            // Phase 1 bump: READ_WRITE now, not READ_ONLY - this side needs to WRITE the
            // mcCamera slot, not just read wowPlayer.
            buf = McwowLinks.map("classiccraft_v1.shm", TOTAL_SIZE, true).asByteBuffer();
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(OFF_MC_PID, (int) ProcessHandle.current().pid());
            LOGGER.info("mcwow-bridge: attached to {} (read-write)", SHM_PATH);
            return true;
        } catch (IOException e) {
            LOGGER.warn("mcwow-bridge: could not open {} ({}) - is mcwow.dll running in WoW?",
                    SHM_PATH, e.getMessage());
            return false;
        }
    }
}
