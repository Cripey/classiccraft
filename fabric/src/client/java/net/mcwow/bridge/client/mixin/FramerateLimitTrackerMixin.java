package net.mcwow.bridge.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.mcwow.bridge.client.McwowOverlayLink;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No background/inactive frame-rate cap while WoW shows Minecraft's overlay (SkyCraft's own). */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void mcwow$unlimited(CallbackInfoReturnable<Integer> cir) {
        if (McwowOverlayLink.linked()) cir.setReturnValue(260);
    }
}
