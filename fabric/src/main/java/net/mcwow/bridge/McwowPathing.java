package net.mcwow.bridge;

import net.mcwow.bridge.mixin.PathNavigationRegionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

/**
 * Minecraft mob AI on WoW's ground (2026-10-01: mobs collided with WoW terrain but never walked).
 * Vanilla pathfinding never asks the collision system where the ground is - it reads BLOCK STATES
 * (WalkNodeEvaluator path types and floor levels, PathNavigation.isStableDestination, the random
 * stroll target pickers), and WoW's ground is air to all of them: every node is OPEN ("over the
 * void"), so no path is ever built and mobs idle where they stand. These helpers describe WoW's
 * geometry the way vanilla describes blocks, from the same 8x8x8 shapes the collision uses
 * (McwowWowShapes):
 * <ul>
 * <li>a cell whose WoW geometry reaches above {@link #STAND_IN_MAX} is BLOCKED (like a slab or a
 * full block - the mob stands in the cell above);</li>
 * <li>a cell with lower WoW geometry is WALKABLE itself (like a carpet or a low snow layer - the mob
 * stands inside it, on top of that geometry).</li>
 * </ul>
 */
public final class McwowPathing {
    private McwowPathing() {
    }

    /** WoW geometry up to this height (fraction of the cell) is stood on inside the cell. */
    public static final double STAND_IN_MAX = 0.5;

    /** The Level behind a pathfinding BlockGetter (the Level itself or a PathNavigationRegion). */
    public static Level levelOf(BlockGetter getter) {
        if (getter instanceof Level level) return level;
        if (getter instanceof PathNavigationRegionAccessor region) return region.mcwow$level();
        return null;
    }

    /** Top (0..1] of the WoW geometry in an AIR cell; 0 = none, not air, or not the WoW map. */
    public static double wowTop(BlockGetter getter, BlockPos pos) {
        if (!McwowGeomStore.appliesTo(levelOf(getter))) return 0.0;
        if (!getter.getBlockState(pos).isAir()) return 0.0;
        return McwowGround.groundTop(pos);
    }

    /**
     * How high (0..1] a mob stands in this air cell on WoW's ground; 0 = no WoW geometry here.
     * The walkable surface at the cell CENTRE when it passes through this cell, otherwise the top of
     * the WoW shape (walls, or a slope only clipping a corner). Not the shape top alone: on a slope
     * that is the uphill edge, so a mob standing low in a cell was told its own cell was BLOCKED
     * (with a floor a block too low) and almost every path was rejected (2026-10-01). The centre
     * matches vanilla's own rule for which node a mob is in (WalkNodeEvaluator.getStart:
     * floor(y + 0.5)).
     */
    public static double standHeight(BlockGetter getter, BlockPos pos) {
        double top = wowTop(getter, pos);
        if (top <= 0.0) return 0.0;
        double lx = pos.getX() + 0.5 - McwowGeomStore.regionOffsetX;
        double lz = pos.getZ() + 0.5 - McwowGeomStore.regionOffsetZ;
        double h = McwowTriHeight.heightAt(lx, lz, pos.getY() + 1.0 - 3.0); // highest walkable <= y + 1
        if (!Double.isNaN(h) && h >= pos.getY() && h < pos.getY() + 1.0) {
            return Math.max(h - pos.getY(), 1.0e-3);
        }
        return top;
    }

    /** Can a mob stand at pos (feet in this cell) on WoW geometry? */
    public static boolean standsOnWow(BlockGetter getter, BlockPos pos) {
        double own = standHeight(getter, pos);
        if (own > 0.0) return own <= STAND_IN_MAX;
        return standHeight(getter, pos.below()) > STAND_IN_MAX;
    }

    /**
     * Moves a random walk target onto WoW's ground in its column: the highest walkable WoW surface
     * within {@code search} blocks above or below. Vanilla picks targets at random heights and only
     * moves them up out of SOLID blocks - WoW's ground is a thin shell with air beneath, so without
     * this almost every target ends up under or over the surface and gets rejected. Returns pos
     * unchanged when there's no WoW surface in range.
     */
    public static BlockPos snapToWowGround(Level level, BlockPos pos, int search) {
        if (!McwowGeomStore.appliesTo(level)) return pos;
        double lx = pos.getX() + 0.5 - McwowGeomStore.regionOffsetX;
        double lz = pos.getZ() + 0.5 - McwowGeomStore.regionOffsetZ;
        // heightAt returns the highest walkable surface at or below referenceY + 3.
        double h = McwowTriHeight.heightAt(lx, lz, pos.getY() + search - 3.0);
        if (Double.isNaN(h) || h < pos.getY() - search) return pos;
        int cy = Mth.floor(h);
        BlockPos cell = new BlockPos(pos.getX(), cy, pos.getZ());
        return h - cy > STAND_IN_MAX ? cell.above() : cell; // same rule as standHeight
    }
}
