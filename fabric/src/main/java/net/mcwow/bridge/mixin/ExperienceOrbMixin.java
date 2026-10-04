package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.combat.McwowXp;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * XP orbs carrying a WoW kill's XP (McwowXp): they never merge (the share would be lost or doubled),
 * and picking one up claims its share from the server. Any orb in a WoW world steps up WoW's
 * stair-stepped ground (2026-10-02: with vanilla's step 0 orbs snagged on every slope on their way
 * to the player).
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin extends Entity {
    private static final float WOW_STEP = 0.6F;

    private ExperienceOrbMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    public float maxUpStep() {
        return McwowGeomStore.appliesTo(this.level()) ? Math.max(super.maxUpStep(), WOW_STEP) : super.maxUpStep();
    }

    @Inject(method = "canMerge(Lnet/minecraft/world/entity/ExperienceOrb;)Z", at = @At("HEAD"), cancellable = true)
    private void mcwow$keepWowXpApart(ExperienceOrb other, CallbackInfoReturnable<Boolean> cir) {
        if (((ExperienceOrb) (Object) this).hasAttached(McwowXp.PAYLOAD) || other.hasAttached(McwowXp.PAYLOAD)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canMerge(Lnet/minecraft/world/entity/ExperienceOrb;II)Z", at = @At("HEAD"), cancellable = true)
    private static void mcwow$noMergeIntoWowXp(ExperienceOrb orb, int id, int value, CallbackInfoReturnable<Boolean> cir) {
        if (orb.hasAttached(McwowXp.PAYLOAD)) cir.setReturnValue(false);
    }

    @Inject(method = "playerTouch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;take(Lnet/minecraft/world/entity/Entity;I)V"))
    private void mcwow$claimWowXp(Player player, CallbackInfo ci) {
        ExperienceOrb orb = (ExperienceOrb) (Object) this;
        int[] payload = orb.getAttached(McwowXp.PAYLOAD);
        if (payload == null || player.level().isClientSide()) return;
        orb.removeAttached(McwowXp.PAYLOAD); // claimed once
        McwowXp.CLAIMS.add(new McwowXp.Claim(payload[0], payload[1]));
    }
}
