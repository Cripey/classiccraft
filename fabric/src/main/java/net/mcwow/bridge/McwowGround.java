package net.mcwow.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * WoW ground per Minecraft cell, in WORLD block coords (the region offset is applied here) - for the
 * fluid rules (FlowingFluidMixin), like SkyCraft's SkyCollision.hasGeometry/groundTop/isKnown.
 */
public final class McwowGround {
    private McwowGround() {
    }

    private static VoxelShape shape(BlockPos pos) {
        return McwowWowShapes.shapeAt(pos.getX() - McwowGeomStore.regionOffsetX, pos.getY(),
                pos.getZ() - McwowGeomStore.regionOffsetZ);
    }

    /** Is there WoW geometry in this cell? */
    public static boolean hasGeometry(BlockPos pos) {
        return !shape(pos).isEmpty();
    }

    /** Top of the WoW geometry in this cell, 0..1 (0 = none). */
    public static float groundTop(BlockPos pos) {
        VoxelShape s = shape(pos);
        return s.isEmpty() ? 0.0F : (float) s.max(Direction.Axis.Y);
    }

    /**
     * Is WoW's ground loaded at world (x, z) and {@code margin} blocks around it? Entities where it
     * isn't are frozen (EntityFreezeMixin) - with nothing there they would fall forever.
     */
    public static boolean isKnownWithin(double x, double z, double margin) {
        double lx = x - McwowGeomStore.regionOffsetX, lz = z - McwowGeomStore.regionOffsetZ;
        return McwowGeomStore.knownAtLocal(lx, lz) && McwowGeomStore.knownAtLocal(lx - margin, lz - margin)
                && McwowGeomStore.knownAtLocal(lx + margin, lz - margin) && McwowGeomStore.knownAtLocal(lx - margin, lz + margin)
                && McwowGeomStore.knownAtLocal(lx + margin, lz + margin);
    }

    /** Has geom_server sent the cell this block is in? */
    public static boolean isKnown(BlockPos pos) {
        return McwowGeomStore.knownAtLocal(pos.getX() + 0.5 - McwowGeomStore.regionOffsetX,
                pos.getZ() + 0.5 - McwowGeomStore.regionOffsetZ);
    }
}
