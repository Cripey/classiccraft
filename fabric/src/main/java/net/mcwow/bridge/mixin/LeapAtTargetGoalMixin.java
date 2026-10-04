package net.mcwow.bridge.mixin;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A leap faces its target (2026-10-02: a wolf jumped backwards at a WoW bear). Vanilla only sets the
 * leap's velocity, and a mob moving against its facing turns its body backwards rather than around
 * (LivingEntity's 95-265 degree rule). Vanilla mobs have nearly always walked at the target first, so
 * they already face it; WoW creatures walk up to a standing wolf themselves (WoW AI chases its
 * proxy), so the wolf still faced wherever it last walked.
 */
@Mixin(LeapAtTargetGoal.class)
public abstract class LeapAtTargetGoalMixin {
    @Shadow @Final private Mob mob;
    @Shadow private LivingEntity target;

    @Inject(method = "start", at = @At("HEAD"))
    private void mcwow$faceTarget(CallbackInfo ci) {
        if (this.target == null) return;
        double dx = this.target.getX() - this.mob.getX();
        double dz = this.target.getZ() - this.mob.getZ();
        if (dx * dx + dz * dz < 1.0e-6) return;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        this.mob.setYRot(yaw);
        this.mob.setYBodyRot(yaw);
        this.mob.setYHeadRot(yaw);
    }
}
