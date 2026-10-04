package net.mcwow.bridge.client.mixin;

import java.util.LinkedHashSet;
import java.util.Set;

import net.mcwow.bridge.client.McwowLocalPack;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The client's resource pack repository also finds the local WoW-art pack (McwowLocalPack). */
@Mixin(PackRepository.class)
abstract class PackRepositoryMixin {
    @Shadow @Final @Mutable private Set<RepositorySource> sources;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void mcwow$localPack(RepositorySource[] given, CallbackInfo ci) {
        if (sources.stream().noneMatch(s -> s instanceof ClientPackSource)) return; // not the resource packs
        Set<RepositorySource> all = new LinkedHashSet<>(sources);
        all.add(new McwowLocalPack());
        sources = all;
    }
}
