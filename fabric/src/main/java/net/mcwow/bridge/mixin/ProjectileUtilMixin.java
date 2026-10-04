package net.mcwow.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.mcwow.bridge.McwowClip;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Thrown projectiles (eggs, snowballs, pearls, potions) see WoW's surfaces (SkyCraft's ProjectileUtilMixin). */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {
    @WrapOperation(method = "getHitResult(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;FLnet/minecraft/world/level/ClipContext$Block;)Lnet/minecraft/world/phys/HitResult;",
            at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private static BlockHitResult mcwow$hitWow(Level level, ClipContext context, Operation<BlockHitResult> original) {
        return McwowClip.refine(level, context.getFrom(), context.getTo(), original.call(level, context));
    }
}
