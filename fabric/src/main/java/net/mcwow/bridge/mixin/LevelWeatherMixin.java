package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Minecraft weather in a WoW map dimension (user, 2026-10-02): WoW has its own. Rain and thunder
 * read as none there, so everything that follows from them stops too - rain rendering, sounds and
 * splash particles (exported into WoW), lightning, and mobs and fires reacting to rain. Client and
 * server alike; other dimensions keep Minecraft's weather.
 */
@Mixin(Level.class)
public abstract class LevelWeatherMixin {
    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void mcwow$noRain(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (McwowGeomStore.appliesTo(this)) cir.setReturnValue(0.0F);
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void mcwow$noThunder(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (McwowGeomStore.appliesTo(this)) cir.setReturnValue(0.0F);
    }
}
