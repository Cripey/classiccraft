package net.mcwow.bridge;

// Shared between the client-tick reader (writer - McwowBridgeClient, client sourceset) and the
// collision mixin (reader - BlockCollisionsMixin, main sourceset, runs on the integrated
// server's own thread). Both live in the SAME JVM process for singleplayer (the integrated
// server is just another thread, not a separate process), so a plain volatile holder is enough
// here - no cross-process IPC needed for this particular hop, unlike the WoW<->MC bridge itself.
//
// Found necessary 2026-10-01 after checking chasmlol/SkyCraft's own source (the user's
// request): SkyCraft never places real blocks for collision - its `BlockCollisionsMixin`
// injects Skyrim's geometry directly into Minecraft's own collision queries
// (`CollisionContext.getCollisionShape`), so vanilla movement/step-up/onGround/fall-damage code
// runs completely unchanged against synthetic shapes, nothing ever touches real block state.
// This project's own first attempt (placing real STONE blocks, tracked/moved every tick) was
// the wrong shape of solution - visible, limited to one flat height, and directly caused the
// "floor spawns above my character" bug (compounded by the separate eye-vs-feet mixup, fixed
// earlier the same day).
//
// UPGRADED same day from a single scalar to a small GRID of cells, after a live report: "when
// going up a slope or stairs I fall through the map." One shared height becomes one infinite
// flat plane in Minecraft's collision - fine on flat ground, but it can't represent a staircase
// or ramp, where neighboring columns genuinely need different heights.
public final class McwowGroundState {
    public record Cell(int x, int z, double groundY) {}

    // Replaced as a whole array each tick (never mutated in place) so a reader always sees a
    // fully-consistent set of cells - a plain volatile reference swap is enough for that,
    // without needing a lock between the two threads.
    public static volatile Cell[] cells = new Cell[0];

    /** The WoW TerrainType.dbc id under the player (benilla), -1 for none (McwowFootsteps). */
    public static volatile int wowTerrain = -1;

    // Added 2026-10-02 - the local player's own feet Y, refreshed once per client tick
    // (McwowBridgeClient.onClientTick, which already has `mc.player`). BlockCollisionsMixin
    // (main sourceset, no direct player access by design) uses this as the shared reference
    // height for every McwowTriHeight query in a tick, INSTEAD OF the queried block row itself.
    // Found necessary live: anchoring each query to its own probed row let vanilla's collision
    // sweep (which probes many different Y rows per column during one movement resolve) each
    // independently find "the nearest walkable real surface below THIS row" - inside a real WMO
    // building with multiple stacked surfaces (a door lintel, a raised threshold, a balcony), a
    // row near head height could snag a real but UNRELATED walkable surface and inject a phantom
    // solid block there, blocking passage - confirmed live as the direct cause of "not able to
    // walk through some doorways". A single shared reference near the player's actual feet,
    // computed once, is what both the old point grid and McwowCollider always used - this
    // restores that same "near the player" semantics for the real-triangle path too.
    public static volatile double playerRefY = 0.0;

    // WoW's OWN client-side ground trace (wow::TraceLine) exactly under the puppet's feet - the
    // grid's center cell, sampled at the WoW player's real position (not a column center). Pure
    // diagnostic ground truth for McwowCollider's log: "what WoW itself thinks the floor is here."
    public static volatile double wowGroundUnderPlayer = Double.NaN;

    // Added 2026-10-01, after the user asked to mimic chasmlol/SkyCraft's own collision as
    // closely as possible. SkyCraft never snaps to a block grid at all - `TriCollider` samples
    // its real triangle mesh at the player's EXACT continuous position every tick, so slopes and
    // stairs are followed smoothly instead of the "stair-stepped" quantization its own comment
    // explicitly calls out as vanilla Minecraft's limitation. We only have point samples (`wow::
    // TraceLine`, one per MC block column, not a real mesh), but bilinear interpolation between
    // the 4 surrounding cells turns that discrete grid into a continuous-enough height function
    // for the same purpose - used by `McwowCollider` (mimicking `SkyCollider`/`TriCollider`'s
    // own vertical-resolution logic), NOT by `BlockCollisionsMixin`, which still needs discrete
    // per-block shapes for its own, coarser job (matches SkyCraft's own two-layer design: a
    // block-query mixin for general "is this solid" questions, plus a movement-level mixin that
    // refines the LOCAL PLAYER's own travel against the finer-grained source).
    public static double heightAt(double x, double z) {
        Cell[] snapshot = cells; // one consistent read
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        Double c00 = find(snapshot, x0, z0), c10 = find(snapshot, x0 + 1, z0);
        Double c01 = find(snapshot, x0, z0 + 1), c11 = find(snapshot, x0 + 1, z0 + 1);
        if (c00 == null && c10 == null && c01 == null && c11 == null) return Double.NaN;
        double fx = x - x0, fz = z - z0;
        // Missing corners fall back to whichever neighbor IS present, rather than failing the
        // whole interpolation - the grid's edge is a soft boundary, not a hard cliff.
        double v00 = c00 != null ? c00 : nearest(c10, c01, c11);
        double v10 = c10 != null ? c10 : nearest(c00, c01, c11);
        double v01 = c01 != null ? c01 : nearest(c00, c10, c11);
        double v11 = c11 != null ? c11 : nearest(c00, c10, c01);
        double top = v00 * (1 - fx) + v10 * fx;
        double bottom = v01 * (1 - fx) + v11 * fx;
        return top * (1 - fz) + bottom * fz;
    }

    private static Double find(Cell[] snapshot, int x, int z) {
        for (Cell c : snapshot) {
            if (c.x() == x && c.z() == z) return c.groundY();
        }
        return null;
    }

    private static double nearest(Double a, Double b, Double c) {
        if (a != null) return a;
        if (b != null) return b;
        if (c != null) return c;
        return Double.NaN;
    }

    private McwowGroundState() {}
}
