package net.mcwow.bridge;

// Real triangle-vs-AABB intersection test (the classic Akenine-Moller "Fast 3D Triangle-Box
// Overlap Testing" algorithm), added 2026-10-02 as part of a from-scratch rewrite of block-level
// collision, at the user's explicit direction to match chasmlol/SkyCraft's own real foundation
// as closely as possible. This project's own prior research into SkyCraft's source found that
// Skyrim ships an actual 8x8x8 SUB-VOXEL OCCUPANCY MASK per region for its general block
// collision - a genuine 3D solid/empty test against the real mesh, not a column-height
// approximation. Every bug this project chased for stairs/doorways/walls (knife-edge gaps,
// neighbor-backing walls, phantom shelves, doorway lintels) was a symptom of trying to represent
// a 3D occupancy problem with a 1D height-per-column heuristic - this replaces that heuristic
// with the real thing: does any real triangle actually pass through this block's volume?
//
// UPGRADED 2026-10-02, same day: now implements the FULL 13-axis SAT test, not just the
// bbox+plane subset. Live testing after the sub-voxel upgrade (SUBDIV=4) found a new, more
// specific symptom: "an invisible barrier partway up a narrow staircase" - a hard stop, not a
// slow catch. Narrow stairwells put real wall/railing geometry very close to the player's own
// path, with little lateral slack - exactly where a FALSE POSITIVE from the deliberately-skipped
// 9 edge-cross-product axis tests (documented below as the known gap) would most likely be
// noticed, since there's no room to walk around a stray solid sub-cell the way there is on open
// ground. The missing tests catch cases where a triangle edge grazes a box corner/edge without
// being caught by the box-AABB or triangle-plane tests alone - completing them removes that
// specific class of false positive rather than just shrinking its footprint (SUBDIV already did
// that; this addresses the root cause instead).
public final class McwowTriBox {
    private McwowTriBox() {
    }

    public static boolean intersects(
            float v0x, float v0y, float v0z, float v1x, float v1y, float v1z,
            float v2x, float v2y, float v2z,
            double boxCx, double boxCy, double boxCz,
            double boxHx, double boxHy, double boxHz) {
        double ax = v0x - boxCx, ay = v0y - boxCy, az = v0z - boxCz;
        double bx = v1x - boxCx, by = v1y - boxCy, bz = v1z - boxCz;
        double cx = v2x - boxCx, cy = v2y - boxCy, cz = v2z - boxCz;

        // Triangle's own AABB vs box - a cheap reject before the more expensive tests.
        if (max3(ax, bx, cx) < -boxHx || min3(ax, bx, cx) > boxHx) return false;
        if (max3(ay, by, cy) < -boxHy || min3(ay, by, cy) > boxHy) return false;
        if (max3(az, bz, cz) < -boxHz || min3(az, bz, cz) > boxHz) return false;

        double e0x = bx - ax, e0y = by - ay, e0z = bz - az;
        double e1x = cx - bx, e1y = cy - by, e1z = cz - bz;
        double e2x = ax - cx, e2y = ay - cy, e2z = az - cz;

        // The 9 edge x box-axis tests - for each of the triangle's 3 edges, crossed with each of
        // the box's 3 principal axes (X/Y/Z), project the triangle onto that axis and check for
        // separation from the box's own projection. Any one of these finding separation proves
        // no overlap, same logic as the box/plane tests below, just along a different axis.
        if (!edgeAxisTest(e0x, e0y, e0z, 1, 0, 0, ax, ay, az, bx, by, bz, cx, cy, cz, boxHx, boxHy, boxHz)) return false;
        if (!edgeAxisTest(e0x, e0y, e0z, 0, 1, 0, ax, ay, az, bx, by, bz, cx, cy, cz, boxHx, boxHy, boxHz)) return false;
        if (!edgeAxisTest(e0x, e0y, e0z, 0, 0, 1, ax, ay, az, bx, by, bz, cx, cy, cz, boxHx, boxHy, boxHz)) return false;
        if (!edgeAxisTest(e1x, e1y, e1z, 1, 0, 0, ax, ay, az, bx, by, bz, cx, cy, cz, boxHx, boxHy, boxHz)) return false;
        if (!edgeAxisTest(e1x, e1y, e1z, 0, 1, 0, ax, ay, az, bx, by, bz, cx, cy, cz, boxHx, boxHy, boxHz)) return false;
        if (!edgeAxisTest(e1x, e1y, e1z, 0, 0, 1, ax, ay, az, bx, by, bz, cx, cy, cz, boxHx, boxHy, boxHz)) return false;
        if (!edgeAxisTest(e2x, e2y, e2z, 1, 0, 0, ax, ay, az, bx, by, bz, cx, cy, cz, boxHx, boxHy, boxHz)) return false;
        if (!edgeAxisTest(e2x, e2y, e2z, 0, 1, 0, ax, ay, az, bx, by, bz, cx, cy, cz, boxHx, boxHy, boxHz)) return false;
        if (!edgeAxisTest(e2x, e2y, e2z, 0, 0, 1, ax, ay, az, bx, by, bz, cx, cy, cz, boxHx, boxHy, boxHz)) return false;

        // The triangle's own plane vs the box - rejects boxes the triangle's AABB overlaps but
        // whose actual plane never reaches (e.g. a box in the corner of a diagonal triangle's
        // bounding box, far from the triangle's own surface).
        double nx = e0y * e1z - e0z * e1y;
        double ny = e0z * e1x - e0x * e1z;
        double nz = e0x * e1y - e0y * e1x;
        return planeBoxOverlap(nx, ny, nz, ax, ay, az, boxHx, boxHy, boxHz);
    }

    // Separating-axis test along axis = cross(edge, boxAxis). Returns false if this axis proves
    // the triangle and box don't overlap (a real separating axis), true otherwise (inconclusive -
    // keep checking the other axes).
    private static boolean edgeAxisTest(double ex, double ey, double ez, double ux, double uy, double uz,
            double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
            double hx, double hy, double hz) {
        double axisX = ey * uz - ez * uy;
        double axisY = ez * ux - ex * uz;
        double axisZ = ex * uy - ey * ux;
        double pa = axisX * ax + axisY * ay + axisZ * az;
        double pb = axisX * bx + axisY * by + axisZ * bz;
        double pc = axisX * cx + axisY * cy + axisZ * cz;
        double pMin = Math.min(pa, Math.min(pb, pc));
        double pMax = Math.max(pa, Math.max(pb, pc));
        double r = hx * Math.abs(axisX) + hy * Math.abs(axisY) + hz * Math.abs(axisZ);
        return !(pMin > r || pMax < -r);
    }

    private static double max3(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }

    private static double min3(double a, double b, double c) {
        return Math.min(a, Math.min(b, c));
    }

    private static boolean planeBoxOverlap(double nx, double ny, double nz,
            double vx, double vy, double vz, double hx, double hy, double hz) {
        double vMinX, vMaxX, vMinY, vMaxY, vMinZ, vMaxZ;
        if (nx > 0) { vMinX = -hx - vx; vMaxX = hx - vx; } else { vMinX = hx - vx; vMaxX = -hx - vx; }
        if (ny > 0) { vMinY = -hy - vy; vMaxY = hy - vy; } else { vMinY = hy - vy; vMaxY = -hy - vy; }
        if (nz > 0) { vMinZ = -hz - vz; vMaxZ = hz - vz; } else { vMinZ = hz - vz; vMaxZ = -hz - vz; }
        if (nx * vMinX + ny * vMinY + nz * vMinZ > 0) return false;
        return nx * vMaxX + ny * vMaxY + nz * vMaxZ >= 0;
    }
}
