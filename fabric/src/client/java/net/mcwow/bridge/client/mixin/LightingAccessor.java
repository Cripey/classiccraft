package net.mcwow.bridge.client.mixin;

import com.mojang.blaze3d.platform.Lighting;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lighting's private per-entry direction write (HandSunMixin). */
@Mixin(Lighting.class)
public interface LightingAccessor {
    @Invoker("updateBuffer")
    void mcwow$updateBuffer(Lighting.Entry entry, Vector3fc light0, Vector3fc light1);
}
