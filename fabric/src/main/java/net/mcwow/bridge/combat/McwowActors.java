package net.mcwow.bridge.combat;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader of /dev/shm/classiccraft_actors_v1.shm (protocol/mcwow_actors_protocol.h): WoW's creatures near
 * the player, written by mcwow.dll every frame. Seqlock read; any thread.
 */
public final class McwowActors {
    private static final String PATH = "/dev/shm/classiccraft_actors_v1.shm";
    // v2 (classiccraft): + the damage ring after the actors (protocol/mcwow_actors_protocol.h).
    // v3 (2026-10-03): + the text ring (system chat lines, GM command replies).
    private static final int MAGIC = 0x6D637761, VERSION = 7, MAX = 64, ACTOR_BYTES = 80, HEADER_BYTES = 64;
    private static final long RING_HEAD = HEADER_BYTES + (long) MAX * ACTOR_BYTES; // 5184
    private static final long RING_DATA = RING_HEAD + 8;
    private static final int RING_ENTRIES = 256, RING_ENTRY_BYTES = 32;
    private static final long TEXT_HEAD = RING_DATA + (long) RING_ENTRIES * RING_ENTRY_BYTES; // 13384
    private static final long TEXT_DATA = TEXT_HEAD + 8;
    private static final int TEXT_SLOTS = 64, TEXT_SLOT = 1024; // v4: NPC speech runs long
    // v5 (2026-10-03): the crosshair focus, what WoW thing a right-click would act on.
    private static final long FOCUS = TEXT_DATA + (long) TEXT_SLOTS * TEXT_SLOT;
    // v6: our own state: u32 stand state (WoW UNIT_FIELD_BYTES_1 byte 0).
    private static final long SELF = FOCUS + 128;
    private static final long TOTAL = SELF + 32;

    /** Our WoW character's stand state (1 sit, 2/4/5/6 chair, ...), 0 when unknown. */
    public static int readStandState() {
        MemorySegment s = segment();
        if (s == null || s.get(I, 0) != MAGIC || s.get(I, 4) != VERSION) return 0;
        return s.get(I, SELF);
    }

    /** What WoW thing is under the crosshair: kind (benilla external::CROSSHAIR_KINDS), distance in blocks. */
    public record Focus(int kind, float distance, boolean unable, long guid, String name) {
    }

    /** The crosshair focus, or null (nothing, or benilla mid-write). */
    public static Focus readFocus() {
        MemorySegment s = segment();
        if (s == null || s.get(I, 0) != MAGIC || s.get(I, 4) != VERSION) return null;
        int seq = s.get(I, FOCUS);
        if ((seq & 1) != 0) return null;
        VarHandle.acquireFence();
        int kind = s.get(I, FOCUS + 4);
        float distance = s.get(F, FOCUS + 8);
        boolean unable = s.get(I, FOCUS + 12) != 0;
        long guid = s.get(L, FOCUS + 16);
        int n = Math.min(96, Math.max(0, s.get(I, FOCUS + 24)));
        byte[] name = new byte[n];
        MemorySegment.copy(s, ValueLayout.JAVA_BYTE, FOCUS + 28, name, 0, n);
        VarHandle.acquireFence();
        if (s.get(I, FOCUS) != seq) return null;
        return new Focus(kind, distance, unable, guid, new String(name, java.nio.charset.StandardCharsets.UTF_8));
    }
    private static long textTail = -1;
    public static final int ATTACKABLE = 1, DEAD = 2, TARGETS_ME = 4, CRITTER = 8;
    private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED;
    private static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT_UNALIGNED;

    public record Actor(long guid, float x, float y, float z, float facing, int entry, int displayId, float scale,
                        float boundingRadius, float combatReach, int health, int maxHealth, int level, long targetGuid,
                        int flags, int unitFlags, float modelHeight, float modelWidth) {
        public boolean attackable() { return (flags & ATTACKABLE) != 0; }
        public boolean dead() { return (flags & DEAD) != 0; }
        /** A WoW critter / non-combat pet: never fights back (MCWOW_ACTOR_CRITTER). */
        public boolean critter() { return (flags & CRITTER) != 0; }
    }

    /** Player fields from the same snapshot. */
    /**
     * xp / nextXp: the WoW character's level bar (PLAYER_XP, PLAYER_NEXT_LEVEL_XP; header 48/52 since
     * leveling). lightAmbient / lightSun: WoW's light at the camera for Minecraft's hand, RGB8 over
     * 0..2 (header 28/32, unused before), valid when lightMark is LIGHT_VALID (header 60).
     */
    public record Me(long guid, int level, int lightAmbient, int lightSun, int mapId, long frame, int xp,
                     int nextXp, int deathState, int lightMark) {
        public static final int LIGHT_VALID = 0x4C494748; // "LIGH"

        public boolean hasLight() { return this.lightMark == LIGHT_VALID; }
    }

    /**
     * A kill's XP, held by the server for Minecraft's orbs (SMSG_CC_XP_DROP, relayed by benilla into
     * the damage ring as kind XP_DROP): drop id, WoW XP (0 for a grey mob), the creature's level and
     * rank, the corpse in WoW yards.
     */
    public record XpDrop(int dropId, int xp, int level, int rank, float x, float y, float z) {
    }

    private static final int XP_DROP = 0x10;

    /**
     * A creature the player killed (SMSG_CC_KILL, ring kind KILL, 2026-10-03), for Minecraft's loot:
     * creature entry, level, rank, type (WoW CreatureType), beast family, the money WoW put on the
     * corpse (copper), flags (KILL_SKINNABLE), quest items the server put in the WoW bags, corpse
     * position (WoW yards).
     */
    public record Kill(int entry, int level, int rank, int type, int family, int money, int flags, int questItems,
                       float x, float y, float z) {
        public static final int SKINNABLE = 1;

        public boolean skinnable() { return (flags & SKINNABLE) != 0; }
    }

    private static final int KILL = 0x11;
    private static final List<Kill> KILLS = new ArrayList<>();

    /** The kills drained from the ring since the last call (drainDamage reads the ring). */
    public static List<Kill> drainKills() {
        if (KILLS.isEmpty()) return List.of();
        List<Kill> out = new ArrayList<>(KILLS);
        KILLS.clear();
        return out;
    }
    /** The WoW server's answer to a vein mined with a pickaxe (ring kind 0x12, McwowNodes). */
    public record Harvested(long guid, int entry, boolean ok, boolean depleted, float x, float y, float z) {
    }

    private static final int HARVEST = 0x12;
    private static final List<Harvested> HARVESTS = new ArrayList<>();

    public static List<Harvested> drainHarvests() {
        if (HARVESTS.isEmpty()) return List.of();
        List<Harvested> out = new ArrayList<>(HARVESTS);
        HARVESTS.clear();
        return out;
    }

    private static final List<XpDrop> XP_DROPS = new ArrayList<>();

    /** The XP drops the last drainDamage calls came across (server thread). */
    public static List<XpDrop> drainXpDrops() {
        if (XP_DROPS.isEmpty()) return List.of();
        List<XpDrop> out = new ArrayList<>(XP_DROPS);
        XP_DROPS.clear();
        return out;
    }

    /**
     * A WoW hit on something Minecraft owns (the server's SMSG_CC_DAMAGE, relayed by benilla):
     * victimKind 0 = the player, 1 = the Minecraft mob {@code mcId}.
     */
    public record Damage(int victimKind, int mcId, long attackerGuid, int wowDamage, int attackerLevel, int flags,
                         int school) {
        public boolean crit() { return (flags & 1) != 0; }

        /** A spell hit: any WoW school but physical (SpellSchoolMask, bit 0 = physical). */
        public boolean spell() { return (school & ~1) != 0; }
    }

    private static MemorySegment shm;
    private static long nextTryNanos;
    private static long ringTail = -1;

    private McwowActors() {
    }

    private static MemorySegment segment() {
        if (shm == null && System.nanoTime() >= nextTryNanos) {
            nextTryNanos = System.nanoTime() + 2_000_000_000L;
            Path p = Path.of(PATH);
            if (!Files.exists(p)) return null;
            try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
                if (ch.size() < TOTAL) return null;
                shm = ch.map(FileChannel.MapMode.READ_ONLY, 0, TOTAL, Arena.global());
            } catch (IOException | RuntimeException e) {
                shm = null;
            }
        }
        return shm;
    }

    /** The server's system chat lines benilla relayed since the last call (GM command replies). */
    public static List<String> drainText() {
        MemorySegment s = segment();
        if (s == null || s.get(I, 0) != MAGIC || s.get(I, 4) != VERSION) return List.of();
        long head = s.get(L, TEXT_HEAD);
        if (textTail < 0 || textTail > head) textTail = head; // first sight or a new writer: start now
        if (head - textTail > TEXT_SLOTS) textTail = head - TEXT_SLOTS;
        VarHandle.acquireFence();
        List<String> out = new ArrayList<>();
        for (; textTail < head; textTail++) {
            long o = TEXT_DATA + (textTail % TEXT_SLOTS) * TEXT_SLOT;
            int n = Math.min(TEXT_SLOT - 2, s.get(ValueLayout.JAVA_SHORT_UNALIGNED, o) & 0xFFFF);
            byte[] bytes = new byte[n];
            MemorySegment.copy(s, ValueLayout.JAVA_BYTE, o + 2, bytes, 0, n);
            out.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    /** Every WoW hit benilla relayed since the last call, in order (skipping any we fell behind on). */
    public static List<Damage> drainDamage() {
        MemorySegment s = segment();
        if (s == null || s.get(I, 0) != MAGIC || s.get(I, 4) != VERSION) return List.of();
        long head = s.get(L, RING_HEAD);
        if (ringTail < 0 || ringTail > head) ringTail = head; // first sight or a new writer: start now
        if (head - ringTail > RING_ENTRIES) ringTail = head - RING_ENTRIES;
        VarHandle.acquireFence();
        List<Damage> out = new ArrayList<>();
        for (; ringTail < head; ringTail++) {
            long o = RING_DATA + (ringTail % RING_ENTRIES) * RING_ENTRY_BYTES;
            int kind = s.get(ValueLayout.JAVA_BYTE, o) & 0xFF;
            if (kind == XP_DROP) {
                if (XP_DROPS.size() < RING_ENTRIES) {
                    XP_DROPS.add(new XpDrop(s.get(I, o + 4), s.get(I, o + 8), s.get(ValueLayout.JAVA_SHORT_UNALIGNED, o + 2) & 0xFFFF,
                            s.get(ValueLayout.JAVA_BYTE, o + 1) & 0xFF, s.get(F, o + 12), s.get(F, o + 16), s.get(F, o + 20)));
                }
                continue;
            }
            if (kind == HARVEST) {
                if (HARVESTS.size() < RING_ENTRIES) {
                    HARVESTS.add(new Harvested(s.get(L, o + 8), s.get(I, o + 4), s.get(ValueLayout.JAVA_BYTE, o + 1) != 0,
                            s.get(ValueLayout.JAVA_BYTE, o + 2) != 0, s.get(F, o + 16), s.get(F, o + 20), s.get(F, o + 24)));
                }
                continue;
            }
            if (kind == KILL) {
                if (KILLS.size() < RING_ENTRIES) {
                    java.util.function.IntUnaryOperator b = k -> s.get(ValueLayout.JAVA_BYTE, o + k) & 0xFF;
                    KILLS.add(new Kill(s.get(I, o + 4), b.applyAsInt(2), b.applyAsInt(1), b.applyAsInt(3),
                            b.applyAsInt(24), s.get(I, o + 8), b.applyAsInt(25), b.applyAsInt(26),
                            s.get(F, o + 12), s.get(F, o + 16), s.get(F, o + 20)));
                }
                continue;
            }
            out.add(new Damage(s.get(ValueLayout.JAVA_BYTE, o) & 0xFF, s.get(I, o + 4), s.get(L, o + 8),
                    s.get(I, o + 16), s.get(I, o + 20), s.get(I, o + 24), s.get(I, o + 28)));
        }
        return out;
    }

    /** The player fields alone (the client's level bar); null if unavailable. */
    public static Me readMe() {
        MemorySegment s = segment();
        if (s == null || s.get(I, 0) != MAGIC || s.get(I, 4) != VERSION) return null;
        for (int attempt = 0; attempt < 50; attempt++) {
            int seq1 = (int) INT.getAcquire(s, 8L);
            if ((seq1 & 1) != 0) { Thread.onSpinWait(); continue; }
            Me me = new Me(s.get(L, 16), s.get(I, 24), s.get(I, 28), s.get(I, 32), s.get(I, 36), s.get(L, 40),
                    s.get(I, 48), s.get(I, 52), s.get(I, 56), s.get(I, 60));
            VarHandle.acquireFence();
            if ((int) INT.getAcquire(s, 8L) == seq1) return me;
        }
        return null;
    }

    /** Fills {@code out} with the current snapshot; returns the player fields or null if unavailable. */
    public static Me read(List<Actor> out) {
        MemorySegment s = segment();
        if (s == null || s.get(I, 0) != MAGIC || s.get(I, 4) != VERSION) return null;
        // benilla rewrites the table every frame; enough retries to never miss a whole tick.
        for (int attempt = 0; attempt < 200; attempt++) {
            int seq1 = (int) INT.getAcquire(s, 8L);
            if ((seq1 & 1) != 0) { Thread.onSpinWait(); continue; }
            out.clear();
            int n = Math.min(MAX, Math.max(0, s.get(I, 12)));
            Me me = new Me(s.get(L, 16), s.get(I, 24), s.get(I, 28), s.get(I, 32), s.get(I, 36), s.get(L, 40),
                    s.get(I, 48), s.get(I, 52), s.get(I, 56), s.get(I, 60));
            for (int i = 0; i < n; i++) {
                long o = HEADER_BYTES + (long) i * ACTOR_BYTES;
                out.add(new Actor(s.get(L, o), s.get(F, o + 8), s.get(F, o + 12), s.get(F, o + 16), s.get(F, o + 20),
                        s.get(I, o + 24), s.get(I, o + 28), s.get(F, o + 32), s.get(F, o + 36), s.get(F, o + 40),
                        s.get(I, o + 44), s.get(I, o + 48), s.get(I, o + 52), s.get(L, o + 56), s.get(I, o + 64),
                        s.get(I, o + 68), s.get(F, o + 72), s.get(F, o + 76)));
            }
            VarHandle.acquireFence();
            if ((int) INT.getAcquire(s, 8L) == seq1) return me;
        }
        return null;
    }
}
