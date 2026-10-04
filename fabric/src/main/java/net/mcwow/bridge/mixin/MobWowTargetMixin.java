package net.mcwow.bridge.mixin;

import net.mcwow.bridge.combat.McwowActorEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hostile Minecraft mobs (zombies, skeletons, spiders, creepers...) also go after WoW creatures -
 * their invisible stand-ins (2026-10-01). Priority 3: below vanilla's "attack the player" (2), so a
 * nearby player still wins. Their hits reach WoW threat-free (McwowActorEntity.HIT_WILD).
 */
@Mixin(Mob.class)
public abstract class MobWowTargetMixin {
    @Shadow @Final protected GoalSelector targetSelector;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void mcwow$huntWowCreatures(EntityType<? extends Mob> type, Level level, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (self instanceof Enemy && !level.isClientSide()) {
            this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(self, McwowActorEntity.class, true));
        }
    }
}
