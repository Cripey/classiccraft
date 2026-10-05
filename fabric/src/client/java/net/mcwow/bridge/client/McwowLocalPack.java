package net.mcwow.bridge.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

/**
 * The local resource pack of WoW-derived art (2026-10-04: WoW weapon models, McwowWowWeapons) -
 * <data dir>/resourcepack (McwowLinks.dataDir), built from the player's own WoW install by
 * tools/wow-weapons.sh, never shipped. Added to Minecraft's resource packs as a required pack on
 * top (PackRepositoryMixin), when it exists.
 */
public final class McwowLocalPack implements RepositorySource {
    public static final Path DIR = net.mcwow.bridge.McwowLinks.dataDir().resolve("resourcepack");

    @Override
    public void loadPacks(Consumer<Pack> out) {
        if (!Files.isRegularFile(DIR.resolve("pack.mcmeta"))) return;
        PackLocationInfo info = new PackLocationInfo("classiccraft_wow_art", Component.literal("WoW art (local)"),
                PackSource.BUILT_IN, Optional.empty());
        Pack pack = Pack.readMetaAndCreate(info, new PathPackResources.PathResourcesSupplier(DIR), PackType.CLIENT_RESOURCES,
                new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) out.accept(pack);
    }
}
