package net.mcwow.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transport decks (2026-10-02, the Deeprun Tram first; protocol/mcwow_geom_protocol.h v3): a boat,
 * zeppelin, tram car or lift is a moving platform. benilla sends each deck's walkable collision
 * once in the deck's own frame (MSG_DECK) and its pose every frame (the deck table); here the
 * triangles are placed at the pose of this tick for the player's collider
 * (McwowTriCollider.trianglesNear), and McwowDeckRide carries Steve along. Region-local block
 * coordinates, like McwowGeomStore. Only the player collides with decks.
 */
public final class McwowDecks {
    private McwowDecks() {
    }

    /** One deck: local triangles (9 floats + walkable each) and their local bounds. */
    private static final class Deck {
        final float[] v;
        final boolean[] walkable;
        final float radius; // horizontal reach of any vertex from the origin
        final float minY, maxY;

        Deck(float[] v, boolean[] walkable) {
            this.v = v;
            this.walkable = walkable;
            float r = 0, lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < v.length; i += 3) {
                r = Math.max(r, (float) Math.hypot(v[i], v[i + 2]));
                lo = Math.min(lo, v[i + 1]);
                hi = Math.max(hi, v[i + 1]);
            }
            this.radius = r;
            this.minY = lo;
            this.maxY = hi;
        }
    }

    /** A deck's pose: origin (region-local blocks) and yaw (radians about +Y, see the protocol). */
    public record Pose(double x, double y, double z, float yaw) {
        /** World (region-local) point of a deck-local one. */
        public double[] toWorld(double lx, double ly, double lz) {
            double s = Math.sin(this.yaw), c = Math.cos(this.yaw);
            return new double[] { this.x + lx * c + lz * s, this.y + ly, this.z - lx * s + lz * c };
        }

        /** Deck-local point of a world (region-local) one. */
        public double[] toLocal(double wx, double wy, double wz) {
            double dx = wx - this.x, dz = wz - this.z;
            double s = Math.sin(this.yaw), c = Math.cos(this.yaw);
            return new double[] { dx * c - dz * s, wy - this.y, dx * s + dz * c };
        }
    }

    private static final ConcurrentHashMap<Long, Deck> DECKS = new ConcurrentHashMap<>();
    /** This tick's poses (McwowDeckRide.startTick), by guid. */
    private static volatile java.util.Map<Long, Pose> poses = java.util.Map.of();

    public static void put(long guid, float[] v, boolean[] walkable) {
        DECKS.put(guid, new Deck(v, walkable));
    }

    public static void remove(long guid) {
        DECKS.remove(guid);
    }

    public static void clear() {
        DECKS.clear();
    }

    public static boolean has(long guid) {
        return DECKS.containsKey(guid);
    }

    public static void setPoses(java.util.Map<Long, Pose> now) {
        poses = now;
    }

    public static Pose pose(long guid) {
        return poses.get(guid);
    }

    /** This tick's deck triangles overlapping a region-local box, placed at their poses. */
    public static List<McwowGeomStore.Tri> near(double minX, double minY, double minZ, double maxX, double maxY,
            double maxZ) {
        List<McwowGeomStore.Tri> out = new ArrayList<>();
        for (var e : poses.entrySet()) {
            Deck d = DECKS.get(e.getKey());
            if (d == null) continue;
            Pose p = e.getValue();
            if (p.x() + d.radius < minX || p.x() - d.radius > maxX || p.z() + d.radius < minZ
                    || p.z() - d.radius > maxZ || p.y() + d.maxY < minY || p.y() + d.minY > maxY) {
                continue;
            }
            for (int t = 0, i = 0; t < d.walkable.length; t++, i += 9) {
                double[] a = p.toWorld(d.v[i], d.v[i + 1], d.v[i + 2]);
                double[] b = p.toWorld(d.v[i + 3], d.v[i + 4], d.v[i + 5]);
                double[] c = p.toWorld(d.v[i + 6], d.v[i + 7], d.v[i + 8]);
                double lo = Math.min(a[0], Math.min(b[0], c[0])), hi = Math.max(a[0], Math.max(b[0], c[0]));
                if (hi < minX || lo > maxX) continue;
                lo = Math.min(a[2], Math.min(b[2], c[2]));
                hi = Math.max(a[2], Math.max(b[2], c[2]));
                if (hi < minZ || lo > maxZ) continue;
                out.add(new McwowGeomStore.Tri((float) a[0], (float) a[1], (float) a[2], (float) b[0], (float) b[1],
                        (float) b[2], (float) c[0], (float) c[1], (float) c[2], d.walkable[t]));
            }
        }
        return out;
    }

    /**
     * The deck whose walkable surface lies under a point (region-local feet) within reach, or 0:
     * the support Steve stands on.
     */
    public static long deckUnder(double x, double y, double z, double below, double above) {
        for (var e : poses.entrySet()) {
            Deck d = DECKS.get(e.getKey());
            if (d == null) continue;
            Pose p = e.getValue();
            double[] l = p.toLocal(x, y, z);
            if (Math.hypot(l[0], l[2]) > d.radius + 1.0) continue;
            for (int t = 0, i = 0; t < d.walkable.length; t++, i += 9) {
                if (!d.walkable[t]) continue;
                double h = heightAt(d.v, i, l[0], l[2]);
                if (!Double.isNaN(h) && h >= l[1] - below && h <= l[1] + above) return e.getKey();
            }
        }
        return 0L;
    }

    /** The height of local triangle i over (x, z), or NaN outside it. */
    private static double heightAt(float[] v, int i, double x, double z) {
        double ax = v[i], ay = v[i + 1], az = v[i + 2], bx = v[i + 3], by = v[i + 4], bz = v[i + 5];
        double cx = v[i + 6], cy = v[i + 7], cz = v[i + 8];
        double det = (bx - ax) * (cz - az) - (cx - ax) * (bz - az);
        if (Math.abs(det) < 1e-9) return Double.NaN;
        double wb = ((x - ax) * (cz - az) - (cx - ax) * (z - az)) / det;
        double wc = ((bx - ax) * (z - az) - (x - ax) * (bz - az)) / det;
        double wa = 1 - wb - wc;
        if (wa < -1e-6 || wb < -1e-6 || wc < -1e-6) return Double.NaN;
        return wa * ay + wb * by + wc * cy;
    }
}
