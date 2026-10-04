package net.mcwow.bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Smooth collision for the local player against WoW's exact triangles - a faithful port of
 * chasmlol/SkyCraft's {@code dev.skycraft.world.TriCollider} (read in full 2026-10-01), adapted
 * only to take {@link McwowGeomStore.Tri} input instead of SkyCraft's {@code SkyTri}.
 *
 * Minecraft still computes every velocity (walking, sprinting, jumping, gravity, friction); this
 * only replaces how that movement is stopped by WoW geometry, which vanilla can only represent
 * as axis-aligned boxes (i.e. stair-stepped slopes):
 *  - walkable ground (<= ~45 deg) is followed exactly, stepping up to Minecraft's step height;
 *  - while grounded and not jumping, the player sticks to ground going downhill;
 *  - steeper surfaces are walls: the player's cylinder slides along them;
 *  - ceilings stop upward movement.
 *
 * IMPORTANT, and the reason the previous McwowCollider never worked on slopes/narrow stairs:
 * SkyCraft's own BlockCollisionsMixin does NOT give players the voxel shapes at all
 * ({@code SkyCollision.usesSmoothCollider}) - players collide with the exact triangles ONLY,
 * through this class. Running both (as this project did) means vanilla's voxel step-terraces stop
 * the player before this smoothing ever sees the movement. See {@link McwowGeomStore#usesSmoothCollider}.
 */
public final class McwowTriCollider {
    private static final double FLOOR_RADIUS = 0.15;   // ground is sampled under a small footprint, like a capsule's base
    private static final double SUBSTEP = 0.1;         // horizontal sub-steps so walls can't be tunnelled
    /**
     * The longest move resolved in one piece (blocks). A faster one (a fall, an elytra flight at 2-3
     * blocks a tick) is resolved in pieces this long, so its vertical pass runs along the path and
     * not only at its end (2026-10-02: elytra flights clipped into slopes). Walking and sprinting
     * stay one piece.
     */
    private static final double MAX_PIECE = 0.3;
    private static final double EPS = 1e-4;
    /**
     * Surfaces at most 50 degrees from flat can be walked on: WoW's own limit (benilla's
     * GROUND_COS, cos 50°). SkyCraft's ~45° (0.7) made a boat's stair ramp, walkable in WoW, a
     * wall here (2026-10-03).
     */
    private static final double WALKABLE_NY = 0.642788;

    private static final double[][] FLOOR_SAMPLES = buildSamples();

    private McwowTriCollider() {
    }

    /** SkyTri equivalent: one triangle with precomputed plane and bounds, in doubles. */
    public static final class T {
        final double ax, ay, az, bx, by, bz, cx, cy, cz;
        final double nx, ny, nz;
        final double minX, minY, minZ, maxX, maxY, maxZ;
        final boolean walkable;

        T(McwowGeomStore.Tri s) {
            ax = s.x0; ay = s.y0; az = s.z0;
            bx = s.x1; by = s.y1; bz = s.z1;
            cx = s.x2; cy = s.y2; cz = s.z2;
            double ux = bx - ax, uy = by - ay, uz = bz - az;
            double wx = cx - ax, wy = cy - ay, wz = cz - az;
            double qx = uy * wz - uz * wy, qy = uz * wx - ux * wz, qz = ux * wy - uy * wx;
            double len = Math.sqrt(qx * qx + qy * qy + qz * qz);
            if (len < 1e-12) {
                nx = 0; ny = 1; nz = 0;
            } else {
                nx = qx / len; ny = qy / len; nz = qz / len;
            }
            minX = Math.min(ax, Math.min(bx, cx));
            minY = Math.min(ay, Math.min(by, cy));
            minZ = Math.min(az, Math.min(bz, cz));
            maxX = Math.max(ax, Math.max(bx, cx));
            maxY = Math.max(ay, Math.max(by, cy));
            maxZ = Math.max(az, Math.max(bz, cz));
            // Classified from the real normal, same as SkyTri - geom_server's own flag uses a
            // 50 deg threshold, SkyCraft's collider is tuned around ~45.
            walkable = Math.abs(ny) >= WALKABLE_NY;
        }

        /** Height of the plane above (x, z) if inside the XZ footprint, else NaN. */
        double heightAt(double x, double z) {
            if (x < minX - 1e-9 || x > maxX + 1e-9 || z < minZ - 1e-9 || z > maxZ + 1e-9 || Math.abs(ny) < 0.05) {
                return Double.NaN;
            }
            double d1 = edge(x, z, ax, az, bx, bz);
            double d2 = edge(x, z, bx, bz, cx, cz);
            double d3 = edge(x, z, cx, cz, ax, az);
            boolean hasNeg = d1 < -1e-12 || d2 < -1e-12 || d3 < -1e-12;
            boolean hasPos = d1 > 1e-12 || d2 > 1e-12 || d3 > 1e-12;
            if (hasNeg && hasPos) {
                return Double.NaN;
            }
            return ay - (nx * (x - ax) + nz * (z - az)) / ny;
        }

        private static double edge(double px, double pz, double x0, double z0, double x1, double z1) {
            return (x1 - x0) * (pz - z0) - (z1 - z0) * (px - x0);
        }
    }

    /** Triangles overlapping the given box, decks included, converted for this collider. */
    public static List<T> trianglesNear(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        List<McwowGeomStore.Tri> raw = new ArrayList<>(McwowGeomStore.near((float) minX, (float) minY, (float) minZ,
                (float) maxX, (float) maxY, (float) maxZ));
        // Transport decks where they are this tick (McwowDecks): a tram car, boat or lift.
        raw.addAll(McwowDecks.near(minX, minY, minZ, maxX, maxY, maxZ));
        List<T> out = new ArrayList<>(raw.size());
        for (McwowGeomStore.Tri t : raw) {
            T c = new T(t);
            if (c.maxX - c.minX < 1e-9 && c.maxZ - c.minZ < 1e-9) continue; // degenerate
            out.add(c);
        }
        return out;
    }

    private static double[][] buildSamples() {
        List<double[]> samples = new ArrayList<>();
        samples.add(new double[] { 0, 0 });
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            samples.add(new double[] { Math.cos(a) * FLOOR_RADIUS, Math.sin(a) * FLOOR_RADIUS });
        }
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            samples.add(new double[] { Math.cos(a) * FLOOR_RADIUS * 0.5, Math.sin(a) * FLOOR_RADIUS * 0.5 });
        }
        return samples.toArray(new double[0][]);
    }

    /**
     * Resolves one tick of movement {@code (mx, my, mz)} for a player whose feet are centred at
     * {@code (x0, y0, z0)}, in pieces of at most {@link #MAX_PIECE}. Returns the allowed movement;
     * an axis no piece changed comes back bit-for-bit as asked (see {@link #resolvePiece}).
     */
    public static double[] resolve(
            List<T> tris, double x0, double y0, double z0, double radius, double height, double step, boolean wasOnGround,
            double mx, double my, double mz) {
        double length = Math.sqrt(mx * mx + my * my + mz * mz);
        if (tris.isEmpty() || length <= MAX_PIECE) {
            return resolvePiece(tris, x0, y0, z0, radius, height, step, wasOnGround, mx, my, mz);
        }
        int pieces = (int) Math.ceil(length / MAX_PIECE);
        double px = mx / pieces, py = my / pieces, pz = mz / pieces;
        double x = x0, y = y0, z = z0;
        boolean ground = wasOnGround, wall = false, vertical = false;
        for (int i = 0; i < pieces; i++) {
            // Once the ground or a ceiling stopped the fall or rise, the rest of the tick slides
            // (vanilla zeroes a collided axis too).
            double dy = vertical ? 0.0 : py;
            double[] r = resolvePiece(tris, x, y, z, radius, height, step, ground, px, dy, pz);
            wall |= r[0] != px || r[2] != pz;
            if (r[1] != dy) {
                vertical = true;
                ground = r[1] > dy || dy <= 0.0; // landed, or held to the ground going downhill
            } else {
                ground = false; // moved freely: in the air
            }
            x += r[0];
            y += r[1];
            z += r[2];
        }
        return new double[] { wall ? x - x0 : mx, vertical ? y - y0 : my, wall ? z - z0 : mz };
    }

    /** One piece of {@link #resolve}. */
    private static double[] resolvePiece(
            List<T> tris, double x0, double y0, double z0, double radius, double height, double step, boolean wasOnGround,
            double mx, double my, double mz) {
        if (tris.isEmpty()) {
            return new double[] { mx, my, mz };
        }

        double x = x0, y = y0, z = z0;

        // 1) Horizontal, in sub-steps, sliding out of walls after each.
        double horizontal = Math.hypot(mx, mz);
        int steps = Math.max(1, (int) Math.ceil(horizontal / SUBSTEP));
        // Grounded, steep faces lower than the step are stepped over, not walls - but never more than
        // half the body: an elytra glider or crawler is 0.6 tall, the step 0.6, so nothing was a wall
        // to it, and skimming the ground it flew through buildings (2026-10-02).
        double wallFrom = wasOnGround ? Math.min(step, height * 0.5) : 0.02;
        boolean hitWall = false;
        for (int i = 0; i < steps; i++) {
            double px = x, pz = z;
            x += mx / steps;
            z += mz / steps;
            double[] out = pushOutOfWalls(tris, x, y, z, radius, height, wallFrom, step, px, pz);
            hitWall |= out[0] != x || out[1] != z;
            x = out[0];
            z = out[1];
        }

        // 2) Vertical.
        double dy = my;
        if (dy > 0) {
            double ceiling = ceilingAbove(tris, x, y + height, z, radius * 0.8);
            if (!Double.isNaN(ceiling)) {
                dy = Math.max(0.0, Math.min(dy, ceiling - (y + height)));
            }
        }
        // In the air too, walkable ground up to the step height catches the feet: that's where
        // pushOutOfWalls starts treating it as a wall, so no slope falls between the two (with 0.3
        // here, a slope 0.3-0.6 over the feet after the horizontal pass was neither, and an elytra
        // flight went through it).
        double walkUp = step;
        double floorWalk = floor(tris, x, y, z, true, walkUp);
        double floorAny = floor(tris, x, y, z, false, EPS);
        double floor = Math.max(floorWalk, floorAny);
        double targetY = y + dy;
        double outY;
        if (targetY <= floor) {
            outY = floor - y0; // land / stand / walk up a slope or small ledge
        } else if (wasOnGround && dy <= 0 && floorWalk > Double.NEGATIVE_INFINITY && y - floorWalk <= Math.max(step, horizontal * 1.5)) {
            outY = floorWalk - y0; // stick to the ground going downhill instead of hopping
        } else {
            outY = dy; // free movement (possibly shortened by a ceiling)
        }
        // Minecraft decides "did I collide?" with exact equality against what it asked for, so any
        // axis we didn't actually change must come back bit-for-bit identical (not (y0 + d) - y0).
        return new double[] { hitWall ? x - x0 : mx, outY, hitWall ? z - z0 : mz };
    }

    /** Highest ground under the footprint at most {@code maxAbove} above the feet (or -inf). */
    private static double floor(List<T> tris, double x, double y, double z, boolean walkableOnly, double maxAbove) {
        double best = Double.NEGATIVE_INFINITY;
        double limit = y + maxAbove;
        for (T t : tris) {
            if (walkableOnly && !t.walkable) {
                continue;
            }
            if (t.minY > limit) {
                continue;
            }
            for (double[] s : FLOOR_SAMPLES) {
                double h = t.heightAt(x + s[0], z + s[1]);
                if (!Double.isNaN(h) && h <= limit && h > best) {
                    best = h;
                }
            }
        }
        return best;
    }

    /** Lowest surface above the head within the footprint, or NaN. */
    private static double ceilingAbove(List<T> tris, double x, double head, double z, double r) {
        double best = Double.NaN;
        for (T t : tris) {
            if (t.maxY < head - 0.05) {
                continue;
            }
            for (double[] s : FLOOR_SAMPLES) {
                double h = t.heightAt(x + s[0] * r / FLOOR_RADIUS, z + s[1] * r / FLOOR_RADIUS);
                if (!Double.isNaN(h) && h >= head - 0.05 && (Double.isNaN(best) || h < best)) {
                    best = h;
                }
            }
        }
        return best;
    }

    /**
     * Pushes the player's vertical cylinder out of every triangle that intersects its body.
     * Steep triangles count from {@code wallFrom} above the feet; walkable ones only from the
     * step height (below that they are ground, handled by {@link #floor}).
     */
    private static double[] pushOutOfWalls(
            List<T> tris, double x, double y, double z, double radius, double height, double wallFrom, double step,
            double prevX, double prevZ) {
        double[] poly = new double[3 * 6];
        for (int iter = 0; iter < 4; iter++) {
            double bestPen = 0, bestDx = 0, bestDz = 0;
            for (T t : tris) {
                double lo = y + (t.walkable ? step : wallFrom);
                double hi = y + height - 0.02;
                if (t.maxY < lo || t.minY > hi || t.maxX < x - radius || t.minX > x + radius || t.maxZ < z - radius || t.minZ > z + radius) {
                    continue;
                }
                int n = clipToSlab(t, lo, hi, poly);
                if (n == 0) {
                    continue;
                }
                double[] closest = closestXZ(poly, n, x, z);
                double cx = closest[0], cz = closest[1];
                double ddx = x - cx, ddz = z - cz;
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                double pen;
                double dirX, dirZ;
                if (d > 1e-6) {
                    pen = radius - d;
                    dirX = ddx / d;
                    dirZ = ddz / d;
                } else {
                    // Axis is inside the wall's footprint: push back along its horizontal normal.
                    double hl = Math.hypot(t.nx, t.nz);
                    if (hl < 1e-6) {
                        continue;
                    }
                    dirX = t.nx / hl;
                    dirZ = t.nz / hl;
                    if ((prevX - t.ax) * dirX + (prevZ - t.az) * dirZ < 0) {
                        dirX = -dirX;
                        dirZ = -dirZ;
                    }
                    pen = radius;
                }
                if (pen > bestPen) {
                    bestPen = pen;
                    bestDx = dirX;
                    bestDz = dirZ;
                }
            }
            if (bestPen <= EPS) {
                break;
            }
            x += bestDx * (bestPen + EPS);
            z += bestDz * (bestPen + EPS);
        }
        return new double[] { x, z };
    }

    /** Sutherland-Hodgman clip of the triangle to lo <= y <= hi. Writes xyz triples, returns vertex count. */
    private static int clipToSlab(T t, double lo, double hi, double[] out) {
        double[] a = { t.ax, t.ay, t.az, t.bx, t.by, t.bz, t.cx, t.cy, t.cz };
        double[] tmp = new double[3 * 6];
        int n = clipPlane(a, 3, tmp, lo, true);
        if (n == 0) {
            return 0;
        }
        return clipPlane(tmp, n, out, hi, false);
    }

    private static int clipPlane(double[] in, int n, double[] out, double level, boolean keepAbove) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ay = in[i * 3 + 1], by = in[j * 3 + 1];
            boolean aIn = keepAbove ? ay >= level : ay <= level;
            boolean bIn = keepAbove ? by >= level : by <= level;
            if (aIn) {
                out[m * 3] = in[i * 3];
                out[m * 3 + 1] = ay;
                out[m * 3 + 2] = in[i * 3 + 2];
                m++;
            }
            if (aIn != bIn) {
                double s = (level - ay) / (by - ay);
                out[m * 3] = in[i * 3] + (in[j * 3] - in[i * 3]) * s;
                out[m * 3 + 1] = level;
                out[m * 3 + 2] = in[i * 3 + 2] + (in[j * 3 + 2] - in[i * 3 + 2]) * s;
                m++;
            }
        }
        return m;
    }

    /** Closest point on the XZ projection of a convex polygon to (x, z). */
    private static double[] closestXZ(double[] poly, int n, double x, double z) {
        double area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += poly[i * 3] * poly[j * 3 + 2] - poly[j * 3] * poly[i * 3 + 2];
        }
        if (Math.abs(area) > 1e-9) {
            boolean inside = true;
            for (int i = 0; i < n && inside; i++) {
                int j = (i + 1) % n;
                double cross = (poly[j * 3] - poly[i * 3]) * (z - poly[i * 3 + 2]) - (poly[j * 3 + 2] - poly[i * 3 + 2]) * (x - poly[i * 3]);
                inside = area > 0 ? cross >= -1e-12 : cross <= 1e-12;
            }
            if (inside) {
                return new double[] { x, z };
            }
        }
        double bestD = Double.MAX_VALUE, bx = poly[0], bz = poly[2];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double x0 = poly[i * 3], z0 = poly[i * 3 + 2], x1 = poly[j * 3], z1 = poly[j * 3 + 2];
            double ex = x1 - x0, ez = z1 - z0;
            double l2 = ex * ex + ez * ez;
            double s = l2 > 1e-12 ? Math.max(0, Math.min(1, ((x - x0) * ex + (z - z0) * ez) / l2)) : 0;
            double px = x0 + ex * s, pz = z0 + ez * s;
            double d = (px - x) * (px - x) + (pz - z) * (pz - z);
            if (d < bestD) {
                bestD = d;
                bx = px;
                bz = pz;
            }
        }
        return new double[] { bx, bz };
    }
}
