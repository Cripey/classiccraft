package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowGround;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dropped items, XP orbs and Minecraft mobs stay put where WoW's ground isn't loaded (2026-10-01:
 * items "did not persist" when the player came back). geom_server only streams WoW's collision
 * near the player (terrain patch ~24 yd); further out Minecraft kept simulating them with nothing
 * underneath, so they fell out of the world. Outside the loaded patch (with a 2-block margin)
 * they don't tick at all - no falling, no ageing (the 5-minute item despawn pauses) - and resume
 * once the player is back and the ground is there again.
 */
@Mixin({ItemEntity.class, ExperienceOrb.class, Mob.class})
public abstract class EntityFreezeMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mcwow$freezeWithoutGround(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof net.mcwow.bridge.combat.McwowActorEntity) return;
        if (!McwowGeomStore.appliesTo(self.level())) return;
        if (!McwowGround.isKnownWithin(self.getX(), self.getZ(), 2.0)) ci.cancel();
    }
}
