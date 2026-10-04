package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowClip;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft ignores projectile hits on air, and to Minecraft a WoW wall is air. A hit on WoW
 * geometry is a real hit: arrows stick, eggs and snowballs break (SkyCraft's ProjectileMixin).
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @Shadow
    protected abstract void onHit(HitResult hitResult);

    @Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
    private void mcwow$hitWow(HitResult hitResult, CallbackInfoReturnable<ProjectileDeflection> cir) {
        if (hitResult instanceof McwowClip.WowHitResult) {
            this.onHit(hitResult);
            cir.setReturnValue(ProjectileDeflection.NONE);
        }
    }
}
