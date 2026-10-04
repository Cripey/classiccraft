package net.mcwow.bridge.client.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.mcwow.bridge.client.McwowOverlayLink;
import net.mcwow.bridge.client.McwowRenderLink;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WoW draws the world. While WoW's mcwow.dll reads the overlay, Minecraft still renders its own
 * level - in this project's empty "Void" world that is just the Minecraft player (third person),
 * and later placed blocks and mobs - but onto a TRANSPARENT background with no sky
 * ({@code addSkyPass}), clouds, weather or world border (sibling mixins), so only Minecraft's own
 * things end up composited over WoW.
 *
 * 2026-10-01: replaces the first version, which cancelled the whole level render exactly like
 * chasmlol/SkyCraft's own LevelRendererMixin - SkyCraft shows its player model by exporting the
 * mesh for Skyrim to draw (AvatarExporter); with an empty world, letting Minecraft draw it
 * directly is far simpler. The clear colour is the render() Vector4f argument, captured by the
 * frame graph's "clear" pass (checked via javap), so zeroing it at HEAD makes the clear transparent.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(
        method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void mcwow$transparentClear(GraphicsResourceAllocator allocator, boolean renderBlockOutline,
            CameraRenderState camera, GpuBufferSlice fog, Vector4f clearColor, boolean a, boolean b, CallbackInfo ci) {
        // Phase 4 step 3 (2026-10-01): with the render link up, WoW draws Minecraft's blocks AND
        // entities itself (McwowWorldExporter / McwowEntityExporter), occluded by WoW geometry -
        // so Minecraft renders none of its level, exactly like SkyCraft's LevelRendererMixin. The
        // overlay is then just hand + HUD.
        if (McwowRenderLink.active()) {
            ci.cancel();
            return;
        }
        if (McwowOverlayLink.linked()) {
            clearColor.set(0.0F, 0.0F, 0.0F, 0.0F);
        }
    }

    /**
     * 2026-10-01 (Phase 4 step 2): blocks are drawn by WoW from McwowWorldExporter's meshes, so
     * Minecraft must not draw them too (they'd be pasted over WoW, unoccluded). Not compiling any
     * chunk sections stops its terrain rendering while entities (Steve in third person) still draw
     * in the overlay until their geometry is exported too (step 3).
     */
    @Inject(method = "compileSections", at = @At("HEAD"), cancellable = true)
    private void mcwow$noTerrain(CameraRenderState camera, CallbackInfo ci) {
        if (McwowRenderLink.active()) ci.cancel();
    }

    @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
    private void mcwow$noSky(FrameGraphBuilder builder, CameraRenderState camera, GpuBufferSlice fog, CallbackInfo ci) {
        if (McwowOverlayLink.linked()) ci.cancel();
    }
}
