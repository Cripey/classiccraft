package net.mcwow.bridge.client.mixin;

import java.util.Map;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The atlas's sprite map and size (private), for McwowAtlas. Same as SkyCraft's. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
    @Accessor("texturesByName")
    Map<Identifier, TextureAtlasSprite> mcwow$sprites();

    @Accessor("width")
    int mcwow$width();

    @Accessor("height")
    int mcwow$height();
}
