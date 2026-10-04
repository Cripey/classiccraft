package net.mcwow.bridge.client;

import java.util.Map;

import net.mcwow.bridge.McwowDecks;
import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Riding a transport deck (2026-10-02, the Deeprun Tram first; user: walk around on it as in WoW).
 * Each tick starts by taking benilla's deck poses (McwowDecks, which the collider places its deck
 * triangles at) and carrying Steve with the deck he stands on, its turn included; after the tick's
 * movement the support decides: standing on a deck boards it, standing on anything else or
 * swimming leaves it, and in the air he stays aboard (a jump on deck lands on deck). While aboard,
 * each frame reports his pose relative to the deck (the rider block), which benilla composes with
 * its own live deck pose: the deck moves every frame there, Steve only every tick here.
 */
public final class McwowDeckRide {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    /** How far under and over the feet a deck surface counts as the support (blocks). */
    private static final double SUPPORT_BELOW = 0.1, SUPPORT_ABOVE = 0.1;
    /**
     * Aboard, the deck still holds within this (blocks): stepping on, the support flickered
     * between the platform and the car several times a second, and a tick off the deck while it
     * moves leaves Steve a block behind it (2026-10-02).
     */
    private static final double KEEP = 0.35;
    /**
     * Ticks on other ground before leaving a deck: single-tick blips off a flying zeppelin each
     * cost a tick of carry (2026-10-02). Stepping onto a dock is three ticks unchanged.
     */
    private static final int LEAVE_TICKS = 3;
    private static int offDeck;
    /**
     * In the air, ticks with no deck below before leaving it: a jump on deck stays aboard (the deck
     * is under him), a jump or glide off the side lets go and flies free (user, 2026-10-03: he
     * stayed carried until he landed somewhere).
     */
    private static final int AIR_LEAVE_TICKS = 10;
    /** How far under the feet a deck still counts as below a jump (blocks). */
    private static final double AIR_BELOW = 4.0;
    private static int offDeckInAir;

    private static long riding;
    /**
     * Aboard, but the deck is out of sight since (nanos; 0 = in sight): a map change clears every
     * deck and benilla resends the ridden one in the destination's coordinates (a boat or zeppelin
     * crossing to the other continent). Steve stays aboard through it, for up to LOST_GRACE.
     */
    private static long lostSince;
    private static final long LOST_GRACE = 20_000_000_000L;
    /** Where Steve stood when the deck went out of sight: he waits there (no deck to stand on). */
    private static Vec3 lostAt;
    /** A deck moving further than this in one tick changed maps: no carry across it (blocks). */
    private static final double JUMP = 32.0;
    /** How far the deck carried Steve this tick (blocks): no walking, for the footsteps. */
    private static double carriedX, carriedY, carriedZ;
    private static Map<Long, McwowDecks.Pose> previous = Map.of(), current = Map.of();

    private McwowDeckRide() {
    }

    /** The deck Steve is aboard, or 0. */
    public static long riding() {
        return riding;
    }

    /** This tick's carry (x, y, z), zero when not aboard. */
    public static Vec3 carried() {
        return new Vec3(carriedX, carriedY, carriedZ);
    }

    static void startTick(Minecraft mc) {
        carriedX = carriedY = carriedZ = 0.0;
        Map<Long, McwowDecks.Pose> now = McwowGeomClient.readDeckPoses();
        if (now == null) now = current; // benilla mid-write: last tick's poses again
        previous = current;
        current = now;
        McwowDecks.setPoses(now);
        LocalPlayer p = mc.player;
        if (riding == 0 || p == null || !McwowGeomStore.appliesTo(p.level())) return;
        McwowDecks.Pose from = previous.get(riding), to = current.get(riding);
        if (to == null || !McwowDecks.has(riding)) {
            long t = System.nanoTime();
            if (lostSince == 0) {
                lostSince = t;
                lostAt = p.position();
                LOGGER.info("mcwow-bridge: deck {} out of sight - still aboard", Long.toHexString(riding));
            } else if (t - lostSince > LOST_GRACE) {
                LOGGER.info("mcwow-bridge: deboard {} (deck gone)", Long.toHexString(riding));
                riding = 0;
                lostSince = 0;
                return;
            }
            // Through the loading screen of a crossing benilla exports no decks: with nothing
            // underfoot Steve would drop to the sea floor (no Minecraft water) before the placement
            // teleports him across; he waits where he stood. The placement's hold takes over.
            if (McwowWorldPlacement.holding()) {
                lostAt = null; // placed elsewhere (the other continent): that spot is gone
            } else if (lostAt != null) {
                p.setDeltaMovement(Vec3.ZERO);
                p.setPos(lostAt);
                p.resetFallDistance();
            }
            return;
        }
        if (lostSince != 0) {
            lostSince = 0;
            LOGGER.info("mcwow-bridge: deck {} back in sight", Long.toHexString(riding));
            return; // no carry from the pose it had before (another map's, after a crossing)
        }
        if (from == null || from.equals(to)) return;
        if (Math.abs(to.x() - from.x()) > JUMP || Math.abs(to.y() - from.y()) > JUMP
                || Math.abs(to.z() - from.z()) > JUMP) {
            return; // the deck changed maps this tick; the placement puts Steve aboard again
        }
        double offX = McwowGeomStore.regionOffsetX, offZ = McwowGeomStore.regionOffsetZ;
        double[] local = from.toLocal(p.getX() - offX, p.getY(), p.getZ() - offZ);
        double[] world = to.toWorld(local[0], local[1], local[2]);
        carriedX = world[0] + offX - p.getX();
        carriedY = world[1] - p.getY();
        carriedZ = world[2] + offZ - p.getZ();
        p.setPos(world[0] + offX, world[1], world[2] + offZ);
        // Minecraft yaw runs the other way round +Y: a deck turning by d turns Steve by -d.
        float turn = (float) -Mth.wrapDegrees(Math.toDegrees(to.yaw() - from.yaw()));
        if (turn != 0.0F) {
            p.setYRot(p.getYRot() + turn);
            p.setYHeadRot(p.getYHeadRot() + turn);
            p.yBodyRot += turn;
        }
    }

    static void endTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || !McwowGeomStore.appliesTo(p.level())) {
            // Mid-crossing: the placement already names the destination's dimension while Steve
            // is still in the old one. That is no reason to leave the deck (dropped here silently,
            // the zeppelin's placement then released him under it, 2026-10-02); only the rules
            // below and the deck's absence (startTick) end a ride.
            return;
        }
        long before = riding;
        if (riding != 0 && (lostSince != 0 || McwowWorldPlacement.holding())) {
            // The deck is out of sight or the placement holds Steve: nothing underfoot decides.
        } else if (p.isInWater() || p.isPassenger()) {
            riding = 0;
        } else if (!p.onGround()) {
            double x = p.getX() - McwowGeomStore.regionOffsetX, z = p.getZ() - McwowGeomStore.regionOffsetZ;
            if (riding == 0 || McwowDecks.deckUnder(x, p.getY(), z, AIR_BELOW, KEEP) != 0) {
                offDeckInAir = 0;
            } else if (++offDeckInAir >= AIR_LEAVE_TICKS) {
                offDeckInAir = 0;
                riding = 0;
            }
        } else {
            offDeckInAir = 0;
            double x = p.getX() - McwowGeomStore.regionOffsetX, z = p.getZ() - McwowGeomStore.regionOffsetZ;
            long kept = riding != 0 ? McwowDecks.deckUnder(x, p.getY(), z, KEEP, KEEP) : 0L;
            long under = kept != 0 ? kept : McwowDecks.deckUnder(x, p.getY(), z, SUPPORT_BELOW, SUPPORT_ABOVE);
            if (under != 0 || riding == 0) {
                offDeck = 0;
                riding = under;
            } else if (++offDeck >= LEAVE_TICKS) {
                offDeck = 0;
                riding = 0;
            }
        }
        if (riding != before) {
            LOGGER.info("mcwow-bridge: {}", riding != 0 ? "board deck " + Long.toHexString(riding)
                    : "deboard " + Long.toHexString(before));
        }
    }

    /**
     * The rider block for this frame: Steve's eye, feet and yaw (region-local, as drawn this frame)
     * relative to the deck at this tick's pose. Not a pose between the last two ticks: the carry
     * runs before the level ticks, so Minecraft's old position (xo, yRotO) is the CARRIED one and
     * Steve is drawn between two points both in this tick's deck frame. Lerping the deck too put him
     * up to a tick's travel ahead of himself at each tick's start - a 20 Hz sawtooth on a moving
     * tram (2026-10-02).
     */
    static void publish(Vec3 eye, Vec3 feet, float yawDeg, float partialTick) {
        McwowDecks.Pose at = current.get(riding);
        if (riding == 0 || at == null) {
            // Aboard with the deck out of sight (a crossing) still names it: benilla must stay
            // aboard too, while "not aboard" makes it leave (2026-10-03: flying behind a boat,
            // benilla still rode it and the server carried Steve across with it).
            McwowGeomClient.writeRider(riding, null, null, 0.0F);
            return;
        }
        double offX = McwowGeomStore.regionOffsetX, offZ = McwowGeomStore.regionOffsetZ;
        double[] e = at.toLocal(eye.x - offX, eye.y, eye.z - offZ);
        double[] f = at.toLocal(feet.x - offX, feet.y, feet.z - offZ);
        McwowGeomClient.writeRider(riding, e, f, (float) (yawDeg + Math.toDegrees(at.yaw())));
    }
}
