package net.mcwow.bridge.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityAccessor {
    /** Entity.syncVelocity (set by markHurt): send the server's velocity to the client this tick. */
    @Accessor("syncVelocity")
    void mcwow$setSyncVelocity(boolean value);
}
