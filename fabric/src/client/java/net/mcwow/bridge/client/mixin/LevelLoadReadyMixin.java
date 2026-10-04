package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowRenderLink;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft's terrain-loading screen (dimension change, respawn) waits until the section around the
 * player is COMPILED for rendering - with a 30 s timeout. While WoW draws the world (render link
 * active) LevelRendererMixin skips Minecraft's section compiling, so the signal never came and every
 * change into another WoW map's dimension sat out the whole timeout (measured 2026-10-01: ~40 s).
 * Here the wait ends as soon as the player's chunk has arrived from the server.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForPlayerChunk")
public abstract class LevelLoadReadyMixin {
    @Shadow public abstract LocalPlayer player();
    @Shadow public abstract ClientLevel level();

    @Inject(method = "isReady", at = @At("HEAD"), cancellable = true)
    private void mcwow$readyWithoutCompiling(CallbackInfoReturnable<Boolean> cir) {
        if (!McwowRenderLink.active()) return;
        LocalPlayer p = this.player();
        if (p != null && this.level().getChunkSource().hasChunk(p.getBlockX() >> 4, p.getBlockZ() >> 4)) {
            cir.setReturnValue(true);
        }
    }
}
