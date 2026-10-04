package net.mcwow.bridge.mixin;

import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowTerrainFill;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Natural spawning inside the Minecraft ground under WoW's surface only (breakable terrain, Phase 3,
 * 2026-10-02). Vanilla tries a random height from the world's floor to the surface, and the WoW map
 * dimensions run from y = -512 while the ground is the 48 blocks under the WoW surface: nearly every
 * try landed in the void and nothing spawned. In a filled chunk the try is now a random column's
 * ground, bedrock to top block - caves, mines and dug pits - never the WoW surface above it (the
 * user's call: Minecraft mobs spawn underground only).
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Inject(method = "getRandomPosWithin", at = @At("HEAD"), cancellable = true)
    private static void mcwow$inTheGround(Level level, LevelChunk chunk, CallbackInfoReturnable<BlockPos> cir) {
        if (!McwowGeomStore.appliesTo(level)) return;
        int[] tops = chunk.getAttached(McwowTerrainFill.TOPS);
        if (tops == null) return;
        int i = level.getRandom().nextInt(256);
        int top = tops[i];
        if (top == McwowTerrainFill.NO_TOP) {
            // No ground in this column: a try that finds nothing, never the WoW surface.
            cir.setReturnValue(new BlockPos(chunk.getPos().getMinBlockX(), level.getMinY(), chunk.getPos().getMinBlockZ()));
            return;
        }
        int bottom = McwowTerrainFill.bottomY(top, level.getMinY());
        int y = bottom + 1 + level.getRandom().nextInt(Math.max(1, top - bottom));
        BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + (i & 15), y, chunk.getPos().getMinBlockZ() + (i >> 4));
        // Ground placed only where needed (McwowGroundReveal): a try in its empty, unplaced part (an
        // air pocket on a tunnel's roof, a cave nobody has opened) finds nothing either.
        if (level instanceof net.minecraft.server.level.ServerLevel server
                && !(net.mcwow.bridge.McwowGroundReveal.known(server, pos) && net.mcwow.bridge.McwowGroundReveal.known(server, pos.above()))) {
            pos = new BlockPos(chunk.getPos().getMinBlockX(), level.getMinY(), chunk.getPos().getMinBlockZ());
        }
        cir.setReturnValue(pos);
    }
}
