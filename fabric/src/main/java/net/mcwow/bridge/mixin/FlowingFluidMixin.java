package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowGround;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Water and lava settle on WoW's ground and run over it, never into it - port of chasmlol/SkyCraft's
 * FlowingFluidMixin. WoW's terrain is a thin surface to Minecraft (air with WoW geometry in it,
 * McwowGround), so the rules follow that surface:
 * <ul>
 * <li>a fluid lying on WoW geometry in its cell never flows down through it;</li>
 * <li>it moves sideways into a cell only where its surface there would be above the ground in that
 * cell, so it runs downhill and over bumps but not uphill;</li>
 * <li>an empty cell right under WoW geometry is under the ground (or an overhang): no sideways flow;</li>
 * <li>it never enters cells geom_server hasn't described yet (beyond the streamed area).</li>
 * </ul>
 * Only in the active WoW map dimension.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    // A falling fluid's surface in its cell (8/9) and the margin it must clear the ground by.
    private static final float FALLING_SURFACE = 8.0F / 9.0F;
    private static final float MARGIN = 0.05F;
    private static long mcwow$lastLog;
    private static int mcwow$logged;

    @Inject(method = "canPassThroughWall", at = @At("HEAD"), cancellable = true)
    private static void mcwow$wowWall(Direction direction, BlockGetter level, BlockPos sourcePos, BlockState sourceState,
                                      BlockPos targetPos, BlockState targetState, CallbackInfoReturnable<Boolean> cir) {
        if (!targetState.isAir() || direction == Direction.UP || !McwowGeomStore.appliesTo(level)) {
            return;
        }
        // WoW's lakes and rivers keep their shape (outdoor water, 2026-10-02): their water never
        // spreads sideways out of WoW's water - a river's stepped surface spilled flowing water onto
        // its flat banks, drawn there over WoW's ground. Down into a dug hole it still drains.
        if (direction != Direction.DOWN && net.mcwow.bridge.McwowColumns.inWowWater(level, sourcePos)
                && !net.mcwow.bridge.McwowColumns.inWowWater(level, targetPos)) {
            refused("leaves WoW water", direction, sourcePos, sourceState, targetPos, 0.0F);
            cir.setReturnValue(false);
            return;
        }
        if (!McwowGround.isKnown(targetPos)) {
            refused("unknown area", direction, sourcePos, sourceState, targetPos, 0.0F);
            cir.setReturnValue(false);
            return;
        }
        if (direction == Direction.DOWN) {
            if (McwowGround.hasGeometry(sourcePos)) {
                refused("resting on ground", direction, sourcePos, sourceState, targetPos, McwowGround.groundTop(sourcePos));
                cir.setReturnValue(false); // lying on WoW ground: it doesn't sink through
                return;
            }
            float top = McwowGround.groundTop(targetPos);
            if (top >= FALLING_SURFACE - MARGIN) {
                refused("ground below", direction, sourcePos, sourceState, targetPos, top);
                cir.setReturnValue(false);
            }
            return;
        }
        // Sideways.
        if (!McwowGround.hasGeometry(targetPos)) {
            if (McwowGround.hasGeometry(targetPos.above())) {
                refused("under ground", direction, sourcePos, sourceState, targetPos, 1.0F);
                cir.setReturnValue(false); // under rising terrain or an overhang
            }
            return;
        }
        FluidState fluid = sourceState.getFluidState();
        float surface;
        if (fluid.isEmpty()) {
            surface = 0.8F; // Minecraft looking ahead for a slope: any cell a flow could reach
        } else if (fluid.getValue(FlowingFluid.FALLING)) {
            surface = 7.0F / 9.0F; // a falling fluid spreads at level 7 where it lands
        } else {
            int drop = fluid.is(FluidTags.LAVA) ? 2 : 1; // one level less there (lava two outside the Nether)
            surface = Math.max(0, fluid.getAmount() - drop) / 9.0F;
        }
        float top = McwowGround.groundTop(targetPos);
        // Level or downhill ground (within a voxel of the ground it leaves) always takes it, as a
        // flat Minecraft floor would: its drawn surface sits on that ground (McwowWorldExporter).
        boolean flatOrDownhill = top <= McwowGround.groundTop(sourcePos) + 0.13F;
        if (!flatOrDownhill && top >= surface - MARGIN) {
            refused("ground beside", direction, sourcePos, sourceState, targetPos, top);
            cir.setReturnValue(false);
        }
    }

    private static void refused(String why, Direction direction, BlockPos sourcePos, BlockState sourceState, BlockPos targetPos, float ground) {
        long now = System.currentTimeMillis();
        if (now - mcwow$lastLog > 1000) {
            mcwow$lastLog = now;
            mcwow$logged = 0;
        }
        if (mcwow$logged++ < 3) {
            FluidState fluid = sourceState.getFluidState();
            LOGGER.info("mcwow-bridge: fluid flow refused ({}): {} -> {} going {}, {} amount {}, WoW ground {}", why,
                    sourcePos.toShortString(), targetPos.toShortString(), direction, fluid.getType(), fluid.getAmount(),
                    String.format("%.2f", ground));
        }
    }
}
