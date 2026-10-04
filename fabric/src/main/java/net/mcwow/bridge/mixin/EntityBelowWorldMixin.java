package net.mcwow.bridge.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No void damage for players (2026-10-01). Steve's Minecraft Y follows WoW's elevation through
 * the anchor (1 block = 1.4667 yd), and WoW's terrain spans far more height than a Minecraft
 * world: in the Void preset the player starts near y=-61 and vanilla's {@code checkBelowWorld}
 * kills anything below minY-64 = -128, i.e. only ~100 yd of descent in WoW. Players don't need
 * Minecraft's world bounds to stand on anything - they collide only with WoW's real triangles
 * ({@code McwowTriCollider}, which ignores block storage) - so the check is simply skipped for
 * them. Other entities keep vanilla behaviour.
 */
@Mixin(Entity.class)
public abstract class EntityBelowWorldMixin {
    @Inject(method = "checkBelowWorld", at = @At("HEAD"), cancellable = true)
    private void mcwow$noVoidForPlayers(CallbackInfo ci) {
        if ((Object) this instanceof Player) ci.cancel();
    }
}
