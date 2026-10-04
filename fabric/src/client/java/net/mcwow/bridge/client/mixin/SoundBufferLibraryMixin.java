package net.mcwow.bridge.client.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import net.mcwow.bridge.client.McwowMusicFiles;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The WoW music discs stream from the user's own extracted tracks (McwowMusicFiles), not from the
 * mod's silent placeholders at assets/mcwow/sounds/wowmusic, which only let the sounds register.
 */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {
    private static final String PREFIX = "sounds/wowmusic/";

    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
    private void mcwow$wowMusic(Identifier id, boolean looping, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        if (!id.getNamespace().equals("mcwow") || !id.getPath().startsWith(PREFIX)) return;
        Path file = McwowMusicFiles.dir().resolve(id.getPath().substring(PREFIX.length()));
        if (!Files.exists(file)) return; // not converted yet: the placeholder's silence
        cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
            try {
                InputStream in = Files.newInputStream(file);
                return looping ? new LoopingAudioStream(JOrbisAudioStream::new, in) : new JOrbisAudioStream(in);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }));
    }
}
