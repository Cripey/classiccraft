package net.mcwow.bridge.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Combat between the Minecraft player and WoW creatures, server side (Phase 5, 2026-10-01) - port
 * of chasmlol/SkyCraft's SkyCombat. Every attackable, living WoW creature near the player gets an
 * invisible {@link McwowActorEntity} at its exact (fixed-mapped) position in the active WoW map
 * dimension; Minecraft weapons hit those like any mob. Each tick's combined hit per creature is
 * collected here (step 3 sends it to WoW).
 */
public final class McwowCombat {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    /** Must equal MCWOW_MC_BLOCKS_TO_WOW_YARDS (protocol/mcwow_protocol.h). */
    private static final float S = 1.4667f;

    public static final ResourceKey<EntityType<?>> WOW_ACTOR_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("mcwow", "wow_actor"));
    public static final EntityType<McwowActorEntity> WOW_ACTOR = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, WOW_ACTOR_KEY,
            EntityType.Builder.<McwowActorEntity>of(McwowActorEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .noSave()
                    .noSummon()
                    .noLootTable()
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build(WOW_ACTOR_KEY));

    /**
     * Typical creature HP per level (aworld.creature_classlevelstats, class 1: basehp0 for levels
     * 1-60, basehp1 61-70, basehp2 71-80 - dumped from this server's DB 2026-10-01).
     */
    private static final int[] TYPICAL_HP = {42, 55, 71, 86, 102, 120, 137, 156, 176, 198, 222, 247, 273, 300, 328, 356,
            386, 417, 449, 484, 521, 562, 605, 651, 699, 750, 800, 853, 905, 955, 1006, 1057, 1110, 1163, 1220, 1277, 1336,
            1395, 1459, 1524, 1585, 1651, 1716, 1782, 1848, 1919, 1990, 2062, 2138, 2215, 2292, 2371, 2453, 2533, 2614, 2699,
            2784, 2871, 2961, 3052, 5158, 5341, 5527, 5715, 5914, 6116, 6326, 6542, 6761, 6986, 9291, 9610, 9940, 10282,
            10635, 11001, 11379, 11770, 12175, 12600};

    /**
     * A hit for the WoW server (benilla's CMSG_CC_HIT): {creature guid, WoW damage, HIT_* flags,
     * attacker}, attacker 0 = the player, else the Minecraft mob's entity id (its server proxy hits).
     */
    public record WowHit(long guid, int wowDamage, int flags, int attackerId) {
    }

    /** Hits for the render thread to send (McwowWorldExporter drains it into the render ring). */
    public static final java.util.concurrent.ConcurrentLinkedQueue<WowHit> HITS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * A Minecraft mob near the player, for its proxy on the WoW server (classiccraft): kind 1 hostile,
     * 2 passive, 3 the player's companion; region-local Minecraft position, yaw in degrees.
     */
    public record Mob(int id, int kind, int hpPct, float x, float y, float z, float yaw) {
    }

    /** The latest mob list and its sequence number, for the render thread (McwowWorldExporter). */
    public static volatile List<Mob> MOBS = List.of();
    public static volatile long mobsSeq;
    /** Mobs farther than this from the player (blocks) get no proxy. */
    private static final double MOB_RANGE = 48.0;

    // takengain 2.5 (user, 2026-10-02): even-level WoW mobs hit about as hard as a zombie (~3).
    private static float gain = 1.0F, takenGain = 2.5F;
    private static long gainReadNanos;

    /** Re-reads the tuning file classiccraft_combat (McwowLinks.tuningFile: combatgain=, takengain=) at most every 2 s. */
    private static void readTuning() {
        long now = System.nanoTime();
        if (now - gainReadNanos <= 2_000_000_000L) return;
        gainReadNanos = now;
        try {
            for (String line : java.nio.file.Files.readAllLines(net.mcwow.bridge.McwowLinks.tuningFile("classiccraft_combat"))) {
                if (line.startsWith("combatgain=")) gain = Float.parseFloat(line.substring(11).trim());
                if (line.startsWith("takengain=")) takenGain = Float.parseFloat(line.substring(10).trim());
            }
        } catch (java.io.IOException | RuntimeException ignored) {
        }
    }

    /**
     * Minecraft damage -> WoW damage: 20 Minecraft damage (a player's full health) = one typical
     * creature's health at {@code level}. The player's hits use their weapon's item level
     * (McwowGear.attackLevel; an iron sword's 6 takes ~30% of a creature of its level), a mob's hits
     * the creature's own. `combatgain=X` in the tuning file classiccraft_combat. Eggs/snowballs (0) do 1: aggro.
     */
    public static int wowDamage(float mcDamage, int level) {
        readTuning();
        int hp = TYPICAL_HP[Math.max(1, Math.min(80, level)) - 1];
        return Math.max(1, Math.round(mcDamage / 20.0F * hp * gain));
    }

    /**
     * The difficulty curve (user, 2026-10-04: the early game easier, the later game firmer but not
     * hard - real difficulty is for dungeons and raids): WoW hits on the PLAYER times this, by the
     * attacker's level. Anchors at the middle of each band (5.5, 15.5 .. 55.5), linear between, flat
     * outside. Fitted with the progression check (tools/progression.sh --fit-taken) so an even-level
     * normal creature takes ~10 / 9 / 8 / 7 / 6 / 6 hits to kill a player in gear of their level.
     */
    private static final float[] TAKEN_CURVE = {0.40F, 0.83F, 1.23F, 2.59F, 4.88F, 5.38F};

    public static float takenCurve(int attackerLevel) {
        float x = (attackerLevel - 5.5F) / 10.0F;
        if (x <= 0) return TAKEN_CURVE[0];
        if (x >= TAKEN_CURVE.length - 1) return TAKEN_CURVE[TAKEN_CURVE.length - 1];
        int i = (int) x;
        return TAKEN_CURVE[i] + (TAKEN_CURVE[i + 1] - TAKEN_CURVE[i]) * (x - i);
    }

    /**
     * WoW damage -> Minecraft damage, the inverse at the attacker's level: a hit worth 10% of a typical
     * creature health at its level takes 10% of 20 (before Minecraft armor). `takengain=X`.
     */
    public static float mcDamage(int wowDamage, int attackerLevel) {
        readTuning();
        int hp = TYPICAL_HP[Math.max(1, Math.min(80, attackerLevel)) - 1];
        return wowDamage / (float) hp * 20.0F * takenGain;
    }

    private static final Map<Long, McwowActorEntity> PROXIES = new HashMap<>();
    private static final List<McwowActors.Actor> ACTORS = new ArrayList<>();
    private static volatile McwowActors.Me me;
    private static long lastFrame = -1, lastFrameChangeNanos;

    private McwowCombat() {
    }

    public static void init() {
        FabricDefaultAttributeRegistry.register(WOW_ACTOR, LivingEntity.createLivingAttributes());
        ServerTickEvents.END_SERVER_TICK.register(McwowCombat::serverTick);
        // Minecraft's Respawn = WoW's release spirit: the WoW character comes back to life at the bed
        // or its hearthstone location (ServerPlayerMixin picked which, user 2026-10-04).
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            java.nio.ByteBuffer respawn = pendingRespawn;
            pendingRespawn = null;
            if (!alive && respawn != null) {
                RESPAWNS.add(respawn);
                LOGGER.info("mcwow-bridge: respawned {}", respawn.getInt(0) == 1
                        ? "at the bed (WoW map " + respawn.getInt(4) + ")" : "where Steve died; the WoW character goes home");
            }
        });
        // Items stay with the player through a death in a WoW map (user, 2026-10-04); the drop is
        // skipped by PlayerKeepInventoryMixin. The XP bar is the WoW level (McwowXp.mirrorLevel).
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            if (!alive && keepsInventory(oldPlayer)) newPlayer.getInventory().replaceWith(oldPlayer.getInventory());
        });
    }

    /** Whether a player dying now keeps their items: in a WoW map dimension. */
    public static boolean keepsInventory(net.minecraft.world.entity.player.Player player) {
        return wowMapOf(player.level().dimension()) >= 0;
    }

    /** The WoW map id of a WoW map dimension (mcwow:map_<id>), or -1. */
    public static int wowMapOf(ResourceKey<net.minecraft.world.level.Level> dim) {
        var id = dim.identifier();
        if (!id.getNamespace().equals("mcwow") || !id.getPath().startsWith("map_")) return -1;
        try {
            return Integer.parseInt(id.getPath().substring(4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Fixed mapping (protocol/mcwow_protocol.h): 1 block = 1.4667 yd. */
    private static final double BLOCKS_TO_YARDS = 1.4667;
    private static volatile java.nio.ByteBuffer pendingRespawn;

    /**
     * Where the WoW character comes back after a death (ServerPlayerMixin, sent on AFTER_RESPAWN):
     * map < 0 = its hearthstone location, else the bed's spot (Minecraft position and yaw).
     */
    public static void queueRespawn(int map, net.minecraft.world.phys.Vec3 pos, float yaw) {
        // REN_RESPAWN: u32 kind (0 home, 1 at), u32 map, f32 WoW x, y, z, o.
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (map < 0) {
            b.putInt(0).putInt(0).putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        } else {
            b.putInt(1).putInt(map)
                    .putFloat((float) (pos.z * BLOCKS_TO_YARDS)).putFloat((float) (pos.x * BLOCKS_TO_YARDS))
                    .putFloat((float) (pos.y * BLOCKS_TO_YARDS)).putFloat((float) -Math.toRadians(yaw));
        }
        pendingRespawn = b.flip();
    }

    /** Respawns for benilla (REN_RESPAWN bodies). */
    public static final java.util.concurrent.ConcurrentLinkedQueue<java.nio.ByteBuffer> RESPAWNS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** The player's WoW level/health from the last snapshot, or null. */
    public static McwowActors.Me me() {
        return me;
    }

    private static volatile boolean ghost;
    private static boolean ghostWasSurvival;

    /** The WoW character is dead or a ghost: Steve is a spirit (no fighting, no building, unseen). */
    public static boolean ghost() {
        return ghost;
    }

    /**
     * Ghost mode (classiccraft, 2026-10-02): while the WoW character is dead or a ghost, Steve can't
     * hurt anything (PlayerGhostMixin), can't be hurt, mobs don't see him (invisibility), and in
     * survival can't break or place blocks (adventure). All of it lifts on resurrection.
     */
    private static void updateGhost(MinecraftServer server, boolean now) {
        if (now == ghost) return;
        ghost = now;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var invisibility = net.minecraft.world.effect.MobEffects.INVISIBILITY;
            if (now) {
                player.setPermanentlyInvulnerable(true);
                player.addEffect(new net.minecraft.world.effect.MobEffectInstance(invisibility,
                        net.minecraft.world.effect.MobEffectInstance.INFINITE_DURATION, 0, false, false));
                ghostWasSurvival = player.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SURVIVAL;
                if (ghostWasSurvival) player.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
            } else {
                player.setPermanentlyInvulnerable(false);
                player.removeEffect(invisibility);
                if (ghostWasSurvival && player.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.ADVENTURE) {
                    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                }
            }
        }
        LOGGER.info("mcwow-bridge: ghost mode {}", now ? "on (the WoW character is dead)" : "off (resurrected)");
    }

    /** The server's proxy creatures (classiccraft_proxies.sql): never mirrored back as stand-ins. */
    private static boolean isServerProxy(int entry) {
        return entry >= 990001 && entry <= 990003;
    }

    private static void serverTick(MinecraftServer server) {
        List<McwowActors.Actor> fresh = new ArrayList<>();
        McwowActors.Me snapshot = McwowActors.read(fresh);
        long now = System.nanoTime();
        // A read that raced the writer keeps last tick's table: wiping every stand-in on one missed
        // read made creatures vanish and reappear mid-fight (pets lost their target).
        if (snapshot == null && me != null && now - lastFrameChangeNanos < 2_000_000_000L) return;
        ACTORS.clear();
        ACTORS.addAll(fresh);
        if (snapshot != null && snapshot.frame() != lastFrame) {
            lastFrame = snapshot.frame();
            lastFrameChangeNanos = now;
        }
        boolean live = snapshot != null && now - lastFrameChangeNanos < 2_000_000_000L;
        ResourceKey<net.minecraft.world.level.Level> dim = McwowGeomStore.activeDimension;
        ServerLevel level = dim != null ? server.getLevel(dim) : null;
        if (!live || level == null) {
            removeAll();
            McwowActors.drainDamage(); // nothing to apply it to
            McwowActors.drainKills();
            McwowActors.drainHarvests();
            return;
        }
        me = snapshot;
        updateGhost(server, snapshot.deathState() != 0);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == level) {
                pickUpNearby(player);
                McwowXp.mirrorLevel(player, snapshot); // Minecraft's XP bar is the WoW level
            }
        }
        sync(level);
        applyWowDamage(server, level);
        McwowXp.dropOrbs(level, McwowActors.drainXpDrops());
        McwowLoot.drop(level, McwowActors.drainKills());
        net.mcwow.bridge.McwowNodes.confirm(server, level, McwowActors.drainHarvests());
        for (McwowActorEntity proxy : PROXIES.values()) {
            java.util.List<float[]> hits = new java.util.ArrayList<>(proxy.takeDotHits());
            float[] direct = proxy.takeHit();
            if (direct != null) hits.add(0, direct);
            for (float[] hit : hits) {
                int flags = Float.floatToRawIntBits(hit[1]);
                // Gear (2026-10-03): the hit lands at the weapon's item level, not the character's;
                // ranged too since 2026-10-04 (the hit names its weapon). A weapon without a gear
                // stamp: melee as item level 1, ranged at the character's level (McwowGear.attackLevel).
                List<ServerPlayer> ps = server.getPlayerList().getPlayers();
                int at = hit[2] > 0 ? (int) hit[2] : ps.isEmpty() ? snapshot.level() : net.mcwow.bridge.McwowGear.attackLevel(
                        ps.getFirst(), snapshot.level(), (flags & McwowActorEntity.HIT_PROJECTILE) != 0);
                int dmg = wowDamage(hit[0], at);
                HITS.add(new WowHit(proxy.guid(), dmg, flags, 0));
                var school = net.mcwow.bridge.McwowSpells.School.byWow((flags >> McwowActorEntity.SCHOOL_SHIFT) & 0x7);
                LOGGER.info("mcwow-bridge: hit WoW creature entry={} lvl={} for {} Minecraft damage at item level {} -> {} WoW damage{}{}{}{}",
                        proxy.entry(), proxy.wowLevel(), hit[0], at, dmg,
                        (flags & McwowActorEntity.HIT_CRITICAL) != 0 ? " CRIT" : "",
                        (flags & McwowActorEntity.HIT_PROJECTILE) != 0 ? " projectile" : "",
                        school != null ? " " + school.title().toLowerCase() : "",
                        (flags & McwowActorEntity.HIT_PERIODIC) != 0 ? " (over time)" : "");
            }
            for (Map.Entry<Integer, Float> mob : proxy.takeMobHits().entrySet()) {
                int dmg = wowDamage(mob.getValue(), proxy.wowLevel());
                HITS.add(new WowHit(proxy.guid(), dmg, 0, mob.getKey()));
                Entity by = level.getEntity(mob.getKey());
                LOGGER.info("mcwow-bridge: {} hit WoW creature entry={} lvl={} for {} Minecraft damage -> {} WoW damage",
                        by != null ? by.getType().toShortString() : "mob " + mob.getKey(), proxy.entry(), proxy.wowLevel(),
                        mob.getValue(), dmg);
            }
        }
        if (level.getGameTime() % 4 == 0) exportMobs(server, level);
    }

    /** The mobs around the player, for their proxies on the WoW server (5 Hz). */
    private static void exportMobs(MinecraftServer server, ServerLevel level) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty() || players.getFirst().level() != level) {
            if (!MOBS.isEmpty()) { MOBS = List.of(); mobsSeq++; }
            return;
        }
        ServerPlayer player = players.getFirst();
        List<Mob> out = new ArrayList<>();
        for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(MOB_RANGE))) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || e instanceof net.minecraft.world.entity.player.Player
                    || e instanceof McwowActorEntity || e instanceof net.minecraft.world.entity.decoration.ArmorStand) continue;
            // Deep under WoW's ground (a cave, a mine): no WoW creature can reach it, so no proxy -
            // a zombie down there would only pull WoW mobs on the surface into a fight they can't get to.
            int top = net.mcwow.bridge.McwowColumns.topOf(level, e.getBlockX(), e.getBlockZ());
            if (top != net.mcwow.bridge.McwowTerrainFill.NO_TOP && e.getY() < top - 1) continue;
            int kind;
            if ((e instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwner() instanceof net.minecraft.world.entity.player.Player)
                    || (e instanceof net.minecraft.world.entity.animal.golem.IronGolem g && g.isPlayerCreated())) {
                kind = 3;
            } else if (e instanceof net.minecraft.world.entity.monster.Enemy) {
                kind = 1;
            } else {
                kind = 2;
            }
            int hp = Math.round(100.0F * le.getHealth() / Math.max(1.0F, le.getMaxHealth()));
            out.add(new Mob(e.getId(), kind, Math.max(1, Math.min(100, hp)),
                    (float) (e.getX() - McwowGeomStore.regionOffsetX), (float) e.getY(),
                    (float) (e.getZ() - McwowGeomStore.regionOffsetZ), e.getYRot()));
        }
        MOBS = out;
        mobsSeq++;
    }

    private static void sync(ServerLevel level) {
        Map<Long, McwowActors.Actor> wanted = new HashMap<>();
        for (McwowActors.Actor a : ACTORS) {
            // Every living creature: Minecraft mobs fight friendly NPCs too; the player's own hits
            // count only on attackable ones (McwowActorEntity.attackable).
            if (!a.dead() && a.health() > 0 && !isServerProxy(a.entry())) wanted.put(a.guid(), a);
        }
        for (Iterator<Map.Entry<Long, McwowActorEntity>> it = PROXIES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, McwowActorEntity> e = it.next();
            McwowActorEntity proxy = e.getValue();
            if (!wanted.containsKey(e.getKey()) || proxy.isRemoved() || proxy.level() != level) {
                proxy.discard();
                it.remove();
            }
        }
        int before = PROXIES.size();
        for (McwowActors.Actor a : wanted.values()) {
            // Fixed mapping (protocol/mcwow_protocol.h MCWOW_WOW_TO_MC_*) plus the instance region offset.
            double x = a.y() / S + McwowGeomStore.regionOffsetX;
            double y = a.z() / S;
            double z = a.x() / S + McwowGeomStore.regionOffsetZ;
            float yaw = (float) Math.toDegrees(-a.facing()); // WoW forward (cos f, sin f) in (X,Y) = MC (sin f, cos f) in (x,z)
            // The visible model's size from benilla (hitboxes, 2026-10-04: big WoW models drew far
            // outside their collision box); WoW's collision size until the model has loaded.
            float[] yd = a.modelHeight() > 0.05F && a.modelWidth() > 0.05F
                    ? new float[] {a.modelHeight(), Math.max(a.modelWidth(), 0.4F)}
                    : McwowCreatureSizes.yards(a.displayId(), a.scale(), a.boundingRadius());
            float h = yd[0] / S, w = yd[1] / S;
            McwowActorEntity proxy = PROXIES.get(a.guid());
            if (proxy == null) {
                proxy = new McwowActorEntity(WOW_ACTOR, level);
                proxy.setWow(a.guid(), a.entry(), a.level(), a.attackable());
                proxy.setSize(w, h);
                proxy.snapTo(x, y, z, yaw, 0.0F);
                if (!level.addFreshEntity(proxy)) continue;
                PROXIES.put(a.guid(), proxy);
                LOGGER.info("mcwow-bridge: stand-in for WoW creature entry={} lvl={} display={} size {}x{} blocks ({}x{} yd) at ({}, {}, {})",
                        a.entry(), a.level(), a.displayId(), String.format("%.2f", w), String.format("%.2f", h),
                        String.format("%.2f", yd[1]), String.format("%.2f", yd[0]),
                        String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z));
                continue;
            }
            proxy.setWow(a.guid(), a.entry(), a.level(), a.attackable());
            proxy.setSize(w, h);
            proxy.setPos(x, y, z);
            proxy.setYRot(yaw);
            proxy.setYHeadRot(yaw);
        }
        if (PROXIES.size() != before) {
            LOGGER.info("mcwow-bridge: {} WoW creatures mirrored as hittable stand-ins", PROXIES.size());
        }
    }

    /** Knockback captured from a mirrored WoW hit {power, xd, zd} (LivingEntityMixin), or null. */
    public static double[] capturedKnockback;
    /** True while hurtServer runs for a mirrored WoW hit. */
    public static boolean mirroring;
    /** Client hook (set by the client initializer): applies knockback {power, xd, zd} to the local player. */
    public static volatile java.util.function.Consumer<double[]> clientKnockback;

    /** Minecraft events for benilla: 1 = the player died (respawns: RESPAWNS). */
    public static final java.util.concurrent.ConcurrentLinkedQueue<Integer> EVENTS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * WoW's hits on what Minecraft owns (classiccraft): the server computed the hit (miss, dodge, crit,
     * armor) and sent it instead of wounding; here it lands as Minecraft damage from the attacking
     * creature's stand-in (mob attack: Minecraft armor, shields, knockback, and a hit mob turns on
     * the stand-in), sized by {@link #mcDamage} at the attacker's level.
     */
    private static void applyWowDamage(MinecraftServer server, ServerLevel level) {
        for (McwowActors.Damage d : McwowActors.drainDamage()) {
            McwowActorEntity attacker = PROXIES.get(d.attackerGuid());
            var sources = level.damageSources();
            // Spell hits (armor classes, 2026-10-04) are magic: vanilla armor ignores them; the
            // player's spell protection cuts them below.
            var source = attacker != null
                    ? (d.spell() ? sources.indirectMagic(attacker, attacker) : sources.mobAttack(attacker))
                    : (d.spell() ? sources.magic() : sources.generic());
            float damage = mcDamage(d.wowDamage(), d.attackerLevel());
            if (d.victimKind() == 1) {
                if (level.getEntity(d.mcId()) instanceof LivingEntity mob && mob.isAlive()) {
                    boolean hurt = mob.hurtServer(level, source, damage);
                    LOGGER.info("mcwow-bridge: WoW creature {} hit {} for {} WoW damage = {} Minecraft damage{}",
                            attacker != null ? "entry " + attacker.entry() : "?", mob.getType().toShortString(),
                            d.wowDamage(), String.format("%.2f", damage), hurt ? "" : " (blocked)");
                }
                continue;
            }
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) continue;
            ServerPlayer player = players.getFirst();
            if (!player.isAlive() || player.level() != level || player.isCreative() || player.isSpectator()) continue;
            // Gear (2026-10-03): armor's item level against the attacker's level.
            float armor = net.mcwow.bridge.McwowGear.armorFactor(player, d.attackerLevel());
            damage *= armor * takenCurve(d.attackerLevel());
            if (d.spell()) {
                // Spell protection (cloth most, metal least) through vanilla's armor formula.
                float[] sp = net.mcwow.bridge.McwowGear.spellProtection(player);
                damage = net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(player, damage, source, sp[0], sp[1]);
            }
            float healthBefore = player.getHealth();
            capturedKnockback = null;
            mirroring = true;
            boolean hurt;
            try {
                hurt = player.hurtServer(level, source, damage);
            } finally {
                mirroring = false;
            }
            // The server's copy of a player's velocity is stale: don't force it onto the client.
            ((net.mcwow.bridge.mixin.EntityAccessor) player).mcwow$setSyncVelocity(false);
            double[] kb = capturedKnockback;
            capturedKnockback = null;
            // Vanilla knocks back on a shield block too (dealDefaultKnockback runs with blocked=true;
            // only the hurt flash is skipped), and hurtServer reports a full block as "not hurt".
            if (kb != null && clientKnockback != null) clientKnockback.accept(kb);
            LOGGER.info("mcwow-bridge: WoW hit the player for {} WoW damage (attacker lvl {}{}{}) = {} Minecraft damage (armor x{}, curve x{}) from {}: health {} -> {}{}",
                    d.wowDamage(), d.attackerLevel(), d.crit() ? ", crit" : "", d.spell() ? ", spell school " + d.school() : "",
                    String.format("%.2f", damage),
                    String.format("%.2f", armor), String.format("%.2f", takenCurve(d.attackerLevel())),
                    attacker != null ? "creature entry " + attacker.entry() : "unknown source", healthBefore,
                    player.getHealth(), hurt ? "" : (player.isBlocking() ? " (shield)" : " (immune)"));
        }
    }

    /**
     * Items and stuck arrows on WoW ground rest on its collision voxels, which on slopes and rough
     * ground sit a little off from where the player (on WoW's exact triangles) stands, so they
     * were picked up only sometimes (2026-10-01). Touch them over a slightly bigger area than
     * vanilla's; playerTouch applies Minecraft's own rules (pickup delay, owner, inventory space).
     * Port of SkyCraft's SkyCombat.pickUpNearby. Reaches 2 blocks under the feet (2026-10-03, user:
     * drops on sloped WoW ground couldn't be picked up - 1.25 blocks downhill on a 45 degree slope
     * is 1.25 lower, past the old 1-block reach).
     */
    private static void pickUpNearby(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return;
        var box = player.getBoundingBox();
        var reach = new net.minecraft.world.phys.AABB(box.minX - 1.25, box.minY - 2.0, box.minZ - 1.25,
                box.maxX + 1.25, box.maxY + 1.0, box.maxZ + 1.25);
        for (Entity entity : player.level().getEntities(player, reach)) {
            if (!entity.isRemoved() && (entity instanceof net.minecraft.world.entity.item.ItemEntity
                    || entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow
                    || entity instanceof net.minecraft.world.entity.ExperienceOrb)) {
                entity.playerTouch(player);
            }
        }
        logStuckDrops(player);
    }

    /** Drops already warned about (entity id), so each is logged once. */
    private static final java.util.Set<Integer> STUCK_LOGGED = new java.util.HashSet<>();

    /**
     * A drop the player stands next to (within 2.5 blocks horizontally) that is still there after its
     * pickup delay is over: logged once with where it lies against the player and WoW's ground, to
     * find what else keeps drops out of reach.
     */
    private static void logStuckDrops(ServerPlayer player) {
        if (player.tickCount % 20 != 0) return;
        if (STUCK_LOGGED.size() > 512) STUCK_LOGGED.clear();
        for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(2.5, 6.0, 2.5))) {
            if (!(e instanceof net.minecraft.world.entity.item.ItemEntity item) || item.isRemoved()
                    || item.hasPickUpDelay() || item.tickCount < 40 || !STUCK_LOGGED.add(item.getId())) continue;
            double dx = item.getX() - player.getX(), dz = item.getZ() - player.getZ();
            if (dx * dx + dz * dz > 2.5 * 2.5) {
                STUCK_LOGGED.remove(item.getId());
                continue;
            }
            double ground = net.mcwow.bridge.McwowTriHeight.heightAt(item.getX() - net.mcwow.bridge.McwowGeomStore.regionOffsetX,
                    item.getZ() - net.mcwow.bridge.McwowGeomStore.regionOffsetZ, item.getY() + 1.0);
            LOGGER.warn("mcwow-bridge: drop {} not picked up: {} blocks away, {} above the player's feet, {} above WoW ground there, onGround={}, inventory full={}",
                    item.getItem().getHoverName().getString(), String.format("%.2f", Math.sqrt(dx * dx + dz * dz)),
                    String.format("%.2f", item.getY() - player.getY()),
                    Double.isNaN(ground) ? "?" : String.format("%.2f", item.getY() - ground), item.onGround(),
                    player.getInventory().getFreeSlot() < 0);
        }
    }

    private static void removeAll() {
        if (PROXIES.isEmpty()) return;
        PROXIES.values().forEach(Entity::discard);
        PROXIES.clear();
    }

    /** Players in a server (unused for now; one local player). */
    static List<ServerPlayer> players(MinecraftServer server) {
        return server.getPlayerList().getPlayers();
    }
}
