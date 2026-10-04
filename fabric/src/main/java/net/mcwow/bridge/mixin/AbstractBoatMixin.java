package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowColumns;
import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowTerrainFill;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boats on WoW's ground (2026-10-02, user's choice: vanilla-like on land). WoW's ground is a
 * stair-stepped voxel surface to a boat (BlockCollisionsMixin) with no block under it:
 * <ul>
 * <li>a boat steps up a third of a block, so it creeps up gentle WoW slopes and stops at steep ones
 * (vanilla boats step 0 - on WoW's all-slope ground they stalled everywhere);</li>
 * <li>resting on WoW's ground it takes the friction of the column's top block (grass, dirt 0.6 -
 * vanilla's land boat), not none (it read as airborne);</li>
 * <li>a boat that ends up wholly under WoW water is lifted to the surface, as vanilla lifts one
 * landing in water from the air: rolling down a bank, it followed WoW's lake bed on under the
 * water, where vanilla's submerged boat sinks (it never gets there in vanilla: a shore block's top
 * is the water's edge);</li>
 * <li>in WoW water a boat's water level is WoW's exact surface over it. Vanilla reads it only from
 * the blocks at the boat's bottom: a full block's top until the bottom rose into the thin top block
 * (FluidHeightMixin), then that block's surface - it floated low and bobbed fast between the two.</li>
 * </ul>
 */
@Mixin(AbstractBoat.class)
public abstract class AbstractBoatMixin extends Entity {
    private static final float WOW_STEP = 1.0F / 3.0F;
    private static final float DEFAULT_FRICTION = 0.6F;

    @Shadow
    private AbstractBoat.Status status;
    @Shadow
    private AbstractBoat.Status oldStatus;
    @Shadow
    private double waterLevel;

    private AbstractBoatMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    public float maxUpStep() {
        return McwowGeomStore.appliesTo(this.level()) ? Math.max(super.maxUpStep(), WOW_STEP) : super.maxUpStep();
    }

    @Inject(method = "floatBoat", at = @At("HEAD"))
    private void mcwow$surfaceInWowWater(CallbackInfo ci) {
        if (this.status != AbstractBoat.Status.UNDER_WATER || !McwowGeomStore.appliesTo(this.level())) return;
        BlockPos top = BlockPos.containing(this.getX(), this.getBoundingBox().maxY, this.getZ());
        // Vanilla's air-to-water branch: up to the water level above, motion stopped, IN_WATER.
        if (McwowColumns.inWowWater(this.level(), top)) this.oldStatus = AbstractBoat.Status.IN_AIR;
    }

    @Inject(method = "checkInWater", at = @At("RETURN"), cancellable = true)
    private void mcwow$wowWaterLevel(CallbackInfoReturnable<Boolean> cir) {
        if (!McwowGeomStore.appliesTo(this.level())) return;
        double minY = this.getBoundingBox().minY;
        BlockPos bottom = BlockPos.containing(this.getX(), minY, this.getZ());
        double surface = McwowColumns.wowSurfaceAt(this.level(), bottom.getX(), bottom.getZ());
        if (Double.isNaN(surface) || !(minY < surface) || !McwowColumns.inWowWater(this.level(), bottom)) return;
        this.waterLevel = Math.max(this.waterLevel, surface);
        cir.setReturnValue(true);
    }

    @Inject(method = "getGroundFriction", at = @At("RETURN"), cancellable = true)
    private void mcwow$wowGroundFriction(CallbackInfoReturnable<Float> cir) {
        if (cir.getReturnValueF() > 0.0F || !this.onGround() || !McwowGeomStore.appliesTo(this.level())) return;
        int top = McwowColumns.topOf(this.level(), this.getBlockX(), this.getBlockZ());
        float friction = top == McwowTerrainFill.NO_TOP ? DEFAULT_FRICTION
                : this.level().getBlockState(new BlockPos(this.getBlockX(), top, this.getBlockZ())).getBlock().getFriction();
        cir.setReturnValue(friction);
    }
}
