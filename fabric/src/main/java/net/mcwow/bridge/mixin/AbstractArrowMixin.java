package net.mcwow.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.mcwow.bridge.McwowClip;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Arrows and tridents hit WoW's surfaces and stick in them (SkyCraft's AbstractArrowMixin). */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult mcwow$hitWow(Level level, ClipContext context, Operation<BlockHitResult> original) {
        return McwowClip.refine(level, context.getFrom(), context.getTo(), original.call(level, context));
    }
}
