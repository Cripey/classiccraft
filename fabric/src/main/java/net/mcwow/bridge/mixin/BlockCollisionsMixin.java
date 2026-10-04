package net.mcwow.bridge.mixin;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowTriBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * REWRITTEN FROM SCRATCH 2026-10-02 at the user's explicit direction, after the previous height/
 * backing-row/neighbor-matching approach (see git history) kept needing a new patch for every new
 * shape of geometry - doorways, stairs, slopes, walls - because it was fundamentally a 1D
 * (one-height-per-column) approximation of what is actually a 3D occupancy problem.
 *
 * This project's own research into chasmlol/SkyCraft's real source found its actual foundation:
 * Skyrim ships a genuine 8x8x8 SUB-VOXEL OCCUPANCY MASK per region for general block collision -
 * a real "does solid geometry occupy this cell" test, not a height heuristic. This mixin does the
 * direct equivalent: for each queried AIR block, test whether any REAL triangle (floor OR wall -
 * all of them, not just "walkable" ones, since general block occupancy should include everything)
 * actually intersects each SUB-cell of that block's volume ({@link McwowTriBox}), building a
 * partial VoxelShape from only the occupied sub-cells. Same @WrapOperation target chasmlol/
 * SkyCraft's own BlockCollisionsMixin uses (confirmed identical method signature via javap).
 *
 * UPGRADED 2026-10-02, same day: the FIRST version tested the whole block as one unit (solid or
 * not, no partial shape) - confirmed live as the direct cause of "an invisible box on flat ground
 * I must jump over": WoW scenes have lots of small real decorative clutter (confirmed in
 * geom_server's own logs: Sack01, Barrel01, Candelabratallwall01, Generalbookstacktall01 ...) -
 * a knee-high barrel touching a block made the ENTIRE column solid, including empty air well
 * above the actual object. Subdividing into a SUBDIV^3 grid per block and only marking the
 * sub-cells a real triangle actually touches fixes that (a small object only blocks where it
 * actually is) and should also represent stair risers more faithfully (a real partial-height step
 * no longer snaps to a whole block). SUBDIV=4 (not SkyCraft's own 8, and per-BLOCK rather than
 * per-8-block-REGION) is a deliberate, practical balance - still enough resolution to stop small
 * clutter from blocking a whole column, cheaper than the full 8x8x8.
 *
 * Vanilla movement, step-up, onGround, jump and fall-damage logic now run COMPLETELY UNCHANGED
 * against these shapes - {@link net.mcwow.bridge.client.McwowCollider} no longer overrides the
 * local player's own collide() result at all (see its own doc comment), so there is nothing left
 * to fight vanilla's own physics over.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin {
    // UPGRADED 2026-10-02, same day: raised 4 -> 8 at the user's request for finer detail, now
    // that the SAT-completeness fix (McwowTriBox) proved the foundation is sound - this now
    // matches SkyCraft's own real resolution exactly (8x8x8), not just the same category of
    // approach. Still cached (CACHE_TTL_NANOS below), so the extra cost (512 vs 64 sub-cell tests
    // per block) is paid once per ~2 ticks per block, not every query.
    @WrapOperation(
        method = "computeNext",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/shapes/CollisionContext;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
        )
    )
    private VoxelShape mcwow$addWowGround(
            CollisionContext context, BlockState state, CollisionGetter level, BlockPos pos,
            Operation<VoxelShape> original) {
        VoxelShape blockShape = original.call(context, state, level, pos);
        if (!state.isAir()) {
            // Ground under WoW's surface, for a player walking on that surface: the WoW triangles are
            // the ground, the block would be an invisible step or wall (McwowColumns.isBuriedFor).
            if (context instanceof EntityCollisionContext ec && McwowGeomStore.usesSmoothCollider(ec.getEntity())
                    && McwowGeomStore.appliesTo(ec.getEntity().level())
                    && net.mcwow.bridge.McwowColumns.isBuriedFor(ec.getEntity(), pos)) {
                return Shapes.empty();
            }
            return blockShape;
        }
        if (context instanceof EntityCollisionContext entityContext
                && McwowGeomStore.usesSmoothCollider(entityContext.getEntity())) {
            return blockShape; // players collide with WoW's exact triangles instead (McwowTriCollider), as in SkyCraft
        }
        Object lvl = level;
        if (context instanceof EntityCollisionContext ec && ec.getEntity() != null) {
            lvl = ec.getEntity().level();
        }
        if (!McwowGeomStore.appliesTo(lvl)) {
            return blockShape;
        }
        // Stored geometry is region-local (see McwowGeomStore.regionOffsetX); the shape itself is
        // block-relative, so only the lookup coordinates shift.
        VoxelShape wow = net.mcwow.bridge.McwowWowShapes.shapeAt(pos.getX() - McwowGeomStore.regionOffsetX, pos.getY(),
                pos.getZ() - McwowGeomStore.regionOffsetZ);
        if (wow.isEmpty()) {
            return blockShape;
        }
        return blockShape.isEmpty() ? wow : Shapes.or(blockShape, wow);
    }
}
