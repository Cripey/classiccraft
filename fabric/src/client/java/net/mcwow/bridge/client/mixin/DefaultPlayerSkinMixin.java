package net.mcwow.bridge.client.mixin;

import java.util.UUID;

import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Always the classic Steve (user, 2026-10-01). The dev client plays offline with a random name and
 * UUID each launch, and Minecraft picks the default skin from the UUID's hash, so the skin changed
 * every restart. DEFAULT_SKINS[15] is entity/player/wide/steve (getDefaultSkin() returns the SLIM
 * Steve, index 6 - not the original).
 */
@Mixin(DefaultPlayerSkin.class)
public abstract class DefaultPlayerSkinMixin {
    @Shadow @Final private static PlayerSkin[] DEFAULT_SKINS;

    @Inject(method = "get(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("HEAD"), cancellable = true)
    private static void mcwow$classicSteve(UUID id, CallbackInfoReturnable<PlayerSkin> cir) {
        cir.setReturnValue(DEFAULT_SKINS[15]);
    }
}
