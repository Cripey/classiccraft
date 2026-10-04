package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowDance;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Seated on a WoW chair (McwowDance.selfSitting), the eye sinks with Steve's body; WoW's camera follows it. */
@Mixin(Camera.class)
public abstract class CameraSitMixin {
    @Shadow
    public abstract Vec3 position();

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void mcwow$sit(float partialTicks, CallbackInfo ci) {
        if (McwowDance.selfSitting()) setPosition(position().subtract(0.0, McwowDance.sitDropBlocks(), 0.0));
    }
}
