package net.mcwow.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Columns of WoW ground (breakable terrain, 2026-10-02), for client and server alike: each column's
 * top filled block as recorded when its chunk was filled (McwowTerrainFill.TOPS), and whether the
 * column is OPEN - its top no longer natural ground (dug out, or built over with stairs, planks...),
 * or something solid standing in the gap cell above it. An open column shows its blocks instead of
 * WoW's ground; a closed one is WoW's ground, its top block hidden just under it.
 */
public final class McwowColumns {
    private McwowColumns() {
    }

    /** A column's top filled block (world x, z), or NO_TOP: not filled, not loaded, or no ground. */
    public static int topOf(Level level, int x, int z) {
        ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return McwowTerrainFill.NO_TOP;
        int[] tops = chunk.getAttached(McwowTerrainFill.TOPS);
        return tops == null ? McwowTerrainFill.NO_TOP : tops[(z & 15) * 16 + (x & 15)];
    }

    /**
     * Whether pos is inside WoW's own lakes and seas (outdoor water, 2026-10-02): over its column's
     * top block and at or under the water top recorded when the chunk was watered
     * (McwowTerrainFill.WATER). False where the chunk isn't loaded.
     */
    public static boolean inWowWater(BlockGetter level, BlockPos pos) {
        if (!(level instanceof net.minecraft.world.level.LevelReader reader)) return false;
        ChunkAccess chunk = reader.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return false;
        int[] water = chunk.getAttached(McwowTerrainFill.WATER), tops = chunk.getAttached(McwowTerrainFill.TOPS);
        if (water == null || tops == null) return false;
        int i = (pos.getZ() & 15) * 16 + (pos.getX() & 15);
        return water[i] != McwowTerrainFill.NO_TOP && pos.getY() <= water[i] && pos.getY() > tops[i];
    }

    /**
     * The WoW water surface's height (0..1) in pos when pos is its column's top WoW water block
     * (McwowTerrainFill.WATER, SURFACE); NaN anywhere else.
     */
    public static float wowWaterHeight(BlockGetter level, BlockPos pos) {
        if (!(level instanceof net.minecraft.world.level.LevelReader reader)) return Float.NaN;
        ChunkAccess chunk = reader.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return Float.NaN;
        int[] water = chunk.getAttached(McwowTerrainFill.WATER), surface = chunk.getAttached(McwowTerrainFill.SURFACE);
        if (water == null || surface == null) return Float.NaN;
        int i = (pos.getZ() & 15) * 16 + (pos.getX() & 15);
        if (water[i] != pos.getY() || surface[i] == McwowTerrainFill.NO_TOP) return Float.NaN;
        return Math.clamp(surface[i] / McwowTerrainFill.SURFACE_SCALE - pos.getY(), 0.02F, 1.0F);
    }

    /** The WoW water surface over world column (x, z) (Minecraft y), NaN where there is none. */
    public static double wowSurfaceAt(BlockGetter level, int x, int z) {
        if (!(level instanceof net.minecraft.world.level.LevelReader reader)) return Double.NaN;
        ChunkAccess chunk = reader.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        int[] surface = chunk == null ? null : chunk.getAttached(McwowTerrainFill.SURFACE);
        if (surface == null) return Double.NaN;
        int s = surface[(z & 15) * 16 + (x & 15)];
        return s == McwowTerrainFill.NO_TOP ? Double.NaN : s / (double) McwowTerrainFill.SURFACE_SCALE;
    }

    /**
     * WoW's ground over an entity caught in its column's gap (2026-10-02: a player dismounting a horse
     * or clipping through a slope on an elytra, mobs after a reload): a closed column, the feet
     * on or about its hidden top block (or one under it, where a player standing on the hidden top
     * sinks), no WoW surface at the feet, and WoW's surface over them within reach. The lowest such
     * surface (Minecraft y), or NaN when the entity isn't buried. Caves are deeper than this (a
     * crust under the top block), mines and cellars have a WoW floor at the feet, dug holes are
     * open columns.
     */
    public static double buriedSurface(net.minecraft.world.entity.Entity e) {
        Level level = e.level();
        int x = net.minecraft.util.Mth.floor(e.getX()), z = net.minecraft.util.Mth.floor(e.getZ());
        int top = topOf(level, x, z);
        if (top == McwowTerrainFill.NO_TOP || isOpen(level, x, z)) return Double.NaN;
        double feet = e.getY();
        if (feet > top + 1.05 || feet < top - 0.05) return Double.NaN;
        double lx = e.getX() - McwowGeomStore.regionOffsetX, lz = e.getZ() - McwowGeomStore.regionOffsetZ;
        if (!Double.isNaN(McwowTriHeight.lowestSurface(lx, lz, feet - 0.15, feet + 0.3))) return Double.NaN;
        return McwowTriHeight.lowestSurface(lx, lz, feet + 0.3, feet + BURIED_REACH);
    }

    /** How far over the feet a buried entity's WoW ground may lie (blocks): the gap plus a slope's rise. */
    private static final double BURIED_REACH = 4.0;

    /** Whether the column at world (x, z) is open (dug). */
    public static boolean isOpen(Level level, int x, int z) {
        return isOpen(level, topOf(level, x, z), x, z, new BlockPos.MutableBlockPos());
    }

    /** isOpen for a column whose top is already known. */
    public static boolean isOpen(BlockGetter level, int top, int x, int z, BlockPos.MutableBlockPos pos) {
        if (top == McwowTerrainFill.NO_TOP) return false;
        if (!isNaturalGround(level.getBlockState(pos.set(x, top, z)))) return true;
        // Something solid in the gap cell over it (a stair, a wall's base) pokes through WoW's ground
        // there: open the column so it shows. Torches, flowers and the like don't.
        BlockState gap = level.getBlockState(pos.set(x, top + 1, z));
        return !gap.isAir() && !gap.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Ground that closes a column, bringing WoW's terrain back over it: what the fill puts there and
     * what digging it gives back (dirt for grass, cobblestone for stone). Anything built with (stairs,
     * slabs, planks, bricks...) keeps the column open, so the build shows - a staircase out of a hole
     * meets the WoW ground seamlessly.
     */
    public static boolean isNaturalGround(BlockState state) {
        // Mud has its own tag in 26.3, outside DIRT: every Soggy-ground (MUD) column read as dug and
        // WoW's terrain was cut away over whole swamps (2026-10-02, Tirisfal).
        return state.is(BlockTags.DIRT) || state.is(BlockTags.MUD) || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.CLAY) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE);
    }

    /**
     * Whether a block is a closed column's top, hidden under WoW's ground, at a height a body at
     * feetY walks over (its feet no more than 0.75 under the block's top): WoW's triangles are the
     * ground there. On a slope a body on its own column's ground reaches over the uphill neighbour's
     * top block, which then stood a little above its feet and stopped it dead - an invisible wall,
     * in one direction only (2026-10-02). Down in a hole the same block is a wall and still collides.
     */
    public static boolean isHiddenGroundUnder(Level level, BlockPos pos, double feetY) {
        if (feetY < pos.getY() + 1.0 - 0.75) return false;
        int top = topOf(level, pos.getX(), pos.getZ());
        return top == pos.getY() && !isOpen(level, top, pos.getX(), pos.getZ(), new BlockPos.MutableBlockPos());
    }

    /**
     * Whether a block is buried ground a player on WoW's surface can't touch: isHiddenGroundUnder,
     * or any block at or under a CLOSED column's top while the player's own column is closed and
     * their feet are not a tunnel's depth under its top (or it has no ground at all - under a building). Such a block lies
     * inside WoW's solid ground, which the triangles already collide with. The player's square box
     * reaches ~0.4 into the columns beside them; where WoW's ground rises steeply there (a bank, a
     * path's edge) a neighbour's top block stood at chest height, the server's pose check found the
     * standing box blocked and synced a crawl pose - the camera dipped for a tick (2026-10-03).
     * In a hole or tunnel (own column open, or feet a block or more under its top) they are walls as ever.
     */
    public static boolean isBuriedFor(net.minecraft.world.entity.Entity e, BlockPos pos) {
        Level level = e.level();
        double feet = e.getBoundingBox().minY;
        if (isHiddenGroundUnder(level, pos, feet)) return true;
        int top = topOf(level, pos.getX(), pos.getZ());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        if (top == McwowTerrainFill.NO_TOP || pos.getY() > top || isOpen(level, top, pos.getX(), pos.getZ(), at)) return false;
        int ex = net.minecraft.util.Mth.floor(e.getX()), ez = net.minecraft.util.Mth.floor(e.getZ());
        int own = topOf(level, ex, ez);
        if (own == McwowTerrainFill.NO_TOP) return true;
        // In a tunnel the feet are a whole block or more under the column's top block (its ceiling).
        // Less than that, the recorded top is above the floor actually walked on - a structure's floor
        // under the terrain that the fill didn't lower its top for (2026-10-04, Dun Morogh: feet 0.66
        // under their own column's top block, the camera dipped there and mid-jump).
        return !isOpen(level, own, ex, ez, at) && feet > own - 0.95;
    }
}
