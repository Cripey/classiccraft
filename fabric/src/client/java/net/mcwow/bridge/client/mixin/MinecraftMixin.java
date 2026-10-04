package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowFrameExporter;
import net.mcwow.bridge.client.McwowInputBridge;
import net.mcwow.bridge.client.McwowTargeting;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the finished frame for WoW right after GameRenderer.render(), as SkyCraft does. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /** Replay WoW-captured input before anything else in the frame runs (SkyCraft's beginFrame). */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void mcwow$beginFrame(boolean advanceGameTime, CallbackInfo ci) {
        McwowInputBridge.beginFrame((Minecraft) (Object) this);
    }

    /** Aiming at WoW surfaces: after vanilla picking, a closer WoW triangle becomes the target. */
    @Inject(method = "pick(F)V", at = @At("TAIL"))
    private void mcwow$pickWow(float partialTick, CallbackInfo ci) {
        McwowTargeting.afterPick((Minecraft) (Object) this, partialTick);
    }

    @Inject(
        method = "renderFrame",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER)
    )
    private void mcwow$afterRender(boolean advanceGameTime, CallbackInfo ci) {
        McwowFrameExporter.afterRender((Minecraft) (Object) this);
    }
}
