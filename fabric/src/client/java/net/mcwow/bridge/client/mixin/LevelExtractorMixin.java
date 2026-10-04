package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowWorldExporter;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change (and chunk load) marks its 16^3 section for re-meshing into WoW. SkyCraft's
 * LevelExtractorMixin, adapted: this 26.3 build's setSectionDirty has no "player changed" flag, so
 * every change goes to the front of the queue (cheap - the Void world has few sections).
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "setSectionDirty(III)V", at = @At("HEAD"))
    private void mcwow$sectionDirty(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
        McwowWorldExporter.markDirtyNow(sectionX, sectionY, sectionZ);
    }
}
