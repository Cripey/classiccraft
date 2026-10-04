package net.mcwow.bridge.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PathNavigationRegion.class)
public interface PathNavigationRegionAccessor {
    /** The level a pathfinding region was cut from (McwowPathing.levelOf). */
    @Accessor("level")
    Level mcwow$level();
}
